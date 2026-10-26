import { useEffect, useRef } from 'react';
import { CalendarDays, ExternalLink, Info } from 'lucide-react';
import { CourseCard } from '../components/CourseCard';
import { ExamCard } from '../components/ExamCard';
import { Button, EmptyState, cx } from '../components/ui';
import { examDay, localToday } from '../core/format';
import { useAppStore } from '../core/store';
import { renderWeekGrid } from '../core/timetable';
import type { Course, Exam, TimetableExam } from '../core/types';

type WeekTab = 'week' | 'courses' | 'exams';

export function TimetableView() {
  const state = useAppStore();
  const snapshot = state.snapshot;
  const timetable = snapshot.timetable || null;
  const today = snapshot.today || localToday();
  const week = state.selectedWeek || timetable?.currentWeek || 1;
  const tab = state.weekTab;
  const hostRef = useRef<HTMLDivElement | null>(null);
  const setSelectedWeek = state.setSelectedWeek;
  const setWeekTab = state.setWeekTab;
  const openSync = state.openSync;
  const openDetail = state.openDetail;

  useEffect(() => {
    const host = hostRef.current;
    if (!host || tab !== 'week' || !timetable) return;
    renderWeekGrid(host, {
      timetable,
      courses: snapshot.courses || [],
      tasks: snapshot.tasks || [],
      today,
      week,
      onSelectWeek: (next: number) => setSelectedWeek(next),
      onOpenCourse: (course: unknown) => openDetail({ kind: 'course', course: course as Course }),
      onOpenExam: (exam: unknown) => {
        const item = exam as TimetableExam;
        const known = (snapshot.exams || []).find(
          (candidate: Exam) => candidate.courseName === item.courseName,
        );
        openDetail({
          kind: 'exam',
          exam: known || {
            courseName: item.courseName,
            classroom: item.classroom,
            seat: item.seat,
            colorHex: item.colorHex,
            whenText: `第 ${item.startPeriod}-${item.endPeriod} 节`,
          },
        });
      },
    });
  }, [
    timetable,
    snapshot.courses,
    snapshot.tasks,
    snapshot.exams,
    today,
    week,
    tab,
    setSelectedWeek,
    openDetail,
  ]);

  useEffect(() => {
    if (tab !== 'week') return;
    const onDocClick = (event: MouseEvent) => {
      const host = hostRef.current;
      if (!host) return;
      const target = event.target as HTMLElement | null;
      if (!target) return;
      if (target.closest('.week-title')) return;
      const picker = host.querySelector<HTMLElement>('.week-picker');
      if (picker && !picker.hidden && !picker.contains(target)) picker.hidden = true;
    };
    document.addEventListener('click', onDocClick);
    return () => document.removeEventListener('click', onDocClick);
  }, [tab]);

  const courses = snapshot.courses || [];
  const exams = [...(snapshot.exams || [])].sort((a, b) =>
    examDay(a) < examDay(b) ? -1 : 1,
  );
  return (
    <div className="timetable-view">
      <div className="week-tabs-row">
        <div className="segmented segmented--glass" role="tablist" aria-label="课表视图">
          {(
            [
              ['week', '周视图', 0],
              ['courses', '课程', courses.length],
              ['exams', '考试', exams.length],
            ] as const
          ).map(([id, label, count]) => (
            <button
              key={id}
              type="button"
              role="tab"
              aria-selected={tab === id}
              className={cx('segment', tab === id && 'is-active')}
              onClick={() => setWeekTab(id as WeekTab)}
            >
              {label}
              {count ? <span className="segment-count">{count}</span> : null}
            </button>
          ))}
        </div>
        <div className="week-legend">
          <span>
            <i className="legend-swatch is-deep" />有未完成待办
          </span>
          <span>
            <i className="legend-swatch is-light" />已结清
          </span>
        </div>
      </div>

      {tab === 'week' ? (
        <section className="week-panel glass">
          {timetable ? (
            <div className="week-scroll">
              <div className="week-host week-grid" ref={hostRef} />
            </div>
          ) : (
            <EmptyState
              icon={CalendarDays}
              title="还没有周课表"
              text="周课表由手机计算（停课、补课、开课周都会考虑），同步一次就有了。"
              action={
                <Button variant="primary" icon={ExternalLink} onClick={() => openSync('connection')}>
                  连接手机
                </Button>
              }
            />
          )}
          {timetable ? (
            <p className="week-footnote">
              <Info size={13} /> 深浅两档表示这门课有没有未完成待办；点课程格可看详情。
            </p>
          ) : null}
        </section>
      ) : null}

      {tab === 'courses' ? (
        <section className="section">
          {courses.length ? (
            <div className="courses-grid">
              {courses.map((course) => (
                <CourseCard
                  key={course.key}
                  course={course}
                  onClick={(item) => openDetail({ kind: 'course', course: item })}
                />
              ))}
            </div>
          ) : (
            <EmptyState
              icon={CalendarDays}
              title="还没有课程"
              text="在手机上登录教务或手动添加课程，同步后就会出现在这里。"
            />
          )}
        </section>
      ) : null}

      {tab === 'exams' ? (
        <section className="section">
          {exams.length ? (
            <div className="exams-list">
              {exams.map((exam, index) => (
                <ExamCard
                  key={exam.id || `${exam.courseName}-${index}`}
                  exam={exam}
                  today={today}
                  onClick={(item) => openDetail({ kind: 'exam', exam: item })}
                />
              ))}
            </div>
          ) : (
            <EmptyState
              icon={CalendarDays}
              title="还没有考试安排"
              text={exams.length ? '' : '手机上的考试信息同步过来后，会按日期排在这里。'}
            />
          )}
        </section>
      ) : null}
    </div>
  );
}
