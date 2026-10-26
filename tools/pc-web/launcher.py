#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""MiuList 网页端 · 电脑端启动器。

## 它做什么

把这个仓库的 `web/` 目录在**这台电脑上**跑起来，并打开浏览器：

    双击 MiuList-Web.exe  →  浏览器打开 http://127.0.0.1:8777/

网页端于是是一份**属于这台电脑的**完整快照（存在浏览器的 localStorage 里，
并且额外镜像一份到磁盘，见下文），**手机不在也能看、能改**。

## 它跟手机是什么关系

手机继续当**接口服务端**（`/api/*` 在手机上），网页端是客户端 —— 与协议
文档 `docs/lan-sync-protocol.md` 里写的一致，协议一个字没改。

于是「手机在哪」就成了一个问题：网页端在 `127.0.0.1`，接口在手机的
`192.168.x.x`，**跨源**，而手机的 HTTP 端口是**随机**的（免得跟电脑上别的
服务撞端口）。

启动器用**局域网 UDP 广播探测**解决它：手机上的 App 开着「局域网服务」时
会应答探测包，里面带着它自己的 HTTP 端口（见 App 侧的 `ServiceDiscovery.kt`）。

找不到时，网页端会一直等 —— 你什么时候在手机上打开局域网服务，它什么时候
连上。也可以在网页端手动填地址（那会存进启动器，之后优先用你填的）。

## 本地快照为什么要镜像到磁盘

浏览器里的 localStorage 会被「清除浏览数据」清掉，也可能因为换了浏览器
而看不到。镜像一份到磁盘（**用 Windows DPAPI 加密**，因为里面有配对密钥
—— 那把钥匙明文落在磁盘上就等于把家门钥匙放在门口），于是：

- 换浏览器、清缓存之后，这一端的数据还在；
- 数据**住在电脑上**，而不是「恰好在某个浏览器的某个缓存目录里」。

## 依赖

只用 Python 标准库。打包成 exe 用 PyInstaller（`build.py`）。

## 用法

    python launcher.py                      # 默认 127.0.0.1:8777，自动开浏览器
    python launcher.py --port 8800          # 换端口
    python launcher.py --phone http://192.168.1.20:8777   # 手动指定手机
    python launcher.py --lan                 # 允许局域网里的其他设备打开
    python launcher.py --no-browser         # 不自动开浏览器
"""

import argparse
import json
import os
import socket
import sys
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlparse

import dpapi

VERSION = "1.0.0"
DEFAULT_PORT = 8777
PORT_ATTEMPTS = 20

DISCOVERY_PORT = 38777
PROBE = b"MIULIST-DISCOVER/1"
DISCOVERY_INTERVAL = 4.0

APP_DIR = os.path.join(
    os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"), "MiuList"
)
STATE_FILE = os.path.join(APP_DIR, "web-state.bin")
TARGET_FILE = os.path.join(APP_DIR, "target.json")
LOG_FILE = os.path.join(APP_DIR, "launcher.log")

MIME = {
    "html": "text/html; charset=utf-8",
    "js": "text/javascript; charset=utf-8",
    "mjs": "text/javascript; charset=utf-8",
    "css": "text/css; charset=utf-8",
    "json": "application/json; charset=utf-8",
    "webmanifest": "application/manifest+json; charset=utf-8",
    "svg": "image/svg+xml",
    "png": "image/png",
    "jpg": "image/jpeg",
    "ico": "image/x-icon",
    "txt": "text/plain; charset=utf-8",
    "woff2": "font/woff2",
}


def log(message: str) -> None:
    line = "[%s] %s" % (time.strftime("%H:%M:%S"), message)
    try:
        print(line, flush=True)
    except Exception:
        pass
    try:
        os.makedirs(APP_DIR, exist_ok=True)
        with open(LOG_FILE, "a", encoding="utf-8") as handle:
            handle.write(line + "\n")
    except OSError:
        pass


def resolve_assets_dir(explicit: str = "") -> str:
    """网页端资源目录。

    打包成 exe 时资源在 `sys._MEIPASS/web`；从源码跑时是仓库根的 `web/`。

    ⚠️ 旧网页端（`web/index.html`）已删除，`web/` 现在只剩共享模块。
    缺入口页时**必须在这里停住**：否则启动器会正常起来、开出一个 404 页面，
    看起来像是「网页端坏了」，而不是「你要的资源已经不在了」。
    """
    if explicit:
        return os.path.abspath(explicit)
    if getattr(sys, "frozen", False):
        base = getattr(sys, "_MEIPASS", os.path.dirname(sys.executable))
        candidate = os.path.join(base, "web")
        if os.path.isdir(candidate):
            return os.path.abspath(candidate)
    here = os.path.dirname(os.path.abspath(__file__))
    candidate = os.path.abspath(os.path.join(here, "..", "..", "web"))
    if os.path.isdir(candidate):
        if not os.path.isfile(os.path.join(candidate, "index.html")):
            raise SystemExit(
                "旧网页端已删除：%s 里没有 index.html。\n"
                "这个启动器已经退役（保留仅为历史参考）；\n"
                "现在的桌面端在 desktop/ 里：cd desktop && npm run build，"
                "产物是单文件 desktop/dist/MiuList-Web.html。" % candidate
            )
        return candidate
    raise SystemExit("找不到网页端资源目录（web/）")


class Launcher:
    """启动器本体：托管网页端、找手机、存本地镜像。"""

    def __init__(self, assets_dir: str, port: int, host: str, phone: str = ""):
        self.assets_dir = assets_dir
        self.port = port
        self.host = host
        self._lock = threading.Lock()
        self._discovered = None          # 探测到的手机
        self._override = phone or self._load_override()
        self._stop = threading.Event()

    # ── 手机地址 ────────────────────────────────────────────────

    def _load_override(self) -> str:
        try:
            with open(TARGET_FILE, "r", encoding="utf-8") as handle:
                return (json.load(handle).get("base") or "").strip()
        except (OSError, ValueError):
            return ""

    def _save_override(self, base: str) -> None:
        try:
            os.makedirs(APP_DIR, exist_ok=True)
            with open(TARGET_FILE, "w", encoding="utf-8") as handle:
                json.dump({"base": base}, handle, ensure_ascii=False)
        except OSError as exc:
            log("手动地址没存住：%s" % exc)

    def set_override(self, base: str) -> None:
        with self._lock:
            self._override = base
        self._save_override(base)

    def target(self) -> dict:
        """网页端问「手机在哪」时的答案。"""
        with self._lock:
            if self._override:
                return {"base": self._override, "source": "manual"}
            if self._discovered:
                result = dict(self._discovered)
                result["source"] = "discovered"
                return result
        return {"base": None, "source": None}

    # ── 局域网发现 ──────────────────────────────────────────────

    @staticmethod
    def _local_ipv4() -> set:
        """本机的 IPv4 地址。

        两个来源互补：`getaddrinfo` 给的是**所有**网卡，
        UDP connect 给的是**主出口**那张卡（默认路由走的那张）。
        广播包只从默认出口发得出去，所以两个都要。
        """
        found = set()
        try:
            for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
                ip = info[4][0]
                if not ip.startswith("127."):
                    found.add(ip)
        except OSError:
            pass
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            probe.settimeout(0.3)
            # UDP 的 connect 不发包，只是让内核选一张网卡。
            probe.connect(("8.8.8.8", 80))
            ip = probe.getsockname()[0]
            if not ip.startswith("127."):
                found.add(ip)
        except OSError:
            pass
        finally:
            probe.close()
        return found

    def _broadcast_targets(self) -> list:
        """往哪些广播地址发探测包。

        全局广播（255.255.255.255）在有些路由器上会被丢掉，
        所以再补上「按本机地址推出来的定向广播」—— /24 与 /16 各一个，
        覆盖家用路由最常见的两种网段。
        """
        targets = {"255.255.255.255"}
        for ip in self._local_ipv4():
            parts = ip.split(".")
            if len(parts) != 4:
                continue
            try:
                a, b, c, _d = (int(x) for x in parts)
            except ValueError:
                continue
            targets.add("%d.%d.%d.255" % (a, b, c))
            targets.add("%d.%d.255.255" % (a, b))
        return sorted(targets)

    def probe_once(self, timeout: float = 1.2):
        """广播一次探测包，收手机的应答。"""
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.settimeout(0.25)
        try:
            for target in self._broadcast_targets():
                try:
                    sock.sendto(PROBE, (target, DISCOVERY_PORT))
                except OSError:
                    pass
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                try:
                    data, addr = sock.recvfrom(4096)
                except socket.timeout:
                    continue
                except OSError:
                    break
                try:
                    payload = json.loads(data.decode("utf-8"))
                except (ValueError, UnicodeDecodeError):
                    continue
                if payload.get("app") != "MiuList":
                    continue
                port = payload.get("port")
                if not isinstance(port, int) or port <= 0:
                    continue
                return {
                    # 用**回包的来源地址**：那一定是手机这张网卡上、
                    # 电脑这边能直接访问到的地址。
                    "base": "http://%s:%d" % (addr[0], port),
                    "deviceName": payload.get("deviceName") or "手机",
                    "appVersion": payload.get("appVersion") or "",
                }
            return None
        finally:
            sock.close()

    def discovery_loop(self) -> None:
        while not self._stop.is_set():
            try:
                found = self.probe_once()
            except Exception:
                found = None
            if found:
                with self._lock:
                    changed = (
                        not self._discovered
                        or self._discovered.get("base") != found.get("base")
                    )
                    self._discovered = found
                if changed:
                    log("找到手机：%s（%s）" % (found["base"], found["deviceName"]))
            self._stop.wait(DISCOVERY_INTERVAL)

    # ── 本地镜像 ────────────────────────────────────────────────

    def load_state(self):
        """读镜像。读不出来就当没有 —— 绝不让网页端因为镜像坏了而启动不了。"""
        try:
            with open(STATE_FILE, "rb") as handle:
                raw = handle.read()
        except OSError:
            return None
        if dpapi.available():
            try:
                raw = dpapi.unprotect(raw)
            except OSError as exc:
                log("镜像文件解不开（多半是被别的 Windows 用户拷过来的）：%s" % exc)
                return None
        try:
            return json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return None

    def save_state(self, payload: bytes) -> None:
        try:
            os.makedirs(APP_DIR, exist_ok=True)
            blob = payload
            if dpapi.available():
                try:
                    blob = dpapi.protect(payload)
                except OSError as exc:
                    log("加密失败，改存明文：%s" % exc)
            tmp = STATE_FILE + ".tmp"
            with open(tmp, "wb") as handle:
                handle.write(blob)
            # 原子替换：中途断电不会留下半个文件。
            os.replace(tmp, STATE_FILE)
        except OSError as exc:
            log("镜像写不进去：%s" % exc)

    # ── 生命周期 ────────────────────────────────────────────────

    def request_stop(self) -> None:
        self._stop.set()


def make_handler(launcher: Launcher):
    class Handler(BaseHTTPRequestHandler):
        server_version = "MiuListWeb/" + VERSION
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):  # 静音：默认实现会往 stderr 刷
            pass

        # ── 工具 ────────────────────────────────────────────────

        def _json(self, status: int, payload) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def _static(self, path: str) -> None:
            rel = unquote(path.lstrip("/")) or "index.html"
            root = os.path.abspath(launcher.assets_dir)
            full = os.path.abspath(os.path.join(root, rel))
            # 越界保护：解析后的路径必须还在 web/ 里面。
            if full != root and not full.startswith(root + os.sep):
                self._json(404, {"error": "没有这个文件"})
                return
            if os.path.isdir(full):
                full = os.path.join(full, "index.html")
            if not os.path.isfile(full):
                self._json(404, {"error": "没有这个文件"})
                return
            try:
                with open(full, "rb") as handle:
                    body = handle.read()
            except OSError:
                self._json(404, {"error": "没有这个文件"})
                return
            ext = full.rsplit(".", 1)[-1].lower() if "." in full else ""
            self.send_response(200)
            self.send_header("Content-Type", MIME.get(ext, "application/octet-stream"))
            self.send_header("Content-Length", str(len(body)))
            # 不缓存：改完 web/ 刷新页面就能看到，不用清缓存。
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        # ── 路由 ────────────────────────────────────────────────

        def do_GET(self):
            path = urlparse(self.path).path
            if path == "/__miulist/ping":
                self._json(200, {"ok": True, "app": "MiuList", "version": VERSION,
                                 "pid": os.getpid(), "port": launcher.port})
                return
            if path == "/__miulist/target":
                self._json(200, launcher.target())
                return
            if path == "/__miulist/state":
                data = launcher.load_state()
                if data is None:
                    self._json(404, {"error": "还没有本地镜像"})
                    return
                self._json(200, data)
                return
            if path == "/__miulist/shutdown":
                self._json(200, {"ok": True})
                launcher.request_stop()
                return
            self._static(path)

        def do_POST(self):
            path = urlparse(self.path).path
            try:
                length = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                length = 0
            body = self.rfile.read(length) if length > 0 else b""

            if path == "/__miulist/target":
                try:
                    payload = json.loads(body.decode("utf-8")) if body else {}
                except (ValueError, UnicodeDecodeError):
                    self._json(400, {"error": "请求体不合法"})
                    return
                base = str(payload.get("base") or "").strip().rstrip("/")
                launcher.set_override(base)
                log("手动指定手机地址：%s" % (base or "（清空，恢复自动发现）"))
                self._json(200, launcher.target())
                return
            if path == "/__miulist/state":
                launcher.save_state(body)
                self._json(200, {"ok": True})
                return
            self._json(404, {"error": "没有这个接口"})

    return Handler


def pick_port(host: str, port: int, handler):
    """挑一个能用的端口。

    端口被占时先问一句「占着的那个是不是我自己」——
    是的话（用户双击了两次）打开浏览器就退出，不是的话往后挪一个。
    """
    for offset in range(PORT_ATTEMPTS):
        candidate = port + offset
        try:
            return ThreadingHTTPServer((host, candidate), handler), candidate
        except OSError:
            if offset == 0:
                existing = probe_existing(host, port)
                if existing:
                    return None, candidate
    raise SystemExit("端口 %d~%d 都被占用了。" % (port, port + PORT_ATTEMPTS - 1))


def probe_existing(host: str, port: int):
    """问占用端口的那个是不是「已经跑着的自己」。"""
    import urllib.error
    import urllib.request

    url = "http://%s:%d/__miulist/ping" % (host, port)
    try:
        with urllib.request.urlopen(url, timeout=1.0) as response:
            payload = json.loads(response.read().decode("utf-8"))
        return payload if payload.get("app") == "MiuList" else None
    except (urllib.error.URLError, OSError, ValueError):
        return None


def main() -> int:
    parser = argparse.ArgumentParser(
        description="MiuList 网页端 · 电脑端启动器",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="本地端口（默认 %d）" % DEFAULT_PORT)
    parser.add_argument("--host", default="127.0.0.1", help="监听地址（--lan 时用 0.0.0.0）")
    parser.add_argument("--phone", default="", help="手动指定手机地址，例如 http://192.168.1.20:8777")
    parser.add_argument("--assets", default="", help="网页端资源目录（默认用打包进来的）")
    parser.add_argument("--lan", action="store_true", help="允许局域网里的其他设备打开")
    parser.add_argument("--no-browser", action="store_true", help="不自动打开浏览器")
    args = parser.parse_args()

    assets = resolve_assets_dir(args.assets)
    host = "0.0.0.0" if args.lan else args.host

    launcher = Launcher(assets, args.port, host, args.phone.strip())
    handler = make_handler(launcher)

    server, bound_port = pick_port(host, args.port, handler)
    if server is None:
        # 已经有一个在跑了 —— 把浏览器指过去就够了。
        log("网页端已经在运行，打开浏览器就好。")
        webbrowser.open("http://127.0.0.1:%d/" % args.port)
        return 0
    launcher.port = bound_port

    url = "http://127.0.0.1:%d/" % bound_port
    print("=" * 52)
    print(" MiuList 网页端 · 电脑端")
    print("=" * 52)
    print(" 网页端地址   %s" % url)
    print(" 资源目录     %s" % assets)
    print(" 手机地址     %s" % (launcher.target().get("base") or "等待发现…"))
    print(" 本地快照     %s%s" % (STATE_FILE, "（已用 DPAPI 加密）" if dpapi.available() else "（明文）"))
    print(" 日志         %s" % LOG_FILE)
    print("-" * 52)
    print(" 在手机上打开 MiuList 的「更多 → 网页同步」，把局域网服务打开，")
    print(" 这里就会自动找到它。关掉这个窗口就等于关掉网页端。")
    print("=" * 52, flush=True)

    threading.Thread(target=launcher.discovery_loop, name="discovery", daemon=True).start()

    if not args.no_browser:
        threading.Timer(0.6, lambda: webbrowser.open(url)).start()

    try:
        server.serve_forever(poll_interval=0.3)
    except KeyboardInterrupt:
        print("\n网页端已退出。", flush=True)
    finally:
        launcher.request_stop()
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
