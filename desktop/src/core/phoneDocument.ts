import type {
  CalendarData,
  Course,
  CourseSlot,
  Exam,
  Snapshot,
  Task,
  TaskTypeOption,
  Timetable,
} from './types';

/**
 * 读**手机 App 导出的整机快照**（`schemaVersion = 3` / `4`）。
 *
 * ## 为什么需要这一层
 *
 * 手机端与网页端各自有一套 JSON：
 * - 手机导出的那份是「**整机快照**」：顶层分块 `calendar` / `timetable` /
 *   `tasks` / `taskTypes` / `taskRules` / `exams` / `weekPlan`；
 * - 网页端自己的那份是「本机快照 + 同步簿记」。
 *
 * 两边**认不出对方的格式**。以前把手机导出的文件丢进网页端，
 * `unpack()` 看到顶层有 `tasks` 就把整份文档当成了本机快照，
 * 于是**待办进来了、课表与考试全丢了、周视图空着**，而界面上还提示「已导入快照」。
 *
 * 这一层把手机那份翻译成网页端认识的形状。
 *
 * ## 翻译时补三样东西
 *
 * 手机端的待办**不带**课程的名称 / 类别 / 颜色，也不带分类的显示名 ——
 * 那三样在手机端是**读时从课程与分类现取**的（改了配色，待办要跟着改）。
 * 网页端没有那个「现取」的过程，所以这里按自然键回查一次补上。
 */

type Obj = Record<string, unknown>;

function isObj(value: unknown): value is Obj {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function str(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function num(value: unknown, fallback = 0): number {
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}

function arr(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

/**
 * 这份 JSON 是不是手机 App 导出的整机快照？
 *
 * 判据是「有 `schemaVersion` + 至少一块手机独有的顶层分块」，
 * 并且**没有**顶层 `snapshot`（那是网页端自己的载荷）。
 */
export function isPhoneDocument(raw: unknown): boolean {
  if (!isObj(raw)) return false;
  if (isObj(raw.snapshot)) return false;
  if (typeof raw.schemaVersion !== 'number') return false;
  return (
    isObj(raw.calendar) ||
    isObj(raw.timetable) ||
    Array.isArray(raw.tasks) ||
    Array.isArray(raw.taskTypes) ||
    Array.isArray(raw.exams) ||
    Array.isArray(raw.taskRules)
  );
}

/** 翻译的结果。每一块都可能为空 —— 手机导出什么就有什么。 */
export type PhoneDocumentParts = {
  courses: Course[];
  exams: Exam[];
  tasks: Task[];
  taskTypes: TaskTypeOption[];
  calendar: CalendarData | null;
  /** 渲染好的周课表。手机算好发过来，网页端只画。 */
  timetable: Timetable | null;
};

/**
 * 把手机导出的文档翻译成网页端的形状。
 *
 * **不是手机文档就返回 null**（调用方据此走「不是有效的 MiuList 快照」那条路）。
 */
export function decodePhoneDocument(raw: unknown): PhoneDocumentParts | null {
  if (!isPhoneDocument(raw)) return null;
  const doc = raw as Obj;

  const courses = decodeCourses(doc);
  const taskTypes = decodeTaskTypes(doc);
  const exams = decodeExams(doc, courses);
  const tasks = decodeTasks(doc, courses, taskTypes);

  return {
    courses,
    exams,
    tasks,
    taskTypes,
    calendar: decodeCalendar(doc),
    timetable: decodeWeekPlan(doc),
  };
}

// ---------------------------------------------------------------- 课程

function decodeCourses(doc: Obj): Course[] {
  const timetable = isObj(doc.timetable) ? doc.timetable : {};
  return arr(timetable.courses)
    .filter(isObj)
    .map((c) => ({
      key: str(c.key),
      name: str(c.name),
      code: str(c.code),
      teacher: str(c.teacher),
      category: str(c.category),
      colorHex: str(c.colorHex),
      classroom: str(c.classroom),
      note: str(c.note),
      weeklyText: weeklyText(c.slots),
      slots: decodeSlots(c.slots),
    }));
}

function decodeSlots(raw: unknown): CourseSlot[] {
  return arr(raw)
    .filter(isObj)
    .map((s) => ({
      dayOfWeek: num(s.dayOfWeek),
      startPeriod: num(s.startPeriod),
      endPeriod: num(s.endPeriod),
      classroom: str(s.classroom),
      weeks: decodeWeeks(s.weeks),
    }));
}

/**
 * 开课周次。
 *
 * 手机**自己导出**的永远是整数数组；但教务网爬虫产出的文档里是
 * `"1-16周(单)"` 这种紧凑写法，两种都得收。
 *
 * 解析不出来时返回 `undefined`（= 每周）—— 与手机端 `WeekSet.parse` 的兜底一致：
 * 宁可多显示几周，也不要因为一个字段解析不了就让整门课从课表上消失。
 */
export function decodeWeeks(raw: unknown): number[] | undefined {
  if (Array.isArray(raw)) {
    const nums = raw.map((v) => Number(v)).filter((n) => Number.isFinite(n) && n >= 1);
    return nums.length ? nums : undefined;
  }
  if (typeof raw !== 'string') return undefined;
  const text = raw.trim();
  if (!text) return undefined;

  // 先剥奇偶标记，剩下按「数字 / 区间」解析。
  const parity = /\(单\)|（单）|单周|\(双\)|（双）|双周|\bodd\b|\beven\b/i.exec(text);
  const odd = parity ? /单|odd/i.test(parity[0]) : false;
  const even = parity ? /双|even/i.test(parity[0]) : false;
  const body = text.replace(parity ? parity[0] : '', '');

  const weeks: number[] = [];
  for (const piece of body.split(/[,，、]/)) {
    const range = /(\d+)\s*[-~—]\s*(\d+)/.exec(piece);
    if (range) {
      const from = Number(range[1]);
      const to = Number(range[2]);
      for (let w = Math.min(from, to); w <= Math.max(from, to); w += 1) weeks.push(w);
      continue;
    }
    const one = /(\d+)/.exec(piece);
    if (one) weeks.push(Number(one[1]));
  }
  if (!weeks.length) return undefined;
  if (odd) return weeks.filter((w) => w % 2 === 1);
  if (even) return weeks.filter((w) => w % 2 === 0);
  return weeks;
}

/** 「周一 3-4 节 · 周三 3-4 节」——课程卡上那行概览。 */
function weeklyText(raw: unknown): string {
  const parts = arr(raw)
    .filter(isObj)
    .map((s) => {
      const day = num(s.dayOfWeek);
      if (!day) return '';
      return `${dayName(day)} ${num(s.startPeriod)}-${num(s.endPeriod)} 节`;
    })
    .filter(Boolean);
  return parts.join(' · ');
}

function dayName(dayOfWeek: number): string {
  return ['周一', '周二', '周三', '周四', '周五', '周六', '周日'][dayOfWeek - 1] ?? '';
}

// ---------------------------------------------------------------- 分类

function decodeTaskTypes(doc: Obj): TaskTypeOption[] {
  return arr(doc.taskTypes)
    .filter(isObj)
    .map((t) => {
      const id = str(t.id);
      return {
        // `name` 是落库用的 key，手机那份没有它 —— 这里补出来。
        name: id ? `CUSTOM:${id}` : '',
        id,
        label: str(t.label),
        colorHex: str(t.colorHex),
        inkHex: str(t.inkHex),
        sortOrder: num(t.sortOrder),
        isCustom: true,
      };
    })
    .filter((t) => t.name && t.label);
}

// ---------------------------------------------------------------- 考试

function decodeExams(doc: Obj, courses: Course[]): Exam[] {
  return arr(doc.exams)
    .filter(isObj)
    .map((e) => {
      const course = findCourse(courses, str(e.courseCode), str(e.courseName));
      const startAt = num(e.startAt);
      const endAt = num(e.endAt);
      const dateLabel = str(e.dateLabel);
      const startMinute = num(e.startMinute);
      const endMinute = num(e.endMinute);
      return {
        // 手机的行号在本机没有意义，改用选课课号当身份。
        id: str(e.xkkh) || `${str(e.courseName)}-${startAt}-${startMinute}`,
        courseCode: str(e.courseCode),
        courseName: str(e.courseName),
        kind: str(e.kind),
        whenText: whenTextOf(startAt, endAt, dateLabel),
        dateLabel,
        classroom: str(e.classroom),
        seat: str(e.seat),
        credit: num(e.credit),
        termLabel: str(e.termLabel),
        colorHex: course?.colorHex,
        startAt,
        endAt,
        startMinute,
        endMinute,
        updatedAt: num(e.updatedAt),
      };
    });
}

/** 「10-24 13:30-15:00」/「考试周第 3 天」/「时间待定」。 */
function whenTextOf(startAt: number, endAt: number, dateLabel: string): string {
  if (startAt > 0) {
    const s = new Date(startAt);
    const e = new Date(endAt > 0 ? endAt : startAt);
    return `${pad(s.getMonth() + 1)}-${pad(s.getDate())} ${pad(s.getHours())}:${pad(s.getMinutes())}-${pad(e.getHours())}:${pad(e.getMinutes())}`;
  }
  if (dateLabel) return dateLabel;
  return '时间待定';
}

function pad(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}

/**
 * 按课程代码认课，认不出再按课程名。
 *
 * 口径与手机端 `ExamRepository.link` 一致：代码优先、名字兜底。
 * 认不出就返回 undefined —— 那场考试照样显示，只是没有颜色可继承。
 */
function findCourse(courses: Course[], courseCode: string, courseName: string): Course | undefined {
  const code = courseCode.trim();
  if (code) {
    const hit = courses.find((c) => (c.code || '').trim() === code);
    if (hit) return hit;
  }
  const name = courseName.trim();
  if (!name) return undefined;
  return courses.find((c) => (c.name || '').trim() === name);
}

// ---------------------------------------------------------------- 待办

function decodeTasks(doc: Obj, courses: Course[], taskTypes: TaskTypeOption[]): Task[] {
  return arr(doc.tasks)
    .filter(isObj)
    .map((t) => {
      const courseKey = str(t.courseKey);
      const course = courses.find((c) => c.key === courseKey);
      const taskType = str(t.taskType);
      const type = taskTypes.find((v) => v.name === taskType);
      return {
        syncUid: str(t.syncUid),
        title: str(t.title),
        description: str(t.description),
        taskType,
        // 手机那份不带分类的显示名 —— 按 key 回查一次。
        taskTypeLabel: type?.label ?? '',
        priority: str(t.priority),
        isCompleted: t.isCompleted === true,
        completedAt: t.completedAt == null ? null : num(t.completedAt),
        startTimeTimestamp: num(t.startTimeTimestamp),
        dueDateTimestamp: num(t.dueDateTimestamp),
        dueDateString: str(t.dueDateString),
        startTimeString: str(t.startTimeString),
        isAutoGenerated: t.isAutoGenerated === true,
        generatedDate: str(t.generatedDate),
        week: num(t.week),
        dayOfWeek: num(t.dayOfWeek),
        courseKey,
        // 课程的三个字段同样要回查：手机端是「读时现取」，这里补上同一份。
        courseName: course?.name ?? '',
        courseCategory: course?.category ?? '',
        courseColorHex: course?.colorHex ?? '',
        createdAt: num(t.createdAt),
        updatedAt: num(t.updatedAt),
      };
    })
    .filter((t) => t.syncUid);
}

// ---------------------------------------------------------------- 校历

function decodeCalendar(doc: Obj): CalendarData | null {
  if (!isObj(doc.calendar)) return null;
  const c = doc.calendar;
  return {
    termConfigs: arr(c.termConfigs).filter(isObj).map((t) => ({
      Year: typeof t.Year === 'string' ? t.Year : undefined,
      Term: typeof t.Term === 'number' ? t.Term : undefined,
      Begin: typeof t.Begin === 'number' ? t.Begin : undefined,
      End: typeof t.End === 'number' ? t.End : undefined,
      FirstWeekNo: typeof t.FirstWeekNo === 'number' ? t.FirstWeekNo : undefined,
    })),
    classTerms: arr(c.classTerms).filter((v) => typeof v === 'string') as string[],
    tweaks: arr(c.tweaks).filter(isObj).map((t) => ({
      TweakType: typeof t.TweakType === 'string' ? t.TweakType : undefined,
      Description: typeof t.Description === 'string' ? t.Description : undefined,
      From: typeof t.From === 'number' ? t.From : undefined,
      To: typeof t.To === 'number' ? t.To : undefined,
    })),
    periods: arr(c.periods).filter(isObj).map((p) => ({
      period: typeof p.period === 'number' ? p.period : undefined,
      startTime: typeof p.startTime === 'string' ? p.startTime : undefined,
      endTime: typeof p.endTime === 'string' ? p.endTime : undefined,
      section: typeof p.section === 'string' ? p.section : undefined,
    })),
  };
}

// ---------------------------------------------------------------- 周课表

/**
 * 渲染好的周课表。
 *
 * **形状与网页端的 `Timetable` 完全一致**（手机那边算好发过来，网页端只画），
 * 所以这里只是逐字段过一遍、顺手补上默认值。
 */
function decodeWeekPlan(doc: Obj): Timetable | null {
  if (!isObj(doc.weekPlan)) return null;
  const w = doc.weekPlan;
  if (!Array.isArray(w.weeks) || !w.weeks.length) return null;
  return {
    totalWeeks: num(w.totalWeeks, 1),
    currentWeek: num(w.currentWeek, 1),
    periodsPerDay: num(w.periodsPerDay),
    periods: arr(w.periods).filter(isObj).map((p) => ({
      period: num(p.period),
      startMinute: num(p.startMinute),
      endMinute: num(p.endMinute),
    })),
    slotList: arr(w.slotList).filter(isObj).map((s) => ({
      courseKey: str(s.courseKey),
      startPeriod: num(s.startPeriod),
      endPeriod: num(s.endPeriod),
      classroom: str(s.classroom),
    })),
    weeks: arr(w.weeks).filter(isObj).map((week) => ({
      week: num(week.week),
      days: arr(week.days).filter(isObj).map((d) => ({
        date: str(d.date),
        dayOfWeek: num(d.dayOfWeek),
        offReason: typeof d.offReason === 'string' ? d.offReason : null,
        makeupDayOfWeek: typeof d.makeupDayOfWeek === 'number' ? d.makeupDayOfWeek : null,
        slotIndexes: arr(d.slotIndexes).map((v) => num(v)),
        exams: arr(d.exams).filter(isObj).map((e) => ({
          courseName: str(e.courseName),
          colorHex: str(e.colorHex),
          startPeriod: num(e.startPeriod),
          endPeriod: num(e.endPeriod),
          startMinute: num(e.startMinute),
          classroom: str(e.classroom),
          seat: str(e.seat),
        })),
      })),
    })),
  };
}

// ---------------------------------------------------------------- 反向：造一份手机能吃的文档

/**
 * 把网页端这一份数据，造成**手机 App 也认得**的整机快照。
 *
 * ## 为什么要有这一份
 *
 * 两端各导各的，手机导出的文件网页端读得懂（`decodePhoneDocument`），
 * 但网页端导出的文件手机读不懂 —— 于是「备份」这件事只在一个方向上成立：
 * 手机丢了可以从网页端救，网页端丢了却救不回来。
 *
 * 这份文档**只出不进**：网页端自己不吃它（网页端吃的是自己的那份），
 * 它是给手机用的。
 *
 * ## 造得出来吗
 *
 * 造得出来，是因为网页端这一份**本来就带着全部原始数据**：
 * 课程带着时段与周次，校历带着学期 / 调休 / 作息，考试带着全部字段。
 * 唯独两样没有：**课程级生成规则**与**类别规则** ——
 * 它们是手机上的用户数据，网页端不碰，也不该碰。
 * 那两块在文档里留空即可：手机侧「包里没有就别动」。
 */
export function buildPhoneDocument(snapshot: Snapshot): Obj {
  const calendar = snapshot.calendar ?? null;
  const termConfigs = calendar?.termConfigs ?? [];
  const first = termConfigs[0];
  return {
    schemaVersion: 4,
    provenance: {
      source: 'desktop-snapshot',
      generator: 'MiuList 网页端',
      generatedAt: new Date().toISOString(),
      note: '由电脑网页端导出；手机可直接导入',
    },
    calendar: calendar
      ? {
          termConfigs,
          classTerms: calendar.classTerms ?? [],
          tweaks: calendar.tweaks ?? [],
          periods: calendar.periods ?? [],
        }
      : null,
    timetable: {
      termRef: first ? { Year: first.Year, Term: first.Term } : null,
      courses: (snapshot.courses ?? []).map((course) => ({
        key: course.key,
        name: course.name,
        code: course.code ?? '',
        teacher: course.teacher ?? '',
        category: course.category ?? '',
        classroom: course.classroom ?? '',
        colorHex: course.colorHex ?? '',
        note: course.note ?? '',
        slots: (course.slots ?? []).map((slot) => ({
          dayOfWeek: slot.dayOfWeek,
          startPeriod: slot.startPeriod,
          endPeriod: slot.endPeriod,
          classroom: slot.classroom ?? '',
          weeks: slot.weeks ?? [],
        })),
      })),
    },
    tasks: (snapshot.tasks ?? []).map((task) => ({
      syncUid: task.syncUid,
      courseKey: task.courseKey || null,
      title: task.title,
      description: task.description,
      taskType: task.taskType,
      startTimeTimestamp: task.startTimeTimestamp,
      dueDateTimestamp: task.dueDateTimestamp,
      startTimeString: task.startTimeString,
      dueDateString: task.dueDateString,
      priority: task.priority,
      isCompleted: task.isCompleted,
      completedAt: task.completedAt,
      isAutoGenerated: task.isAutoGenerated,
      generatedDate: task.generatedDate ?? '',
      week: task.week,
      dayOfWeek: task.dayOfWeek,
      createdAt: task.createdAt,
      updatedAt: task.updatedAt,
    })),
    taskTypes: (snapshot.taskTypes ?? []).map((type) => ({
      id: type.id ?? '',
      label: type.label,
      colorHex: type.colorHex,
      inkHex: type.inkHex,
      sortOrder: type.sortOrder ?? 0,
    })),
    exams: (snapshot.exams ?? []).map((exam) => ({
      xkkh: exam.id ?? '',
      courseCode: exam.courseCode ?? '',
      courseName: exam.courseName,
      kind: exam.kind ?? '',
      startAt: exam.startAt ?? 0,
      endAt: exam.endAt ?? 0,
      startMinute: exam.startMinute ?? 0,
      endMinute: exam.endMinute ?? 0,
      dateLabel: exam.dateLabel ?? '',
      classroom: exam.classroom ?? '',
      seat: exam.seat ?? '',
      credit: exam.credit ?? 0,
      termLabel: exam.termLabel ?? '',
      updatedAt: exam.updatedAt ?? 0,
    })),
    weekPlan: snapshot.timetable ?? null,
  };
}
