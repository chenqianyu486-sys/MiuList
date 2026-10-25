/* MiuList 网页端 —— 周课表。
 *
 * ## 这一页的分工
 *
 * 网页端**不自己算**周课表：哪些课落在哪一天牵扯学期范围、停课、补课、
 * 开课周次，而手机那边已经有一份权威实现（`ScheduleResolver`）。
 * 所以手机把整张周表算好放进快照（`snapshot.timetable`），网页端只负责画。
 *
 * 画出来的东西要和 App 的课表页**长得像**：同一套令牌、同一套色彩推导、
 * 同一套「真实日期列」的语义。所以下面每一处颜色算法都是照抄 App 的
 * `ui/theme/Color.kt`，不是另挑的一套。
 *
 * ## 颜色两档（抄 App）
 *
 * - **深档**（这门课有未完成待办）：`deepEnoughForWhite` 把课程色压到
 *   「白字站得住」的深度（对比度目标 3.8，暖色自动降档），白字。
 * - **浅档**（没有未完成待办）：`pushAwayFrom` 把上面那支深色朝白推，
 *   推到固定明度（相对亮度 ≈ 0.616），字退到「刚好看得见」的下限。
 *
 * 两档色相严格一致，只有深浅之分 —— 深浅本身就是「有没有待办」的信号。
 */

/* ── 颜色推导（抄 App 的 ui/theme/Color.kt） ────────────────────── */

/** 深档（有待办）的字色。App 的 `CourseCellOnPending`。 */
export const COURSE_CELL_ON_PENDING = '#FCFCF8';
/** 浅档（无待办）的字色。App 的 `CourseCellOnIdle`。 */
export const COURSE_CELL_ON_IDLE = '#8E9390';
/**
 * 浅档底色的钉点。App 的 `CourseKhakiMetaOnIdle`。
 *
 * 它在 App 里已经是「背景调亮旋钮」而不是「要画出来的字色」，
 * 这里照抄同一个身份：浅档底色被钉死在它算出来的明度上。
 */
const COURSE_KHAKI_META_ON_IDLE = '#565A4C';

/** 色相修正区间（橙黄脏色带）。App 的 `DEEPEN_HUE_CORRECT_LO/HI`。 */
const DEEPEN_HUE_CORRECT_LO = 15;
const DEEPEN_HUE_CORRECT_HI = 55;

const clamp01 = (v) => (v < 0 ? 0 : v > 1 ? 1 : v);

/** `#rrggbb` → {r,g,b}（0..1）。认不出来时返回 null。 */
function parseHex(hex) {
  let h = String(hex || '').replace('#', '').trim();
  if (h.length === 3) h = h.split('').map((c) => c + c).join('');
  if (h.length !== 6 || /[^0-9a-fA-F]/.test(h)) return null;
  return {
    r: parseInt(h.slice(0, 2), 16) / 255,
    g: parseInt(h.slice(2, 4), 16) / 255,
    b: parseInt(h.slice(4, 6), 16) / 255,
  };
}

function rgbToHex(c) {
  const to = (v) => Math.round(clamp01(v) * 255).toString(16).padStart(2, '0');
  return `#${to(c.r)}${to(c.g)}${to(c.b)}`;
}

/** 标准 HSL 色相（0..1）。App 的 `hue`。 */
function hueOf(r, g, b, maxC, minC) {
  const d = maxC - minC;
  if (d === 0) return 0;
  let raw;
  if (maxC === r) raw = (g - b) / d + (g < b ? 6 : 0);
  else if (maxC === g) raw = (b - r) / d + 2;
  else raw = (r - g) / d + 4;
  return (((raw / 6) % 1) + 1) % 1;
}

/** 标准 HSL → RGB。App 的 `fromHsl`。 */
function fromHsl(h, s, l) {
  const c = (1 - Math.abs(2 * l - 1)) * s;
  const hp = h * 6;
  const x = c * (1 - Math.abs((hp % 2) - 1));
  const m = l - c / 2;
  let r, g, b;
  if (hp < 1) { r = c; g = x; b = 0; }
  else if (hp < 2) { r = x; g = c; b = 0; }
  else if (hp < 3) { r = 0; g = c; b = x; }
  else if (hp < 4) { r = 0; g = x; b = c; }
  else if (hp < 5) { r = x; g = 0; b = c; }
  else { r = c; g = 0; b = x; }
  return { r: clamp01(r + m), g: clamp01(g + m), b: clamp01(b + m) };
}

/** WCAG 相对亮度。App 的 `relativeLuminance`。 */
function relativeLuminance(c) {
  const ch = (v) => (v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
  return 0.2126 * ch(c.r) + 0.7152 * ch(c.g) + 0.0722 * ch(c.b);
}

/** WCAG 对比度。App 的 `contrastRatio`。 */
export function contrastRatio(a, b) {
  const ca = parseHex(a);
  const cb = parseHex(b);
  if (!ca || !cb) return 1;
  const la = relativeLuminance(ca);
  const lb = relativeLuminance(cb);
  const hi = Math.max(la, lb);
  const lo = Math.min(la, lb);
  return (hi + 0.05) / (lo + 0.05);
}

/** 按比例保留原色、其余混白。App 的 `overWhite`（alpha 是「保留多少原色」）。 */
function overWhite(c, alpha) {
  const a = clamp01(alpha);
  return { r: c.r * a + (1 - a), g: c.g * a + (1 - a), b: c.b * a + (1 - a) };
}

/**
 * 把背景朝「离 fg 更远」的方向推（朝白），直到对比度达标。
 * App 的 `pushAwayFrom`。
 */
export function pushAwayFrom(bgHex, fgHex, target = 4.5) {
  const bg = parseHex(bgHex);
  if (!bg) return bgHex;
  if (contrastRatio(bgHex, fgHex) >= target) return bgHex;
  // 二分：lo = 原色那端（不满足），hi = 白那端（满足）。
  let lo = 1;
  let hi = 0;
  for (let i = 0; i < 20; i++) {
    const mid = (lo + hi) / 2;
    if (contrastRatio(rgbToHex(overWhite(bg, mid)), fgHex) >= target) hi = mid;
    else lo = mid;
  }
  return rgbToHex(overWhite(bg, hi));
}

/** 橙黄色相在压深时的色相微调。App 的 `hueCorrectForDeepen`。 */
function hueCorrectForDeepen(h) {
  const deg = h * 360;
  if (deg < DEEPEN_HUE_CORRECT_LO || deg > DEEPEN_HUE_CORRECT_HI) return h;
  let shift;
  if (deg <= 30) {
    const t = (deg - DEEPEN_HUE_CORRECT_LO) / (30 - DEEPEN_HUE_CORRECT_LO);
    shift = t * 11.0;
  } else if (deg <= 50) {
    const t = (deg - 30) / 20;
    shift = 11.0 - t * 4.5;
  } else {
    const t = (deg - 50) / (DEEPEN_HUE_CORRECT_HI - 50);
    shift = 6.5 * (1 - t);
  }
  return clamp01((deg - shift) / 360);
}

/** 把课程色压深（明度按比例降、饱和度补回来）。App 的 `deepenCourse`。 */
function deepenCourse(color, lightnessScale, satBoost) {
  const { r, g, b } = color;
  const maxC = Math.max(r, g, b);
  const minC = Math.min(r, g, b);
  if (maxC === minC) return color;      // 纯灰没有色相可加深
  const l = (maxC + minC) / 2;
  const d = (maxC - minC) / 2;
  const denom = l > 0.5 ? 2 - maxC - minC : maxC + minC;
  const s = denom <= 0 ? 0 : d / denom;
  const h = hueOf(r, g, b, maxC, minC);
  const hDeg = h * 360;
  // 暖色系（橙、黄）另给一档饱和度：压深之后暖色更容易发闷。
  const effectiveSatBoost =
    hDeg >= DEEPEN_HUE_CORRECT_LO && hDeg <= DEEPEN_HUE_CORRECT_HI ? 1.60 : satBoost;
  return fromHsl(hueCorrectForDeepen(h), clamp01(s * effectiveSatBoost), clamp01(l * lightnessScale));
}

/** 暖色区的对比度目标下调。App 的 `warmHueTargetAdjust`。 */
function warmHueTargetAdjust(color, target) {
  const { r, g, b } = color;
  const maxC = Math.max(r, g, b);
  const minC = Math.min(r, g, b);
  if (maxC === minC) return target;
  const hDeg = hueOf(r, g, b, maxC, minC) * 360;
  if (hDeg < DEEPEN_HUE_CORRECT_LO || hDeg > DEEPEN_HUE_CORRECT_HI) return target;
  let drop;
  if (hDeg <= 30) {
    const t = (hDeg - DEEPEN_HUE_CORRECT_LO) / (30 - DEEPEN_HUE_CORRECT_LO);
    drop = t * 1.45;
  } else if (hDeg <= 50) {
    const t = (hDeg - 30) / 20;
    drop = 1.45 + t * 0.75;
  } else {
    const t = (hDeg - 50) / (DEEPEN_HUE_CORRECT_HI - 50);
    drop = 2.20 * (1 - t);
  }
  return target - drop;
}

/** 把课程色压到「白字站得住」的深度。App 的 `deepEnoughForWhite`。 */
export function deepEnoughForWhite(colorHex, target = 3.8, satBoost = 1.45) {
  const color = parseHex(colorHex);
  if (!color) return colorHex;
  const effectiveTarget = warmHueTargetAdjust(color, target);
  if (contrastRatio(rgbToHex(color), COURSE_CELL_ON_PENDING) >= effectiveTarget) {
    return rgbToHex(color);
  }
  let lo = 0.04;
  let hi = 1.0;
  for (let i = 0; i < 20; i++) {
    const mid = (lo + hi) / 2;
    const candidate = deepenCourse(color, mid, satBoost);
    if (contrastRatio(rgbToHex(candidate), COURSE_CELL_ON_PENDING) >= effectiveTarget) lo = mid;
    else hi = mid;
  }
  return rgbToHex(deepenCourse(color, lo, satBoost));
}

/**
 * 一门课在周课表格子里的两档底色。
 *
 * @returns {{deep: string, idle: string}} 深档（有未完成待办）/ 浅档（没有）
 */
export function courseCellColors(colorHex) {
  const deep = deepEnoughForWhite(colorHex);
  return { deep, idle: pushAwayFrom(deep, COURSE_KHAKI_META_ON_IDLE) };
}

/* ── 撞课分列（抄 App 的 layoutDaySlots） ────────────────────────── */

/**
 * 把一天里的课程与考试按时间重叠分簇，簇内再贪心分列。
 *
 * App 的 `layoutDaySlots`：原来所有格子一律铺满整列，两门撞课的课互相压住，
 * 后画的把前一个整个盖掉 —— 时间上其实是两节，用户只看得见一节，
 * 等于课表在关键信息上撒谎。现在拆成并排的几列。
 *
 * @param {Array} items 每项要有 `key`、`startPeriod`、`endPeriod`
 * @returns {Array<{item: object, column: number, columns: number}>}
 */
export function layoutDayItems(items) {
  if (!items.length) return [];
  const sorted = items.slice().sort(
    (a, b) => a.startPeriod - b.startPeriod || a.endPeriod - b.endPeriod
  );

  const placements = [];
  let cluster = [];
  let clusterEnd = -Infinity;

  const flush = () => {
    if (!cluster.length) return;
    // 每列记「这一列最后一节课上到第几节」
    const columnEnds = [];
    const columnOf = new Map();
    for (const it of cluster) {
      let placedAt = -1;
      for (let i = 0; i < columnEnds.length; i++) {
        if (columnEnds[i] < it.startPeriod) {
          columnEnds[i] = it.endPeriod;
          placedAt = i;
          break;
        }
      }
      if (placedAt < 0) {
        placedAt = columnEnds.length;
        columnEnds.push(it.endPeriod);
      }
      columnOf.set(it.key, placedAt);
    }
    const total = columnEnds.length;
    for (const it of cluster) {
      placements.push({ item: it, column: columnOf.get(it.key) ?? 0, columns: total });
    }
    cluster = [];
    clusterEnd = -Infinity;
  };

  for (const it of sorted) {
    if (cluster.length && it.startPeriod > clusterEnd) flush();
    cluster.push(it);
    clusterEnd = Math.max(clusterEnd, it.endPeriod);
  }
  flush();
  return placements;
}

/* ── 渲染 ──────────────────────────────────────────────────────── */

const DAY_LABELS = ['一', '二', '三', '四', '五', '六', '日'];

/** 周次条上的日期区间：「10/12 – 10/18」。 */
function shortRange(from, to) {
  const f = shortDate(from);
  const t = shortDate(to);
  return f && t ? `${f} – ${t}` : '';
}

function shortDate(iso) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(iso || ''));
  if (!m) return '';
  return `${Number(m[2])}/${Number(m[3])}`;
}

function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text !== undefined && text !== null) n.textContent = text;
  return n;
}

/** 放假水印图标（App 的 EventAvailable：日历 + 一勾）。 */
const HOLIDAY_ICON =
  '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3.5" y="5" width="17" height="15.5" rx="3"/><path d="M3.5 9.5h17M8 3.5v3M16 3.5v3M8.6 14.8l2.1 2.1 4.7-4.7"/></svg>';

const CHEV_LEFT = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15 5l-7 7 7 7"/></svg>';
const CHEV_RIGHT = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 5l7 7-7 7"/></svg>';

/**
 * 画一张周课表。
 *
 * @param {HTMLElement} host 容器
 * @param {object} opts
 * @param {object} opts.timetable 快照里的 `timetable`
 * @param {Array} opts.courses 快照里的 `courses`（按 key 索引）
 * @param {Array} opts.tasks 快照里的 `tasks`（算「N 待办」徽标）
 * @param {string} opts.today `YYYY-MM-DD`
 * @param {number} opts.week 当前在看第几周
 * @param {(week:number)=>void} opts.onSelectWeek 翻周
 * @param {(course:object)=>void} opts.onOpenCourse 点课程格
 * @param {(exam:object)=>void} opts.onOpenExam 点考试格
 */
export function renderWeekGrid(host, opts) {
  const {
    timetable, courses, tasks, today, week,
    onSelectWeek, onOpenCourse, onOpenExam,
  } = opts;

  host.replaceChildren();
  if (!timetable || !timetable.weeks || !timetable.weeks.length) {
    host.appendChild(el('div', 'empty', '还没有周课表。连上手机同步一次就有了。'));
    return;
  }

  const totalWeeks = Math.max(1, timetable.totalWeeks || timetable.weeks.length);
  const weekData =
    timetable.weeks.find((w) => w.week === week) ||
    timetable.weeks.find((w) => w.week === timetable.currentWeek) ||
    timetable.weeks[0];
  const shownWeek = weekData.week;
  const days = weekData.days || [];

  const courseByKey = new Map((courses || []).map((c) => [c.key, c]));
  const pendingByCourse = new Map();
  const linkedByCourse = new Map();
  for (const t of tasks || []) {
    if (!t.courseKey) continue;
    linkedByCourse.set(t.courseKey, (linkedByCourse.get(t.courseKey) || 0) + 1);
    if (!t.isCompleted) {
      pendingByCourse.set(t.courseKey, (pendingByCourse.get(t.courseKey) || 0) + 1);
    }
  }

  const periodsPerDay = Math.max(1, timetable.periodsPerDay || 13);

  // ── 周次条 ──────────────────────────────────────────────────
  const strip = el('div', 'week-strip');
  const stepBtn = (icon, label, target, enabled) => {
    const b = el('button', 'week-step');
    b.type = 'button';
    b.title = label;
    b.setAttribute('aria-label', label);
    b.innerHTML = icon;
    b.disabled = !enabled;
    if (enabled) b.addEventListener('click', () => onSelectWeek(target));
    return b;
  };
  strip.appendChild(stepBtn(CHEV_LEFT, '上一周', shownWeek - 1, shownWeek > 1));

  const titleBox = el('div', 'week-title-box');
  const title = el('button', 'week-title');
  title.type = 'button';
  title.title = '选择周次';
  const range = shortRange(days[0] && days[0].date, days[6] && days[6].date);
  title.textContent = `第 ${shownWeek} 周${range ? ' · ' + range : ''}`;
  titleBox.appendChild(title);
  strip.appendChild(titleBox);

  strip.appendChild(stepBtn(CHEV_RIGHT, '下一周', shownWeek + 1, shownWeek < totalWeeks));

  // 周次条下面那行概况（App 的 WeekStrip notes）
  const offDays = days.filter(
    (d) => d.offReason === 'NO_CLASS' || d.offReason === 'VACATED'
  ).length;
  const makeupDays = days.filter(
    (d) => d.makeupDayOfWeek && !d.offReason
  ).length;
  const allOutOfTerm = days.length > 0 && days.every((d) => d.offReason === 'OUT_OF_TERM');
  const notes = [];
  if (shownWeek === timetable.currentWeek) notes.push('本周');
  if (allOutOfTerm) notes.push('不在学期范围内');
  if (offDays > 0) notes.push(`放假 ${offDays} 天`);
  if (makeupDays > 0) notes.push(`补课 ${makeupDays} 天`);
  if (notes.length) strip.appendChild(el('div', 'week-notes', notes.join(' · ')));

  host.appendChild(strip);

  // 周次选择器（点中间的「第 N 周」打开）
  const picker = el('div', 'week-picker');
  picker.hidden = true;
  for (let w = 1; w <= totalWeeks; w++) {
    const b = el('button', 'week-picker-item' + (w === shownWeek ? ' is-active' : ''));
    b.type = 'button';
    const wData = timetable.weeks.find((x) => x.week === w);
    const wDays = (wData && wData.days) || [];
    const wOff = wDays.filter(
      (d) => d.offReason === 'NO_CLASS' || d.offReason === 'VACATED'
    ).length;
    b.textContent = `第 ${w} 周` + (wOff ? ` · 放假 ${wOff} 天` : '');
    b.addEventListener('click', () => {
      picker.hidden = true;
      onSelectWeek(w);
    });
    picker.appendChild(b);
  }
  host.appendChild(picker);
  // 点标题开/关选择器。**不在这里挂 document 监听** —— 每次渲染都挂一个的话，
  // 改一条待办就会多一个监听器，页面开久了会漏成几十个。
  // 「点别处关闭」由 app.js 统一挂一个。
  title.addEventListener('click', (e) => {
    e.stopPropagation();
    const opening = picker.hidden;
    picker.hidden = !opening;
    if (!opening) return;
    // 当前周可能在列表的下面（学期有 16~25 周），
    // 不滚进视野的话用户打开选择器只看到「第 1 周…第 9 周」，
    // 还得自己拖才知道自己在第几周。
    //
    // **滚的是选择器自己**，不是 `scrollIntoView`：后者会把整页也带着滚，
    // 页面会莫名其妙跳一下。
    const active = picker.querySelector('.week-picker-item.is-active');
    if (active) {
      picker.scrollTop = Math.max(
        0,
        active.offsetTop - (picker.clientHeight - active.offsetHeight) / 2
      );
    }
  });

  // ── 星期表头 ────────────────────────────────────────────────
  const head = el('div', 'week-head');
  head.appendChild(el('div', 'week-ruler-head'));
  days.forEach((day, i) => {
    const isToday = day.date === today;
    const cell = el('div', 'week-day-head' + (isToday ? ' is-today' : ''));
    cell.textContent = `周${DAY_LABELS[day.dayOfWeek - 1] || ''}`;
    cell.title = day.date;
    head.appendChild(cell);
  });
  host.appendChild(head);
  host.appendChild(el('div', 'week-head-sep'));

  // ── 网格主体 ────────────────────────────────────────────────
  const body = el('div', 'week-body');
  body.style.setProperty('--week-rows', String(periodsPerDay));

  // 左侧节次刻度列：数字只在这里出现一次，永远可见，不与课程块争位置。
  const ruler = el('div', 'week-ruler');
  for (let p = 1; p <= periodsPerDay; p++) {
    const n = el('div', 'week-ruler-num', String(p));
    ruler.appendChild(n);
  }
  body.appendChild(ruler);

  days.forEach((day) => {
    const isToday = day.date === today;
    const isHoliday = day.offReason === 'NO_CLASS' || day.offReason === 'VACATED';
    const col = el('div', 'week-col' + (isToday ? ' is-today' : ''));

    // 行线
    for (let p = 1; p <= periodsPerDay; p++) {
      const line = el('div', 'week-row-line');
      line.style.top = `calc(${p} * var(--week-row) - var(--hair))`;
      col.appendChild(line);
    }

    // 放假水印
    if (isHoliday) {
      const mark = el('div', 'week-holiday');
      mark.innerHTML = HOLIDAY_ICON;
      mark.title = day.offReason === 'VACATED' ? '这天的课让给了别的日子' : '放假停课';
      col.appendChild(mark);
    }

    // 课程 + 考试 一起过分栏算法：同一格里既有课又有考时，它们得并排，不能互相压住。
    const items = [];
    (day.slotIndexes || []).forEach((idx, i) => {
      const slot = (timetable.slotList || [])[idx];
      if (!slot) return;
      items.push({
        key: `s${idx}_${i}`,
        kind: 'course',
        startPeriod: slot.startPeriod,
        endPeriod: slot.endPeriod,
        courseKey: slot.courseKey,
        classroom: slot.classroom,
      });
    });
    (day.exams || []).forEach((exam, i) => {
      items.push({
        key: `e${i}`,
        kind: 'exam',
        startPeriod: exam.startPeriod,
        endPeriod: exam.endPeriod,
        exam,
      });
    });

    const placements = layoutDayItems(items);
    for (const place of placements) {
      const it = place.item;
      const conflicted = place.columns > 1;
      const start = Math.max(1, Math.min(periodsPerDay, it.startPeriod));
      const end = Math.max(start, Math.min(periodsPerDay, it.endPeriod));
      const span = end - start + 1;

      const cell = el('div', 'week-cell' + (conflicted ? ' is-conflict' : ''));
      // 外框 = App 里那个带 padding 的 Box（缩进 2px），内芯 = 卡片本身。
      cell.style.top = `calc(${start - 1} * var(--week-row) + 2px)`;
      cell.style.height = `calc(${span} * var(--week-row) - 4px)`;
      const gap = conflicted ? 2 : 0;
      cell.style.setProperty('--cell-w', `calc((100% - ${gap * (place.columns - 1)}px) / ${place.columns})`);
      cell.style.setProperty('--cell-left', `calc(var(--cell-w) * ${place.column} + ${gap * place.column}px)`);
      const face = el('div', 'week-cell-face');
      cell.appendChild(face);

      if (it.kind === 'exam') {
        renderExamCell(face, it.exam, conflicted, span);
        cell.addEventListener('click', () => onOpenExam && onOpenExam(it.exam));
      } else {
        const course = courseByKey.get(it.courseKey);
        renderCourseCell(face, {
          course, slot: it, conflicted, span,
          pending: pendingByCourse.get(it.courseKey) || 0,
          linked: linkedByCourse.get(it.courseKey) || 0,
        });
        cell.addEventListener('click', () => course && onOpenCourse && onOpenCourse(course));
      }
      col.appendChild(cell);
    }

    body.appendChild(col);
  });

  host.appendChild(body);
}

/** 课程格：只有课程名一行 + 底部状态徽标。教室位置在 App 里也不显示。 */
function renderCourseCell(face, { course, slot, conflicted, span, pending, linked }) {
  const name = (course && course.name) || '(未命名课程)';
  const base = (course && course.colorHex) || '#C9D0D8';
  const { deep, idle } = courseCellColors(base);
  const hasPending = pending > 0;

  if (hasPending) {
    // 深档：玻璃面 + 课程色 94%（背后透上来 6% 的壁纸）。
    face.style.backgroundColor = hexToRgba(deep, 0.94);
    face.classList.add('is-pending');
    face.style.color = COURSE_CELL_ON_PENDING;
  } else {
    // 浅档：实色平涂（底色被钉在固定明度上，不受壁纸影响）。
    face.style.backgroundColor = idle;
    face.style.color = COURSE_CELL_ON_IDLE;
  }

  const title = el('div', 'week-cell-title', name);
  // App 的行数规则：单节一行，连堂三行，撞课四行。
  title.style.setProperty('--lines', String(conflicted ? 4 : span >= 2 ? 3 : 1));
  face.appendChild(title);

  if (hasPending) {
    const badge = el('div', 'week-badge');
    badge.appendChild(el('span', 'week-badge-dot'));
    badge.appendChild(el('span', 'week-badge-text', conflicted ? String(pending) : `${pending} 待办`));
    face.appendChild(badge);
  } else if (linked > 0) {
    // 全部完成：只留一个 ✓，不写「已结清」三个字。
    const badge = el('div', 'week-badge is-dot', '✓');
    face.appendChild(badge);
  }
}

/** 考试格：深色档 + 底部「期中/期末」徽标；跨节时写开始时刻。 */
function renderExamCell(face, exam, conflicted, span) {
  const base = exam.colorHex || '#FDE047';
  const { deep } = courseCellColors(base);
  face.style.backgroundColor = deep;
  face.style.color = COURSE_CELL_ON_PENDING;
  face.classList.add('is-pending');

  const title = el('div', 'week-cell-title', exam.courseName || '(未命名考试)');
  title.style.setProperty('--lines', String(conflicted ? 4 : span >= 2 ? 3 : 1));
  face.appendChild(title);

  if (span >= 2 && exam.startMinute > 0) {
    face.appendChild(el('div', 'week-cell-time', formatMinute(exam.startMinute)));
  }

  const badge = el('div', 'week-badge', exam.kind === 'FINAL' ? '期末' : '期中');
  face.appendChild(badge);
}

/** 自 00:00 起的分钟数 → 「09:00」。 */
function formatMinute(minute) {
  const h = Math.floor(minute / 60);
  const m = minute % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

function hexToRgba(hex, alpha) {
  const h = String(hex || '').replace('#', '').trim();
  if (h.length !== 6 || /[^0-9a-fA-F]/.test(h)) return 'transparent';
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}
