import { formatExamWhen } from '../core/format';
import { useAppStore } from '../core/store';
import { accentBarColor } from '../core/tint';
import { Sheet } from './Sheet';

export function DetailSheet() {
  const detail = useAppStore((s) => s.detail);
  const close = useAppStore((s) => s.closeDetail);
  if (!detail) return null;

  const course = detail.kind === 'course' ? detail.course : undefined;
  const exam = detail.kind === 'exam' ? detail.exam : undefined;
  const title = course?.name || exam?.courseName || '详情';
  const color = course?.colorHex || exam?.colorHex;
  const rows: [string, string][] = [];

  if (course) {
    if (course.code) rows.push(['课程代码', course.code]);
    if (course.teacher) rows.push(['教师', course.teacher]);
    if (course.category) rows.push(['分类', course.category]);
    if (course.classroom) rows.push(['教室', course.classroom]);
    if (course.weeklyText) rows.push(['上课时间', course.weeklyText]);
    if (course.note) rows.push(['备注', course.note]);
  }
  if (exam) {
    const when = formatExamWhen(exam);
    if (when) rows.push(['时间', when]);
    rows.push(['考场', exam.classroom || '待学校安排']);
    if (exam.seat) rows.push(['座位', exam.seat]);
    if (exam.termLabel) rows.push(['学期', exam.termLabel]);
    if (exam.kind) rows.push(['类型', exam.kind === 'FINAL' ? '期末考试' : exam.kind === 'MIDTERM' ? '期中考试' : exam.kind]);
  }

  return (
    <Sheet
      open
      onClose={close}
      title={title}
      subtitle={course ? '在手机上修改课程信息；这里只读。' : '考试信息来自手机快照。'}
      headerExtra={
        color ? (
          <span className="detail-color" style={{ background: accentBarColor(color) }} aria-hidden="true" />
        ) : null
      }
    >
      <div className="detail-list">
        {rows.length ? (
          rows.map(([label, value]) => (
            <div className="detail-row" key={label}>
              <span>{label}</span>
              <strong>{value}</strong>
            </div>
          ))
        ) : (
          <p className="field-hint">没有更多信息。</p>
        )}
      </div>
    </Sheet>
  );
}
