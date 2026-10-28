import {
  ArrowRight,
  CalendarDays,
  Coffee,
  GraduationCap,
  ListTodo,
  Plus,
  Sparkles,
} from 'lucide-react';
import { CourseCard } from '../components/CourseCard';
import { ExamCard } from '../components/ExamCard';
import { TaskCard } from '../components/TaskCard';
import { Button, EmptyState, SectionHead, cx } from '../components/ui';
import {
  allOpenTasks,
  examDay,
  examIsToday,
  formatFullDate,
  formatMonthDay,
  greeting,
  isOverdue,
  localToday,
  todayStats,
  weekdayLabel,
  weekdayOf,
} from '../core/format';
import { useAppStore } from '../core/store';
import { COURSE_TINT_ALPHA, accentBarColor, courseFace } from '../core/tint';
import { todayCoursesOf } from '../core/todayCourses';
import type { Course, Exam } from '../core/types';

export function Overview() {
  const state = useAppStore();
  const snapshot = state.snapshot;
  const today = snapshot.today || localToday();
  const weekday = snapshot.weekday || weekdayOf(today);
  const stats = todayStats(snapshot);
  const types = snapshot.taskTypes || [];
  const allTasks = allOpenTasks(snapshot.tasks || [], today);
  const overdueCount = allTasks.filter((task) => isOverdue(task, today)).length;
  const todayCourses = todayCoursesOf(snapshot, today);
  const todayExams = (snapshot.exams || []).filter((exam) => examIsToday(exam, today));
  const upcomingExams = (snapshot.exams || [])
    .filter((exam) => !exam.isToday && examDay(exam) >= today)
    .sort((a, b) => (examDay(a) < examDay(b) ? -1 : 1))
    .slice(0, 3);
  const week = snapshot.timetable?.currentWeek;
  const setView = state.setView;
  const openTaskEditor = state.openTaskEditor;
  const openDetail = state.openDetail;
  const openSync = state.openSync;

  return (
    <div className="overview">
      <section className="hero glass">
        <div className="hero-main">
          <div className="hero-eyebrow">
            {week ? <span className="week-pill">第 {week} 周</span> : null}
            <span className="hero-greeting">{greeting()}，这是今天。</span>
          </div>
          <h2 className="hero-date">
            {formatMonthDay(today)}
            <span className="hero-weekday">{weekdayLabel(weekday)}</span>
          </h2>
          <p className="hero-sub">
            今天 <strong>{stats.courses}</strong> 节课 · <strong>{stats.tasks}</strong> 项待办
            {stats.exams ? (
              <>
                {' '}
                · <strong>{stats.exams}</strong> 场考试
              </>
            ) : null}
            {overdueCount ? <span className="hero-warn">其中 {overdueCount} 项已逾期</span> : null}
          </p>
          <div className="hero-actions">
            <Button variant="primary" icon={Plus} onClick={() => openTaskEditor()}>
              新建待办
            </Button>
            <Button variant="ghost" icon={CalendarDays} onClick={() => setView('week')}>
              看本周课表
            </Button>
          </div>
        </div>
        <div className="hero-side">
          <div className="hero-stats">
            <div className="hero-stat">
              <span className="hero-stat-icon course">
                <GraduationCap size={16} />
              </span>
              <strong>{stats.courses}</strong>
              <span>今日课程</span>
            </div>
            <div className="hero-stat">
              <span className="hero-stat-icon task">
                <ListTodo size={16} />
              </span>
              <strong>{stats.tasks}</strong>
              <span>待办</span>
            </div>
            <div className="hero-stat">
              <span className="hero-stat-icon exam">
                <Sparkles size={16} />
              </span>
              <strong>{stats.exams}</strong>
              <span>考试</span>
            </div>
          </div>
          <p className="hero-note" title={formatFullDate(today, weekday)}>
            {state.phoneReachable
              ? '手机在线，改动会自动同步。'
              : '手机不在也没关系，数据就在这台电脑上。'}
            {!state.phoneReachable && !state.secret ? (
              <button type="button" onClick={() => openSync('connection')}>
                连接手机 <ArrowRight size={13} />
              </button>
            ) : null}
          </p>
        </div>
      </section>

      <div className="overview-grid">
        <section className="section">
          <SectionHead
            icon={GraduationCap}
            title="今天的课"
            count={todayCourses.length}
            action={
              <button type="button" className="link-btn" onClick={() => setView('week')}>
                查看课表 <ArrowRight size={14} />
              </button>
            }
          />
          {todayCourses.length ? (
            <div className="timeline">
              {todayCourses.map((entry, index) => {
                const start = (entry.timeText || '').split(' - ')[0];
                const color = entry.colorHex || '#C9D0D8';
                const course: Course = {
                  key: `today-${index}`,
                  name: entry.name,
                  teacher: entry.teacher,
                  classroom: entry.classroom,
                  colorHex: color,
                  weeklyText: entry.timeText,
                };
                return (
                  <div className="timeline-row" key={`${entry.name}-${index}`}>
                    <div className="timeline-time">
                      <span>{start || '—'}</span>
                    </div>
                    <div className="timeline-marker">
                      <span
                        className={cx('timeline-dot', entry.state === 'now' && 'is-now')}
                        style={{ background: accentBarColor(color) }}
                      />
                    </div>
                    <button
                      type="button"
                      className={cx('course-card', entry.state === 'now' && 'is-now')}
                      style={
                        {
                          '--course-bar': accentBarColor(color),
                          '--course-face': courseFace(color, COURSE_TINT_ALPHA),
                        } as never
                      }
                      onClick={() => openDetail({ kind: 'course', course })}
                    >
                      <span className="course-card-bar" />
                      <span className="course-card-body">
                        <span className="course-card-title">
                          {entry.name}
                          {entry.state === 'now' ? <em className="chip chip--now">正在上</em> : null}
                        </span>
                        <span className="course-card-meta">
                          {entry.timeText}
                          {entry.classroom ? ` · ${entry.classroom}` : ''}
                          {entry.teacher ? ` · ${entry.teacher}` : ''}
                        </span>
                      </span>
                    </button>
                  </div>
                );
              })}
            </div>
          ) : (
            <EmptyState
              icon={Coffee}
              title="今天没有课"
              text="把时间留给待办，或者出去走走。"
            />
          )}
        </section>

        <section className="section">
          <SectionHead
            icon={ListTodo}
            title="全部待办"
            count={allTasks.length}
            action={
              <Button variant="ghost" icon={Plus} onClick={() => openTaskEditor()}>
                新建
              </Button>
            }
          />
          {allTasks.length ? (
            <div className="task-list">
              {allTasks.map((task) => (
                <TaskCard
                  key={task.syncUid}
                  task={task}
                  today={today}
                  types={types}
                  compact
                  onOpen={(item) => openTaskEditor(item)}
                />
              ))}
            </div>
          ) : (
            <EmptyState
              icon={ListTodo}
              title="暂无待办"
              text="新建一条，开始规划吧。"
              action={
                <Button variant="primary" icon={Plus} onClick={() => openTaskEditor()}>
                  新建待办
                </Button>
              }
            />
          )}

        </section>
      </div>

      <section className="section overview-exams">
        <SectionHead
          icon={CalendarDays}
          title="考试"
          count={todayExams.length || upcomingExams.length}
          action={
            upcomingExams.length ? (
              <button type="button" className="link-btn" onClick={() => setView('week')}>
                全部考试 <ArrowRight size={14} />
              </button>
            ) : null
          }
        />
        {todayExams.length ? (
          <div className="exam-grid">
            {todayExams.map((exam: Exam, index) => (
              <ExamCard
                key={exam.id || `${exam.courseName}-${index}`}
                exam={exam}
                today={today}
                compact
                onClick={(item) => openDetail({ kind: 'exam', exam: item })}
              />
            ))}
          </div>
        ) : upcomingExams.length ? (
          <div className="exam-grid">
            {upcomingExams.map((exam: Exam, index) => (
              <ExamCard
                key={exam.id || `${exam.courseName}-${index}`}
                exam={exam}
                today={today}
                compact
                onClick={(item) => openDetail({ kind: 'exam', exam: item })}
              />
            ))}
          </div>
        ) : (
          <EmptyState icon={CalendarDays} title="还没有考试安排" text="有考试时，这里会提前提醒你。" />
        )}
      </section>

      {state.snapshot.courses?.length ? (
        <section className="section">
          <SectionHead
            icon={GraduationCap}
            title="课程"
            count={state.snapshot.courses.length}
            action={
              <button type="button" className="link-btn" onClick={() => setView('week')}>
                课程列表 <ArrowRight size={14} />
              </button>
            }
          />
          <div className="courses-preview">
            {state.snapshot.courses.slice(0, 4).map((course) => (
              <CourseCard
                key={course.key}
                course={course}
                onClick={(item) => openDetail({ kind: 'course', course: item })}
              />
            ))}
          </div>
        </section>
      ) : null}
    </div>
  );
}
