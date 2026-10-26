import { useEffect } from 'react';
import { useAppStore } from '../core/store';
import { TasksView } from '../views/TasksView';
import { Overview } from '../views/Overview';
import { TimetableView } from '../views/TimetableView';
import { MobileNav } from './MobileNav';
import { Sidebar } from './Sidebar';
import { Topbar } from './Topbar';

export function Shell() {
  const view = useAppStore((s) => s.view);
  const pinned = useAppStore((s) => s.sidebarPinned);

  useEffect(() => {
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }, [view]);

  return (
    <div className={`shell${pinned ? ' is-pinned' : ''}`}>
      <Sidebar />
      <main className="main">
        <Topbar />
        <div className="view" key={view}>
          {view === 'today' ? <Overview /> : view === 'week' ? <TimetableView /> : <TasksView />}
        </div>
      </main>
      <MobileNav />
    </div>
  );
}
