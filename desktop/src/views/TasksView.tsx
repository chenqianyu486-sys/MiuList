import { useMemo, useState } from 'react';
import {
  CheckCircle2,
  Circle,
  FileJson2,
  ListChecks,
  Plus,
  Search,
  Star,
  TriangleAlert,
} from 'lucide-react';
import { TaskCard } from '../components/TaskCard';
import { Button, EmptyState, SectionHead, cx } from '../components/ui';
import {
  formatSyncTime,
  inTodayList,
  isOverdue,
  localToday,
  sortDoneTasks,
  sortOpenTasks,
} from '../core/format';
import { useAppStore } from '../core/store';
import { taskTypeOf, taskTypeOptions } from '../core/taskTypes';
import type { Task } from '../core/types';

type Filter = 'open' | 'done' | 'all';

export function TasksView() {
  const state = useAppStore();
  const snapshot = state.snapshot;
  const today = snapshot.today || localToday();
  const types = snapshot.taskTypes || [];
  const options = taskTypeOptions(types);
  const [filter, setFilter] = useState<Filter>('open');
  const [query, setQuery] = useState('');
  const [typeFilter, setTypeFilter] = useState('');
  const openTaskEditor = state.openTaskEditor;
  const openSync = state.openSync;

  const all = snapshot.tasks || [];
  const open = sortOpenTasks(all.filter((task) => !task.isCompleted), today);
  const done = sortDoneTasks(all.filter((task) => task.isCompleted));
  const overdue = open.filter((task) => isOverdue(task, today));
  const starred = open.filter((task) => task.priority === 'STAR');
  const todayOpen = open.filter((task) => inTodayList(task, today));
  const completion = all.length ? Math.round((done.length / all.length) * 100) : 0;

  const typeCounts = useMemo(() => {
    const map = new Map<string, number>();
    for (const task of all) {
      const label = taskTypeOf(task, types).label;
      if (!label) continue;
      map.set(label, (map.get(label) || 0) + 1);
    }
    return options
      .map((option) => ({ ...option, count: map.get(option.label) || 0 }))
      .filter((option) => option.count > 0);
  }, [all, options, types]);

  const matches = (task: Task) => {
    const type = taskTypeOf(task, types);
    if (typeFilter && type.label !== typeFilter) return false;
    const q = query.trim().toLowerCase();
    if (!q) return true;
    return [task.title, task.courseName, task.taskTypeLabel, type.label]
      .filter(Boolean)
      .some((text) => String(text).toLowerCase().includes(q));
  };

  const openFiltered = open.filter(matches);
  const doneFiltered = done.filter(matches);
  const hasAny = openFiltered.length + doneFiltered.length > 0;
  const emptyTitle =
    query || typeFilter
      ? '没有匹配的待办'
      : filter === 'done'
        ? '还没有完成的待办'
        : filter === 'open'
          ? '没有未完成的待办'
          : '清单还是空的';
  const emptyText =
    query || typeFilter
      ? '换个关键词或清掉类别筛选试试。'
      : filter === 'done'
        ? '完成一条之后，它会被收进这里。'
        : '新建一条待办，或在手机上让它跟着课程自动生成。';

  return (
    <div className="tasks-view">
      <div className="tasks-toolbar glass">
        <div className="segmented" role="tablist" aria-label="清单筛选">
          {(
            [
              ['open', '未完成', open.length],
              ['done', '已完成', done.length],
              ['all', '全部', all.length],
            ] as const
          ).map(([id, label, count]) => (
            <button
              key={id}
              type="button"
              role="tab"
              aria-selected={filter === id}
              className={cx('segment', filter === id && 'is-active')}
              onClick={() => setFilter(id)}
            >
              {label}
              <span className="segment-count">{count}</span>
            </button>
          ))}
        </div>
        <label className="search-field">
          <Search size={16} />
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="搜索标题、课程或类别"
            aria-label="搜索待办"
          />
        </label>
        <Button variant="primary" icon={Plus} onClick={() => openTaskEditor()}>
          新建待办
        </Button>
      </div>

      {typeCounts.length ? (
        <div className="type-filter" aria-label="按类别筛选">
          <button
            type="button"
            className={cx('type-pill', !typeFilter && 'is-active')}
            onClick={() => setTypeFilter('')}
          >
            全部类别
          </button>
          {typeCounts.map((option) => (
            <button
              key={option.name || option.label}
              type="button"
              className={cx('type-pill', typeFilter === option.label && 'is-active')}
              style={
                {
                  '--chip-bg': option.colorHex,
                  '--chip-ink': option.inkHex,
                } as never
              }
              onClick={() => setTypeFilter(typeFilter === option.label ? '' : option.label)}
            >
              <span className="type-dot" />
              {option.label}
              <span className="segment-count">{option.count}</span>
            </button>
          ))}
        </div>
      ) : null}

      <div className="tasks-layout">
        <div className="tasks-main">
          {hasAny ? (
            <>
              {(filter === 'open' || filter === 'all') && openFiltered.length > 0 ? (
                <section className="task-group">
                  <SectionHead
                    icon={Circle}
                    title="未完成"
                    count={openFiltered.length}
                    action={
                      <span className="group-note">
                        {todayOpen.length} 条今天到期 · {starred.length} 条星标
                      </span>
                    }
                  />
                  <div className="task-list">
                    {openFiltered.map((task) => (
                      <TaskCard
                        key={task.syncUid}
                        task={task}
                        today={today}
                        types={types}
                        onOpen={(item) => openTaskEditor(item)}
                      />
                    ))}
                  </div>
                </section>
              ) : null}

              {(filter === 'done' || filter === 'all') && doneFiltered.length > 0 ? (
                <section className="task-group is-done-group">
                  <SectionHead icon={CheckCircle2} title="已完成" count={doneFiltered.length} />
                  <div className="task-list">
                    {doneFiltered.map((task) => (
                      <TaskCard
                        key={task.syncUid}
                        task={task}
                        today={today}
                        types={types}
                        onOpen={(item) => openTaskEditor(item)}
                      />
                    ))}
                  </div>
                </section>
              ) : null}

            </>
          ) : (
            <EmptyState
              icon={ListChecks}
              title={emptyTitle}
              text={emptyText}
              action={
                query || typeFilter ? (
                  <Button
                    variant="soft"
                    onClick={() => {
                      setQuery('');
                      setTypeFilter('');
                    }}
                  >
                    清掉筛选
                  </Button>
                ) : (
                  <Button variant="primary" icon={Plus} onClick={() => openTaskEditor()}>
                    新建待办
                  </Button>
                )
              }
            />
          )}
        </div>

        <aside className="tasks-aside">
          <div className="summary-card glass">
            <div className="summary-ring" style={{ '--value': `${completion * 3.6}deg` } as never}>
              <div className="summary-ring-inner">
                <strong>{completion}%</strong>
                <span>已完成</span>
              </div>
            </div>
            <div className="summary-copy">
              <p className="summary-kicker">清单概览</p>
              <h3>{open.length} 条待办等着你</h3>
              <p>完成一条，就离清空近一点。</p>
            </div>
            <div className="summary-stats">
              <div>
                <strong>{starred.length}</strong>
                <span>
                  <Star size={12} /> 星标
                </span>
              </div>
              <div>
                <strong>{overdue.length}</strong>
                <span>
                  <TriangleAlert size={12} /> 逾期
                </span>
              </div>
              <div>
                <strong>{done.length}</strong>
                <span>
                  <CheckCircle2 size={12} /> 完成
                </span>
              </div>
            </div>
          </div>

          <div className="aside-card glass-soft">
            <div className="aside-card-head">
              <FileJson2 size={16} />
              <span>本机备份</span>
            </div>
            {state.snapshotFile.active ? (
              <>
                <p className="aside-mono">{state.snapshotFile.name}</p>
                <p className="aside-note">
                  {state.snapshotFile.lastSavedAt
                    ? `最近保存 ${formatSyncTime(state.snapshotFile.lastSavedAt, snapshot)}`
                    : '编辑后会自动保存'}
                </p>
                <Button variant="ghost" onClick={() => openSync('snapshot')}>
                  管理备份文件
                </Button>
              </>
            ) : (
              <>
                <p className="aside-note">选一份文件，之后每次改动都会自动写进去。</p>
                <Button variant="soft" onClick={() => openSync('snapshot')}>
                  选择备份文件
                </Button>
              </>
            )}
          </div>

          <div className="aside-quote glass-soft">
            <span className="quote-mark">“</span>
            <p>{state.phoneReachable ? '手机在线，清单会自动同步。' : '手机不在也能看、能改，连上后再同步。'}</p>
            <button type="button" className="link-btn" onClick={() => openSync('connection')}>
              查看同步状态
            </button>
          </div>
        </aside>
      </div>
    </div>
  );
}
