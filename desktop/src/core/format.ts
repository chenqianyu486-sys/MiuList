import { NO_DUE, type Exam, type Snapshot, type Task } from './types.ts';
import { todayCoursesOf } from './todayCourses.ts';

const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六'];

export function parseDay(day: string): number | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day || '');
  if (!m) return null;
  return Date.UTC(+m[1], +m[2] - 1, +m[3]);
}

export function dayDiff(from: string, to: string): number | null {
  const a = parseDay(from);
  const b = parseDay(to);
  if (a === null || b === null) return null;
  return Math.round((b - a) / 86400000);
}

export function localToday(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export function weekdayOf(day: string): number {
  const ms = parseDay(day);
  if (ms === null) return 0;
  return new Date(ms).getUTCDay();
}

export function weekdayLabel(weekday: number): string {
  if (weekday === 7 || weekday === 0) return '星期日';
  return `星期${WEEKDAYS[weekday] || ''}`;
}

export function formatFullDate(day: string, weekday?: number): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day || '');
  if (!m) return day || '';
  return `${m[1]}年${+m[2]}月${+m[3]}日`;
}

export function formatMonthDay(day: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day || '');
  if (!m) return day || '';
  return `${+m[2]}月${+m[3]}日`;
}

export function shortDate(day: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day || '');
  if (!m) return day || '';
  return `${+m[2]}-${String(+m[3]).padStart(2, '0')}`;
}

export function dueLabel(task: Task, today: string): string {
  const s = task.dueDateString || '';
  if (!s) return '';
  const date = s.slice(0, 10);
  const time = s.length > 10 ? s.slice(11) : '';
  const diff = dayDiff(today, date);
  if (diff === null) return s;
  if (diff === 0) return time ? `今天 ${time}` : '今天';
  if (diff === 1) return time ? `明天 ${time}` : '明天';
  if (diff === -1) return time ? `昨天 ${time}` : '昨天';
  if (diff < 0) return `逾期 ${Math.abs(diff)} 天`;
  if (diff <= 7) return `${diff} 天后`;
  return time ? `${shortDate(date)} ${time}` : shortDate(date);
}

export function isOverdue(task: Task, today: string): boolean {
  if (task.isCompleted) return false;
  if (!task.dueDateString) return false;
  const diff = dayDiff(today, task.dueDateString.slice(0, 10));
  return diff !== null && diff < 0;
}

export function isDueToday(task: Task, today: string): boolean {
  if (task.isCompleted || !task.dueDateString) return false;
  return task.dueDateString.slice(0, 10) === today;
}

export function inTodayList(task: Task, today: string): boolean {
  if (task.isCompleted) return false;
  if (!task.dueDateString) return true;
  return task.dueDateString.slice(0, 10) <= today;
}

export function formatTime(millis: number | null | undefined, offsetMinutes: number): string {
  if (!millis) return '—';
  const d = new Date(millis + (offsetMinutes ?? 480) * 60000);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())} ${p(
    d.getUTCHours(),
  )}:${p(d.getUTCMinutes())}`;
}

export function formatSyncTime(millis: number | null | undefined, snapshot: Snapshot): string {
  return formatTime(millis, snapshot.utcOffsetMinutes ?? 480);
}

export function sortOpenTasks(tasks: Task[], today: string): Task[] {
  return [...tasks].sort((a, b) => {
    const aStar = a.priority === 'STAR' ? 1 : 0;
    const bStar = b.priority === 'STAR' ? 1 : 0;
    if (aStar !== bStar) return bStar - aStar;

    const aOver = isOverdue(a, today) ? 0 : 1;
    const bOver = isOverdue(b, today) ? 0 : 1;
    if (aOver !== bOver) return aOver - bOver;

    const aDue = a.dueDateString ? a.dueDateString : '9999-99-99';
    const bDue = b.dueDateString ? b.dueDateString : '9999-99-99';
    if (aDue !== bDue) return aDue < bDue ? -1 : 1;

    return (b.createdAt || 0) - (a.createdAt || 0);
  });
}

/**
 * 总览页"全部待办"：从所有任务中筛出未完成的，再按 sortOpenTasks 排序。
 *
 * 包括逾期、今天到期、未来到期、没有截止日期的未完成任务。
 * 已完成任务不会入选。
 *
 * **不影响 inTodayList** —— 那个仍然只用于"今天"统计和清单页。
 */
export function allOpenTasks(tasks: Task[], today: string): Task[] {
  return sortOpenTasks(
    tasks.filter((t) => !t.isCompleted),
    today,
  );
}

export function sortDoneTasks(tasks: Task[]): Task[] {
  return [...tasks].sort((a, b) => (b.completedAt || 0) - (a.completedAt || 0));
}

export function examTimestamp(exam: Exam): number {
  if (exam.startAt) return exam.startAt;
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(exam.whenText || '');
  if (!m) return 0;
  return Date.UTC(+m[1], +m[2] - 1, +m[3]);
}

export function examDay(exam: Exam): string {
  if (exam.startAt) {
    const d = new Date(exam.startAt + 8 * 3600000);
    const p = (n: number) => String(n).padStart(2, '0');
    return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())}`;
  }
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(exam.whenText || '');
  return m ? `${m[1]}-${m[2]}-${m[3]}` : '';
}

export function examCountdown(exam: Exam, today: string): string {
  const day = examDay(exam);
  const diff = day ? dayDiff(today, day) : null;
  if (diff === null) return '';
  if (diff === 0) return '今天';
  if (diff === 1) return '明天';
  if (diff > 1) return `${diff} 天后`;
  return `已过 ${Math.abs(diff)} 天`;
}

/**
 * 这场考试是不是今天。
 *
 * **不看快照里那个 `isToday` 标记**：那是**快照生成那一刻**算的。
 * 网页端的配置是独立的 —— 课表可能是昨天搬过来的，标记还停在昨天，
 * 于是一张「今天有考试」的卡片会挂在那里好几天，而且看起来完全正常。
 * 有绝对时刻就按日期算，算不出来（学校还没公布日期）才回退到那个标记。
 */
export function examIsToday(exam: Exam, today: string): boolean {
  const day = examDay(exam);
  if (day) return day === today;
  return exam.isToday === true;
}

export function formatExamWhen(exam: Exam): string {
  if (exam.whenText) return exam.whenText;
  if (exam.startAt) return formatTime(exam.startAt, 480);
  return exam.dateLabel || '';
}

export function toDueInput(task: Task | null): string {
  if (!task) return '';
  const s = task.dueDateString || '';
  const m = /^(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2})/.exec(s);
  if (!m) return '';
  return `${m[1]}T${m[2]}`;
}

export function dueFromInput(input: string, offsetMinutes: number): {
  dueDateString: string;
  dueDateTimestamp: number;
} {
  if (!input) return { dueDateString: '', dueDateTimestamp: NO_DUE };
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(input);
  if (!m) return { dueDateString: '', dueDateTimestamp: NO_DUE };
  const [, y, mo, d, h, mi] = m;
  const ts = Date.UTC(+y, +mo - 1, +d, +h, +mi) - (offsetMinutes ?? 480) * 60000;
  return {
    dueDateString: `${y}-${mo}-${d} ${h}:${mi}`,
    dueDateTimestamp: ts,
  };
}

export function greeting(): string {
  const h = new Date().getHours();
  if (h < 6) return '夜深了';
  if (h < 12) return '早上好';
  if (h < 18) return '下午好';
  return '晚上好';
}

/**
 * ⚠️ 这里原来有 `hexToRgba(课程色, alpha)` —— 把课程原色薄薄叠一层。
 * 它被 `core/tint.ts` 的 `courseFace()` 取代了：直接叠原色会让八门课的卡面
 * 深浅不齐（黄很重、青很淡），扫列表时重的课比轻的课更抢眼。
 * 反解的做法见 tint.ts。
 */

export function todayStats(snapshot: Snapshot): {
  courses: number;
  tasks: number;
  exams: number;
  done: number;
} {
  const today = snapshot.today || localToday();
  const tasks = snapshot.tasks || [];
  const openToday = tasks.filter((t) => inTodayList(t, today));
  const done = tasks.filter(
    (t) => t.isCompleted && t.completedAt && formatDayOf(t.completedAt, snapshot.utcOffsetMinutes) === today,
  ).length;
  return {
    courses: todayCoursesOf(snapshot, today).length,
    tasks: openToday.length,
    exams: (snapshot.exams || []).filter((e) => examIsToday(e, today)).length,
    done,
  };
}

function formatDayOf(millis: number, offsetMinutes: number): string {
  const d = new Date(millis + (offsetMinutes ?? 480) * 60000);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())}`;
}
