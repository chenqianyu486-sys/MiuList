import {
  deriveKeys,
  fromBase64Url,
  open as openSealed,
  randomBytes,
  seal,
  signRequest,
  toBase64Url,
  utf8Bytes,
  dhGenerateKeypair,
  dhSharedSecret,
  dhValidatePublicKey,
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
/**
 * 配对用的临时 DH **公钥**（= 自己的 A）。
 *
 * 协议 v3.2 的配对码是「双方公钥的转录哈希 H(A‖B)」，
 * 所以算码时需要**自己**的公钥也在手上 —— 只存私钥是不够的。
 *
 * 公钥不是秘密（它就是要摆给手机看的），但它跟私钥同生共死：
 * 换一条路重来就一起换掉，免得算出上一轮的码。
 */
let pairPub: Uint8Array | null = null;
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
  // 「这次请求带没带签名」—— 签名头只在 auth && !plain 时才加。
  // 响应处理那边要靠它判断「这个响应**必须**是密文」。
  const signed = auth && !plain;
  if (signed) {
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
    const error = new ApiError('配对已失效，需要重新配对。', 401);
    error.unauthorized = true;
    throw error;
  }

  const text = await res.text();
  let payload: any = text;
  const isEncrypted = Boolean(res.headers.get('X-ML-Enc'));
  // **带签名的请求，响应必须是密文**（协议 v3.2 起强制）。
  //
  // 为什么：签名只能证明「请求是我发的」，证明不了「回包真是手机发的」。
  // 以前缺 X-ML-Enc 就按明文 JSON 收下，等于**明文通道**一直开着 ——
  // 冒名的服务器或中间人可以随便回一份明文 JSON，被当成手机的数据合并进来。
  //
  // 免签名的请求（/api/health、/api/pair/*）本来就该是明文 JSON，不受这条约束。
  if (signed && !isEncrypted) {
    // **两种情况都中止** —— 安全性质不因状态码而放松。
    //
    // 但话要说准：4xx/5xx 的**明文**错误是手机自己发的
    // （303 来源不允许 / 413 请求体太大 / 404 没有这个接口），
    // 传输层错误本来就没有 X-ML-Enc。它一律说成「中间人拦截」，
    // 用户会去查错方向 —— 真正要拦的只有「200 却不是密文」那一种。
    if (res.ok) {
      throw new ApiError('返回的数据没有加密，已中止同步。', 500);
    }
    let detail = '';
    try {
      detail = JSON.parse(text)?.error || '';
    } catch {
      /* ignore */
    }
    throw new ApiError(detail || `请求失败（${res.status}）`, res.status);
  }
  if (isEncrypted) {
    try {
      payload = new TextDecoder().decode(openSealed(sessionKeys, fromBase64Url(text)));
    } catch {
      // 解不开只有一种可能：**两边的密钥不是同一把**。
      // 密钥 = 协商出的共享密钥 + 同步密码 派生出来的，共享密钥两端已经核对过，
      // 所以差异只可能出在**同步密码**上。
      throw new ApiError(
        '配对没成功 —— 如果手机上设了同步密码，在配对处填同样的 4 位数字后重新配对。',
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
    throw new ApiError('返回的数据读不懂，已中止同步。', 500);
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
 * ## 只有一条路
 *
 * 电脑带着自己的 DH 公钥发 `POST /api/pair/request` —— 公钥不是秘密，
 * 手机收到后当场算出共享密钥、弹出确认卡。用户在手机上核对 6 位码后点「允许」，
 * 电脑轮询到 approved，用手机回的公钥算出同一把共享密钥。
 *
 * **密钥是两端各自算出来的，从不过网。**
 *
 * ## 为什么默认**不**重复申请
 *
 * 同一个时刻可能有两个地方都想要一张配对码：
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
 */
export async function requestPairCode(force = false): Promise<void> {
  if (!shouldRequestPairCode(get().pair.status, force)) return;
  stopPairPolling();
  const s = get();
  if (!s.base) {
    set({ pair: { status: 'error', message: '先填写手机地址。' } });
    return;
  }
  set({ pair: { status: 'requesting' } });
  try {
    // 先确认手机上真的有服务 —— 也顺便问清楚它支持哪一版配对协议。
    const health = await probePhone();
    if (!health) {
      set({
        pair: {
          status: 'error',
          message: '这个地址上没有 MiuList 服务。确认手机上的「网页同步」已经打开。',
        },
      });
      toast('这个地址上没有 MiuList 服务。确认手机上的「网页同步」已经打开。', 'error');
      return;
    }
    // **版本闸门**：配对码是「双方公钥的转录哈希 H(A‖B)」，
    // 网页端与手机必须**同一版**才可能算出同一个码。
    const pairingProtocol = Number((health as any)?.pairing ?? 3);
    if (pairingProtocol < 5) {
      set({
        pair: {
          status: 'error',
          message: '手机版本太旧，请更新 MiuList App 后再配对。',
        },
      });
      toast('手机版本太旧，请更新 MiuList App 后再配对', 'error');
      return;
    }
    await startDhPairing();
  } catch (error: any) {
    const message = error?.message || '拿不到配对码';
    set({ pair: { status: 'error', message } });
    toast(message, 'error');
  }
}

/**
 * **DH 协商**：电脑生成一对临时密钥，把公钥带进配对请求。
 *
 * 密钥是两端各自算出来的，**从不过网** —— 过网的只有公钥。
 */
async function startDhPairing(): Promise<void> {
  const keypair = dhGenerateKeypair();
  const requestId = toBase64Url(randomBytes(16));
  const publicKey = toBase64Url(keypair.pub);
  pairPriv = keypair.priv;
  pairPub = keypair.pub;

  const body = { requestId, deviceName: '电脑网页端', publicKey };
  await api('/api/pair/request', {
    method: 'POST',
    body,
    auth: false,
    plain: true,
    timeoutMs: 8000,
  });
  // **配对码这里还拿不到**：v3.2 的码是「双方公钥」的哈希，
  // 手机的公钥要等 `/api/pair/status` 回包里才有。
  // 所以先空着，等轮询拿到对端公钥再补上（见 pollPair）。
  set({ pair: { status: 'waiting', requestId, code: null } });
  pollPair({ requestId });
}

/** 一次配对的现场。 */
type PairAttempt = {
  requestId: string;
  /**
   * 配对码（用户核对用）。
   *
   * **一开始是空的**：v3.2 的码要等手机的公钥才知道。
   * 真正拿去核对的是「自己算出来的那个」，不是手机报上来的。
   */
  code?: string | null;
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
          code: attempt.code,
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
      // **手机的公钥一到就能算出码了**：v3.2 的码是 H(自己 ‖ 对方)。
      // 先把它显示出来，用户可以早点跟手机屏幕上的码对上，
      // 不用等到批准那一刻才知道对不对。
      const expected = pairCodeFromStatus(res);
      // **重新读一次状态**：上面那次 await 期间用户可能已经取消了配对，
      // 拿旧状态回写会把「已取消」复活成「还在等」。
      const latest = get().pair;
      if (
        expected &&
        latest.status === 'waiting' &&
        latest.requestId === attempt.requestId &&
        latest.code !== expected
      ) {
        attempt.code = expected;
        set({ pair: { ...latest, code: expected } });
      }
      if (res?.state === 'approved' && res.deviceId) {
        stopPairPolling();
        const result = resolveDhSecret(res);
        if (!result) {
          set({
            pair: {
              status: 'error',
              requestId: attempt.requestId,
              code: expected ?? attempt.code,
              message: '配对码对不上，已中止配对。请确认手机上显示的是同一个码。',
            },
          });
          toast('配对码对不上，已中止配对', 'error');
          return;
        }
        set({
          secret: result.secret,
          deviceId: res.deviceId,
          pair: {
            status: 'approved',
            requestId: attempt.requestId,
            secret: result.secret,
            code: result.code,
          },
          phoneReachable: true,
        });
        persistSession(result.secret, res.deviceId);
        initKeys(result.secret);
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
            code: attempt.code,
            message: res.state === 'expired' ? '配对码过期了，换一个再试。' : '手机那边拒绝了。',
          },
        });
      }
    } catch {
      /* 下一轮再试 */
    }
  }, 1500);
}

/**
 * 从 `/api/pair/status` 的回包里算出「**电脑这边**的配对码」。
 *
 * 码 = H(A‖B)：A = 自己的公钥（电脑），B = 对端的公钥（手机）。
 * **顺序不能反** —— 手机那边也是按这个顺序算的，反了就永远对不上。
 *
 * 手机的公钥没到就返回 null（此时还算不出码），公钥不合法也返回 null ——
 * 宁可不出码，也不能给一个假的。
 */
function pairCodeFromStatus(res: any): string | null {
  const peerPubB64 = res?.publicKey;
  if (!peerPubB64 || typeof peerPubB64 !== 'string' || !pairPub) return null;
  try {
    const peerPub = fromBase64Url(peerPubB64);
    // 退化公钥（0 / 1 / p-1）会让共享密钥变成公开常量，先查掉。
    dhValidatePublicKey(peerPub);
    return pairCodeOf(pairPub, peerPub);
  } catch {
    return null;
  }
}

/**
 * 拿手机回的公钥算出共享密钥 —— **强制核对配对码**。
 *
 * ## 为什么核对码这么重要
 *
 * 配对码是「双方公钥的转录哈希 H(A‖B)」。手机报上来的码必须跟电脑自己算的
 * 一个字不差：对不上，说明有人在中间把公钥换掉了 ——
 * 这时宁可配不成，也不能拿一把来路不明的密钥去加密数据。
 *
 * ## 为什么是「强制」
 *
 * 以前写的是「有码才比」—— 响应里**缺** `pairCode` 字段时整段校验被跳过，
 * 等于给中间人留了一条「不带这个字段就绕过」的后门。
 * 现在**缺码与不等码都中止**。
 *
 * @returns 成功时是 `{ secret, code }`；任何一处对不上就返回 null。
 */
function resolveDhSecret(res: any): { secret: string; code: string } | null {
  const peerPubB64 = res?.publicKey;
  if (!peerPubB64 || typeof peerPubB64 !== 'string') return null;
  if (!pairPriv || !pairPub) return null;
  let peerPub: Uint8Array;
  try {
    peerPub = fromBase64Url(peerPubB64);
    // 先查公钥 —— 退化公钥会让共享密钥变成**全世界都知道的常量**。
    dhValidatePublicKey(peerPub);
  } catch {
    return null;
  }
  // A = 自己（电脑），B = 对端（手机）。
  const expected = pairCodeOf(pairPub, peerPub);
  // **强制比对**：缺码即中止，不等即中止。
  if (!res.pairCode) return null;
  if (res.pairCode !== expected) return null;
  try {
    return { secret: toBase64Url(dhSharedSecret(pairPriv, peerPub)), code: expected };
  } catch {
    return null;
  }
}

export function cancelPair(): void {
  stopPairPolling();
  // 私钥与公钥一起扔掉 —— 换一条路重来时不该留着上一把。
  pairPriv = null;
  pairPub = null;
  set({ pair: { status: 'idle' } });
}

export async function connectBase(input: string): Promise<boolean> {
  const base = normalizeBase(input);
  if (!base) {
    toast('手机地址看起来不对，应该是 http://192.168.x.x:端口', 'error');
    return false;
  }
  cancelPair();
  stopEvents();
  stopPolling();
  set({
    base,
    phoneReachable: false,
    remoteSnapshot: null,
    conflict: null,
    syncError: null,
    pair: { status: 'idle' },
  });
  persistBase(base);
  const health = await probePhone();
  if (!health) {
    toast('这个地址上没有 MiuList 服务。确认手机上的「网页同步」已经打开。', 'error');
    return false;
  }
  const s = get();
  if (s.secret && s.deviceId) {
    connectEvents();
    ensurePolling();
    await autoSync('connect');
  } else {
    await requestPairCode();
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
 * 而「搬」的成本：
 *
 * - 手打 `http://192.168.x.x:8765` —— 慢，且容易打错一个数字；
 * - **手机上一键复制、电脑上一键粘贴** —— 两个动作，不用抄数字。
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
  toast('配对失效了，请重新配对。', 'error');
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
    toast('本页还没有课程。先导入一份备份文件，或用手机的课表。', 'error');
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
    toast('浏览器本地存储写不下了，数据可能没保存。', 'error');
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
    // 地址有了但还没配对 → 发一条带公钥的配对请求，手机上会弹确认卡。
    if (health && get().pair.status === 'idle') {
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
