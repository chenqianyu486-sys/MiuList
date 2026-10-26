import type { Snapshot, TimetableDay, TodayCourse } from './types';

/**
 * 「今天要上的课」——**本机自己算**。
 *
 * ## 为什么不在这里重写一份排课规则
 *
 * 周课表里的某一列是「这一周里的一个**真实日期**」，不是「星期几」：
 * 2026-10-17 是周六，它要出周五的课；同一周的 10-16（周五）反而一节都没有。
 * 这套规则（学期范围、停课日、补课日、开课周次）在 App 里是唯一一份实现。
 *
 * 但手机算好的那份 `todayCourses` 有一个致命前提：**它是「今天」算的**。
 * 网页端的配置是独立的 —— 可能今天才从手机上搬过来，也可能是三天前搬的，
 * 之后一直没再同步过配置。这时直接用手机那份 `todayCourses`，
 * 屏幕上会显示着另一天的课，而且**看起来完全正常**，没人会发现。
 *
 * 所以这里改成：从快照里那份**周课表**（`timetable`）自己取今天那一列。
 * 周课表本身已经把停课、补课、开课周次都算成了「哪一天有哪几节课」，
 * 网页端只要按日期找到那一列、按节次取时段就行 —— 不重写任何排课规则，
 * 只是把「今天」这一列取出来用。
 */

/** 自 00:00 起的分钟数 → 「08:00」。 */
function formatMinute(minute: number): string {
  const h = Math.floor(minute / 60);
  const m = minute % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

/**
 * 此刻是**快照所在时区**的第几分钟。
 *
 * 用 `utcOffsetMinutes`（手机 App 的时区）而不是浏览器本地时区：
 * 两台设备的时区设置可能不同，手机上写「今天 21:30 截止」的事，
 * 在网页端也要显示成同一个时刻。
 */
export function nowMinutes(offsetMinutes = 480): number {
  const now = new Date();
  const utc = now.getUTCHours() * 60 + now.getUTCMinutes();
  return (utc + (offsetMinutes ?? 480) + 1440 * 2) % 1440;
}

/**
 * 从周课表里取出「今天」那一列，算出今天要上的课。
 *
 * 返回 null = **算不出来**（没有周课表 / 今天不在学期范围内），
 * 调用方据此回退到手机发来的 `todayCourses`。
 */
export function deriveTodayCourses(snapshot: Snapshot, today: string): TodayCourse[] | null {
  const timetable = snapshot.timetable;
  if (!timetable || !Array.isArray(timetable.weeks) || !timetable.weeks.length) return null;
  if (!today) return null;

  let day: TimetableDay | null = null;
  for (const week of timetable.weeks) {
    const hit = (week.days || []).find((item) => item.date === today);
    if (hit) {
      day = hit;
      break;
    }
  }
  if (!day) return null;

  const courseByKey = new Map((snapshot.courses || []).map((course) => [course.key, course]));
  const periodByIndex = new Map((timetable.periods || []).map((period) => [period.period, period]));
  const slotList = timetable.slotList || [];
  const now = nowMinutes(snapshot.utcOffsetMinutes);

  const out: TodayCourse[] = [];
  for (const index of day.slotIndexes || []) {
    const slot = slotList[index];
    if (!slot) continue;
    const course = courseByKey.get(slot.courseKey);
    if (!course) continue;

    const start = periodByIndex.get(slot.startPeriod);
    const end = periodByIndex.get(slot.endPeriod);
    const startMinute = start?.startMinute ?? 0;
    const endMinute = end?.endMinute ?? startMinute;

    let state: 'past' | 'now' | 'future' = 'future';
    if (endMinute > 0 && now > endMinute) state = 'past';
    else if (startMinute > 0 && now >= startMinute && now <= endMinute) state = 'now';

    out.push({
      name: course.name,
      colorHex: course.colorHex,
      classroom: slot.classroom || course.classroom,
      teacher: course.teacher,
      timeText: `${formatMinute(startMinute)} - ${formatMinute(endMinute)}`,
      state,
      courseKey: course.key,
      startPeriod: slot.startPeriod,
      endPeriod: slot.endPeriod,
    });
  }

  out.sort((a, b) => (a.startPeriod || 0) - (b.startPeriod || 0));
  return out;
}

/**
 * 「今天要上的课」——本机算得出来就用本机的，算不出来才用手机发来的那份。
 *
 * 快照里的 `todayCourses` 保留着：老版本手机可能不带周课表，
 * 那时它是唯一的数据来源。
 */
export function todayCoursesOf(snapshot: Snapshot, today: string): TodayCourse[] {
  const derived = deriveTodayCourses(snapshot, today);
  if (derived) return derived;
  return snapshot.todayCourses || [];
}
