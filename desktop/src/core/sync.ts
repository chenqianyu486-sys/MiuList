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
} from './crypto';
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

export function normalizeBase(input: string): string {
  let text = String(input || '').trim();
  if (!text) return '';
  if (!/^https?:\/\//i.test(text)) text = `http://${text}`;
  try {
    const url = new URL(text);
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return '';
    if (!url.hostname) return '';
    return url.origin;
  } catch {
    return '';
  }
}

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
      throw new ApiError('响应解密失败（密钥对不上？）', 500);
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

export async function requestPairCode(): Promise<void> {
  stopPairPolling();
  const s = get();
  if (!s.base) {
    set({
      pair: { status: 'error', message: '先填写手机地址，或扫描手机「网页同步」里的地址二维码。' },
    });
    return;
  }
  set({ pair: { status: 'requesting' } });
  try {
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
    set({ pair: { status: 'waiting', requestId, secret } });
    pollPair(requestId, secret);
  } catch (error: any) {
    const message = error?.message || '拿不到配对码';
    set({ pair: { status: 'error', message } });
    toast(message, 'error');
  }
}

function pollPair(requestId: string, secret: string): void {
  stopPairPolling();
  pairTimer = setInterval(async () => {
    const current = get().pair;
    if (current.requestId !== requestId || current.status !== 'waiting') {
      stopPairPolling();
      return;
    }
    pairAttempts += 1;
    if (pairAttempts > 80) {
      stopPairPolling();
      set({
        pair: {
          status: 'expired',
          requestId,
          secret,
          message: '配对码过期了，或手机一直没响应。',
        },
      });
      return;
    }
    try {
      const res = await api(
        `/api/pair/status?requestId=${encodeURIComponent(requestId)}`,
        { auth: false, timeoutMs: 6000 },
      );
      if (res?.state === 'approved' && res.deviceId) {
        stopPairPolling();
        const s = get();
        set({
          secret,
          deviceId: res.deviceId,
          pair: { status: 'approved', requestId, secret },
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
            requestId,
            secret,
            message: res.state === 'expired' ? '配对码过期了，换一个再扫。' : '手机那边拒绝了。',
          },
        });
      }
    } catch {
      /* 下一轮再试 */
    }
  }, 1500);
}

export function cancelPair(): void {
  stopPairPolling();
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
    await requestPairCode();
  }
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
  }, 25000);
}

function stopPolling(): void {
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = null;
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
 */
export async function syncConfigPull(): Promise<void> {
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
      hasCourses: (remote.courses?.length || 0) > 0,
      hasExams: (remote.exams?.length || 0) > 0,
      hasTaskTypes: (remote.taskTypes?.length || 0) > 0,
      revision: remote.revision,
      generatedAt: remote.generatedAt,
    };
    set({ snapshot, remoteSnapshot: remote });
    saveLocalSnapshot(snapshot);
    toast(
      `已用手机上的课表：${snapshot.courses.length} 门课 · ${snapshot.exams.length} 场考试`,
      'success',
    );
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
    });
  });
  window.addEventListener('offline', () => set({ phoneReachable: false, sseConnected: false }));
  window.addEventListener('focus', () => {
    if (document.visibilityState !== 'visible') return;
    if (isOnline() && get().phoneReachable) scheduleAutoSync();
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
    }
  } else if (s.base && !s.secret) {
    const health = await probePhone();
    if (health && devParam('devsecret')) await requestPairCode();
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
  toggleTask,
  toggleStar,
  addTask,
  updateTask,
  afterImport,
  normalizeBase,
};

export type { PairState };
export { formatTime };
