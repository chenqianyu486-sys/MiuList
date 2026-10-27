import { localToday } from './format';
import type {
  Meta,
  PendingMap,
  RemoteBase,
  Snapshot,
  WallpaperId,
} from './types';

export const LS = {
  base: 'miulist.base',
  snapshot: 'miulist.snapshot',
  remoteBase: 'miulist.remoteBase',
  pending: 'miulist.pending',
  meta: 'miulist.meta',
  secret: 'miulist.secret',
  deviceId: 'miulist.deviceId',
  passcode: 'miulist.passcode',
  wallpaper: 'miulist.wallpaper',
  sidePinned: 'miulist.sidePinned',
  setupSkipped: 'miulist.setupSkipped',
} as const;

const WALLPAPERS: WallpaperId[] = ['cloud', 'mint', 'dusk', 'sakura'];

export function emptySnapshot(): Snapshot {
  return {
    protocol: 1,
    revision: 0,
    generatedAt: 0,
    today: '',
    weekday: 0,
    deviceName: '这台设备',
    appVersion: '',
    latestEditAt: 0,
    utcOffsetMinutes: 480,
    hasCourses: false,
    hasExams: false,
    hasTaskTypes: false,
    tasks: [],
    taskTypes: [],
    courses: [],
    exams: [],
    todayCourses: [],
    timetable: null,
  };
}

export function normalizeSnapshot(raw: unknown): Snapshot {
  const base = emptySnapshot();
  if (!raw || typeof raw !== 'object') return base;
  const value = raw as Partial<Snapshot>;
  return {
    ...base,
    ...value,
    tasks: Array.isArray(value.tasks) ? value.tasks : [],
    taskTypes: Array.isArray(value.taskTypes) ? value.taskTypes : [],
    courses: Array.isArray(value.courses) ? value.courses : [],
    exams: Array.isArray(value.exams) ? value.exams : [],
    todayCourses: Array.isArray(value.todayCourses) ? value.todayCourses : [],
    timetable: value.timetable ?? null,
    latestEditAt: Number(value.latestEditAt) || 0,
    utcOffsetMinutes: Number.isFinite(Number(value.utcOffsetMinutes))
      ? Number(value.utcOffsetMinutes)
      : 480,
    today: value.today || localToday(),
    hasCourses: value.hasCourses === true,
    hasExams: value.hasExams === true,
    hasTaskTypes: value.hasTaskTypes === true,
  };
}

export function loadJson<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : fallback;
  } catch {
    return fallback;
  }
}

export function saveJson(key: string, value: unknown): boolean {
  try {
    localStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch (error) {
    console.warn('MiuList: 本地存储写入失败', error);
    try {
      window.dispatchEvent(new CustomEvent('miulist:storage-error'));
    } catch {
      /* ignore */
    }
    return false;
  }
}

export function loadPasscode(): string {
  return localStorage.getItem(LS.passcode) || '';
}

export function persistPasscode(passcode: string): void {
  if (passcode) localStorage.setItem(LS.passcode, passcode);
  else localStorage.removeItem(LS.passcode);
}

export function persistSnapshot(snapshot: Snapshot): void {
  saveJson(LS.snapshot, snapshot);
}

export function persistPending(pending: PendingMap): void {
  saveJson(LS.pending, pending);
}

export function persistRemoteBase(remoteBase: RemoteBase): void {
  saveJson(LS.remoteBase, remoteBase);
}

export function persistMeta(meta: Meta): void {
  saveJson(LS.meta, meta);
}

export function persistBase(base: string): void {
  if (base) localStorage.setItem(LS.base, base);
  else localStorage.removeItem(LS.base);
}

export function persistSession(secret: string | null, deviceId: string | null): void {
  if (secret) localStorage.setItem(LS.secret, secret);
  else localStorage.removeItem(LS.secret);
  if (deviceId) localStorage.setItem(LS.deviceId, deviceId);
  else localStorage.removeItem(LS.deviceId);
}

export function persistAppearance(wallpaper: WallpaperId, pinned: boolean): void {
  localStorage.setItem(LS.wallpaper, wallpaper);
  localStorage.setItem(LS.sidePinned, pinned ? '1' : '0');
}

/**
 * 用户明确跳过初始配置后，就把欢迎页收起来。
 *
 * 网页端是能独立运行的一端：没有手机、没有导入快照，也可以先新建待办。
 * 空快照不足以判断「用户想不想看欢迎页」，所以这个意图要单独记住。
 */
export function persistSetupSkipped(skipped: boolean): void {
  if (skipped) localStorage.setItem(LS.setupSkipped, '1');
  else localStorage.removeItem(LS.setupSkipped);
}

export type InitialData = {
  base: string;
  secret: string | null;
  deviceId: string | null;
  passcode: string;
  snapshot: Snapshot;
  pending: PendingMap;
  remoteBase: RemoteBase;
  meta: Meta;
  wallpaper: WallpaperId;
  sidePinned: boolean;
  setupSkipped: boolean;
};

export function loadInitialData(): InitialData {
  const rawSnapshot = loadJson<unknown>(LS.snapshot, null);
  const snapshot = normalizeSnapshot(rawSnapshot);
  const pendingRaw = loadJson<Record<string, unknown>>(LS.pending, {});
  const pending: PendingMap = {};
  for (const [uid, entry] of Object.entries(pendingRaw || {})) {
    if (!entry || typeof entry !== 'object') continue;
    const value = entry as { task?: unknown; updatedAt?: unknown; localOnly?: unknown };
    if (!value.task || typeof value.task !== 'object') continue;
    pending[uid] = {
      task: value.task as PendingMap[string]['task'],
      updatedAt: Number(value.updatedAt) || 0,
      localOnly: value.localOnly === true,
    };
  }
  const remoteRaw = loadJson<Record<string, unknown>>(LS.remoteBase, {});
  const remoteBase: RemoteBase = {};
  for (const [uid, value] of Object.entries(remoteRaw || {})) {
    if (Number.isFinite(Number(value))) remoteBase[uid] = Number(value);
  }
  const metaRaw = loadJson<Partial<Meta>>(LS.meta, {});
  const meta: Meta = {
    lastSyncAt: Number(metaRaw.lastSyncAt) || null,
    lastDirection: metaRaw.lastDirection || null,
  };
  const wallpaperRaw = localStorage.getItem(LS.wallpaper) as WallpaperId | null;
  const wallpaper = wallpaperRaw && WALLPAPERS.includes(wallpaperRaw) ? wallpaperRaw : 'cloud';
  return {
    base: (localStorage.getItem(LS.base) || '').trim(),
    secret: localStorage.getItem(LS.secret) || null,
    deviceId: localStorage.getItem(LS.deviceId) || null,
    passcode: loadPasscode(),
    snapshot,
    pending,
    remoteBase,
    meta,
    wallpaper,
    sidePinned: localStorage.getItem(LS.sidePinned) === '1',
    setupSkipped: localStorage.getItem(LS.setupSkipped) === '1',
  };
}

export function clearLocalData(): void {
  for (const key of Object.values(LS)) {
    try {
      localStorage.removeItem(key);
    } catch {
      /* ignore */
    }
  }
}
