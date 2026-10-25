#!/usr/bin/env node
/* 网页端多尺寸截图矩阵（走 CDP，真正设成那个视口宽度）。
 *
 * ## 为什么不用 `--window-size`
 *
 * Chrome 在 Windows 上有**最小窗口宽度**（约 514px），`--window-size=390,844`
 * 会被悄悄抬到 500 多 —— 截出来的图看着像手机，其实视口是 534px，
 * 窄屏断点根本没被触发过。「手机尺寸下不溢出」这种结论就是假的。
 *
 * 所以走 DevTools 协议：`Emulation.setDeviceMetricsOverride` 直接把
 * 视口设成多少就是多少，还���以顺带模拟移动端（`mobile: true` 会带上
 * 设备像素比与触摸事件）。
 *
 * ## 用法
 *
 *   node tools/capture-web.mjs --url http://127.0.0.1:8777/ --out .tmp/web-shots
 *   node tools/capture-web.mjs --only phone-390 --scenes today,sync
 *
 * 场景就是「进页面之后点一下什么」：
 *   today / tasks / courses / exams / week / weekDetail / sync / pair
 */

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(__dirname, '..');

const argv = process.argv.slice(2);
function arg(name, fallback) {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] !== undefined ? argv[i + 1] : fallback;
}

const URL_ = arg('--url', 'http://127.0.0.1:8777/');
const OUT = path.resolve(REPO, arg('--out', '.tmp/web-shots'));
const ONLY = arg('--only', '');
const SCENES = arg('--scenes', 'today,tasks,courses,exams,sync').split(',').filter(Boolean);
const CHROME = arg('--chrome', 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe');
const SETTLE_MS = Number(arg('--settle', 3200));
/**
 * 已经配对好的凭据。
 *
 * 有了它，截图脚本就不用每次走一遍「申请配对码 → 手机上点允许」——
 * 直接把配对 secret 与 deviceId 注入浏览器的 localStorage，
 * 页面一进去就是已连接状态，拍出来的图是**真机数据**而不是 mock 数据。
 *
 * secret 是配对时由网页端本地生成、只经二维码传给手机的那把钥匙，
 * 所以「拿它注入浏览器」等价于「假装已经扫过码」。
 */
const SECRET = arg('--secret', '');
const DEVICE_ID = arg('--device-id', '');

const SIZES = [
  { name: 'phone-360',  width: 360,  height: 780,  mobile: true },
  { name: 'phone-390',  width: 390,  height: 844,  mobile: true },
  { name: 'phone-412',  width: 412,  height: 915,  mobile: true },
  { name: 'tablet-768', width: 768,  height: 1024, mobile: true },
  { name: 'desktop-1280', width: 1280, height: 800, mobile: false },
];

/**
 * 每个场景：进页面之后做点什么。
 *
 * 导航是 **总览 / 课表 / 清单** 三个主入口（与 App 的浮岛一致），
 * 考试归在课表页的子页签里 —— 所以选「课程 / 考试」要点子页签，不是主导航。
 */
const SCENE_ACTIONS = {
  today:    null,
  tasks:    `document.querySelector("[data-tab='tasks']")?.click()`,
  schedule: `document.querySelector("[data-tab='schedule']")?.click()`,
  /** 周课表栅格（App 课表页的同一张表）。 */
  week:     `document.querySelector("[data-tab='schedule']")?.click(); document.querySelector(".subtab[data-sub='week']")?.click()`,
  courses:  `document.querySelector("[data-tab='schedule']")?.click(); document.querySelector(".subtab[data-sub='courses']")?.click()`,
  exams:    `document.querySelector("[data-tab='schedule']")?.click(); document.querySelector(".subtab[data-sub='exams']")?.click()`,
  /** 点周课表里的一个课程格 → 详情弹层。 */
  weekDetail: `document.querySelector("[data-tab='schedule']")?.click();
               document.querySelector(".subtab[data-sub='week']")?.click();
               document.querySelector(".week-cell")?.click()`,
  /** 点周次条中间的「第 N 周」→ 周次选择器。 */
  weekPicker: `document.querySelector("[data-tab='schedule']")?.click();
               document.querySelector(".subtab[data-sub='week']")?.click();
               document.querySelector(".week-title")?.click()`,
  sync:     `document.querySelector("#btnSync")?.click()`,
  pair:     `document.querySelector(".pair-advanced summary")?.click()`,
  /**
   * 展开后的侧栏。侧栏默认是收起的图标条，鼠标靠近才展开 ——
   * 截图里没有鼠标，所以只能直接把它钉住。
   *
   * 这里**不点按钮**：点是「翻转」，而下一张截图可能还是这个场景，
   * 翻一下就反了。改成直接置状态，幂等。
   */
  sideOpen: `localStorage.setItem('miulist.sidePinned','1');
             document.querySelector('#app')?.classList.add('is-side-pinned');
             document.querySelector('#btnSideToggle')?.setAttribute('aria-expanded','true')`,
};

/**
 * 每个场景要等多久再截。
 *
 * `pair` 要**早**：配对成功后页面就切到主界面了，而 mock 是 2.5 秒后自动批准，
 * 轮询又是 1.5 秒一次 —— 等满了就只剩主界面，配对页那张永远截不到。
 */
const SCENE_SETTLE = { pair: 1100 };

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── CDP 小客户端 ────────────────────────────────────────────

class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.id = 0;
    this.pending = new Map();
    this.listeners = [];
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) reject(new Error(`${msg.error.message} (${JSON.stringify(msg.error.data ?? '')})`));
        else resolve(msg.result);
      } else if (msg.method) {
        for (const fn of this.listeners) fn(msg);
      }
    });
  }

  send(method, params = {}, sessionId) {
    const id = ++this.id;
    const payload = { id, method, params };
    if (sessionId) payload.sessionId = sessionId;
    this.ws.send(JSON.stringify(payload));
    return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject }));
  }

  once(method, sessionId) {
    return new Promise((resolve) => {
      const fn = (msg) => {
        if (msg.method === method && (!sessionId || msg.sessionId === sessionId)) {
          this.listeners = this.listeners.filter((f) => f !== fn);
          resolve(msg.params);
        }
      };
      this.listeners.push(fn);
    });
  }
}

async function launchChrome() {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'miulist-cdp-'));
  const proc = spawn(CHROME, [
    '--headless=new',
    '--disable-gpu',
    '--hide-scrollbars',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    '--remote-debugging-port=0',
    `--user-data-dir=${profile}`,
    'about:blank',
  ], { stdio: 'ignore', detached: false });

  // Chrome 把实际端口写在这个文件里（--remote-debugging-port=0 时）
  const portFile = path.join(profile, 'DevToolsActivePort');
  let port = null;
  for (let i = 0; i < 100; i++) {
    if (fs.existsSync(portFile)) {
      const raw = fs.readFileSync(portFile, 'utf8').split('\n');
      if (raw[0] && raw[0].trim()) { port = Number(raw[0].trim()); break; }
    }
    await sleep(100);
  }
  if (!port) {
    proc.kill();
    throw new Error('Chrome 没起来（没写出 DevToolsActivePort）');
  }
  return { proc, port, profile };
}

async function connect(port) {
  const res = await fetch(`http://127.0.0.1:${port}/json/version`);
  const info = await res.json();
  const ws = new WebSocket(info.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true });
    ws.addEventListener('error', reject, { once: true });
  });
  return new Cdp(ws);
}

// ── 主流程 ──────────────────────────────────────────────────

async function main() {
  if (!fs.existsSync(CHROME)) throw new Error(`找不到 Chrome：${CHROME}`);
  fs.mkdirSync(OUT, { recursive: true });

  const { proc, port, profile } = await launchChrome();
  const cdp = await connect(port);
  const shots = [];

  try {
    const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' });
    const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true });
    await cdp.send('Page.enable', {}, sessionId);
    await cdp.send('Runtime.enable', {}, sessionId);

    if (SECRET && DEVICE_ID) {
      // 页面脚本跑起来之前就把配对凭据放好 ——
      // 否则第一帧是「未配对」，等轮询回来又要好几秒，截图会拍到中间态。
      await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
        source: `try {
          localStorage.setItem('miulist.secret', ${JSON.stringify(SECRET)});
          localStorage.setItem('miulist.deviceId', ${JSON.stringify(DEVICE_ID)});
        } catch (e) {}`,
      }, sessionId);
    }

    const sizes = ONLY ? SIZES.filter((s) => s.name === ONLY) : SIZES;
    if (!sizes.length) throw new Error(`没有叫 ${ONLY} 的档位`);

    for (const size of sizes) {
      await cdp.send('Emulation.setDeviceMetricsOverride', {
        width: size.width,
        height: size.height,
        deviceScaleFactor: 2,
        mobile: size.mobile,
      }, sessionId);

      for (const scene of SCENES) {
        const action = SCENE_ACTIONS[scene];
        if (action === undefined) continue;

        const loaded = cdp.once('Page.loadEventFired', sessionId);
        await cdp.send('Page.navigate', { url: URL_ }, sessionId);
        await loaded;
        // 配对要轮询一轮（1.5s 一次），等它落定再截图。
        await sleep(SCENE_SETTLE[scene] ?? SETTLE_MS);
        if (action) {
          await cdp.send('Runtime.evaluate', { expression: action, awaitPromise: false }, sessionId);
          await sleep(700);
        }

        const { data } = await cdp.send('Page.captureScreenshot', {
          format: 'png',
          captureBeyondViewport: false,
        }, sessionId);
        const file = path.join(OUT, `${size.name}-${scene}.png`);
        fs.writeFileSync(file, Buffer.from(data, 'base64'));
        const kb = Math.round(fs.statSync(file).size / 102.4) / 10;
        shots.push({ file, size: size.name, scene, width: size.width, kb });
        console.log(`${(size.name + '-' + scene).padEnd(24)} ${size.width}x${size.height}  ->  ${file} (${kb} KB)`);
      }
    }
  } finally {
    try { proc.kill(); } catch (_) { /* 已经退了 */ }
    try { fs.rmSync(profile, { recursive: true, force: true }); } catch (_) { /* 临时目录，留着也无妨 */ }
  }

  console.log(`\n共 ${shots.length} 张，目录：${OUT}`);
}

main().catch((e) => {
  console.error('失败：', e && e.message ? e.message : e);
  process.exit(1);
});
