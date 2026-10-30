# 电脑端网页端（MiuList-Web）

> ⚠️ **已退役（beta）**：桌面端现在是 `desktop/` 里的**单文件 HTML**，
> 不再需要 Python 启动器或 exe。本目录保留作历史参考；旧 exe 仍会触发
> Windows 的「未知发布者 / 未知应用」提示。

把 MiuList 的网页端**在这台电脑上独立跑起来** —— 双击一下，浏览器就开了，
不用开手机的局域网服务、不用记地址。

```
双击 MiuList-Web.exe
   ↓
浏览器打开 http://127.0.0.1:8777/
   ↓
网页端自动找到局域网里的手机（UDP 广播探测）
   ↓
屏幕上出现一张配对二维码 → 用手机相机扫 → 手机上点「允许」
   ↓
两端的待办、课程、考试开始实时同步
```

## 它是什么，不是什么

| | 网页端（电脑） | 手机 App |
|---|---|---|
| 快照存哪 | 浏览器 localStorage **+ 镜像一份到磁盘**（DPAPI 加密） | Room 数据库 |
| 手机不在能用吗 | **能**（看得到、能改，改动记着，连上后一起推） | 本来就在手机上 |
| 谁是接口服务端 | —— | **手机**（协议不变） |
| 谁扫谁的码 | —— | **手机扫网页端的码** |

也就是说：**协议一个字没改**，变的只是「网页端由谁托管」。

- 以前：手机既提供接口也提供网页文件，你在电脑上打开手机地址 → 网页端离开手机就不能用。
- 现在：电脑上有个本地服务托管网页端，手机只负责当接口服务端 → 网页端是**这一端自己的**。

详见 [docs/lan-sync-protocol.md](../../docs/lan-sync-protocol.md)。

## 用法

```bash
# 直接跑源码（需要 Python 3.9+，只用标准库）
python tools/pc-web/launcher.py

# 换端口 / 允许局域网里别的设备打开 / 手动指定手机地址
python tools/pc-web/launcher.py --port 8800
python tools/pc-web/launcher.py --lan
python tools/pc-web/launcher.py --phone http://192.168.1.20:8777
```

跑起来以后窗口里会打印：

```
 网页端地址   http://127.0.0.1:8777/
 手机地址     等待发现…
 本地快照     C:\Users\<你>\AppData\Local\MiuList\web-state.bin（已用 DPAPI 加密）
```

**关掉这个窗口就等于关掉网页端。** 双击第二次不会起两个 —— 它会发现
「已经在跑」，直接打开浏览器。

## 打包成独立 exe

目标电脑不需要装 Python，也不需要装 Node：

```bash
python tools/pc-web/build.py
```

产物在 `tools/pc-web/dist/MiuList-Web.exe`（约 10 MB，含网页端资源与 App 图标）。

> 打包只在这台开发机上做（要 PyInstaller 与 Pillow）；
> 别人拿去用只需要那个 exe。

## 手机怎么被找到

手机的 HTTP 端口从**固定候选**里挑（`38780`–`38784`，首选 `38780`），
但电脑端事先不知道它挑中了哪一个，所以仍然要靠发现协议拿到。
于是：

1. 电脑端往局域网广播一个 UDP 探测包（固定端口 `38777`）；
2. 手机上的 App 开着「更多 → 网页同步 → 局域网服务」时，会应答一个包，
   里面带着它自己的 HTTP 端口（App 侧实现见 `ServiceDiscovery.kt`）；
3. 电脑端拿这个端口拼出 `http://192.168.x.x:端口`，告诉网页端。

**找不到也不会卡住**：网页端会一直等（每 2.5 秒问一次启动器），
你在手机上把局域网服务打开，它就自动连上。也可以在网页端手动填地址 ——
那会记在启动器里（`%LOCALAPPDATA%\MiuList\target.json`），之后优先用你填的。

跨网段（手机和电脑不在同一个局域网）时广播到不了，只能手动填地址。

## 本地快照为什么要落盘

浏览器里的 localStorage 会被「清除浏览数据」清掉，也可能因为换了浏览器而看不到。
镜像一份到磁盘，于是：

- 换浏览器、清缓存之后，这一端的数据还在；
- 数据**住在电脑上**，而不是「恰好在某个浏览器的某个缓存目录里」。

镜像里含配对密钥（那把「钥匙」），所以落盘时用 **Windows DPAPI** 加密 ——
绑定当前 Windows 用户，拷到别的电脑上也解不开。

## 开发时的用法

```bash
# 1. 起一个「假手机」（自带发现口与假数据）
node tools/web-mock-server.mjs --port 8801 --secret <base64url>

# 2. 起电脑端启动器
python tools/pc-web/launcher.py --no-browser

# 3. 打开 http://127.0.0.1:8777/ 验收
```

mock 的 `--auto-secret` 会打印一把随机密钥，网页端要带上
`?devsecret=<那个值>` 才能配对得上（mock 没有摄像头，扮演不了扫码的那一方）。

截图验收用 `tools/capture-web.mjs`：

```bash
node tools/capture-web.mjs --url http://127.0.0.1:8777/ --out .tmp/web-shots
```

## 文件

| 文件 | 职责 |
|---|---|
| `launcher.py` | 主程序：托管网页端、发现手机、存本地镜像 |
| `dpapi.py` | Windows DPAPI 封装（加密/解密镜像文件） |
| `build.py` | PyInstaller 打包脚本 |
| `dist/MiuList-Web.exe` | 打包产物（不入版本库） |
