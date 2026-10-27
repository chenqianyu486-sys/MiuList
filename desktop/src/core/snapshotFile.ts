import { del, get, set } from 'idb-keyval';
import { useAppStore } from './store';
import { decodePhoneDocument, buildPhoneDocument } from './phoneDocument';
import {
  normalizeSnapshot,
  persistBase,
  persistMeta,
  persistPending,
  persistRemoteBase,
  persistSession,
  persistSnapshot,
} from './storage';
import type { SnapshotFilePayload, Task } from './types';

const HANDLE_KEY = 'miulist.snapshotHandle.v1';

const PICKER_TYPES = [
  { description: 'MiuList 备份', accept: { 'application/json': ['.json'] } },
];

/** 这次导入到底带进来了什么。用来**说清楚**，而不是只说「已导入」。 */
export type SnapshotSummary = {
  tasks: number;
  taskTypes: number;
  courses: number;
  exams: number;
  weekPlan: boolean;
  calendar: boolean;
};

/**
 * 把摘要说成一句话。
 *
 * **只报「已导入」是不够的**：以前手机导出的文件丢进来，
 * 课表与考试全被丢掉，而界面照样提示「已导入快照」——
 * 用户看到的是「课表怎么没了」，却没人在哪一步弄丢的。
 */
function summaryText(summary: SnapshotSummary | undefined): string {
  if (!summary) return '已导入备份';
  const parts = [`${summary.tasks} 条待办`];
  if (summary.courses) parts.push(`${summary.courses} 门课`);
  if (summary.exams) parts.push(`${summary.exams} 场考试`);
  if (summary.taskTypes) parts.push(`${summary.taskTypes} 个分类`);
  if (summary.weekPlan) parts.push('周课表');
  if (summary.calendar) parts.push('校历');
  return `已导入：${parts.join(' · ')}`;
}

let handle: any = null;
let saveTimer: ReturnType<typeof setTimeout> | null = null;
let ready = false;
let writing = false;

function supported(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.showOpenFilePicker === 'function' &&
    typeof window.showSaveFilePicker === 'function'
  );
}

export function initFileSupport(): void {
  useAppStore.getState().setSnapshotFile({ supported: supported() });
}

export function buildSnapshotPayload(): SnapshotFilePayload {
  const s = useAppStore.getState();
  return {
    app: 'MiuList',
    kind: 'desktop-snapshot',
    version: 1,
    savedAt: Date.now(),
    base: s.base,
    deviceId: s.deviceId,
    snapshot: s.snapshot,
    pending: s.pending,
    remoteBase: s.remoteBase,
    meta: s.meta,
    // 同一份数据的另一种形状：**手机 App 也认得的整机快照**。
    // 有了它，这份文件就能双向通用 —— 手机丢了可以从电脑上的这份救回来。
    document: buildPhoneDocument(s.snapshot),
  };
}

async function readPayload(fileHandle: any): Promise<unknown> {
  const file = await fileHandle.getFile();
  const text = await file.text();
  return JSON.parse(text);
}

function unpack(raw: any): {
  snapshot: unknown;
  pending: any;
  remoteBase: any;
  meta: any;
  base: string;
  deviceId: string | null;
  savedAt: number | null;
} | null {
  if (!raw || typeof raw !== 'object') return null;

  // **手机 App 导出的整机快照**：翻译成网页端认识的形状。
  //
  // 以前这里没有这一分支：手机的文档顶层有 `tasks`，于是被当成了本机快照，
  // 而 `normalizeSnapshot` 只认网页端自己的字段 ——
  // 课程在手机那边是嵌在 `timetable.courses` 里的，找不到；
  // 考试手机那边根本没带（v4 起才有）；周课表形状也对不上。
  // 结果是**待办进来了、课表与考试全丢了、周视图空着**，
  // 而界面上还提示「已导入快照」。
  const phone = decodePhoneDocument(raw);
  if (phone) {
    return {
      snapshot: {
        tasks: phone.tasks,
        taskTypes: phone.taskTypes,
        courses: phone.courses,
        exams: phone.exams,
        timetable: phone.timetable,
        calendar: phone.calendar,
        hasCourses: phone.courses.length > 0,
        hasExams: phone.exams.length > 0,
        hasTaskTypes: phone.taskTypes.length > 0,
        // 「谁新谁赢」要有个可比的值：取待办里最晚的那次改动。
        latestEditAt: phone.tasks.reduce((max, task) => Math.max(max, task.updatedAt || 0), 0),
      },
      pending: {},
      remoteBase: {},
      meta: {},
      base: '',
      deviceId: null,
      savedAt: null,
    };
  }

  const snapshot = raw.snapshot ?? (raw.tasks || raw.courses || raw.timetable ? raw : null);
  if (!snapshot || typeof snapshot !== 'object') return null;
  return {
    snapshot,
    pending: raw.pending && typeof raw.pending === 'object' ? raw.pending : {},
    remoteBase: raw.remoteBase && typeof raw.remoteBase === 'object' ? raw.remoteBase : {},
    meta: raw.meta && typeof raw.meta === 'object' ? raw.meta : {},
    base: typeof raw.base === 'string' ? raw.base : '',
    deviceId: typeof raw.deviceId === 'string' ? raw.deviceId : null,
    savedAt: Number.isFinite(Number(raw.savedAt)) ? Number(raw.savedAt) : null,
  };
}

/**
 * 把快照文件并入本机。
 *
 * - 自动恢复（boot）：谁新谁赢，旧文件不会覆盖更新的浏览器数据；
 * - 手动载入（import）：用户明确选了它，就按文件内容替换。
 *
 * 文件里**没有**配对密钥；secret 永远只留在浏览器里。
 */
export function applySnapshotPayload(
  raw: unknown,
  mode: 'boot' | 'import',
): { applied: boolean; reason?: string; summary?: SnapshotSummary } {
  const data = unpack(raw);
  if (!data) return { applied: false, reason: 'invalid' };

  const state = useAppStore.getState();
  const nextSnapshot = normalizeSnapshot(data.snapshot);
  if (
    mode === 'boot' &&
    (state.snapshot.latestEditAt || 0) > (nextSnapshot.latestEditAt || 0)
  ) {
    return { applied: false, reason: 'local-newer' };
  }

  const nextBase = state.base || data.base || '';
  const nextDeviceId = state.secret ? state.deviceId : data.deviceId || state.deviceId;
  const nextMeta = {
    lastSyncAt: Number.isFinite(Number(data.meta.lastSyncAt)) ? Number(data.meta.lastSyncAt) : null,
    lastDirection: typeof data.meta.lastDirection === 'string' ? data.meta.lastDirection : null,
  };

  const pending: typeof state.pending = {};
  for (const [uid, entry] of Object.entries(data.pending || {})) {
    const value = entry as { task?: unknown; updatedAt?: unknown; localOnly?: unknown };
    if (!value || typeof value !== 'object' || !value.task) continue;
    pending[uid] = {
      task: value.task as Task,
      updatedAt: Number(value.updatedAt) || 0,
      localOnly: value.localOnly === true,
    };
  }
  const remoteBase: typeof state.remoteBase = {};
  for (const [uid, value] of Object.entries(data.remoteBase || {})) {
    if (Number.isFinite(Number(value))) remoteBase[uid] = Number(value);
  }

  useAppStore.setState({
    snapshot: nextSnapshot,
    pending,
    remoteBase,
    meta: nextMeta,
    base: nextBase,
    deviceId: nextDeviceId,
    snapshotFile: {
      ...state.snapshotFile,
      lastSavedAt: data.savedAt,
      error: null,
    },
  });

  persistSnapshot(nextSnapshot);
  persistPending(pending);
  persistRemoteBase(remoteBase);
  persistMeta(nextMeta);
  if (nextBase !== state.base) persistBase(nextBase);
  if (nextDeviceId !== state.deviceId) persistSession(state.secret, nextDeviceId);

  return {
    applied: true,
    summary: {
      tasks: nextSnapshot.tasks.length,
      taskTypes: nextSnapshot.taskTypes.length,
      courses: nextSnapshot.courses.length,
      exams: nextSnapshot.exams.length,
      weekPlan: Boolean(nextSnapshot.timetable),
      calendar: Boolean(nextSnapshot.calendar),
    },
  };
}

async function permissionOf(fileHandle: any): Promise<'granted' | 'prompt' | 'denied'> {
  try {
    if (typeof fileHandle.queryPermission === 'function') {
      return await fileHandle.queryPermission({ mode: 'readwrite' });
    }
  } catch {
    /* ignore */
  }
  return 'prompt';
}

async function ensurePermission(fileHandle: any, request: boolean): Promise<boolean> {
  const current = await permissionOf(fileHandle);
  if (current === 'granted') return true;
  if (!request) return false;
  try {
    if (typeof fileHandle.requestPermission === 'function') {
      const next = await fileHandle.requestPermission({ mode: 'readwrite' });
      return next === 'granted';
    }
  } catch {
    /* ignore */
  }
  return false;
}

export async function restoreSnapshotFileOnBoot(): Promise<void> {
  initFileSupport();
  ready = true;
  if (!supported()) return;
  try {
    const stored = (await get(HANDLE_KEY)) as any;
    if (!stored) return;
    handle = stored;
    useAppStore.getState().setSnapshotFile({
      active: true,
      name: stored.name || 'miulist-snapshot.json',
    });
    const permission = await permissionOf(stored);
    useAppStore.getState().setSnapshotFile({ permission });
    if (permission !== 'granted') return;
    const raw = await readPayload(stored);
    const result = applySnapshotPayload(raw, 'boot');
    const fileSavedAt = unpack(raw)?.savedAt ?? null;
    useAppStore.getState().setSnapshotFile({
      active: true,
      name: stored.name || 'miulist-snapshot.json',
      permission: 'granted',
      lastSavedAt: fileSavedAt,
      error: null,
    });
    if (!result.applied && result.reason === 'local-newer') {
      scheduleSnapshotFileSave();
    }
  } catch (error) {
    console.warn('MiuList: 快照文件恢复失败', error);
    useAppStore.getState().setSnapshotFile({
      error: '备份文件读不出来，可以在「同步 → 本机文件」里重新选择。',
    });
  }
}

export async function pickSnapshotFile(): Promise<boolean> {
  if (!supported()) {
    useAppStore.getState().pushToast('当前浏览器不支持直接读写文件，请用导出/导入。', 'error');
    return false;
  }
  let picked: any;
  try {
    const files = await window.showOpenFilePicker!({
      multiple: false,
      types: PICKER_TYPES,
      excludeAcceptAllOption: false,
    });
    picked = files[0];
    if (!picked) return false;
    const granted = await ensurePermission(picked, true);
    if (!granted) {
      useAppStore.getState().pushToast('没有文件读写权限。', 'error');
      return false;
    }
    const raw = await readPayload(picked);
    const result = applySnapshotPayload(raw, 'import');
    if (!result.applied) {
      useAppStore.getState().pushToast('这个文件不是有效的 MiuList 备份。', 'error');
      return false;
    }
    handle = picked;
    await set(HANDLE_KEY, picked);
    useAppStore.getState().setSnapshotFile({
      active: true,
      name: picked.name,
      permission: 'granted',
      lastSavedAt: unpack(raw)?.savedAt ?? null,
      error: null,
    });
    useAppStore.getState().pushToast(summaryText(result.summary), 'success');
    return true;
  } catch (error: any) {
    if (error?.name === 'AbortError') return false;
    console.warn('MiuList: 选择快照失败', error);
    useAppStore.getState().pushToast('读不了这个备份文件。', 'error');
    return false;
  }
}

export async function createSnapshotFile(): Promise<boolean> {
  if (!supported()) {
    useAppStore.getState().pushToast('当前浏览器不支持直接写文件，请用「导出备份」。', 'error');
    return false;
  }
  try {
    const picked = await window.showSaveFilePicker!({
      suggestedName: 'miulist-snapshot.json',
      types: PICKER_TYPES,
      excludeAcceptAllOption: false,
    });
    if (!picked) return false;
    const granted = await ensurePermission(picked, true);
    if (!granted) {
      useAppStore.getState().pushToast('没有文件写权限。', 'error');
      return false;
    }
    handle = picked;
    await set(HANDLE_KEY, picked);
    useAppStore.getState().setSnapshotFile({
      active: true,
      name: picked.name,
      permission: 'granted',
      error: null,
    });
    return await writeSnapshotNow(true);
  } catch (error: any) {
    if (error?.name === 'AbortError') return false;
    console.warn('MiuList: 创建快照失败', error);
    useAppStore.getState().pushToast('创建备份文件失败。', 'error');
    return false;
  }
}

export async function writeSnapshotNow(manual: boolean): Promise<boolean> {
  if (!handle) {
    if (manual) useAppStore.getState().pushToast('先选择一个备份文件。', 'error');
    return false;
  }
  if (writing) return false;
  const granted = await ensurePermission(handle, manual);
  if (!granted) {
    useAppStore.getState().setSnapshotFile({ permission: 'prompt' });
    if (manual) useAppStore.getState().pushToast('需要文件写入权限，请再点一次。', 'error');
    return false;
  }
  writing = true;
  try {
    const payload = buildSnapshotPayload();
    const blob = JSON.stringify(payload, null, 2);
    const writable = await handle.createWritable();
    await writable.write(blob);
    await writable.close();
    useAppStore.getState().setSnapshotFile({
      active: true,
      name: handle.name || 'miulist-snapshot.json',
      permission: 'granted',
      lastSavedAt: payload.savedAt,
      error: null,
    });
    if (manual) useAppStore.getState().pushToast('备份已保存', 'success');
    return true;
  } catch (error) {
    console.warn('MiuList: 写快照失败', error);
    useAppStore.getState().setSnapshotFile({ error: '备份写不进去，文件可能被移动或权限被收回。' });
    if (manual) useAppStore.getState().pushToast('备份写不进去。', 'error');
    return false;
  } finally {
    writing = false;
  }
}

export function scheduleSnapshotFileSave(): void {
  if (!ready || !handle) return;
  if (useAppStore.getState().snapshotFile.permission !== 'granted') return;
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    saveTimer = null;
    void writeSnapshotNow(false);
  }, 800);
}

export async function requestSnapshotPermission(): Promise<void> {
  if (!handle) return;
  const granted = await ensurePermission(handle, true);
  useAppStore.getState().setSnapshotFile({ permission: granted ? 'granted' : 'denied' });
  if (!granted) {
    useAppStore.getState().pushToast('没有拿到文件权限。', 'error');
    return;
  }
  try {
    const raw = await readPayload(handle);
    applySnapshotPayload(raw, 'boot');
    useAppStore.getState().setSnapshotFile({
      active: true,
      name: handle.name || 'miulist-snapshot.json',
      permission: 'granted',
      lastSavedAt: unpack(raw)?.savedAt ?? null,
      error: null,
    });
    useAppStore.getState().pushToast('备份文件已重新连接', 'success');
  } catch (error) {
    console.warn('MiuList: 重新读取快照失败', error);
    useAppStore.getState().setSnapshotFile({ error: '备份文件读不出来。' });
  }
}

export function exportSnapshotJson(): void {
  const payload = buildSnapshotPayload();
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'miulist-snapshot.json';
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 4000);
  useAppStore.getState().pushToast('已导出备份', 'success');
}

export async function importSnapshotJsonFile(file: File): Promise<boolean> {
  try {
    const text = await file.text();
    const raw = JSON.parse(text);
    const result = applySnapshotPayload(raw, 'import');
    if (!result.applied) {
      useAppStore.getState().pushToast('这个文件不是有效的 MiuList 备份。', 'error');
      return false;
    }
    useAppStore.getState().pushToast(`${summaryText(result.summary)}；连接手机后可同步。`, 'success');
    return true;
  } catch (error) {
    console.warn('MiuList: 导入快照失败', error);
    useAppStore.getState().pushToast('导入失败：不是有效的 MiuList 备份文件。', 'error');
    return false;
  }
}

export async function disconnectSnapshotFile(): Promise<void> {
  handle = null;
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = null;
  try {
    await del(HANDLE_KEY);
  } catch {
    /* ignore */
  }
  useAppStore.getState().setSnapshotFile({
    active: false,
    name: null,
    lastSavedAt: null,
    permission: null,
    error: null,
  });
  useAppStore.getState().pushToast('已断开备份文件（磁盘上的文件没有删）');
}
