import { MapPin, UserRound } from 'lucide-react';
import { COURSE_TINT_ALPHA, accentBarColor, courseFace } from '../core/tint';
import type { Course } from '../core/types';
import { Chip, cx } from './ui';

export function CourseCard({
  course,
  onClick,
}: {
  course: Course;
  onClick?: (course: Course) => void;
}) {
  const color = course.colorHex || '#C9D0D8';
  return (
    <button
      type="button"
      className={cx('course-tile', onClick && 'is-clickable')}
      style={
        {
          '--course-bar': accentBarColor(color),
          '--course-face': courseFace(color, COURSE_TINT_ALPHA),
        } as never
      }
      onClick={() => onClick?.(course)}
    >
      <span className="course-tile-bar" />
      <div className="course-tile-body">
        <div className="course-tile-head">
          <h3>{course.name || '(未命名课程)'}</h3>
          {course.category ? <Chip>{course.category}</Chip> : null}
        </div>
        <div className="course-tile-meta">
          {course.teacher ? (
            <span>
              <UserRound size={13} />
              {course.teacher}
            </span>
          ) : null}
          {course.classroom ? (
            <span>
              <MapPin size={13} />
              {course.classroom}
            </span>
          ) : null}
        </div>
        {course.weeklyText ? <p className="course-tile-time">{course.weeklyText}</p> : null}
        {course.code ? <p className="course-tile-code">{course.code}</p> : null}
      </div>
    </button>
  );
}
