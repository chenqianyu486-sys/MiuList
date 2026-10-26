import { CalendarDays, LayoutGrid, ListChecks } from 'lucide-react';
import { useAppStore } from '../core/store';
import { deriveSyncStatus } from './SyncPill';
import { cx } from './ui';

const NAV = [
  { id: 'today', label: '总览', icon: LayoutGrid },
  { id: 'week', label: '课表', icon: CalendarDays },
  { id: 'tasks', label: '清单', icon: ListChecks },
] as const;

export function MobileNav() {
  const view = useAppStore((s) => s.view);
  const setView = useAppStore((s) => s.setView);
  const status = deriveSyncStatus(useAppStore());
  const openSync = useAppStore((s) => s.openSync);

  return (
    <nav className="mobile-nav" aria-label="主导航">
      {NAV.map((item) => {
        const Icon = item.icon;
        return (
          <button
            key={item.id}
            type="button"
            className={cx('mobile-nav-item', view === item.id && 'is-active')}
            onClick={() => setView(item.id)}
            aria-current={view === item.id ? 'page' : undefined}
          >
            <Icon size={20} />
            <span>{item.label}</span>
          </button>
        );
      })}
      <button
        type="button"
        className={cx('mobile-nav-item', `status-${status.kind}`)}
        onClick={() => openSync('connection')}
      >
        <span className={cx('status-dot', `is-${status.kind}`)} />
        <span>{status.text}</span>
      </button>
    </nav>
  );
}
