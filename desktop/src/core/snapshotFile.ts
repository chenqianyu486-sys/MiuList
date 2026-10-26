import { del, get, set } from 'idb-keyval';
import { useAppStore } from './store';
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
  { description: 'MiuList 快照', accept: { 'application/json': ['.json'] } },
];

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
): { applied: boolean; reason?: string } {
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

  return { applied: true };
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
      error: '快照文件读不出来，可以在「同步 → 快照」里重新选择。',
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
      useAppStore.getState().pushToast('这个文件不是有效的 MiuList 快照。', 'error');
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
    useAppStore.getState().pushToast('已载入快照文件', 'success');
    return true;
  } catch (error: any) {
    if (error?.name === 'AbortError') return false;
    console.warn('MiuList: 选择快照失败', error);
    useAppStore.getState().pushToast('读不了这个快照文件。', 'error');
    return false;
  }
}

export async function createSnapshotFile(): Promise<boolean> {
  if (!supported()) {
    useAppStore.getState().pushToast('当前浏览器不支持直接写文件，请用「导出 JSON」。', 'error');
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
    useAppStore.getState().pushToast('创建快照文件失败。', 'error');
    return false;
  }
}

export async function writeSnapshotNow(manual: boolean): Promise<boolean> {
  if (!handle) {
    if (manual) useAppStore.getState().pushToast('先选择一个快照文件。', 'error');
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
    if (manual) useAppStore.getState().pushToast('快照已保存', 'success');
    return true;
  } catch (error) {
    console.warn('MiuList: 写快照失败', error);
    useAppStore.getState().setSnapshotFile({ error: '快照写不进去，文件可能被移动或权限被收回。' });
    if (manual) useAppStore.getState().pushToast('快照写不进去。', 'error');
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
    useAppStore.getState().pushToast('快照文件已重新连接', 'success');
  } catch (error) {
    console.warn('MiuList: 重新读取快照失败', error);
    useAppStore.getState().setSnapshotFile({ error: '快照文件读不出来。' });
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
  useAppStore.getState().pushToast('已导出 JSON 快照', 'success');
}

export async function importSnapshotJsonFile(file: File): Promise<boolean> {
  try {
    const text = await file.text();
    const raw = JSON.parse(text);
    const result = applySnapshotPayload(raw, 'import');
    if (!result.applied) {
      useAppStore.getState().pushToast('这个文件不是有效的 MiuList 快照。', 'error');
      return false;
    }
    useAppStore.getState().pushToast('已导入快照；连接手机后可同步。', 'success');
    return true;
  } catch (error) {
    console.warn('MiuList: 导入快照失败', error);
    useAppStore.getState().pushToast('导入失败：不是有效的 JSON 快照。', 'error');
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
  useAppStore.getState().pushToast('已断开快照文件（磁盘上的文件没有删）');
}
