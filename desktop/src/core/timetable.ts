// 周课表的布局与两档颜色推导照抄 App（web/timetable.js），新前端不重写这套算法。
// @ts-ignore - 共享的纯 JS 渲染器
import { renderWeekGrid as render } from '../../../web/timetable.js';

export type WeekGridOptions = {
  timetable: unknown;
  courses: unknown[];
  tasks: unknown[];
  today: string;
  week: number;
  onSelectWeek: (week: number) => void;
  onOpenCourse: (course: unknown) => void;
  onOpenExam: (exam: unknown) => void;
};

export function renderWeekGrid(host: HTMLElement, options: WeekGridOptions): void {
  render(host, options as never);
}
