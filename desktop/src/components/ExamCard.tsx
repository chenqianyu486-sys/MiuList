import { MapPin, Medal } from 'lucide-react';
import {
  examCountdown,
  examDay,
  formatExamWhen,
  hexToRgba,
  shortDate,
} from '../core/format';
import type { Exam } from '../core/types';
import { cx } from './ui';

export function ExamCard({
  exam,
  today,
  onClick,
  compact = false,
}: {
  exam: Exam;
  today: string;
  onClick?: (exam: Exam) => void;
  compact?: boolean;
}) {
  const day = examDay(exam);
  const color = exam.colorHex || '#C9D0D8';
  const countdown = examCountdown(exam, today);
  const [month, date] = day ? shortDate(day).split('-') : ['', ''];

  return (
    <button
      type="button"
      className={cx('exam-card', compact && 'is-compact', onClick && 'is-clickable')}
      style={{ '--course': color, '--tint': hexToRgba(color, 0.14) } as never}
      onClick={() => onClick?.(exam)}
    >
      <span className="exam-date">
        <span>{month ? `${Number(month)}月` : '考试'}</span>
        <strong>{date || '—'}</strong>
      </span>
      <span className="exam-body">
        <span className="exam-title">{exam.courseName || '未命名考试'}</span>
        <span className="exam-meta">{formatExamWhen(exam) || '时间待定'}</span>
        <span className="exam-meta">
          {exam.classroom ? (
            <>
              <MapPin size={12} />
              {exam.classroom}
            </>
          ) : null}
          {exam.seat ? (
            <>
              <Medal size={12} />
              座位 {exam.seat}
            </>
          ) : null}
        </span>
      </span>
      {countdown ? (
        <span className={cx('exam-count', countdown === '今天' && 'is-today')}>{countdown}</span>
      ) : null}
    </button>
  );
}
