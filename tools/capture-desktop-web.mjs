#!/usr/bin/env node
/* 桌面网页端（单文件 HTML）的**行为验证 + 多尺寸截图**。
 *
 * ## 它验什么
 *
 * 「网页端是独立的一端」这条规则，只有在**手机那边先改了配置**时才看得出来：
 *
 * 1. 自动同步之后，本机的课表仍是空的（配置不跟着同步跑）；
 * 2. 点「用手机的课表和考试」→ 本机拿到 4 门课；
 * 3. 把**手机那边**的课表换成另一份 → 自动同步之后，本机还是那 4 门课
 *    （证明配置没有被远端改掉）；
 * 4. 点「把本页课表推给手机」→ 手机那边换成这一份。
 *
 * 只截图不看这些数字，等于没验 —— 图上分不出「配置没同步」和「同步了但画得一样」。
 *
 * ## 用法
 *
 *   node tools/web-mock-server.mjs --port 8801 --auto-secret   # 另开一个终端
 *   node tools/capture-desktop-web.mjs --port 8801 --secret <打印出来的值> --out .tmp/desktop-shots
 *
 * ## 为什么走 CDP 而不是 --window-size
 *
 * Chrome 在 Windows 上有最小窗口宽度（约 514px），`--window-size=390` 会被抬到 534 ——
 * 截出来看着像手机，其实窄屏断点根本没触发过。详见 tools/capture-web.mjs 的注释。
 */

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(__dirname, '..');
const HTML = path.join(REPO, 'desktop', 'dist', 'MiuList-Web.html');

const argv = process.argv.slice(2);
function arg(name, fallback) {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] !== undefined ? argv[i + 1] : fallback;
}

const PORT = Number(arg('--port', 8801));
const SECRET = arg('--secret', '');
const OUT = path.resolve(REPO, arg('--out', '.tmp/desktop-shots'));
const CHROME = arg('--chrome', 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe');
const ONLY = arg('--only', '');
const SKIP_SHOTS = argv.includes('--no-shots');
const APP_URL = `file:///${HTML.replace(/\\/g, '/')}?base=${encodeURIComponent(`http://127.0.0.1:${PORT}`)}&devsecret=${encodeURIComponent(SECRET)}`;

const SIZES = [
  { name: 'phone-390', width: 390, height: 844, mobile: true },
  { name: 'tablet-768', width: 768, height: 1024, mobile: true },
  { name: 'desktop-1280', width: 1280, height: 800, mobile: false },
  { name: 'desktop-1600', width: 1600, height: 900, mobile: false },
];

/**
 * 场景：进页面之后点什么。
 *
 * 一个场景可以是**多步**。必须分步的地方：点「打开设置面板」和点「面板里的标签」——
 * 前者触发 React 状态更新，面板是下一帧才挂进 DOM 的；把两句塞进同一个
 * `Runtime.evaluate` 的话，第二句会在面板还不存在时执行，`clickText` 找不到
 * 按钮，返回 not-found，而截图照样拍出一张「连接」页 —— 看起来很正常的假图。
 */
const SCENES = {
  today: null,
  week: [`clickText('课表')`],
  tasks: [`clickText('清单')`],
  /** 同步面板的「连接」分区 —— 新的「课表与考试」卡片在这里。 */
  sync: [`document.querySelector('.side-status')?.click()`],
  /** 同步面板的「快照」分区。 */
  snapshot: [`document.querySelector('.side-status')?.click()`, `clickTab('快照')`],
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── CDP 小客户端（与 tools/capture-web.mjs 同一套） ──────────────

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
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'miulist-desktop-cdp-'));
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

// ── 页面上跑的辅助函数 ────────────────────────────────────────

const HELPERS = `
  window.__miu = {
    clickText(text) {
      const btn = [...document.querySelectorAll('button')].find((b) => (b.textContent || '').includes(text));
      if (!btn) return 'not-found';
      btn.click();
      return 'ok';
    },
    /** 设置面板的标签页：按**整段文字精确**匹配，免得命中内容里带同样字的按钮。 */
    clickTab(text) {
      const btn = [...document.querySelectorAll('.sheet-tab')].find((b) => (b.textContent || '').trim() === text);
      if (!btn) return 'not-found';
      btn.click();
      return 'ok';
    },
    activeTab() {
      const el = document.querySelector('.sheet-tab.is-active');
      return el ? (el.textContent || '').trim() : null;
    },
    snapshot() {
      try { return JSON.parse(localStorage.getItem('miulist.snapshot') || '{}'); } catch (e) { return {}; }
    },
    courses() { return (this.snapshot().courses || []).length; },
    tasks() { return (this.snapshot().tasks || []).length; },
    todayCourses() { return (this.snapshot().todayCourses || []).length; },
    paired() { return Boolean(localStorage.getItem('miulist.deviceId')); },
  };
  window.clickText = (text) => window.__miu.clickText(text);
  window.clickTab = (text) => window.__miu.clickTab(text);
`;

/** 在页面里跑一段表达式，返回它的值。 */
async function evaluate(cdp, sessionId, expression) {
  const { result, exceptionDetails } = await cdp.send(
    'Runtime.evaluate',
    { expression, awaitPromise: true, returnByValue: true },
    sessionId,
  );
  if (exceptionDetails) {
    throw new Error(`页面里报错：${exceptionDetails.text} ${exceptionDetails.exception?.description || ''}`);
  }
  return result.value;
}

// ── 断言 ──────────────────────────────────────────────────────

const results = [];
function check(name, ok, detail) {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  —— ${detail}` : ''}`);
}

async function mockState() {
  const res = await fetch(`http://127.0.0.1:${PORT}/__mock/state`);
  return res.json();
}

async function mockSetCourses(keep) {
  const res = await fetch(`http://127.0.0.1:${PORT}/__mock/courses`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ keep }),
  });
  return res.json();
}

// ── 主流程 ────────────────────────────────────────────────────

/**
 * 等页面真正 ready。
 *
 * 为什么不能只 `await Page.loadEventFired`：
 * 网页端开着 SSE 长连接，连着 mock 的时候**每次导航都占住一条到 127.0.0.1 的连接**。
 * Chrome 对同一 host 的并发连接数有上限（HTTP/1.1 下是 6），攒满之后新的请求会排队，
 * 于是页面**永远等不到 load 事件** —— 表现是截图脚本卡死在一个场景上，
 * 而 Chrome 的 CPU 其实很闲（卡在等事件，不是在渲染）。
 *
 * 所以这里做两件事：
 *   1. 导航前先把旧页卸掉（about:blank），让上一条 SSE 断掉；
 *   2. 不只等 load 事件，还轮询 `document.readyState`，并且**超时就抛错** ——
 *      宁可让这一轮失败，也不要产出一张「页面根本没加载出来」的截图冒充验收。
 */
async function waitForReady(cdp, sessionId, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  let last = '';
  while (Date.now() < deadline) {
    // ⚠️ evaluate 本身也要兜一层超时：渲染进程一旦不响应，
    // 这一句 await 会永远挂着，外层的 deadline 根本轮不到 —— 那样「超时即失败」就形同虚设。
    const state = await Promise.race([
      evaluate(
        cdp,
        sessionId,
        'document.readyState + "|" + (document.querySelector("#root") ? "root" : "no-root")',
      ),
      sleep(5000).then(() => 'EVAL-TIMEOUT'),
    ]);
    last = state;
    if (state.startsWith('complete') && state.endsWith('root')) return;
    if (state === 'EVAL-TIMEOUT') {
      await sleep(500);
      continue;
    }
    await sleep(250);
  }
  throw new Error(`页面没在 ${timeoutMs}ms 内 ready（readyState=${last}）`);
}

async function main() {
  if (!fs.existsSync(CHROME)) throw new Error(`找不到 Chrome：${CHROME}`);
  if (!fs.existsSync(HTML)) throw new Error(`找不到构建产物：${HTML}（先 npm run build）`);
  if (!SECRET) throw new Error('要 --secret（mock 用 --auto-secret 打印的那把）');
  fs.mkdirSync(OUT, { recursive: true });

  const { proc, port, profile } = await launchChrome();
  const cdp = await connect(port);

  try {
    const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' });
    const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true });
    await cdp.send('Page.enable', {}, sessionId);
    await cdp.send('Runtime.enable', {}, sessionId);
    await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: HELPERS }, sessionId);

    // 固定成桌面尺寸跑验证；截图时再逐档切。
    await cdp.send('Emulation.setDeviceMetricsOverride', {
      width: 1440, height: 900, deviceScaleFactor: 1, mobile: false,
    }, sessionId);

    const loaded = cdp.once('Page.loadEventFired', sessionId);
    await cdp.send('Page.navigate', { url: APP_URL }, sessionId);
    await loaded;

    // 等配对 + 首次自动同步落定
    let paired = false;
    for (let i = 0; i < 40; i++) {
      await sleep(500);
      paired = await evaluate(cdp, sessionId, 'window.__miu.paired()');
      if (paired) break;
    }
    check('配对成功（mock 自动批准）', paired === true);
    if (!paired) throw new Error('没配上对，后面的验证没意义');

    await sleep(2500); // 等首次自动同步

    // ── 1. 待办同步过来了，配置没有 ──
    const tasks1 = await evaluate(cdp, sessionId, 'window.__miu.tasks()');
    const courses1 = await evaluate(cdp, sessionId, 'window.__miu.courses()');
    check('自动同步把待办拉过来了', tasks1 >= 7, `本机待办 ${tasks1} 条`);
    check('自动同步**没有**搬课表（配置不自动流动）', courses1 === 0, `本机课程 ${courses1} 门`);

    // ── 2. 显式搬一次：手机的课表 → 本机 ──
    await evaluate(cdp, sessionId, 'document.querySelector(".side-status")?.click()');
    await sleep(600);
    const clicked = await evaluate(cdp, sessionId, `clickText('用手机的课表和考试')`);
    await sleep(2500);
    const courses2 = await evaluate(cdp, sessionId, 'window.__miu.courses()');
    check('「用手机的课表和考试」能点到', clicked === 'ok', clicked === 'ok' ? '' : `点不到（${clicked}）`);
    check('搬完之后本机有 4 门课', courses2 === 4, `本机课程 ${courses2} 门`);

    // ── 3. 手机那边改课表 → 本机不被改掉 ──
    await mockSetCourses(2);
    await sleep(3000); // 等 SSE change → 自动同步
    const courses3 = await evaluate(cdp, sessionId, 'window.__miu.courses()');
    const phone = await mockState();
    check('手机那边已经换成 2 门课', phone.courses === 2, `手机 ${phone.courses} 门`);
    check('自动同步**没有**把手机的课表搬过来', courses3 === 4, `本机课程 ${courses3} 门（应仍是 4）`);

    // ── 4. 显式推一次：本机的课表 → 手机 ──
    await evaluate(cdp, sessionId, `clickText('把本页课表推给手机')`);
    await sleep(2000);
    const phone2 = await mockState();
    check(
      '「把本页课表推给手机」把本机的课表推上去了',
      phone2.courses === 4 && !phone2.courseNames.some((n) => n.includes('（改）')),
      `手机 ${phone2.courses} 门：${phone2.courseNames.join(' / ')}`,
    );

    // ── 5. 今天的课是本机按自己的课表算的 ──
    const todayCourses = await evaluate(cdp, sessionId, `
      (() => {
        const s = window.__miu.snapshot();
        return (s.todayCourses || []).map((c) => c.name + ' ' + (c.timeText || ''));
      })()
    `);
    check('今天的课有内容', Array.isArray(todayCourses) && todayCourses.length > 0,
      Array.isArray(todayCourses) ? todayCourses.join(' | ') : '读不到');

    // ── 6. 截图 ──
    if (!SKIP_SHOTS) {
      const sizes = ONLY ? SIZES.filter((s) => s.name === ONLY) : SIZES;
      for (const size of sizes) {
        await cdp.send('Emulation.setDeviceMetricsOverride', {
          width: size.width, height: size.height, deviceScaleFactor: 2, mobile: size.mobile,
        }, sessionId);
        for (const [scene, steps] of Object.entries(SCENES)) {
          // 先把上一页卸掉：它那条 SSE 长连接会占着到 mock 的并发连接数。
          await cdp.send('Page.navigate', { url: 'about:blank' }, sessionId);
          await sleep(250);

          const done = cdp.once('Page.loadEventFired', sessionId);
          await cdp.send('Page.navigate', { url: APP_URL }, sessionId);
          await Promise.race([done, sleep(20000)]);
          await waitForReady(cdp, sessionId);
          await sleep(2200);
          // 逐步执行：每一步之间都要给 React 一帧把面板/标签渲染出来。
          for (const step of steps || []) {
            const r = await evaluate(cdp, sessionId, step);
            if (typeof r === 'string' && r === 'not-found') {
              throw new Error(`场景「${scene}」的第一步点不到：${step}`);
            }
            await sleep(900);
          }
          // 设置面板场景：把当前标签记下来，方便事后确认这张图拍的是哪个分区。
          const activeTab = await evaluate(cdp, sessionId, 'window.__miu.activeTab()');
          const { data } = await cdp.send('Page.captureScreenshot', {
            format: 'png', captureBeyondViewport: false,
          }, sessionId);
          const file = path.join(OUT, `${size.name}-${scene}.png`);
          fs.writeFileSync(file, Buffer.from(data, 'base64'));
          const kb = Math.round(fs.statSync(file).size / 1024);
          const tabNote = activeTab ? `  标签=${activeTab}` : '';
          console.log(`shot  ${(size.name + '-' + scene).padEnd(26)} ${size.width}x${size.height} -> ${file} (${kb} KB)${tabNote}`);
        }
      }
    }
  } finally {
    try { proc.kill(); } catch (_) { /* 已经退了 */ }
    try { fs.rmSync(profile, { recursive: true, force: true }); } catch (_) { /* 临时目录 */ }
  }

  const failed = results.filter((r) => !r.ok);
  console.log(`\n验证：${results.length - failed.length}/${results.length} 通过`);
  if (failed.length) {
    console.log('失败项：\n' + failed.map((f) => `  - ${f.name}（${f.detail || ''}）`).join('\n'));
    process.exit(1);
  }
}

main().catch((e) => {
  console.error('失败：', e && e.message ? e.message : e);
  process.exit(1);
});
