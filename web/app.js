/* MiuList 网页端 —— 双端快照 + 局域网同步（协议 v3）。
 *
 * ## 这一端的地位
 *
 * 网页端**不是手机的镜像**。它自己有一份完整快照（存在浏览器里），
 * 没有手机连着也能看、能改。手机那边同样有一份。
 *
 * ## 同步的规则
 *
 * - **两端都在线且没有冲突** → 双向自动同步，不用点任何东西；
 * - **否则**（有一端离线，或者同一条待办两边都改过）→ 交给用户选方向，
 *   并把**两端快照的最新编辑时间**摆出来 —— 用户判断该往哪边同步，
 *   靠的就是这个时间，而不是「谁的条数多」。
 *
 * ## 传输安全（v3）
 *
 * - **配对 secret 由本页本地生成**，只经二维码（视觉通道）交给手机，
 *   **从不上网络**。服务端只见到它的摘要。
 * - 每个请求带**挑战-应答签名**（时间戳 + 一次性 nonce），
 *   抓包拿到的签名既不能重放也不能伪造。
 * - 请求与响应都是**密文**（ChaCha20 + HMAC-SHA256）。
 * - 密钥由 secret 派生；设了「同步密码」时，密码会先混进 secret ——
 *   于是二维码被别人拍到也不够。
 *
 * 详见 docs/lan-sync-protocol.md。
 */

import {
  sha256, hmacSha256, deriveKeys, seal, open, signRequest,
  toBase64Url, fromBase64Url, randomBytes, utf8Bytes,
} from './crypto.js';
// 周课表：颜色两档推导 + 栅格渲染。放在单独一个文件里是因为它自成一套
// （照抄 App 的 Color.kt 推导 + layoutDaySlots 分列），挤进 app.js 只会淹没主线。
import { renderWeekGrid } from './timetable.js';

const LS = {
  token: 'miulist.token',
  base: 'miulist.base',
  theme: 'miulist.theme',
  snapshot: 'miulist.snapshot',
  remoteBase: 'miulist.remoteBase',
  pending: 'miulist.pending',
  meta: 'miulist.meta',
  /** 配对 secret（base64url）。**它等价于钥匙**，所以只存在本机。 */
  secret: 'miulist.secret',
  deviceId: 'miulist.deviceId',
  passcode: 'miulist.passcode',
  gradient: 'miulist.gradient',
  /** 侧栏是否被「钉住」展开。'1' = 钉住，'0'/缺省 = 靠近才展开。 */
  sidePinned: 'miulist.sidePinned',
};

const state = {
  base: localStorage.getItem(LS.base) || location.origin,
  /** 配对 secret（base64url 字符串），null = 没配对。 */
  secret: localStorage.getItem(LS.secret) || null,
  deviceId: localStorage.getItem(LS.deviceId) || null,
  /** 派生出来的密钥。 */
  keys: null,
  /** 本地快照：这一端持有的完整数据。 */
  snapshot: loadJson(LS.snapshot, emptySnapshot()),
  /** 上次同步时**对方**每条待办的 updatedAt。用来判断「对方动过哪些」。 */
  remoteBase: loadJson(LS.remoteBase, {}),
  /** 本地相对上次同步被改过的条目：uid → {updatedAt, task, localOnly}。 */
  pending: loadJson(LS.pending, {}),
  meta: loadJson(LS.meta, { lastSyncAt: null, lastDirection: null }),
  events: null,
  conflict: null,
  remoteSnapshot: null,
  activeTab: 'today',
  /**
   * 周课表当前在看第几周。null = 跟着手机算出来的「当前周」。
   *
   * 翻周之后**不写进 localStorage**：下次打开还看当前周，
   * 与 App 的课表页一致（App 也不记浏览位置）。
   */
  selectedWeek: null,
  syncing: false,
  pairCode: null,
  pairTimer: null,
};

if (state.secret) {
  try {
    state.keys = deriveKeys(fromBase64Url(state.secret), localStorage.getItem(LS.passcode) || '');
  } catch (_) {
    state.secret = null;
  }
}

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

// ── 本地存储 ────────────────────────────────────────────────

function loadJson(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : fallback;
  } catch (_) {
    return fallback;
  }
}

function saveJson(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch (_) {
    // 配额满了：这一端的本地快照写不进去。
    toast('浏览器存储写不下了，本地快照可能没保存', true);
  }
}

function emptySnapshot() {
  return {
    protocol: 1,
    revision: 0,
    generatedAt: 0,
    today: '',
    weekday: 0,
    deviceName: '这台设备',
    appVersion: '',
    latestEditAt: 0,
    // App 时区（Asia/Shanghai）。渲染时刻一律用它，不用浏览器本地时区。
    utcOffsetMinutes: 480,
    // 三个「这份快照里没有的东西别乱动」的护栏。
    // 网页端还没成功拉过一次手机数据时，这三块是**不知道**，不是**没有**。
    hasCourses: false,
    hasExams: false,
    hasTaskTypes: false,
    tasks: [],
    taskTypes: [],
    courses: [],
    exams: [],
    todayCourses: [],
  };
}

// ── 基础工具 ────────────────────────────────────────────────

function toast(msg, isError = false) {
  const el = $('#toast');
  el.textContent = msg;
  el.classList.toggle('is-error', isError);
  el.hidden = false;
  clearTimeout(el._t);
  el._t = setTimeout(() => { el.hidden = true; }, isError ? 4200 : 2400);
}

class ApiError extends Error {
  constructor(message, status) { super(message); this.status = status; }
}

// ── 签名与加密 ──────────────────────────────────────────────

/** 给一次请求签名。覆盖方法、路径、时间戳、nonce 与请求体摘要。 */
function sign(method, path, body) {
  const ts = Date.now();
  const nonce = toBase64Url(randomBytes(12));
  const sig = signRequest(state.keys, { method, path, ts, nonce, body: body || new Uint8Array(0) });
  return { ts, nonce, sig };
}

function authHeaders(method, path, body) {
  const { ts, nonce, sig } = sign(method, path, body);
  return {
    'X-ML-Device': state.deviceId,
    'X-ML-Ts': String(ts),
    'X-ML-Nonce': nonce,
    'X-ML-Sig': sig,
  };
}

/** SSE 用：EventSource 不能设请求头，所以签名参数走查询串。 */
function authQuery(path) {
  const { ts, nonce, sig } = sign('GET', path, new Uint8Array(0));
  return `device=${encodeURIComponent(state.deviceId)}&ts=${ts}&nonce=${encodeURIComponent(nonce)}&sig=${encodeURIComponent(sig)}`;
}

/**
 * 一次 API 调用。
 *
 * - 请求体被**加密**放进 `X-ML-Enc` 头（原文不上网络）；
 * - 响应体是密文，收到后解开。
 *
 * `plain: true` 用于**还没有密钥的时候**（申请配对）——
 * 那条请求里只有 secret 的摘要，没有需要保密的东西。
 */
async function api(path, { method = 'GET', body, auth = true, plain = false } = {}) {
  const headers = {};
  let rawBody = null;

  if (body !== undefined) {
    rawBody = utf8Bytes(JSON.stringify(body));
    if (plain) {
      headers['Content-Type'] = 'application/json';
    } else {
      headers['X-ML-Enc'] = toBase64Url(seal(state.keys, rawBody));
    }
  }
  if (auth && !plain) {
    Object.assign(headers, authHeaders(method, path, rawBody));
  }

  let res;
  try {
    res = await fetch(state.base + path, {
      method,
      headers,
      body: rawBody === null ? undefined : (plain ? rawBody : new Uint8Array(0)),
      cache: 'no-store',
    });
  } catch (e) {
    throw new ApiError(`连不上 ${state.base}`, 0);
  }

  if (res.status === 401) {
    const err = new ApiError('配对已失效或签名不对，需要重新扫码', 401);
    err.unauthorized = true;
    throw err;
  }

  const text = await res.text();
  let payload = text;
  if (res.headers.get('X-ML-Enc')) {
    try {
      payload = new TextDecoder().decode(open(state.keys, fromBase64Url(text)));
    } catch (e) {
      throw new ApiError('响应解密失败（密钥对不上？）', 500);
    }
  }

  if (!res.ok) {
    let detail = '';
    try { detail = (JSON.parse(payload)).error || ''; } catch (_) { /* 非 JSON */ }
    throw new ApiError(detail || `请求失败（${res.status}）`, res.status);
  }
  if (!text) return null;
  try {
    return JSON.parse(payload);
  } catch (_) {
    throw new ApiError('响应不是合法 JSON', 500);
  }
}

// ── 日期（只用服务端给的字符串，不做时区换算） ────────────────

function parseDay(day) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day || '');
  if (!m) return null;
  return new Date(Date.UTC(+m[1], +m[2] - 1, +m[3]));
}

/** 距今天几天：负数=过去，正数=未来。 */
function dayDiff(from, to) {
  const a = parseDay(from), b = parseDay(to);
  if (!a || !b) return null;
  return Math.round((b - a) / 86400000);
}

/** 「今天 23:59」/「明天 08:00」/「10-28 23:59」 */
function dueLabel(task, today) {
  const s = task.dueDateString || '';
  if (!s) return '';
  const date = s.slice(0, 10);
  const time = s.length > 10 ? s.slice(11) : '';
  const diff = dayDiff(today, date);
  if (diff === null) return s;
  if (diff === 0) return `今天 ${time}`;
  if (diff === 1) return `明天 ${time}`;
  if (diff === -1) return `昨天 ${time}`;
  if (diff < 0) return `逾期 ${Math.abs(diff)} 天`;
  if (diff <= 7) return `${diff} 天后`;
  return `${date.slice(5)} ${time}`;
}

function isOverdue(task, today) {
  if (task.isCompleted) return false;
  if (!task.dueDateString) return false;
  const diff = dayDiff(today, task.dueDateString.slice(0, 10));
  return diff !== null && diff < 0;
}

/** 两条待办「是不是同一份内容」。冲突判定用它。 */
function fingerprint(t) {
  if (!t) return '';
  return [
    t.title || '',
    t.description || '',
    t.isCompleted ? '1' : '0',
    t.priority || '',
    String(t.dueDateTimestamp ?? ''),
    t.taskType || '',
    t.courseKey || '',
  ].join('');
}

/**
 * 渲染一个时刻。**用 App 时区，不用浏览器本地时区。**
 *
 * 两台设备的时区设置可能不同。用户看到的「本页 21:14 · 手机 21:14」
 * 应该是同一个时刻的两种写法，而不是「差了一整个晚上」。
 */
function formatTime(millis, offsetMinutes) {
  if (!millis) return '—';
  const offset = offsetMinutes ?? state.snapshot?.utcOffsetMinutes ?? 480;
  const d = new Date(millis + offset * 60000);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())} ${p(d.getUTCHours())}:${p(d.getUTCMinutes())}`;
}

// ── 本地改动与冲突 ──────────────────────────────────────────

function hasPending() {
  return Object.keys(state.pending).length > 0;
}

/** 记一笔本地改动。离线时也会记 —— 连上之后一起推。 */
function markPending(task, localOnly = false) {
  state.pending[task.syncUid] = {
    updatedAt: task.updatedAt,
    task,
    localOnly: localOnly || state.pending[task.syncUid]?.localOnly || false,
  };
  saveJson(LS.pending, state.pending);
}

function saveLocal() {
  state.snapshot.latestEditAt = state.snapshot.tasks.reduce(
    (max, t) => Math.max(max, t.updatedAt || 0), 0);
  saveJson(LS.snapshot, state.snapshot);
}

/**
 * 冲突判定。
 *
 * 冲突 = **同一条待办，两端都动过，而且动得不一样**。
 *
 * 三种情况：
 * 1. 对方也改了这条，内容不同 → 真冲突；
 * 2. 对方也改了这条，内容一样 → 不算冲突（两边做了同一件事）；
 * 3. 对方**没有**这条，而本地改过它 → 冲突（一边改一边删）。
 *
 * `localOnly` 的那条是例外：它只在本地存在（新建的），对方没有它是正常的。
 */
function detectConflicts(remote) {
  const remoteByUid = new Map((remote.tasks || []).map((t) => [t.syncUid, t]));
  const conflicts = [];
  for (const [uid, p] of Object.entries(state.pending)) {
    const r = remoteByUid.get(uid);
    if (!r) {
      if (p.localOnly) continue;      // 本地新建，还没推过去
      conflicts.push({ uid, title: p.task.title, reason: 'deleted' });
      continue;
    }
    const base = state.remoteBase[uid] ?? 0;
    const remoteChanged = (r.updatedAt || 0) > base;
    if (!remoteChanged) continue;      // 对方没动过 → 不冲突
    if (fingerprint(r) === fingerprint(p.task)) continue; // 两边做了同一件事
    conflicts.push({ uid, title: p.task.title, reason: 'both' });
  }
  return conflicts;
}

/**
 * 把远端快照并进本地。
 *
 * **远端整体替换本地**，除了本地 pending 的那几条 ——
 * 它们要么是本地新建（远端没有），要么是本地更新（还没推过去）。
 * 远端有、两边都没动过的那些，自然跟着替换走，删除也就传播过来了。
 */
function applyRemote(remote, { keepPending = true } = {}) {
  const pendingByUid = new Map(Object.entries(state.pending).map(([uid, p]) => [uid, p.task]));
  const tasks = (remote.tasks || []).map((t) => {
    const p = pendingByUid.get(t.syncUid);
    if (keepPending && p && (p.updatedAt || 0) > (t.updatedAt || 0)) return p;
    return t;
  });
  for (const [uid, p] of Object.entries(state.pending)) {
    if (!(remote.tasks || []).some((t) => t.syncUid === uid) && p.localOnly) tasks.push(p.task);
  }

  const next = { ...state.snapshot, ...remote };
  next.tasks = tasks;
  next.hasCourses = remote.hasCourses !== false;
  next.hasExams = remote.hasExams !== false;
  next.hasTaskTypes = remote.hasTaskTypes !== false;
  state.snapshot = next;
  saveLocal();
}

/** 要推给手机的那一份：本地快照 + pending 的改动。 */
function buildPushPayload(mode) {
  const snap = state.snapshot;
  const tasks = mode === 'merge'
    ? Object.values(state.pending).map((p) => p.task)
    : snap.tasks;
  return {
    ...snap,
    tasks,
    // 护栏：本地从没拉到过手机的数据时，这三块是「不知道」不是「没有」。
    hasCourses: snap.hasCourses === true,
    hasExams: snap.hasExams === true,
    hasTaskTypes: snap.hasTaskTypes === true,
  };
}

// ── 同步 ────────────────────────────────────────────────────

function isOnline() {
  return !!state.secret && !!state.deviceId;
}

/**
 * 自动同步：两端都在线且没有冲突时走这条。
 *
 * 顺序有讲究：
 * 1. 先拉远端快照 —— 冲突判定需要「对方动过哪些」；
 * 2. 有冲突就**停下来**，不硬合；
 * 3. 没冲突就把远端并进本地，再把本地改动推上去。
 */
async function autoSync(reason = '') {
  if (!isOnline() || state.syncing) return;
  if (state.conflict) return;      // 有冲突在等用户选方向，不再自动
  state.syncing = true;
  updateSyncPill();
  try {
    const remote = await api('/api/snapshot');
    state.remoteSnapshot = remote;

    const conflicts = detectConflicts(remote);
    if (conflicts.length) {
      showConflict(conflicts, remote);
      return;
    }

    applyRemote(remote, { keepPending: true });
    render();

    if (hasPending()) {
      const resp = await api('/api/snapshot/apply', {
        method: 'POST',
        body: { mode: 'merge', snapshot: buildPushPayload('merge') },
      });
      if (resp && resp.keptUids && resp.keptUids.length) {
        const byUid = new Map((remote.tasks || []).map((t) => [t.syncUid, t]));
        showConflict(
          resp.keptUids.map((uid) => ({
            uid,
            title: (byUid.get(uid) || state.pending[uid]?.task)?.title || '',
            reason: 'both',
          })),
          remote,
        );
        return;
      }
      for (const [uid, p] of Object.entries(state.pending)) {
        state.remoteBase[uid] = p.updatedAt;
      }
      state.pending = {};
      saveJson(LS.pending, state.pending);
    }

    for (const t of remote.tasks || []) state.remoteBase[t.syncUid] = t.updatedAt || 0;
    saveJson(LS.remoteBase, state.remoteBase);

    state.meta.lastSyncAt = Date.now();
    state.meta.lastDirection = 'auto';
    saveJson(LS.meta, state.meta);
    render();
  } catch (e) {
    if (e.unauthorized) return handleUnauthorized();
    setLink('error', '同步失败');
    toast(e.message || '同步失败', true);
  } finally {
    state.syncing = false;
    updateSyncPill();
  }
}

/** 手动：以手机为准。 */
async function syncPull() {
  try {
    const remote = await api('/api/snapshot');
    state.remoteSnapshot = remote;
    state.snapshot = { ...emptySnapshot(), ...remote };
    state.pending = {};
    saveJson(LS.pending, state.pending);
    for (const t of remote.tasks || []) state.remoteBase[t.syncUid] = t.updatedAt || 0;
    saveJson(LS.remoteBase, state.remoteBase);
    saveLocal();
    state.meta.lastSyncAt = Date.now();
    state.meta.lastDirection = 'pull';
    saveJson(LS.meta, state.meta);
    state.conflict = null;
    render();
    updateSyncPill();
    toast('已换成手机上的数据');
  } catch (e) {
    if (e.unauthorized) return handleUnauthorized();
    toast(e.message || '拉取失败', true);
  }
}

/** 手动：以本页为准。 */
async function syncPush() {
  try {
    const resp = await api('/api/snapshot/apply', {
      method: 'POST',
      body: { mode: 'replace', snapshot: buildPushPayload('replace') },
    });
    if (resp && resp.appliedUids) {
      for (const uid of resp.appliedUids) {
        const t = localTask(uid);
        if (t) state.remoteBase[uid] = t.updatedAt || 0;
      }
      saveJson(LS.remoteBase, state.remoteBase);
    }
    state.pending = {};
    saveJson(LS.pending, state.pending);
    state.meta.lastSyncAt = Date.now();
    state.meta.lastDirection = 'push';
    saveJson(LS.meta, state.meta);
    state.conflict = null;
    render();
    updateSyncPill();
    toast('已把本页推到手机上');
  } catch (e) {
    if (e.unauthorized) return handleUnauthorized();
    toast(e.message || '推送失败', true);
  }
}

/** 手动：合并。远端为准 + 本地 pending 赢，然后整份推回去。 */
async function syncMerge() {
  try {
    const remote = await api('/api/snapshot');
    state.remoteSnapshot = remote;
    applyRemote(remote, { keepPending: true });
    const resp = await api('/api/snapshot/apply', {
      method: 'POST',
      body: { mode: 'replace', snapshot: buildPushPayload('replace') },
    });
    if (resp && resp.appliedUids) {
      for (const uid of resp.appliedUids) {
        const t = localTask(uid);
        if (t) state.remoteBase[uid] = t.updatedAt || 0;
      }
      saveJson(LS.remoteBase, state.remoteBase);
    }
    state.pending = {};
    saveJson(LS.pending, state.pending);
    state.meta.lastSyncAt = Date.now();
    state.meta.lastDirection = 'merge';
    saveJson(LS.meta, state.meta);
    state.conflict = null;
    render();
    updateSyncPill();
    toast('已合并');
  } catch (e) {
    if (e.unauthorized) return handleUnauthorized();
    toast(e.message || '合并失败', true);
  }
}

function showConflict(conflicts, remote) {
  state.conflict = { conflicts, remote };
  updateSyncPill();
  openSyncSheet();
  toast('两台设备改了同一条待办，需要你选一下方向', true);
}

function localTask(uid) {
  return state.snapshot.tasks.find((t) => t.syncUid === uid) || null;
}

// ── 连接与实时通道 ──────────────────────────────────────────

function setLink(kind, text) {
  $$('.pill').forEach((pill) => {
    pill.className = 'pill pill-' + kind;
    const label = pill.querySelector('#linkStateText, #linkStateTextMobile');
    if (label) label.textContent = text;
  });
  // 侧栏收起时只剩一颗状态点，它的颜色也得跟着走
  const dot = $('#btnSyncCompact');
  if (dot) dot.className = 'side-sync-dot is-' + kind;
}

/** 顶栏那个同步状态：实时同步 / 本地模式 / 需手动同步。 */
function updateSyncPill() {
  let text, cls;
  if (state.conflict) {
    text = '需手动同步';
    cls = 'is-conflict';
  } else if (!isOnline()) {
    text = '本地模式';
    cls = 'is-local';
  } else if (state.syncing) {
    text = '同步中…';
    cls = 'is-live';
  } else {
    text = '实时同步';
    cls = 'is-live';
  }
  $$('.sync-pill').forEach((pill) => {
    pill.className = 'sync-pill ' + cls;
    const label = pill.querySelector('#syncState, #syncStateMobile');
    if (label) label.textContent = text;
  });
}

function connectEvents() {
  stopEvents();
  if (!isOnline()) return;
  let es;
  try {
    es = new EventSource(`${state.base}/api/events?${authQuery('/api/events')}`);
  } catch (_) { return; }
  state.events = es;

  es.addEventListener('open', () => setLink('live', '已连接'));
  es.addEventListener('change', () => { scheduleAutoSync(); });
  // 手机端发起的同步请求：方向是从**网页端**（执行方）的视角说的。
  es.addEventListener('sync-request', (ev) => {
    let payload = {};
    try {
      const raw = JSON.parse(ev.data);
      payload = state.keys ? JSON.parse(new TextDecoder().decode(open(state.keys, fromBase64Url(raw)))) : raw;
    } catch (_) { /* 坏载荷按默认处理 */ }
    const dir = payload.direction || 'merge';
    if (dir === 'pull') syncPull();
    else if (dir === 'push') syncPush();
    else syncMerge();
  });
  es.addEventListener('error', () => setLink('wait', '重连中'));
}

let autoTimer = null;
function scheduleAutoSync() {
  clearTimeout(autoTimer);
  autoTimer = setTimeout(() => autoSync('change'), 250);
}

function stopEvents() {
  if (state.events) { state.events.close(); state.events = null; }
}

function handleUnauthorized() {
  state.secret = null;
  state.deviceId = null;
  state.keys = null;
  localStorage.removeItem(LS.secret);
  localStorage.removeItem(LS.deviceId);
  stopEvents();
  updateSyncPill();
  showPairView('配对已失效，请重新扫码');
}

// ── 渲染 ────────────────────────────────────────────────────

function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text !== undefined && text !== null) n.textContent = text;
  return n;
}

/**
 * 把课程色按 App 的方式混进卡片。
 *
 * App 的卡片是「白 55% 衬底 + 课程色 30%」（`CardBaseCoatAlpha` / `CourseTintAlpha`），
 * 左边再竖一条 4dp 的课程色条。网页端照抄这两条。
 */
function applyCourseTint(card, colorHex) {
  if (!colorHex) return;
  card.style.setProperty('--course', colorHex);
  card.style.setProperty('--tint', hexToRgba(colorHex, 0.30));
}

function hexToRgba(hex, alpha) {
  const h = String(hex || '').replace('#', '').trim();
  if (h.length !== 6 || /[^0-9a-fA-F]/.test(h)) return 'transparent';
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

const CHECK_SVG = '<svg viewBox="0 0 24 24"><path d="M5 13l4 4L19 7"/></svg>';

function taskCard(task, today) {
  const card = el('div', 'card' + (task.isCompleted ? ' is-done' : ''));
  applyCourseTint(card, task.courseColorHex);

  const check = el('button', 'check' + (task.isCompleted ? ' is-checked' : ''));
  check.type = 'button';
  check.setAttribute('role', 'checkbox');
  check.setAttribute('aria-checked', String(!!task.isCompleted));
  check.setAttribute('aria-label', task.isCompleted ? '标记为未完成' : '标记为已完成');
  check.innerHTML = CHECK_SVG;
  check.addEventListener('click', () => toggleTask(task, check));
  card.appendChild(check);

  const body = el('div', 'card-body');
  body.appendChild(el('p', 'card-title', task.title || '(无标题)'));

  const meta = el('div', 'card-meta');
  const bits = [];
  if (task.dueDateString) bits.push(dueLabel(task, today));
  if (task.courseName) bits.push(task.courseName);
  if (bits.length) meta.appendChild(document.createTextNode(bits.join(' · ')));
  body.appendChild(meta);

  const chips = el('div', 'card-meta');
  if (isOverdue(task, today)) chips.appendChild(el('span', 'chip chip-overdue', '逾期'));
  else if (task.dueDateString && dayDiff(today, task.dueDateString.slice(0, 10)) === 0)
    chips.appendChild(el('span', 'chip chip-soon', '今天截止'));
  if (task.taskTypeLabel) chips.appendChild(el('span', 'chip', task.taskTypeLabel));
  if (chips.children.length) body.appendChild(chips);

  card.appendChild(body);

  const actions = el('div', 'card-actions');
  const star = el('button', 'star-btn' + (task.priority === 'STAR' ? ' is-on' : ''), '★');
  star.type = 'button';
  star.title = task.priority === 'STAR' ? '取消星标' : '加星标';
  star.setAttribute('aria-label', star.title);
  star.addEventListener('click', () => toggleStar(task, star));
  actions.appendChild(star);
  card.appendChild(actions);

  return card;
}

function courseEntryCard(entry) {
  const card = el('div', 'card' + (entry.state === 'now' ? ' is-now' : ''));
  applyCourseTint(card, entry.colorHex);
  const bar = el('div', 'card-bar');
  bar.style.background = entry.colorHex || 'var(--text-3)';
  card.appendChild(bar);

  const body = el('div', 'card-body');
  body.appendChild(el('p', 'card-title', entry.name || '(未命名课程)'));
  const bits = [];
  if (entry.timeText) bits.push(entry.timeText);
  if (entry.classroom) bits.push(entry.classroom);
  if (entry.teacher) bits.push(entry.teacher);
  body.appendChild(el('div', 'card-meta', bits.join(' · ')));
  card.appendChild(body);

  if (entry.state === 'now') {
    card.appendChild(el('div', 'card-actions')).appendChild(el('span', 'chip chip-soon', '正在上'));
  }
  return card;
}

function courseCard(course) {
  const card = el('div', 'card');
  applyCourseTint(card, course.colorHex);
  const bar = el('div', 'card-bar');
  bar.style.background = course.colorHex || 'var(--text-3)';
  card.appendChild(bar);

  const body = el('div', 'card-body');
  body.appendChild(el('p', 'card-title', course.name || '(未命名课程)'));
  const bits = [];
  if (course.code) bits.push(course.code);
  if (course.teacher) bits.push(course.teacher);
  if (course.category) bits.push(course.category);
  body.appendChild(el('div', 'card-meta', bits.join(' · ')));
  if (course.weeklyText) body.appendChild(el('div', 'card-meta', course.weeklyText));
  if (course.note) body.appendChild(el('div', 'card-meta', '备注：' + course.note));
  card.appendChild(body);
  return card;
}

function examCard(exam) {
  const card = el('div', 'card');
  applyCourseTint(card, exam.colorHex);
  const bar = el('div', 'card-bar');
  bar.style.background = exam.colorHex || 'var(--text-3)';
  card.appendChild(bar);

  const body = el('div', 'card-body');
  body.appendChild(el('p', 'card-title', exam.courseName || exam.name || '(未命名考试)'));
  const bits = [];
  if (exam.whenText) bits.push(exam.whenText);
  bits.push(exam.classroom ? exam.classroom : '待学校安排');
  if (exam.seat) bits.push('座位 ' + exam.seat);
  body.appendChild(el('div', 'card-meta', bits.filter(Boolean).join(' · ')));
  if (exam.dateLabel) body.appendChild(el('div', 'card-meta', exam.dateLabel));
  card.appendChild(body);
  return card;
}

function renderEmpty(container, text) {
  container.replaceChildren(el('div', 'empty', text));
}

function render() {
  const snap = state.snapshot;
  if (!snap) return;
  const today = snap.today || '';

  // 今天的课
  const tc = $('#todayCourses');
  const entries = snap.todayCourses || [];
  if (!entries.length) renderEmpty(tc, '今天没有课');
  else {
    const tl = el('div', 'timeline');
    entries.forEach((e) => tl.appendChild(courseEntryCard(e)));
    tc.replaceChildren(tl);
  }
  $('#todayCourseCount').textContent = entries.length;

  // 今天的待办
  const todayTasks = (snap.tasks || []).filter((t) =>
    !t.isCompleted && (!t.dueDateString || t.dueDateString.slice(0, 10) === today));
  const tt = $('#todayTasks');
  if (!todayTasks.length) renderEmpty(tt, '今天没有待办');
  else tt.replaceChildren(...todayTasks.map((t) => taskCard(t, today)));
  $('#todayTaskCount').textContent = todayTasks.length;

  // 今天的考试
  const te = $('#todayExams');
  const exams = snap.exams || [];
  const todayExams = exams.filter((e) => e.isToday);
  if (!todayExams.length) renderEmpty(te, '今天没有考试');
  else te.replaceChildren(...todayExams.map((e) => examCard(e)));
  $('#todayExamCount').textContent = todayExams.length;

  // 全部待办
  const all = snap.tasks || [];
  const open = all.filter((t) => !t.isCompleted);
  const done = all.filter((t) => t.isCompleted);
  $('#openTaskCount').textContent = open.length;
  $('#doneTaskCount').textContent = done.length;
  const ot = $('#openTasks');
  if (!open.length) renderEmpty(ot, '没有待办');
  else ot.replaceChildren(...open.map((t) => taskCard(t, today)));
  const dt = $('#doneTasks');
  if (!done.length) renderEmpty(dt, '还没有完成的');
  else dt.replaceChildren(...done.map((t) => taskCard(t, today)));

  // 课程
  const courses = snap.courses || [];
  $('#courseCount').textContent = courses.length;
  const cl = $('#courseList');
  if (!courses.length) renderEmpty(cl, '还没有课程');
  else cl.replaceChildren(...courses.map((c) => courseCard(c)));

  // 考试
  $('#examCount').textContent = exams.length;
  const el2 = $('#examList');
  if (!exams.length) renderEmpty(el2, '还没有考试');
  else el2.replaceChildren(...exams.map((e) => examCard(e)));

  // 周课表
  renderWeekView(snap, courses, all, today);

  // 「周视图」这一档在**还没有周课表数据**时藏起来：
  // 老版本手机的快照里没有这一块，画出来是一张空表 ——
  // 那还不如直接不显示这一档，落在「课程」上。
  const hasTimetable = !!(snap.timetable && snap.timetable.weeks && snap.timetable.weeks.length);
  const weekTab = $('.subtab[data-sub="week"]');
  if (weekTab) weekTab.hidden = !hasTimetable;
  if (!hasTimetable && weekTab && weekTab.classList.contains('is-active')) {
    selectSubTab('courses');
  }

  $('#deviceName').textContent = state.remoteSnapshot?.deviceName || snap.deviceName || '这台设备';
  const dm = $('#deviceNameMobile');
  if (dm) dm.textContent = $('#deviceName').textContent;

  renderSyncSheet();
}

/**
 * 画周课表。
 *
 * ## 这一页**不自己算**课表
 *
 * 哪些课落在哪一天牵扯学期范围、停课、补课、开课周次，手机那边已经有一份
 * 权威实现（`ScheduleResolver`）。网页端手里只有快照里那份算好的
 * `timetable`，照着画即可 —— 两边不会出现「同一份数据两个答案」。
 *
 * ## 「今天」是哪一列
 *
 * 按**日期**判，不按星期几：翻到别的周时，周一那一列并不是今天。
 */
function renderWeekView(snap, courses, tasks, today) {
  const host = $('#weekGrid');
  if (!host) return;
  const timetable = snap.timetable || null;

  // 看第几周：翻过就跟着翻，没翻过就看当前周。
  let week = state.selectedWeek;
  if (!timetable || !timetable.weeks || !timetable.weeks.length) {
    state.selectedWeek = null;
    renderWeekGrid(host, {
      timetable: null, courses, tasks, today, week: 0,
      onSelectWeek: () => {}, onOpenCourse: () => {}, onOpenExam: () => {},
    });
    return;
  }
  const total = Math.max(1, timetable.totalWeeks || timetable.weeks.length);
  if (!week || week < 1 || week > total) week = timetable.currentWeek || 1;
  state.selectedWeek = week;

  renderWeekGrid(host, {
    timetable,
    courses,
    tasks,
    today,
    week,
    onSelectWeek: (w) => {
      const next = Math.max(1, Math.min(total, w));
      if (next === state.selectedWeek) return;
      state.selectedWeek = next;
      renderWeekView(snap, courses, tasks, today);
    },
    onOpenCourse: openCourseDetail,
    onOpenExam: openExamDetail,
  });
}

/** 课程详情（网页端只读：改课表在手机上做）。 */
function openCourseDetail(course) {
  const bits = [];
  if (course.code) bits.push(['课程代码', course.code]);
  if (course.teacher) bits.push(['教师', course.teacher]);
  if (course.category) bits.push(['分类', course.category]);
  if (course.classroom) bits.push(['教室', course.classroom]);
  if (course.weeklyText) bits.push(['上课时间', course.weeklyText]);
  if (course.note) bits.push(['备注', course.note]);
  openDetailSheet(course.name || '课程', bits);
}

/** 考试详情。 */
function openExamDetail(exam) {
  const bits = [];
  if (exam.whenText) bits.push(['时间', exam.whenText]);
  if (exam.classroom) bits.push(['考场', exam.classroom]);
  if (exam.seat) bits.push(['座位', exam.seat]);
  if (exam.termLabel) bits.push(['学期', exam.termLabel]);
  openDetailSheet(exam.courseName || '考试', bits);
}

function openDetailSheet(title, rows) {
  const body = $('#detailBody');
  if (!body) return;
  body.replaceChildren();
  if (!rows.length) body.appendChild(el('div', 'card-meta', '没有更多信息。'));
  for (const [label, value] of rows) {
    const row = el('div', 'detail-row');
    row.appendChild(el('span', 'detail-row-label', label));
    row.appendChild(el('span', 'detail-row-value', value));
    body.appendChild(row);
  }
  $('#detailTitle').textContent = title;
  $('#detailSheet').hidden = false;
}

// ── 本地改动 ────────────────────────────────────────────────

/** 改一条待办：先改本地（立刻可见），再尝试同步。 */
function applyLocalTaskChange(uid, mutate, { localOnly = false } = {}) {
  const snap = state.snapshot;
  const idx = snap.tasks.findIndex((t) => t.syncUid === uid);
  if (idx < 0) return null;
  const next = { ...snap.tasks[idx] };
  mutate(next);
  // 本地时间戳只用于**本地**的排序与仲裁；推到手机上之后，
  // 手机会用自己的时钟盖章，以手机那一份为准。
  next.updatedAt = Math.max(next.updatedAt || 0, Date.now());
  snap.tasks[idx] = next;
  saveLocal();
  markPending(next, localOnly);
  render();
  scheduleAutoSync();
  return next;
}

function toggleTask(task, checkEl) {
  const next = !task.isCompleted;
  applyLocalTaskChange(task.syncUid, (t) => {
    t.isCompleted = next;
    t.completedAt = next ? Date.now() : null;
  });
  checkEl.classList.toggle('is-checked', next);
  checkEl.setAttribute('aria-checked', String(next));
}

function toggleStar(task, starEl) {
  const next = task.priority === 'STAR' ? '' : 'STAR';
  applyLocalTaskChange(task.syncUid, (t) => { t.priority = next; });
  starEl.classList.toggle('is-on', next === 'STAR');
}

function addTask(title) {
  const uid = 'web-' + Math.random().toString(36).slice(2, 10) + Date.now().toString(36);
  const task = {
    syncUid: uid,
    title,
    description: '',
    taskType: '',
    taskTypeLabel: '',
    priority: '',
    isCompleted: false,
    completedAt: null,
    startTimeTimestamp: 0,
    dueDateTimestamp: -9223372036854775808,
    dueDateString: '',
    startTimeString: '',
    isAutoGenerated: false,
    week: 0,
    dayOfWeek: 0,
    courseKey: '',
    courseName: '',
    courseCategory: '',
    courseColorHex: '#C9D0D8',
    createdAt: Date.now(),
    updatedAt: Date.now(),
  };
  state.snapshot.tasks = [task, ...(state.snapshot.tasks || [])];
  saveLocal();
  markPending(task, true);
  render();
  toast('已添加');
  scheduleAutoSync();
}

// ── 同步面板 ────────────────────────────────────────────────

function openSyncSheet() {
  renderSyncSheet();
  $('#syncSheet').hidden = false;
}

function renderSyncSheet() {
  const local = state.snapshot.latestEditAt || 0;
  const remote = state.remoteSnapshot?.latestEditAt ?? 0;
  $('#syncLocalTime').textContent = formatTime(local);
  $('#syncRemoteTime').textContent = state.remoteSnapshot ? formatTime(remote) : '未连接';

  const conflictBox = $('#syncConflict');
  if (state.conflict) {
    conflictBox.hidden = false;
    const titles = state.conflict.conflicts.map((c) => c.title).filter(Boolean).slice(0, 3);
    $('#syncConflictDetail').textContent =
      '「' + titles.join('」「') + '」两边都改过，' +
      '需要你决定以哪边为准（' +
      `本页 ${formatTime(local)} · 手机 ${formatTime(remote)}` + '）。';
    $('#syncDesc').textContent = '有冲突，自动同步已经停下。选一个方向继续。';
  } else {
    conflictBox.hidden = true;
    $('#syncDesc').textContent = state.meta.lastSyncAt
      ? '上次同步：' + formatTime(state.meta.lastSyncAt) + '。两端都在线且没有冲突时会自动同步。'
      : '两端都在线且没有冲突时会自动同步。';
  }
}

// ── 配对 ────────────────────────────────────────────────────

function showPairView(message) {
  $('#mainView').hidden = true;
  $('#pairView').hidden = false;
  setLink('idle', '未配对');
  updateSyncPill();
  if (message) $('#pairDesc').textContent = message;
  requestPairCode();
}

function showMainView() {
  $('#pairView').hidden = true;
  $('#mainView').hidden = false;
}

/**
 * 申请配对：**secret 在本页本地生成**。
 *
 * 它只经二维码（视觉通道）交给手机，**从不上网络** ——
 * 服务端只见到 `requestId`（secret 的摘要）。
 *
 * ## `?devsecret=` 这个口子
 *
 * 只有本地 mock 服务用得上：mock 没有摄像头，扮演不了「扫码的那一方」，
 * 于是它自己持有一把固定的 secret，而本页在 URL 里显式带了 `devsecret`
 * 时就改用它（而不是随机生成）—— 两边于是算得出同一套密钥。
 *
 * **真机上没有这个参数**，secret 照旧随机生成、只走二维码。
 */
async function requestPairCode() {
  stopPairPolling();
  try {
    const devSecret = new URLSearchParams(location.search).get('devsecret');
    const secretBytes = devSecret ? fromBase64Url(devSecret) : randomBytes(32);
    const secret = toBase64Url(secretBytes);
    // requestId = base64url(sha256(secret) 的前 16 字节)，与服务端同一套算法。
    const requestId = toBase64Url(sha256(secretBytes).slice(0, 16));

    await api('/api/pair/request', {
      method: 'POST',
      body: { requestId, deviceName: '网页端' },
      auth: false,
      plain: true,
    });

    state.pairCode = requestId;
    state.pairSecret = secret;
    $('#pairCode').textContent = requestId.slice(0, 6).toUpperCase();
    $('#pairDesc').textContent = '用手机相机扫描这张二维码，在 MiuList 里确认一次即可。';

    renderPairQr(requestId, secret);

    pollPair(requestId, secret);
  } catch (e) {
    $('#pairDesc').textContent = '拿不到配对码：' + (e.message || e);
    setLink('error', '配对失败');
  }
}

/**
 * 二维码在**本页**画 —— secret 不能交给服务端去渲染（那会让它过一次网络）。
 *
 * 用的是 vendored 的 `qrcode-generator`（MIT，见 web/vendor/）。
 */
function renderPairQr(requestId, secret) {
  const box = $('#pairQr');
  const fallback = $('#pairQrFallback');
  const payload = `miulist://pair?v=3&s=${encodeURIComponent(secret)}&r=${requestId}`;
  try {
    const qr = window.qrcode(0, 'M');
    qr.addData(payload);
    qr.make();
    box.innerHTML = qr.createSvgTag({ cellSize: 4, margin: 2, scalable: true });
    box.hidden = false;
    fallback.hidden = true;
    $('#pairPayload').textContent = payload;
  } catch (e) {
    box.hidden = true;
    fallback.hidden = false;
    fallback.textContent = payload;
  }
}

function stopPairPolling() {
  clearInterval(state.pairTimer);
  state.pairTimer = null;
}

function pollPair(requestId, secret) {
  stopPairPolling();
  state.pairTimer = setInterval(async () => {
    try {
      // 状态查询是免鉴权的，但**里面没有凭据**。
      const res = await api(`/api/pair/status?requestId=${encodeURIComponent(requestId)}`, { auth: false });
      if (res.state === 'approved' && res.deviceId) {
        stopPairPolling();
        // 密钥在这里才第一次出现：secret 从头到尾没有上过网络。
        state.secret = secret;
        state.deviceId = res.deviceId;
        localStorage.setItem(LS.secret, secret);
        localStorage.setItem(LS.deviceId, res.deviceId);
        state.keys = deriveKeys(fromBase64Url(secret), localStorage.getItem(LS.passcode) || '');
        showMainView();
        updateSyncPill();
        await autoSync('paired');
        connectEvents();
        toast('配对成功');
      } else if (res.state === 'expired' || res.state === 'denied') {
        stopPairPolling();
        $('#pairDesc').textContent = res.state === 'expired' ? '配对码过期了，点「换一个配对码」' : '手机那边拒绝了';
      }
    } catch (_) { /* 轮询失败就等下一轮 */ }
  }, 1500);
}

// ── 主题与外观 ──────────────────────────────────────────────

const THEMES = [
  { id: 'system', label: '跟随系统' },
  { id: 'light', label: '浅色' },
  { id: 'dark', label: '深色' },
];

/**
 * 背景渐变预设。
 *
 * **名字与色值都取自 App 的壁纸预设**（`WallpaperPresets` 里记着每张的实测三段色），
 * 所以手机与网页端看到的是同一组渐变。
 */
const GRADIENTS = [
  { id: 'cloudy', label: 'Cloudy Knoxville', cls: 'grad-cloudy' },
  { id: 'kind-steel', label: 'Kind Steel', cls: 'grad-kind-steel' },
  { id: 'soft-grass', label: 'Soft Grass', cls: 'grad-soft-grass' },
  { id: 'rare-wind', label: 'Rare Wind', cls: 'grad-rare-wind' },
  { id: 'teen-notebook', label: 'Teen Notebook', cls: 'grad-teen-note' },
  { id: 'sweet-period', label: 'Sweet Period', cls: 'grad-sweet-period' },
  { id: 'nega', label: 'Nega', cls: 'grad-nega' },
  { id: 'salt-mountain', label: 'Salt Mountain', cls: 'grad-salt' },
];

function applyGradient(id) {
  const preset = GRADIENTS.find((g) => g.id === id) || GRADIENTS[0];
  document.body.className = preset.cls;
  localStorage.setItem(LS.gradient, preset.id);
  $$('.grad-swatch').forEach((b) => b.classList.toggle('is-active', b.dataset.id === preset.id));
}

function renderGradientSwatches() {
  const box = $('#gradSwatches');
  if (!box) return;
  const current = localStorage.getItem(LS.gradient) || GRADIENTS[0].id;
  box.replaceChildren(...GRADIENTS.map((g) => {
    const b = el('button', 'grad-swatch ' + g.cls);
    b.type = 'button';
    b.dataset.id = g.id;
    b.title = g.label;
    b.setAttribute('aria-label', '背景：' + g.label);
    b.addEventListener('click', () => {
      applyGradient(g.id);
      toast('背景：' + g.label);
    });
    return b;
  }));
  applyGradient(current);
}

function applyTheme(theme) {
  const root = document.documentElement;
  if (theme === 'system') root.removeAttribute('data-theme');
  else root.setAttribute('data-theme', theme);
  localStorage.setItem(LS.theme, theme);
  $$('#btnTheme, #btnThemeDesktop').forEach((btn) => {
    if (!btn) return;
    btn.title = '主题：' + (THEMES.find((t) => t.id === theme) || THEMES[0]).label;
    btn.setAttribute('aria-label', btn.title);
  });
}

function cycleTheme() {
  const cur = localStorage.getItem(LS.theme) || 'system';
  const i = THEMES.findIndex((t) => t.id === cur);
  const next = THEMES[(i + 1) % THEMES.length];
  applyTheme(next.id);
  toast('主题：' + next.label);
}

// ── 侧栏形态 ────────────────────────────────────────────────

/**
 * 侧栏有两个形态：**收起的图标条** 与 **展开的完整栏**。
 *
 * - 鼠标**靠近**侧栏就展开、移开就收回 —— 纯 CSS 的 `:has(.sidebar:hover)` 干的；
 * - 点折叠按钮 = **钉住**，钉住后一直展开，鼠标移开也不收；
 * - 再点一下 = 取消钉住，回到「靠近就展开」。
 *
 * 「钉住」记进 localStorage：用户主动选过的形态，不该下次打开又被改回去。
 */
function applySideState() {
  const pinned = localStorage.getItem(LS.sidePinned) === '1';
  $('#app').classList.toggle('is-side-pinned', pinned);
  const btn = $('#btnSideToggle');
  if (!btn) return;
  const label = pinned ? '收起侧栏（鼠标靠近仍会展开）' : '展开侧栏';
  btn.title = label;
  btn.setAttribute('aria-label', label);
  btn.setAttribute('aria-expanded', pinned ? 'true' : 'false');
}

function toggleSidePinned() {
  const pinned = localStorage.getItem(LS.sidePinned) === '1';
  localStorage.setItem(LS.sidePinned, pinned ? '0' : '1');
  applySideState();
}

// ── 事件绑定 ────────────────────────────────────────────────

function bind() {
  applyTheme(localStorage.getItem(LS.theme) || 'system');
  applyGradient(localStorage.getItem(LS.gradient) || GRADIENTS[0].id);
  renderGradientSwatches();
  applySideState();

  $('#btnTheme').addEventListener('click', cycleTheme);
  $('#btnThemeDesktop').addEventListener('click', cycleTheme);
  $('#btnSync').addEventListener('click', openSyncSheet);
  $('#btnSyncMobile').addEventListener('click', openSyncSheet);
  // 侧栏收起时底部只剩一颗状态点，点它照样打开同步面板
  $('#btnSyncCompact').addEventListener('click', openSyncSheet);
  $('#btnSideToggle').addEventListener('click', toggleSidePinned);
  $('#btnSyncClose').addEventListener('click', () => { $('#syncSheet').hidden = true; });
  $('#btnSyncPull').addEventListener('click', syncPull);
  $('#btnSyncPush').addEventListener('click', syncPush);
  $('#btnSyncMerge').addEventListener('click', syncMerge);

  // 侧栏与浮岛上的入口共用 `data-tab`，一处切换两处高亮。
  $$('[data-tab]').forEach((b) => {
    b.addEventListener('click', () => selectTab(b.dataset.tab));
  });

  // 课表页的子页签（周视图 / 课程 / 考试）——与 App 的课表页同一层级。
  $$('.subtab').forEach((b) => {
    b.addEventListener('click', () => selectSubTab(b.dataset.sub));
  });

  // 周课表格子 / 考试格子的详情弹层
  $('#btnDetailClose').addEventListener('click', () => { $('#detailSheet').hidden = true; });
  $('#detailSheet').addEventListener('click', (e) => {
    // 点遮罩空白处关闭（点弹层本身不关）
    if (e.target === $('#detailSheet')) $('#detailSheet').hidden = true;
  });

  // 点周次选择器外面就收起它。**只挂这一个** —— 挂进渲染里会每次渲染多一个。
  document.addEventListener('click', () => {
    const picker = $('.week-picker');
    if (picker && !picker.hidden) picker.hidden = true;
  });

  const doRefresh = async (e) => {
    const b = e.currentTarget;
    b.classList.add('is-busy');
    await autoSync('manual-refresh');
    b.classList.remove('is-busy');
  };
  $('#btnRefresh').addEventListener('click', doRefresh);
  $('#btnRefreshDesktop').addEventListener('click', doRefresh);

  $('#btnPair').addEventListener('click', () => {
    if (isOnline()) {
      toast(`已连接 ${state.remoteSnapshot?.deviceName || '手机'}`);
      return;
    }
    showPairView();
  });

  $('#btnPairRetry').addEventListener('click', requestPairCode);

  // 同步密码：**在申请配对码之前就要定下来** —— 它会混进密钥派生，
  // 两边算错一个字符就永远解不开。
  //
  // 规则是 **4 位数字**（用户定的）：好敲、好念。
  // 代价要说清楚：10⁴ 种 —— 拿到配对 secret 的人可以离线毫秒级爆破它。
  // 它挡的是「旁边有人瞄到屏幕」，不是密码学意义上的第二因子。
  const passcodeInput = $('#pairPasscode');
  if (passcodeInput) {
    passcodeInput.value = localStorage.getItem(LS.passcode) || '';
    passcodeInput.addEventListener('change', () => {
      const raw = passcodeInput.value;
      const digits = raw.replace(/\D/g, '');
      if (digits !== raw || digits.length > 4) {
        passcodeInput.value = digits.slice(0, 4);
        toast('同步密码只能是 4 位数字');
      }
      localStorage.setItem(LS.passcode, digits.slice(0, 4));
      // 密码变了，已派生的密钥就作废了。
      if (state.secret) {
        state.keys = deriveKeys(fromBase64Url(state.secret), digits.slice(0, 4));
      }
    });
  }

  $('#quickAdd').addEventListener('submit', (e) => {
    e.preventDefault();
    const input = $('#quickAddInput');
    const title = input.value.trim();
    if (!title) return;
    input.value = '';
    addTask(title);
  });

  const manualUrlInline = $('#manualUrlInline');
  if (manualUrlInline) manualUrlInline.value = state.base;
  $('#btnManualInline').addEventListener('click', async () => {
    const v = (manualUrlInline.value || '').trim().replace(/\/+$/, '');
    if (!v) return;
    state.base = v;
    localStorage.setItem(LS.base, v);
    await boot();
  });

  // 「手动输入地址」在配对卡的折叠区里（#btnManualInline）。
  // 旧的那个独立弹层还留着（#manualSheet），它自己那套按钮照样能用。
  $('#btnManualCancel').addEventListener('click', () => { $('#manualSheet').hidden = true; });
  $('#btnManualOk').addEventListener('click', async () => {
    const v = $('#manualUrl').value.trim().replace(/\/+$/, '');
    if (!v) return;
    state.base = v;
    localStorage.setItem(LS.base, v);
    $('#manualSheet').hidden = true;
    await boot();
  });

  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && isOnline()) autoSync('visible');
  });

  window.addEventListener('online', () => { if (isOnline()) autoSync('online'); });
}

/**
 * 三个主入口，与 App 的浮岛一致：**总览 / 课表 / 清单**。
 * 考试不在这里 —— 它归在课表页里（App 的课表页也是这么分的）。
 */
const PAGE_META = {
  today: ['总览', '今天的课、待办与考试。'],
  schedule: ['课表', '本学期的课，以及考试安排。'],
  tasks: ['清单', '手动加的、课表自动生成的，都在这。'],
};

function selectTab(name) {
  state.activeTab = name;
  $$('[data-tab]').forEach((b) => b.classList.toggle('is-active', b.dataset.tab === name));
  $$('.panel').forEach((p) => { p.hidden = p.dataset.panel !== name; });
  document.body.dataset.tab = name;
  const meta = PAGE_META[name];
  if (meta) {
    $('#pageTitle').textContent = meta[0];
    $('#pageSub').textContent = meta[1];
  }
}

function selectSubTab(name) {
  $$('.subtab').forEach((b) => b.classList.toggle('is-active', b.dataset.sub === name));
  $$('[data-subpanel]').forEach((p) => { p.hidden = p.dataset.subpanel !== name; });
}

// ── 启动 ────────────────────────────────────────────────────

async function boot() {
  // 调试用：`?reset` 清掉本地快照与配对，重新从头来。
  // 没有它的话，换了协议版本之后旧的本地快照会一直留在浏览器里，
  // 排查时看到的是「旧数据 + 新界面」，很难判断是哪一边的问题。
  //
  // **只去掉 reset 这一个参数**：`?devsecret=…&reset` 得保留 devsecret，
  // 否则重定向过去就配不上了。
  if (new URLSearchParams(location.search).has('reset')) {
    const q = new URLSearchParams(location.search);
    q.delete('reset');
    Object.values(LS).forEach((k) => localStorage.removeItem(k));
    location.replace(location.pathname + (q.toString() ? '?' + q.toString() : ''));
    return;
  }

  bind();
  updateSyncPill();

  // 先把本地快照画出来 —— **没有手机也能用**。
  render();

  try {
    const health = await api('/api/health', { auth: false });
    $('#deviceName').textContent = health.deviceName || '这台设备';
  } catch (_) {
    $('#deviceName').textContent = '本地模式';
    updateSyncPill();
    showPairView('这个地址上没有 MiuList 服务。可以先看本地保存的数据，连上后再同步。');
    return;
  }

  if (!isOnline()) {
    showPairView();
    return;
  }
  showMainView();
  connectEvents();
  await autoSync('boot');
}

boot();
