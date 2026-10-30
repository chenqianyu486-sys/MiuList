import { create } from 'zustand';
import { loadInitialData, persistAppearance, persistSetupSkipped } from './storage';
import type {
  ConflictState,
  DetailState,
  Meta,
  PairState,
  PendingMap,
  RemoteBase,
  Snapshot,
  SnapshotFileState,
  SyncTab,
  Task,
  ToastKind,
  ToastState,
  ViewName,
  WallpaperId,
  WeekTab,
} from './types';

const initial = loadInitialData();

const filePickerSupported =
  typeof window !== 'undefined' &&
  typeof (window as unknown as { showOpenFilePicker?: unknown }).showOpenFilePicker ===
    'function' &&
  typeof (window as unknown as { showSaveFilePicker?: unknown }).showSaveFilePicker ===
    'function';

let toastTimer: ReturnType<typeof setTimeout> | null = null;

export type AppStore = {
  booted: boolean;
  base: string;
  secret: string | null;
  deviceId: string | null;
  passcode: string;
  snapshot: Snapshot;
  pending: PendingMap;
  remoteBase: RemoteBase;
  meta: Meta;
  setupSkipped: boolean;
  phoneReachable: boolean;
  phoneName: string;
  phoneVersion: string;
  syncing: boolean;
  syncError: string | null;
  conflict: ConflictState | null;
  pair: PairState;
  remoteSnapshot: Snapshot | null;
  sseConnected: boolean;
  view: ViewName;
  weekTab: WeekTab;
  selectedWeek: number | null;
  sidebarPinned: boolean;
  wallpaper: WallpaperId;
  syncOpen: boolean;
  syncTab: SyncTab;
  editorOpen: boolean;
  editingTask: Task | null;
  detail: DetailState | null;
  toast: ToastState | null;
  snapshotFile: SnapshotFileState;

  setView: (view: ViewName) => void;
  setWeekTab: (tab: WeekTab) => void;
  setSelectedWeek: (week: number | null) => void;
  setSidebarPinned: (pinned: boolean) => void;
  setWallpaper: (wallpaper: WallpaperId) => void;
  openSync: (tab?: SyncTab) => void;
  closeSync: () => void;
  setSyncTab: (tab: SyncTab) => void;
  openTaskEditor: (task?: Task | null) => void;
  closeTaskEditor: () => void;
  openDetail: (detail: DetailState) => void;
  closeDetail: () => void;
  setSnapshotFile: (patch: Partial<SnapshotFileState>) => void;
  setPair: (pair: PairState) => void;
  setConflict: (conflict: ConflictState | null) => void;
  skipSetup: () => void;
  pushToast: (message: string, kind?: ToastKind) => void;
  dismissToast: (id?: number) => void;
};

export const useAppStore = create<AppStore>((set, get) => ({
  booted: false,
  base: initial.base,
  secret: initial.secret,
  deviceId: initial.deviceId,
  passcode: initial.passcode,
  snapshot: initial.snapshot,
  pending: initial.pending,
  remoteBase: initial.remoteBase,
  meta: initial.meta,
  setupSkipped: initial.setupSkipped,
  phoneReachable: false,
  phoneName: initial.snapshot.deviceName || '',
  phoneVersion: initial.snapshot.appVersion || '',
  syncing: false,
  syncError: null,
  conflict: null,
  pair: { status: 'idle' },
  remoteSnapshot: null,
  sseConnected: false,
  view: 'today',
  weekTab: 'week',
  selectedWeek: null,
  sidebarPinned: initial.sidePinned,
  wallpaper: initial.wallpaper,
  syncOpen: false,
  syncTab: 'connection',
  editorOpen: false,
  editingTask: null,
  detail: null,
  toast: null,
  snapshotFile: {
    supported: filePickerSupported,
    name: null,
    active: false,
    lastSavedAt: null,
    permission: null,
    error: null,
  },

  setView: (view) => set({ view }),
  setWeekTab: (weekTab) => set({ weekTab }),
  setSelectedWeek: (selectedWeek) => set({ selectedWeek }),
  setSidebarPinned: (sidebarPinned) => {
    persistAppearance(get().wallpaper, sidebarPinned);
    set({ sidebarPinned });
  },
  setWallpaper: (wallpaper) => {
    persistAppearance(wallpaper, get().sidebarPinned);
    set({ wallpaper });
  },
  openSync: (tab = 'connection') => set({ syncOpen: true, syncTab: tab }),
  closeSync: () => set({ syncOpen: false }),
  setSyncTab: (syncTab) => set({ syncTab }),
  openTaskEditor: (task = null) => set({ editorOpen: true, editingTask: task ?? null }),
  closeTaskEditor: () => set({ editorOpen: false, editingTask: null }),
  openDetail: (detail) => set({ detail }),
  closeDetail: () => set({ detail: null }),
  setSnapshotFile: (patch) =>
    set((state) => ({ snapshotFile: { ...state.snapshotFile, ...patch } })),
  setPair: (pair) => set({ pair }),
  setConflict: (conflict) => set({ conflict }),
  skipSetup: () => {
    persistSetupSkipped(true);
    set({ setupSkipped: true });
  },

  pushToast: (message, kind = 'info') => {
    const id = Date.now() + Math.random();
    set({ toast: { id, message, kind } });
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(
      () => {
        const current = get().toast;
        if (current?.id === id) set({ toast: null });
      },
      kind === 'error' ? 4800 : 2800,
    );
  },
  dismissToast: (id) => {
    const current = get().toast;
    if (!current) return;
    if (id !== undefined && current.id !== id) return;
    set({ toast: null });
  },
}));

export function snapshotHasData(snapshot: Snapshot): boolean {
  return (
    (snapshot.tasks?.length || 0) > 0 ||
    (snapshot.courses?.length || 0) > 0 ||
    (snapshot.exams?.length || 0) > 0 ||
    (snapshot.timetable?.weeks?.length || 0) > 0
  );
}

export function isPaired(state: Pick<AppStore, 'secret' | 'deviceId'>): boolean {
  return Boolean(state.secret && state.deviceId);
}
