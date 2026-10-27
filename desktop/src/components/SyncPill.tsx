import { RefreshCw } from 'lucide-react';
import { useAppStore, type AppStore } from '../core/store';
import { cx, Spinner } from './ui';

export type SyncStatusKind = 'live' | 'local' | 'conflict' | 'error' | 'syncing';

export function deriveSyncStatus(
  state: Pick<
    AppStore,
    | 'conflict'
    | 'syncError'
    | 'phoneReachable'
    | 'secret'
    | 'deviceId'
    | 'syncing'
    | 'base'
  >,
): { text: string; kind: SyncStatusKind; detail: string } {
  if (state.conflict) {
    return { text: '需手动同步', kind: 'conflict', detail: '两端改过同一条待办，等你选方向' };
  }
  if (state.syncing) {
    return { text: '同步中…', kind: 'syncing', detail: '正在与手机同步' };
  }
  if (state.syncError) {
    return { text: '同步失败', kind: 'error', detail: state.syncError };
  }
  if (!state.base || !state.secret || !state.deviceId) {
    return { text: '本地模式', kind: 'local', detail: '还没有和手机配对，数据只在本机' };
  }
  if (!state.phoneReachable) {
    return { text: '未连接', kind: 'local', detail: '手机上的网页同步没开，或不在同一网络' };
  }
  return { text: '实时同步', kind: 'live', detail: '和手机保持着实时同步' };
}

export function SyncPill() {
  const state = useAppStore();
  const status = deriveSyncStatus(state);
  const openSync = useAppStore((s) => s.openSync);
  return (
    <button
      type="button"
      className={cx('sync-pill', `sync-pill--${status.kind}`)}
      onClick={() => openSync('connection')}
      title={status.detail}
      aria-label={`${status.text}，点开同步设置`}
    >
      {status.kind === 'syncing' ? (
        <Spinner size={14} />
      ) : status.kind === 'error' ? (
        <RefreshCw size={14} />
      ) : (
        <span className="sync-dot" />
      )}
      <span className="sync-text">{status.text}</span>
    </button>
  );
}
