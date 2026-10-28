import { FileJson2, Palette, ScanLine } from 'lucide-react';
import { formatMonthDay, localToday, weekdayLabel } from '../core/format';
import { useAppStore } from '../core/store';
import { SyncPill } from './SyncPill';
import { IconButton } from './ui';

const PAGE_META = {
  today: { title: '总览', subtitle: '今天的课、待办与考试。' },
  week: { title: '课表', subtitle: '本学期的课，以及考试安排。' },
  tasks: { title: '清单', subtitle: '把要做的事收进一张清单。' },
} as const;

export function Topbar() {
  const view = useAppStore((s) => s.view);
  const today = useAppStore((s) => s.snapshot.today || localToday());
  const weekday = useAppStore((s) => s.snapshot.weekday || 0);
  const openScanner = useAppStore((s) => s.openScanner);
  const openSync = useAppStore((s) => s.openSync);
  const snapshotActive = useAppStore((s) => s.snapshotFile.active);
  const meta = PAGE_META[view];

  return (
    <header className="topbar">
      <div className="page-head">
        <h1 className="page-title">{meta.title}</h1>
        <p className="page-sub">
          {meta.subtitle}
          <span className="topbar-date">
            {formatMonthDay(today)}
            {weekday ? ` · ${weekdayLabel(weekday)}` : ''}
          </span>
        </p>
      </div>
      <div className="top-actions">
        <SyncPill />
        <IconButton icon={ScanLine} label="扫描手机二维码" onClick={openScanner} />
        {snapshotActive ? (
          <IconButton
            icon={FileJson2}
            label="备份文件"
            onClick={() => openSync('snapshot')}
            className="has-file"
          />
        ) : null}
        <IconButton icon={Palette} label="外观" onClick={() => openSync('appearance')} />
      </div>
    </header>
  );
}
