import {
  deriveKeys,
  fromBase64Url,
  open as openSealed,
  randomBytes,
  seal,
  sha256,
  signRequest,
  toBase64Url,
  utf8Bytes,
  dhGenerateKeypair,
  dhSharedSecret,
  pairCodeOf,
} from './crypto';
// 配对的纯逻辑（地址归一化、要不要再要一张配对码）与协议一样只有一份：
// 直接复用 web/pairing.js，node 能直接跑它的自检（web/pairing.test.mjs）。
// @ts-ignore - 共享的纯 JS 模块
import * as pairing from '../../../web/pairing.js';
// 「找到手机」也一样只有一份：web/discovery.js（node 能跑它的自检）。
// @ts-ignore - 共享的纯 JS 模块
import * as discovery from '../../../web/discovery.js';
import { dueFromInput, formatTime } from './format';
import { scheduleSnapshotFileSave, restoreSnapshotFileOnBoot } from './snapshotFile';
import {
  loadPasscode,
  normalizeSnapshot,
  persistBase,
  persistMeta,
  persistPending,
  persistRemoteBase,
  persistSession,
  persistSnapshot,
} from './storage';
import { useAppStore } from './store';
import { taskTypeOptions } from './taskTypes';
import {
  NO_DUE,
  type ConflictItem,
  type ConflictState,
  type PairMode,
  type PairState,
  type PendingMap,
  type Snapshot,
  type Task,
  type TaskDraft,
} from './types';

type ApiOptions = {
  method?: string;
  body?: unknown;
  auth?: boolean;
  plain?: boolean;
  timeoutMs?: number;
};

class ApiError extends Error {
  status: number;
  unauthorized?: boolean;
  constructor(message: string, status = 0) {
    super(message);
    this.status = status;
  }
}

let sessionKeys: unknown = null;
let pairTimer: ReturnType<typeof setInterval> | null = null;
let pairAttempts = 0;
/**
 * 配对用的临时 DH 私钥。
 *
 * **不放进 store** —— 它是密钥材料，配对一结束就该扔掉。
 * store 里的东西会被快照文件、localStorage 带着走，密钥不该进去。
 */
let pairPriv: Uint8Array | null = null;
let sse: EventSource | null = null;
let pollTimer: ReturnType<typeof setInterval> | null = null;
let autoTimer: ReturnType<typeof setTimeout> | null = null;
let booted = false;
let globalBound = false;
let lastErrorToastAt = 0;

const get = () => useAppStore.getState();
const set = (patch: Partial<ReturnType<typeof get>>) => useAppStore.setState(patch);
const toast = (message: string, kind: 'info' | 'success' | 'error' = 'info') =>
  get().pushToast(message, kind);

function baseRoot(): string {
  return (get().base || '').replace(/\/+$/, '');
}

function isOnline(): boolean {
  const s = get();
  return Boolean(s.base && s.secret && s.deviceId);
}

/**
 * 地址归一化。**实现只有一份**，在 `web/pairing.js` 里（可被 node 自检）。
 *
 * 这里再导出一次，是为了别处只认 `../core/sync` 这一个入口。
 */
export const normalizeBase: (input: string) => string = pairing.normalizeBase as (
  input: string,
) => string;

/** 「现在该不该再要一张配对码」的判定。同样只有一份，在 `web/pairing.js` 里。 */
const shouldRequestPairCode: (status: string, force?: boolean) => boolean =
  pairing.shouldRequestPairCode as (status: string, force?: boolean) => boolean;

function initKeys(secret: string, passcode = loadPasscode()): boolean {
  try {
    sessionKeys = deriveKeys(fromBase64Url(secret), passcode || '');
    return true;
  } catch {
    sessionKeys = null;
    return false;
  }
}

function sign(method: string, path: string, body: Uint8Array | null) {
  const ts = Date.now();
  const nonce = toBase64Url(randomBytes(12));
  const sig = signRequest(sessionKeys, {
    method,
    path,
    ts,
    nonce,
    body: body || new Uint8Array(0),
  });
  return { ts, nonce, sig };
}

function authHeaders(method: string, path: string, body: Uint8Array | null) {
  const { ts, nonce, sig } = sign(method, path, body);
  return {
    'X-ML-Device': get().deviceId || '',
    'X-ML-Ts': String(ts),
    'X-ML-Nonce': nonce,
    'X-ML-Sig': sig,
  };
}

function authQuery(path: string): string {
  const { ts, nonce, sig } = sign('GET', path, new Uint8Array(0));
  return `device=${encodeURIComponent(get().deviceId || '')}&ts=${ts}&nonce=${encodeURIComponent(
    nonce,
  )}&sig=${encodeURIComponent(sig)}`;
}

async function api(path: string, options: ApiOptions = {}): Promise<any> {
  const {
    method = 'GET',
    body,
    auth = true,
    plain = false,
    timeoutMs = 15000,
  } = options;
  const root = baseRoot();
  if (!root) throw new ApiError('还没有填写手机地址', 0);

  const headers: Record<string, string> = {};
  let rawBody: Uint8Array | null = null;
  let requestBody: BodyInit | undefined;
  if (body !== undefined) {
    rawBody = utf8Bytes(JSON.stringify(body));
    if (plain) {
      headers['Content-Type'] = 'application/json';
      requestBody = rawBody as unknown as BodyInit;
    } else {
      if (!sessionKeys) throw new ApiError('还没有配对', 401);
      // 密文走 HTTP body：完整快照带周课表会超过 16 KB 的请求头上限，
      // 放在 X-ML-Enc 头里会被手机直接顶断（浏览器只报 CORS 失败）。
      headers['X-ML-Enc'] = '1';
      headers['Content-Type'] = 'application/octet-stream';
      requestBody = seal(sessionKeys, rawBody) as unknown as BodyInit;
    }
  }
  if (auth && !plain) {
    if (!sessionKeys) throw new ApiError('还没有配对', 401);
    Object.assign(headers, authHeaders(method, path, rawBody));
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  let res: Response;
  try {
    res = await fetch(root + path, {
      method,
      headers,
      body: requestBody,
      cache: 'no-store',
      signal: controller.signal,
    });
  } catch {
    throw new ApiError(`连不上 ${root}`, 0);
  } finally {
    clearTimeout(timer);
  }

  if (res.status === 401) {
    const error = new ApiError('配对已失效或签名不对，需要重新扫码', 401);
    error.unauthorized = true;
    throw error;
  }

  const text = await res.text();
  let payload: any = text;
  if (res.headers.get('X-ML-Enc')) {
    try {
      payload = new TextDecoder().decode(openSealed(sessionKeys, fromBase64Url(text)));
    } catch {
      // 解不开只有一种可能：**两边的密钥不是同一把**。
      // 密钥 = secret + 同步密码 派生出来的，而 secret 两端是一样的
      // （都来自那张二维码），所以差异只可能出在**同步密码**上。
      throw new ApiError(
        '密钥对不上 —— 手机上若设了同步密码，在配对处填同样的 4 位数字后重新配对',
        500,
      );
    }
  }
  if (!res.ok) {
    let detail = '';
    try {
      detail = JSON.parse(payload)?.error || '';
    } catch {
      /* ignore */
    }
    throw new ApiError(detail || `请求失败（${res.status}）`, res.status);
  }
  if (!text) return null;
  try {
    return JSON.parse(payload);
  } catch {
    throw new ApiError('响应不是合法 JSON', 500);
  }
}

// ── 本地快照 ────────────────────────────────────────────────

function saveLocalSnapshot(snapshot: Snapshot): void {
  const latestEditAt = (snapshot.tasks || []).reduce(
    (max, task) => Math.max(max, task.updatedAt || 0),
    0,
  );
  const next = { ...snapshot, latestEditAt };
  set({ snapshot: next });
  persistSnapshot(next);
  scheduleSnapshotFileSave();
}

function persistAll(): void {
  const s = get();
  persistSnapshot(s.snapshot);
  persistPending(s.pending);
  persistRemoteBase(s.remoteBase);
  persistMeta(s.meta);
  persistSession(s.secret, s.deviceId);
  persistBase(s.base);
  scheduleSnapshotFileSave();
}

function hasPending(pending: PendingMap): boolean {
  return Object.keys(pending).length > 0;
}

function markPending(task: Task, localOnly = false): PendingMap {
  const pending = {
    ...get().pending,
    [task.syncUid]: {
      updatedAt: task.updatedAt,
      task,
      localOnly: localOnly || get().pending[task.syncUid]?.localOnly || false,
    },
  };
  set({ pending });
  persistPending(pending);
  scheduleSnapshotFileSave();
  return pending;
}

function fingerprint(task: Task | null): string {
  if (!task) return '';
  return [
    task.title || '',
    task.description || '',
    task.isCompleted ? '1' : '0',
    task.priority || '',
    String(task.dueDateTimestamp ?? ''),
    task.taskType || '',
    task.courseKey || '',
  ].join('\u0001');
}

function detectConflicts(remote: Snapshot): ConflictItem[] {
  const s = get();
  const remoteByUid = new Map((remote.tasks || []).map((task) => [task.syncUid, task]));
  const conflicts: ConflictItem[] = [];
  for (const [uid, entry] of Object.entries(s.pending)) {
    const remoteTask = remoteByUid.get(uid);
    if (!remoteTask) {
      if (entry.localOnly) continue;
      conflicts.push({ uid, title: entry.task.title, reason: 'deleted' });
      continue;
    }
    const base = s.remoteBase[uid] ?? 0;
    if ((remoteTask.updatedAt || 0) <= base) continue;
    if (fingerprint(remoteTask) === fingerprint(entry.task)) continue;
    conflicts.push({ uid, title: entry.task.title, reason: 'both' });
  }
  return conflicts;
}

/**
 * 把远端（手机）那份快照并进本机。
 *
 * ## 只有待办会跟着同步走
 *
 * 课表、考试、自定义分类、周课表是**配置**，不是数据：
 * 两端各持一份、各管各的（网页端是独立的一端，不是手机的镜像）。
 * 所以这里**只**并入待办，配置原样留在本机。
 *
 * 远端的「今天 / 星期几 / 时区偏移」照收 —— 那是**时间基准**，
 * 不是配置：网页端显示截止时间要按手机那边的时区算，换算错一位就会差一天。
 */
function applyRemote(remote: Snapshot): void {
  const s = get();
  const pendingTasks = new Map(
    Object.entries(s.pending).map(([uid, entry]) => [uid, entry.task]),
  );
  const tasks: Task[] = (remote.tasks || []).map((task) => {
    const local = pendingTasks.get(task.syncUid);
    if (local && (local.updatedAt || 0) > (task.updatedAt || 0)) return local;
    return task;
  });
  for (const [uid, entry] of Object.entries(s.pending)) {
    if (!(remote.tasks || []).some((task) => task.syncUid === uid) && entry.localOnly) {
      tasks.push(entry.task);
    }
  }
  const next: Snapshot = {
    ...s.snapshot,
    ...remote,
    tasks,
    // 配置：留本机自己的。
    courses: s.snapshot.courses,
    exams: s.snapshot.exams,
    taskTypes: s.snapshot.taskTypes,
    timetable: s.snapshot.timetable,
    todayCourses: s.snapshot.todayCourses,
    // 这三个标记描述的是**本机**这份快照有没有配置 ——
    // 推给手机时，手机按它决定要不要动自己的课表。
    hasCourses: (s.snapshot.courses?.length || 0) > 0,
    hasExams: (s.snapshot.exams?.length || 0) > 0,
    hasTaskTypes: (s.snapshot.taskTypes?.length || 0) > 0,
  };
  saveLocalSnapshot(next);
}

/**
 * 推给手机的载荷。
 *
 * `includeConfig` 默认**关**：不带配置，手机就保留自己的课表。
 * 只有用户明确点「把本页课表推给手机」时才打开。
 *
 * 带上 `hasCourses` 这个标记是**危险**的：手机侧收到它就会
 * 「先清空整张课表，再按这份重建」（见 `SnapshotApplier.replaceCourses`）。
 * 所以本机一门课都没有的时候，标记必须是 false ——
 * 否则一次空推送就把手机上的课表清空了。
 */
function buildPushPayload(mode: 'merge' | 'replace', includeConfig = false): Snapshot {
  const s = get();
  const snapshot = s.snapshot;
  const tasks =
    mode === 'merge'
      ? Object.values(s.pending).map((entry) => entry.task)
      : snapshot.tasks;
  return {
    ...snapshot,
    tasks,
    hasCourses: includeConfig && (snapshot.courses?.length || 0) > 0,
    hasExams: includeConfig && (snapshot.exams?.length || 0) > 0,
    hasTaskTypes: includeConfig && (snapshot.taskTypes?.length || 0) > 0,
  };
}

/**
 * 只推配置、不动待办的载荷。
 *
 * `tasks` 传空数组 + `mode=merge`：手机侧一条待办都不会碰，
 * 而配置照常被替换（`SnapshotApplier` 里配置与待办是两条独立的路）。
 */
function buildConfigPushPayload(): Snapshot {
  const s = get();
  const snapshot = s.snapshot;
  return {
    ...snapshot,
    tasks: [],
    hasCourses: (snapshot.courses?.length || 0) > 0,
    hasExams: (snapshot.exams?.length || 0) > 0,
    hasTaskTypes: (snapshot.taskTypes?.length || 0) > 0,
  };
}

// ── 连接 / 配对 ─────────────────────────────────────────────

export async function probePhone(): Promise<any | null> {
  if (!get().base) {
    set({ phoneReachable: false });
    return null;
  }
  try {
    const health = await api('/api/health', { auth: false, timeoutMs: 4000 });
    set({
      phoneReachable: true,
      phoneName: health?.deviceName || get().phoneName,
      phoneVersion: health?.appVersion || get().phoneVersion,
      syncError: null,
    });
    return health;
  } catch {
    set({ phoneReachable: false });
    return null;
  }
}

function devParam(name: string): string | null {
  try {
    return new URLSearchParams(location.search).get(name);
  } catch {
    return null;
  }
}

function stopPairPolling(): void {
  if (pairTimer) clearInterval(pairTimer);
  pairTimer = null;
  pairAttempts = 0;
}

/**
 * 申请一个配对码。
 *
 * ## 两条路（用户二选一，**各只做一个动作**）
 *
 * | 路 | 电脑做什么 | 手机做什么 | 公钥怎么到 |
 * |---|---|---|---|
 * | `scan` | 显示二维码 | **扫这张码** + 点「允许」 | 走二维码（视觉通道） |
 * | `link` | 发一个 HTTP 请求 | 点「允许」 | 走 HTTP（**公钥不是秘密**） |
 *
 * 两条路都用 **DH 协商**：密钥是两端各自算出来的，**从不过网**。
 * 过网的只有公钥 —— 而公钥本来就是公开的东西，抓包的人看到它
 * 也算不出共享密钥（DH 的单向性）。
 *
 * ## 为什么默认**不**重复申请
 *
 * 同一个时刻可能有两条路都想要一张配对码：
 *
 * - [connectBase] 连上地址后会申请一次；
 * - [PairingCard] 在地址已知、还没配对时也会自动申请一次。
 *
 * 两条都跑的话会拿到**两个不同的密钥对**，手机上会多出一条待确认的请求，
 * 而网页端只在轮询**后一个** —— 用户若批准了前一个，
 * 网页端就会一直转圈直到配对码过期（症状是「点了允许，但电脑那边没反应」）。
 *
 * 所以这里默认**让正在进行的配对继续**：只有显式换码（[force]）才重新申请。
 *
 * @param force true = 用户明确要「换一个配对码」，无视当前状态。
 * @param mode 走哪条路：`scan` 出二维码，`link` 只填地址。
 */
export async function requestPairCode(force = false, mode: PairMode = 'scan'): Promise<void> {
  if (!shouldRequestPairCode(get().pair.status, force)) return;
  stopPairPolling();
  const s = get();
  if (!s.base) {
    set({
      pair: {
        status: 'error',
        mode,
        message: '先填写手机地址，或扫描手机「网页同步」里的地址二维码。',
      },
    });
    return;
  }
  set({ pair: { status: 'requesting', mode } });
  try {
    // 先确认手机上真的有服务 —— 也顺便问清楚它支持哪一版配对协议。
    const health = await probePhone();
    if (!health) {
      set({
        pair: {
          status: 'error',
          mode,
          message: '这个地址上没有 MiuList 服务。确认手机开着「局域网服务」。',
        },
      });
      toast('这个地址上没有 MiuList 服务。确认手机开着「局域网服务」。', 'error');
      return;
    }
    // 旧版手机（配对协议 v3）只会「把 secret 从二维码搬过去」，
    // 没有「只填地址」那条路 —— 那就老老实实退回扫码。
    const pairingProtocol = Number((health as any)?.pairing ?? 3);
    if (pairingProtocol < 4) {
      if (mode === 'link') {
        set({
          pair: {
            status: 'error',
            mode: 'scan',
            message: '手机版本太旧，只能扫码配对 —— 把手机上的 MiuList 更新一下。',
          },
        });
        return;
      }
      await startLegacyPairing(mode);
      return;
    }
    await startDhPairing(mode);
  } catch (error: any) {
    const message = error?.message || '拿不到配对码';
    set({ pair: { status: 'error', mode, message } });
    toast(message, 'error');
  }
}

/**
 * 新协议（v3.1）：**DH 协商**。
 *
 * 密钥是两端各自算出来的，**从不过网** —— 过网的只有公钥。
 */
async function startDhPairing(mode: PairMode): Promise<void> {
  const keypair = dhGenerateKeypair();
  const requestId = toBase64Url(randomBytes(16));
  const publicKey = toBase64Url(keypair.pub);
  // 配对码是**公钥**的哈希：电脑算得出自己的，手机收到公钥也算得出同一个。
  // 用户拿两个码一对，就知道「手机上要连的」是不是眼前这台电脑。
  const code = pairCodeOf(keypair.pub);
  pairPriv = keypair.priv;

  const body: Record<string, unknown> = { requestId, deviceName: '电脑网页端' };
  // 「只填地址」那条路：公钥跟着请求走。
  // 扫码那条路：**不带**公钥 —— 手机在用户扫码之前不该弹确认卡，
  // 否则「扫码」就不是那一步了。
  if (mode === 'link') body.publicKey = publicKey;

  await api('/api/pair/request', {
    method: 'POST',
    body,
    auth: false,
    plain: true,
    timeoutMs: 8000,
  });
  set({ pair: { status: 'waiting', requestId, code, mode, publicKey } });
  pollPair({ requestId, code, mode, publicKey });
}

/**
 * 旧协议（v3）：secret 从二维码搬到手机上。
 *
 * 只在**手机还是旧版**时走 —— 新手机也认这条路（为了兼容旧网页端）。
 */
async function startLegacyPairing(mode: PairMode): Promise<void> {
  const devSecret = devParam('devsecret');
  const secretBytes = devSecret ? fromBase64Url(devSecret) : randomBytes(32);
  const secret = toBase64Url(secretBytes);
  const requestId = toBase64Url(sha256(secretBytes).slice(0, 16));
  await api('/api/pair/request', {
    method: 'POST',
    body: { requestId, deviceName: '电脑网页端' },
    auth: false,
    plain: true,
    timeoutMs: 8000,
  });
  // 旧网页端显示的就是 requestId 的前 6 位 —— 手机那边也显示一样的。
  const code = requestId.slice(0, 6).toUpperCase();
  set({ pair: { status: 'waiting', requestId, secret, code, mode } });
  pollPair({ requestId, secret, code, mode });
}

/** 一次配对的现场。 */
type PairAttempt = {
  requestId: string;
  /** 旧协议：secret 本身。新协议：null（走 DH 协商）。 */
  secret?: string;
  /** 配对码（用户核对用）。 */
  code: string;
  mode: PairMode;
  /** 新协议：电脑的 DH 公钥（base64url），二维码里带的就是它。 */
  publicKey?: string;
};

function pollPair(attempt: PairAttempt): void {
  stopPairPolling();
  pairTimer = setInterval(async () => {
    const current = get().pair;
    if (current.requestId !== attempt.requestId || current.status !== 'waiting') {
      stopPairPolling();
      return;
    }
    pairAttempts += 1;
    if (pairAttempts > 80) {
      stopPairPolling();
      set({
        pair: {
          status: 'expired',
          requestId: attempt.requestId,
          secret: attempt.secret,
          code: attempt.code,
          mode: attempt.mode,
          message: '配对码过期了，或手机一直没响应。',
        },
      });
      return;
    }
    try {
      const res = await api(
        `/api/pair/status?requestId=${encodeURIComponent(attempt.requestId)}`,
        { auth: false, timeoutMs: 6000 },
      );
      if (res?.state === 'approved' && res.deviceId) {
        stopPairPolling();
        const secret = attempt.secret ?? resolveDhSecret(res, attempt);
        if (!secret) {
          stopPairPolling();
          set({
            pair: {
              status: 'error',
              requestId: attempt.requestId,
              code: attempt.code,
              mode: attempt.mode,
              message: '配对码对不上 —— 可能有人在中间拦截，别点「允许」。',
            },
          });
          toast('配对码对不上，已中止配对', 'error');
          return;
        }
        set({
          secret,
          deviceId: res.deviceId,
          pair: {
            status: 'approved',
            requestId: attempt.requestId,
            secret,
            code: attempt.code,
            mode: attempt.mode,
          },
          phoneReachable: true,
        });
        persistSession(secret, res.deviceId);
        initKeys(secret);
        stopEvents();
        connectEvents();
        ensurePolling();
        await autoSync('paired');
        toast('配对成功', 'success');
      } else if (res?.state === 'expired' || res?.state === 'denied') {
        stopPairPolling();
        set({
          pair: {
            status: res.state,
            requestId: attempt.requestId,
            secret: attempt.secret,
            code: attempt.code,
            mode: attempt.mode,
            message: res.state === 'expired' ? '配对码过期了，换一个再扫。' : '手机那边拒绝了。',
          },
        });
      }
    } catch {
      /* 下一轮再试 */
    }
  }, 1500);
}

/**
 * 拿手机回的公钥算出共享密钥 —— **顺便核对配对码**。
 *
 * ## 为什么核对码这么重要
 *
 * 配对码是**电脑那个公钥**的哈希。手机报上来的码必须跟电脑自己算的
 * 一个字不差：对不上，说明有人在中间把公钥换掉了 ——
 * 这时宁可配不成，也不能拿一把来路不明的密钥去加密数据。
 *
 * @returns secret（base64url）；对不上时返回 null。
 */
function resolveDhSecret(res: any, attempt: PairAttempt): string | null {
  const peerPubB64 = res?.publicKey;
  if (!peerPubB64 || !pairPriv) return null;
  if (res?.pairCode && res.pairCode !== attempt.code) return null;
  const peerPub = fromBase64Url(peerPubB64);
  return toBase64Url(dhSharedSecret(pairPriv, peerPub));
}

export function cancelPair(): void {
  stopPairPolling();
  // 私钥也一起扔掉 —— 换一条路重来时不该留着上一把。
  pairPriv = null;
  set({ pair: { status: 'idle' } });
}

export async function connectBase(
  input: string,
  options: { pairMode?: PairMode } = {},
): Promise<boolean> {
  const base = normalizeBase(input);
  if (!base) {
    toast('手机地址看起来不对，应该是 http://192.168.x.x:端口', 'error');
    return false;
  }
  const pairMode: PairMode = options.pairMode || 'scan';
  cancelPair();
  stopEvents();
  stopPolling();
  set({
    base,
    phoneReachable: false,
    remoteSnapshot: null,
    conflict: null,
    syncError: null,
    // **先把用户选的那条路记下来**：配对卡片会自动申请一张配对码，
    // 它得知道该走扫码还是「只填地址」。
    pair: { status: 'idle', mode: pairMode },
  });
  persistBase(base);
  const health = await probePhone();
  if (!health) {
    toast('这个地址上没有 MiuList 服务。确认手机开着「局域网服务」。', 'error');
    return false;
  }
  const s = get();
  if (s.secret && s.deviceId) {
    connectEvents();
    ensurePolling();
    await autoSync('connect');
  } else {
    await requestPairCode(false, pairMode);
  }
  return true;
}

/**
 * 从剪贴板读手机地址，然后连上。
 *
 * ## 为什么要有「粘贴」这一下
 *
 * 浏览器**不能发 UDP**（见 `docs/lan-sync-protocol.md` §1），
 * 所以网页端没法自动找到手机 —— 地址只能由「人」搬过来。
 *
 * 而「搬」有三种成本：
 *
 * - 手打 `http://192.168.x.x:8765` —— 慢，且容易打错一个数字；
 * - 电脑摄像头扫手机上的码 —— 要架手机、要点权限；
 * - **手机上一键复制、电脑上一键粘贴** —— 两个动作，不用摄像头，不用抄数字。
 *
 * 手机端那个「复制连接链接」按钮就是为它准备的。
 *
 * ## 读不到剪贴板怎么办
 *
 * `navigator.clipboard.readText()` 在 `file://` 下可能被浏览器拒掉
 * （file:// 虽是 secure context，但剪贴板**读**权限仍要用户授予）。
 *
 * 这时返回 false，由调用方**退回「聚焦输入框、让用户按 Ctrl+V」** ——
 * 那条路永远可用，因为剪贴板里已经有那个地址了。
 */
export async function pasteAddress(): Promise<boolean> {
  let text = '';
  try {
    text = await navigator.clipboard.readText();
  } catch {
    return false;
  }
  const base = normalizeBase(text);
  if (!base) return false;
  await connectBase(base);
  return true;
}

export function disconnectPair(): void {
  cancelPair();
  stopEvents();
  stopPolling();
  sessionKeys = null;
  set({
    secret: null,
    deviceId: null,
    sseConnected: false,
    conflict: null,
    syncError: null,
    remoteSnapshot: null,
    pair: { status: 'idle' },
  });
  persistSession(null, null);
  toast('已断开配对。本地数据还在。');
}

function handleUnauthorized(): void {
  sessionKeys = null;
  stopEvents();
  stopPolling();
  set({
    secret: null,
    deviceId: null,
    sseConnected: false,
    syncing: false,
    conflict: null,
    pair: { status: 'idle' },
    syncError: '配对失效',
    syncOpen: true,
    syncTab: 'connection',
  });
  persistSession(null, null);
  toast('配对失效了，请重新扫码配对。', 'error');
}

function connectEvents(): void {
  stopEvents();
  if (!isOnline() || !sessionKeys) return;
  try {
    const es = new EventSource(`${baseRoot()}/api/events?${authQuery('/api/events')}`);
    sse = es;
    es.addEventListener('open', () => set({ sseConnected: true }));
    es.addEventListener('change', () => scheduleAutoSync());
    es.addEventListener('sync-request', (event: Event) => {
      let payload: any = {};
      try {
        const raw = JSON.parse((event as MessageEvent).data);
        payload = sessionKeys
          ? JSON.parse(new TextDecoder().decode(openSealed(sessionKeys, fromBase64Url(raw))))
          : raw;
      } catch {
        /* 坏载荷按 merge */
      }
      const direction = payload?.direction || 'merge';
      // 手机上明确点了「把手机上的推给网页端」→ 取手机的**整份**（含课表）。
      // 那是一次明确的整体意图；自动同步与网页端自己的冲突按钮都不带配置。
      if (direction === 'pull') void syncPull(true);
      else if (direction === 'push') void syncPush();
      else void syncMerge();
    });
    es.addEventListener('error', () => set({ sseConnected: false }));
  } catch {
    sse = null;
    set({ sseConnected: false });
  }
}

function stopEvents(): void {
  if (sse) {
    sse.close();
    sse = null;
  }
  set({ sseConnected: false });
}

function ensurePolling(): void {
  if (pollTimer) return;
  if (!isOnline()) return;
  pollTimer = setInterval(async () => {
    const s = get();
    if (!s.base || !s.secret || !s.deviceId) return;
    if (s.syncing || s.conflict) return;
    const health = await probePhone();
    if (health) await autoSync('poll');
    // **手机可能只是换了 IP** —— 扫一遍网段把它找回来，用户什么都不用做。
    else void ensurePhoneFound();
  }, 25000);
}

function stopPolling(): void {
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = null;
}

// ── 自动找到手机 ──────────────────────────────────────────────

/** 上次扫网段是什么时候。**别扫得太勤** —— 一次扫要 254 个请求。 */
let lastDiscoverAt = 0;
/** 正在扫（防止并发的两次扫描）。 */
let discovering = false;

/**
 * 找到手机：**按上次记住的网段扫一遍**。
 *
 * 浏览器不能发 UDP 广播（见 `docs/lan-sync-protocol.md` §1），
 * 所以「电脑自己找到手机」只剩这一条路：挨个敲。
 *
 * 它成立的前提有两个，缺一不可：
 *
 * 1. **手机端口固定**（38780）—— 否则不知道敲哪儿；
 * 2. **知道上次那个网段** —— 记住的地址里就有（`192.168.1.20` → 扫 `192.168.1.x`）。
 *
 * @returns 找到的地址（`http://ip:port`）；没找到返回 null。
 */
export async function discoverPhone(): Promise<string | null> {
  const s = get();
  if (!s.base) return null;
  const hosts = discovery.subnetHosts(s.base);
  if (!hosts.length) return null;
  const hit = await discovery.sweepPhone({
    hosts,
    ports: discovery.PHONE_PORTS,
    probe: (host: string, port: number) => discovery.probeHealth(host, port),
  });
  if (!hit) return null;
  return `http://${hit.host}:${hit.port}`;
}

/**
 * 连不上手机时，**自动去把它找回来**。
 *
 * 手机换了 IP（DHCP 换了地址）是常事 —— 用户不该为这件事再填一次地址。
 * 找到之后直接接上：密钥还在本地，不用重新配对。
 */
export async function ensurePhoneFound(): Promise<boolean> {
  const s = get();
  if (!s.base) return false;
  if (s.phoneReachable) return true;
  if (discovering) return false;
  const now = Date.now();
  // 一次扫描要 254 个请求，别连着扫。
  if (now - lastDiscoverAt < 20000) return false;
  lastDiscoverAt = now;
  discovering = true;
  try {
    const found = await discoverPhone();
    if (!found) return false;
    if (found !== s.base) {
      set({ base: found });
      persistBase(found);
    }
    const health = await probePhone();
    if (!health) return false;
    if (get().secret && get().deviceId) {
      connectEvents();
      ensurePolling();
      await autoSync('discovered');
    }
    return true;
  } finally {
    discovering = false;
  }
}

// ── 同步 ────────────────────────────────────────────────────

function showConflict(conflicts: ConflictItem[], remote: Snapshot): void {
  const conflict: ConflictState = { conflicts, remote };
  set({ conflict, syncOpen: true, syncTab: 'connection' });
  toast('两台设备改了同一条待办，需要你选一下方向', 'error');
}

function syncFailureToast(message: string): void {
  set({ syncError: message });
  const now = Date.now();
  if (now - lastErrorToastAt > 15000) {
    lastErrorToastAt = now;
    toast(message || '同步失败', 'error');
  }
}

export async function autoSync(reason = ''): Promise<void> {
  const s = get();
  if (!s.base || !s.secret || !s.deviceId) return;
  if (s.syncing || s.conflict) return;
  if (!s.phoneReachable && reason !== 'manual') return;
  set({ syncing: true, syncError: null });
  try {
    const remote = normalizeSnapshot(await api('/api/snapshot'));
    set({ remoteSnapshot: remote, phoneReachable: true });
    const conflicts = detectConflicts(remote);
    if (conflicts.length) {
      showConflict(conflicts, remote);
      return;
    }
    applyRemote(remote);

    const afterApply = get();
    if (hasPending(afterApply.pending)) {
      const resp = await api('/api/snapshot/apply', {
        method: 'POST',
        body: { mode: 'merge', snapshot: buildPushPayload('merge') },
      });
      if (resp?.keptUids?.length) {
        const remoteByUid = new Map(remote.tasks.map((task) => [task.syncUid, task]));
        showConflict(
          (resp.keptUids as string[]).map((uid) => ({
            uid,
            title:
              remoteByUid.get(uid)?.title || get().pending[uid]?.task?.title || '这条待办',
            reason: 'both' as const,
          })),
          remote,
        );
        return;
      }
      const nextRemoteBase = { ...get().remoteBase };
      for (const [uid, entry] of Object.entries(get().pending)) {
        nextRemoteBase[uid] = entry.updatedAt;
      }
      set({ pending: {}, remoteBase: nextRemoteBase });
      persistPending({});
      persistRemoteBase(nextRemoteBase);
    }

    const remoteBase = { ...get().remoteBase };
    for (const task of remote.tasks || []) {
      remoteBase[task.syncUid] = task.updatedAt || 0;
    }
    const meta = { lastSyncAt: Date.now(), lastDirection: 'auto' };
    set({ remoteBase, meta });
    persistRemoteBase(remoteBase);
    persistMeta(meta);
    scheduleSnapshotFileSave();
  } catch (error: any) {
    if (error?.unauthorized) {
      handleUnauthorized();
      return;
    }
    syncFailureToast(error?.message || '同步失败');
  } finally {
    set({ syncing: false });
  }
}

export async function manualSync(): Promise<void> {
  if (!get().base) {
    get().openSync('connection');
    return;
  }
  if (!get().secret || !get().deviceId) {
    get().openSync('connection');
    return;
  }
  const health = await probePhone();
  if (!health) {
    syncFailureToast('连不上手机');
    get().openSync('connection');
    return;
  }
  await autoSync('manual');
}

/**
 * 「以手机为准」——**待办**以手机为准。
 *
 * 配置（课表 / 考试 / 分类）默认不动：解决一条待办的冲突，
 * 不该顺手把整张课表换掉。要搬配置走 [syncConfigPull]。
 *
 * `includeConfig` 只有一种情况为 true：**手机上明确点了「把手机上的推给网页端」**。
 * 那是一次明确的整体意图，网页端取手机那一份（含课表）是顺着它的意思；
 * 自动同步与网页端自己的冲突按钮都不走这条。
 */
export async function syncPull(includeConfig = false): Promise<void> {
  const s = get();
  if (!s.base || !s.secret || !s.deviceId) return;
  set({ syncing: true, syncError: null });
  try {
    const remote = normalizeSnapshot(await api('/api/snapshot'));
    const tasks = remote.tasks || [];
    const pending: PendingMap = {};
    const remoteBase: Record<string, number> = {};
    for (const task of tasks) remoteBase[task.syncUid] = task.updatedAt || 0;
    const meta = { lastSyncAt: Date.now(), lastDirection: 'pull' };
    const snapshot: Snapshot = {
      ...s.snapshot,
      tasks,
      revision: remote.revision,
      generatedAt: remote.generatedAt,
      latestEditAt: remote.latestEditAt,
      // 明确要整份时才把配置也换成手机那份。
      ...(includeConfig
        ? {
            courses: remote.courses || [],
            exams: remote.exams || [],
            taskTypes: remote.taskTypes || [],
            timetable: remote.timetable ?? null,
            todayCourses: remote.todayCourses || [],
            hasCourses: (remote.courses?.length || 0) > 0,
            hasExams: (remote.exams?.length || 0) > 0,
            hasTaskTypes: (remote.taskTypes?.length || 0) > 0,
          }
        : {}),
    };
    set({ snapshot, pending, remoteBase, meta, remoteSnapshot: remote, conflict: null });
    persistSnapshot(snapshot);
    persistPending(pending);
    persistRemoteBase(remoteBase);
    persistMeta(meta);
    scheduleSnapshotFileSave();
    toast(includeConfig ? '已换成手机上的数据' : '待办已换成手机上的', 'success');
  } catch (error: any) {
    if (error?.unauthorized) return handleUnauthorized();
    syncFailureToast(error?.message || '拉取失败');
  } finally {
    set({ syncing: false });
  }
}

/**
 * 「以本页为准」——把本机的待办整份推给手机。
 *
 * 载荷不带配置（`buildPushPayload` 的 `includeConfig` 默认关），
 * 手机上的课表不会被这一推换掉。
 */
export async function syncPush(): Promise<void> {
  const s = get();
  if (!s.base || !s.secret || !s.deviceId) return;
  set({ syncing: true, syncError: null });
  try {
    const resp = await api('/api/snapshot/apply', {
      method: 'POST',
      body: { mode: 'replace', snapshot: buildPushPayload('replace') },
    });
    const remoteBase = { ...get().remoteBase };
    if (resp?.appliedUids) {
      for (const uid of resp.appliedUids as string[]) {
        const task = get().snapshot.tasks.find((item) => item.syncUid === uid);
        if (task) remoteBase[uid] = task.updatedAt || 0;
      }
    }
    const pending: PendingMap = {};
    const meta = { lastSyncAt: Date.now(), lastDirection: 'push' };
    set({ pending, remoteBase, meta, conflict: null });
    persistPending(pending);
    persistRemoteBase(remoteBase);
    persistMeta(meta);
    scheduleSnapshotFileSave();
    toast('已把本页待办推到手机上', 'success');
  } catch (error: any) {
    if (error?.unauthorized) return handleUnauthorized();
    syncFailureToast(error?.message || '推送失败');
  } finally {
    set({ syncing: false });
  }
}

/** 「合并」——两边的待办并成一份。配置仍然各管各的。 */
export async function syncMerge(): Promise<void> {
  const s = get();
  if (!s.base || !s.secret || !s.deviceId) return;
  set({ syncing: true, syncError: null });
  try {
    const remote = normalizeSnapshot(await api('/api/snapshot'));
    set({ remoteSnapshot: remote });
    applyRemote(remote);
    const resp = await api('/api/snapshot/apply', {
      method: 'POST',
      body: { mode: 'replace', snapshot: buildPushPayload('replace') },
    });
    const remoteBase = { ...get().remoteBase };
    if (resp?.appliedUids) {
      for (const uid of resp.appliedUids as string[]) {
        const task = get().snapshot.tasks.find((item) => item.syncUid === uid);
        if (task) remoteBase[uid] = task.updatedAt || 0;
      }
    }
    const pending: PendingMap = {};
    const meta = { lastSyncAt: Date.now(), lastDirection: 'merge' };
    set({ pending, remoteBase, meta, conflict: null });
    persistPending(pending);
    persistRemoteBase(remoteBase);
    persistMeta(meta);
    scheduleSnapshotFileSave();
    toast('已合并', 'success');
  } catch (error: any) {
    if (error?.unauthorized) return handleUnauthorized();
    syncFailureToast(error?.message || '合并失败');
  } finally {
    set({ syncing: false });
  }
}

/**
 * 「用手机的课表和考试」——把手机的配置搬到本机。
 *
 * 这是**配置唯一的入口**。自动同步不会做这件事（网页端是独立的一端），
 * 要搬就得明确点一下。
 *
 * 待办一个字都不动。
 *
 * @param quiet 搬完不弹提示。用于「刚拉完浙大，顺手搬一次」那条路 ——
 *   那条路自己要说清楚拉到了什么，多一条「已用手机上的课表」只会添乱。
 */
export async function syncConfigPull(quiet = false): Promise<void> {
  const s = get();
  if (!s.base || !s.secret || !s.deviceId) return;
  set({ syncing: true, syncError: null });
  try {
    const remote = normalizeSnapshot(await api('/api/snapshot'));
    const snapshot: Snapshot = {
      ...s.snapshot,
      courses: remote.courses || [],
      exams: remote.exams || [],
      taskTypes: remote.taskTypes || [],
      timetable: remote.timetable ?? null,
      todayCourses: remote.todayCourses || [],
      // 手机那边也会把**原始校历**发过来（见 P1-2）：有了它，
      // 网页端导出的 JSON 才是完整的「整机快照」，而不只是待办。
      calendar: remote.calendar ?? s.snapshot.calendar ?? null,
      hasCourses: (remote.courses?.length || 0) > 0,
      hasExams: (remote.exams?.length || 0) > 0,
      hasTaskTypes: (remote.taskTypes?.length || 0) > 0,
      revision: remote.revision,
      generatedAt: remote.generatedAt,
    };
    set({ snapshot, remoteSnapshot: remote });
    saveLocalSnapshot(snapshot);
    if (!quiet) {
      toast(
        `已用手机上的课表：${snapshot.courses.length} 门课 · ${snapshot.exams.length} 场考试`,
        'success',
      );
    }
  } catch (error: any) {
    if (error?.unauthorized) return handleUnauthorized();
    syncFailureToast(error?.message || '搬课表失败');
  } finally {
    set({ syncing: false });
  }
}

/**
 * 「把本页课表推给手机」——用本机的配置覆盖手机上的课表与考试。
 *
 * 本机一门课都没有时**直接拒绝**：手机侧收到 `hasCourses=true` 会
 * 「先清空整张课表再重建」，一份空配置推过去等于把手机上的课表删了。
 */
export async function syncConfigPush(): Promise<void> {
  const s = get();
  if (!s.base || !s.secret || !s.deviceId) return;
  if (!(s.snapshot.courses?.length || 0)) {
    toast('本页还没有课程。先导入一份 JSON 快照，或用手机的课表。', 'error');
    return;
  }
  set({ syncing: true, syncError: null });
  try {
    await api('/api/snapshot/apply', {
      method: 'POST',
      body: { mode: 'merge', snapshot: buildConfigPushPayload() },
    });
    toast('已把本页的课表和考试推给手机', 'success');
  } catch (error: any) {
    if (error?.unauthorized) return handleUnauthorized();
    syncFailureToast(error?.message || '推课表失败');
  } finally {
    set({ syncing: false });
  }
}

// ── 学在浙大 ──────────────────────────────────────────────────────

/** 学在浙大学期下拉里的一项。 */
export type ZjuTerm = {
  /** 学年，如 `"2026-2027"`。 */
  year: string;
  /** 教务网查询用哪一半：秋冬 → 0(秋)，春夏 → 4(春)。 */
  term: number;
  label: string;
};

/** 一次拉取的结果。 */
export type ZjuSyncResult = {
  ok: boolean;
  courseCount: number;
  slotCount: number;
  examCount: number;
  termLabel: string;
  /** 考试没拉到时的原因；null = 一切正常（学校还没公布考试是正常状态）。 */
  examError?: string | null;
  /** 凭据是否被转存进了手机的 Keystore。 */
  savedCredentials: boolean;
  error?: string;
  kind?: string;
  localDataIntact?: boolean;
};

/**
 * 可选的学期。**不需要账号** —— 学期表是公共配置。
 */
export async function zjuTerms(): Promise<ZjuTerm[]> {
  const res = await api('/api/zju/terms');
  return Array.isArray(res?.terms) ? (res.terms as ZjuTerm[]) : [];
}

/**
 * 让手机去学在浙大拉一次课表（顺带把考试也拉了）。
 *
 * ## 为什么是「让手机去拉」
 *
 * 网页端是 `file://` 打开的单文件 HTML（Origin 为 `null`），
 * 而学在浙大的登录链路跨三个域，中间要自己读 302 的 `Location` 取 ticket、
 * 读登录页的 hidden input 拿 `execution`、手改 TGC 的 Cookie Domain ——
 * 跨源脚本一件都做不到，浏览器也拿不到浙大接口的 CORS 头。
 * 手机那边已经有整套能力（Keystore 里的凭据 + CAS 客户端），所以由它代劳。
 *
 * ## 密码的去向
 *
 * 密码只在这条请求的**密文**里存在（会话密钥派生，局域网上也解不开），
 * **不落浏览器存储** —— 网页端不记密码。手机侧会把它转存进 Keystore，
 * 下次就不用再填。
 */
export async function zjuSync(input: {
  studentId?: string;
  password?: string;
  year?: string;
  term?: number;
  remember?: boolean;
}): Promise<ZjuSyncResult> {
  const res = await api('/api/zju/sync', { method: 'POST', body: input });
  return {
    ok: res?.ok === true,
    courseCount: Number(res?.courseCount) || 0,
    slotCount: Number(res?.slotCount) || 0,
    examCount: Number(res?.examCount) || 0,
    termLabel: typeof res?.termLabel === 'string' ? res.termLabel : '',
    examError: typeof res?.examError === 'string' ? res.examError : null,
    savedCredentials: res?.savedCredentials === true,
    error: typeof res?.error === 'string' ? res.error : undefined,
    kind: typeof res?.kind === 'string' ? res.kind : undefined,
    localDataIntact: res?.localDataIntact !== false,
  };
}

/**
 * 拉完之后把手机上的课表搬过来。
 *
 * 单独拎出来是因为「拉」与「搬」是**两件事**：
 * 拉是让手机去浙大取数据，搬是把结果放到这台电脑上。
 * 中间还差一次同步 —— 手机那边 Room 变了，要再取一次快照。
 */
export async function pullConfigAfterZju(): Promise<void> {
  await syncConfigPull(true);
}

let autoSyncTimer: ReturnType<typeof setTimeout> | null = null;
function scheduleAutoSync(): void {
  if (autoSyncTimer) clearTimeout(autoSyncTimer);
  autoSyncTimer = setTimeout(() => {
    autoSyncTimer = null;
    void autoSync('change');
  }, 250);
}

// ── 本地改动 ────────────────────────────────────────────────

function applyLocalTaskChange(
  uid: string,
  mutate: (task: Task) => void,
  options: { localOnly?: boolean } = {},
): Task | null {
  const s = get();
  const index = s.snapshot.tasks.findIndex((task) => task.syncUid === uid);
  if (index < 0) return null;
  const next = { ...s.snapshot.tasks[index] };
  mutate(next);
  next.updatedAt = Math.max(next.updatedAt || 0, Date.now());
  const tasks = s.snapshot.tasks.slice();
  tasks[index] = next;
  const snapshot = { ...s.snapshot, tasks };
  saveLocalSnapshot(snapshot);
  markPending(next, options.localOnly);
  scheduleAutoSync();
  return next;
}

export function toggleTask(task: Task): void {
  applyLocalTaskChange(task.syncUid, (next) => {
    next.isCompleted = !task.isCompleted;
    next.completedAt = next.isCompleted ? Date.now() : null;
  });
}

export function toggleStar(task: Task): void {
  applyLocalTaskChange(task.syncUid, (next) => {
    next.priority = task.priority === 'STAR' ? '' : 'STAR';
  });
}

export function addTask(draft: TaskDraft): Task | null {
  const title = draft.title.trim();
  if (!title) return null;
  const s = get();
  const types = taskTypeOptions(s.snapshot.taskTypes);
  const type = types.find((item) => item.name === draft.taskTypeName);
  const due = dueFromInput(draft.dueLocal, s.snapshot.utcOffsetMinutes ?? 480);
  const now = Date.now();
  const uid = `web-${Math.random().toString(36).slice(2, 10)}${now.toString(36)}`;
  const task: Task = {
    syncUid: uid,
    title,
    description: draft.description.trim(),
    taskType: type?.isCustom ? type.name : type?.name || '',
    taskTypeLabel: type?.label || '',
    priority: draft.starred ? 'STAR' : '',
    isCompleted: false,
    completedAt: null,
    startTimeTimestamp: 0,
    dueDateTimestamp: due.dueDateTimestamp,
    dueDateString: due.dueDateString,
    startTimeString: '',
    isAutoGenerated: false,
    week: s.snapshot.timetable?.currentWeek || 0,
    dayOfWeek: s.snapshot.weekday || 0,
    courseKey: '',
    courseName: '',
    courseCategory: '',
    courseColorHex: '',
    createdAt: now,
    updatedAt: now,
  };
  const snapshot = { ...s.snapshot, tasks: [task, ...s.snapshot.tasks] };
  saveLocalSnapshot(snapshot);
  markPending(task, true);
  toast('已添加待办', 'success');
  scheduleAutoSync();
  return task;
}

export function updateTask(uid: string, draft: TaskDraft): Task | null {
  const title = draft.title.trim();
  if (!title) return null;
  const s = get();
  const types = taskTypeOptions(s.snapshot.taskTypes);
  const type = types.find((item) => item.name === draft.taskTypeName);
  const due = dueFromInput(draft.dueLocal, s.snapshot.utcOffsetMinutes ?? 480);
  return applyLocalTaskChange(uid, (task) => {
    task.title = title;
    task.description = draft.description.trim();
    task.taskType = type?.isCustom ? type.name : type?.name || '';
    task.taskTypeLabel = type?.label || '';
    task.priority = draft.starred ? 'STAR' : '';
    task.dueDateTimestamp = draft.dueLocal ? due.dueDateTimestamp : NO_DUE;
    task.dueDateString = draft.dueLocal ? due.dueDateString : '';
  });
}

// ── 启动 / 生命周期 ─────────────────────────────────────────

export async function afterImport(): Promise<void> {
  const s = get();
  if (!s.base) return;
  const health = await probePhone();
  if (!health) return;
  if (s.secret && s.deviceId) {
    connectEvents();
    ensurePolling();
    await autoSync('import');
  }
}

function bindGlobalEvents(): void {
  if (globalBound) return;
  globalBound = true;
  window.addEventListener('online', () => {
    if (!get().base) return;
    void probePhone().then((health) => {
      if (health && isOnline()) void autoSync('online');
      // 刚从「没网」恢复过来：手机可能换了地址，扫一遍找回来。
      else void ensurePhoneFound();
    });
  });
  window.addEventListener('offline', () => set({ phoneReachable: false, sseConnected: false }));
  window.addEventListener('focus', () => {
    if (document.visibilityState !== 'visible') return;
    if (isOnline() && get().phoneReachable) scheduleAutoSync();
    // 切回前台时手机可能换了 IP —— 顺手找一下。
    else if (get().base) void ensurePhoneFound();
  });
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && isOnline() && get().phoneReachable) {
      scheduleAutoSync();
    }
  });
  window.addEventListener('miulist:storage-error', () => {
    toast('浏览器本地存储写不下了，快照可能没保存。', 'error');
  });
}

export async function boot(): Promise<void> {
  if (booted) return;
  booted = true;
  bindGlobalEvents();

  const initial = get();
  if (initial.secret) {
    if (!initKeys(initial.secret)) {
      sessionKeys = null;
      set({ secret: null, deviceId: null });
      persistSession(null, null);
    }
  }

  const devBase = devParam('base');
  if (devBase) {
    const normalized = normalizeBase(devBase);
    if (normalized) {
      set({ base: normalized });
      persistBase(normalized);
    }
  }

  await restoreSnapshotFileOnBoot();

  // 先把本机数据交给界面；手机探测/同步在后台继续，不阻塞首屏。
  set({ booted: true });

  const s = get();
  if (s.base && s.secret && s.deviceId) {
    const health = await probePhone();
    if (health) {
      connectEvents();
      ensurePolling();
      await autoSync('boot');
    } else {
      // **连不上就自己去找**：手机换了 IP 也不要紧，扫一遍网段就找回来。
      void ensurePhoneFound();
    }
  } else if (s.base && !s.secret) {
    const health = await probePhone();
    if (health && devParam('devsecret') && get().pair.status === 'idle') {
      await requestPairCode();
    }
  }
}

export const sync = {
  boot,
  autoSync,
  manualSync,
  syncPull,
  syncPush,
  syncMerge,
  syncConfigPull,
  syncConfigPush,
  probePhone,
  connectBase,
  requestPairCode,
  cancelPair,
  disconnectPair,
  discoverPhone,
  ensurePhoneFound,
  toggleTask,
  toggleStar,
  addTask,
  updateTask,
  afterImport,
  normalizeBase,
};

export type { PairState };
export { formatTime };
