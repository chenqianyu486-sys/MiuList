import {
  CalendarDays,
  FileJson2,
  LayoutGrid,
  ListChecks,
  Pin,
  PinOff,
  Settings2,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import appIcon from '../assets/app-icon.png';
import { formatSyncTime } from '../core/format';
import { useAppStore } from '../core/store';
import type { ViewName } from '../core/types';
import { deriveSyncStatus } from './SyncPill';
import { cx } from './ui';

const NAV: { id: ViewName; label: string; icon: LucideIcon }[] = [
  { id: 'today', label: '总览', icon: LayoutGrid },
  { id: 'week', label: '课表', icon: CalendarDays },
  { id: 'tasks', label: '清单', icon: ListChecks },
];

export function Sidebar() {
  const state = useAppStore();
  const status = deriveSyncStatus(state);
  const setView = useAppStore((s) => s.setView);
  const pinned = state.sidebarPinned;
  const setPinned = useAppStore((s) => s.setSidebarPinned);
  const openSync = useAppStore((s) => s.openSync);
  const lastSync = state.meta.lastSyncAt
    ? formatSyncTime(state.meta.lastSyncAt, state.snapshot)
    : '';
  const detail =
    state.phoneName || state.snapshot.deviceName
      ? `手机：${state.phoneName || state.snapshot.deviceName}`
      : '点击连接手机';

  return (
    <aside className="sidebar" aria-label="主导航">
      <div className="sidebar-top">
        <div className="brand">
          <img src={appIcon} alt="" className="brand-mark" />
          <div className="brand-copy">
            <span className="brand-name">MiuList</span>
            <span className="brand-kicker">电脑网页端</span>
          </div>
        </div>

        <nav className="nav">
          {NAV.map((item) => {
            const Icon = item.icon;
            return (
              <button
                key={item.id}
                type="button"
                className={cx('nav-item', state.view === item.id && 'is-active')}
                onClick={() => setView(item.id)}
                aria-current={state.view === item.id ? 'page' : undefined}
                title={item.label}
              >
                <span className="nav-icon">
                  <Icon size={20} />
                </span>
                <span className="nav-label">{item.label}</span>
              </button>
            );
          })}
        </nav>
      </div>

      <div className="sidebar-bottom">
        <button
          type="button"
          className="side-status"
          onClick={() => openSync('connection')}
          title={`${status.text} · ${detail}`}
        >
          <span className={cx('status-dot', `is-${status.kind}`)} />
          <span className="side-status-copy">
            <span className="side-status-text">{status.text}</span>
            <span className="side-status-sub">{lastSync ? `上次同步 ${lastSync.slice(5)}` : detail}</span>
          </span>
        </button>

        <div className="side-actions">
          {state.snapshotFile.active ? (
            <button
              type="button"
              className="side-icon-btn has-file"
              title="本机 JSON 快照"
              aria-label="本机 JSON 快照"
              onClick={() => openSync('snapshot')}
            >
              <FileJson2 size={18} />
              <span className="file-dot" />
            </button>
          ) : null}
          <button
            type="button"
            className="side-icon-btn"
            title="外观与毛玻璃"
            aria-label="外观与毛玻璃"
            onClick={() => openSync('appearance')}
          >
            <Settings2 size={18} />
          </button>
          <button
            type="button"
            className={cx('side-icon-btn', pinned && 'is-active')}
            title={pinned ? '取消钉住侧栏' : '钉住侧栏'}
            aria-label={pinned ? '取消钉住侧栏' : '钉住侧栏'}
            aria-pressed={pinned}
            onClick={() => setPinned(!pinned)}
          >
            {pinned ? <PinOff size={18} /> : <Pin size={18} />}
          </button>
        </div>
      </div>
    </aside>
  );
}
