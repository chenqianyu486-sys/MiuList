#!/usr/bin/env node
/* MiuList 网页端的本地 mock 服务（协议 v3 / 配对协议 6）。
 *
 * 用途：不开手机也能把网页端跑起来 —— 开发时调样式、截图验收时出图。
 *
 * ## 它怎么扮演「手机」
 *
 * 真机上，手机是**确认的一方**：网页端带着自己的 DH 公钥来（公钥不是秘密），
 * 两端各自算出同一把共享密钥 —— **密钥从不过网**。
 *
 * mock 就照着这个来：网页端 POST 来的公钥它接着，
 * 算出一把共享密钥，然后按 `--no-auto-approve` 决定批不批。
 *
 * 密码学直接 import 网页端那份 `web/crypto.js` ——
 * 这样 mock 与手机端的算法**不可能**对不齐（那是「配对过了但一直解密失败」的元凶）。
 *
 * 用法：
 *   node tools/web-mock-server.mjs --port 8777
 *   node tools/web-mock-server.mjs --port 8777 --no-auto-approve
 *   node tools/web-mock-server.mjs --port 8778 --no-timetable
 *
 * 最后一个 `--no-timetable` 是**模拟旧版本手机**：快照里不带 `timetable`，
 * 好验网页端在没有周课表时的那条降级路径（「周视图」那一档藏起来）。
 */

import http from 'node:http';
import dgram from 'node:dgram';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import {
  hmacSha256, chacha20, deriveKeys, seal, open, signRequest,
  toBase64Url, fromBase64Url, utf8Bytes,
  dhGenerateKeypair, dhSharedSecret, pairCodeOf,
} from '../web/crypto.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// 静态托管目录 = **新桌面端**的构建产物。旧网页端（web/index.html 等）已删除，
// 原来这里指的是 web/；现在 127.0.0.1:8801/ 打开就是桌面端本身，
// 不用再拿 file:// 打开 HTML —— 同源，省掉一轮跨源配置。
// 没构建过就退回 web/（里面还有 crypto.js / timetable.js 这些共享模块）。
const DESKTOP_DIST = path.resolve(__dirname, '..', 'desktop', 'dist');
const WEB_DIR = fs.existsSync(path.join(DESKTOP_DIST, 'MiuList-Web.html'))
  ? DESKTOP_DIST
  : path.resolve(__dirname, '..', 'web');

const argv = process.argv.slice(2);
function arg(name, fallback) {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] !== undefined ? argv[i + 1] : fallback;
}
const PORT = Number(arg('--port', 8777));
const HOST = arg('--host', '0.0.0.0');
const PASSCODE = arg('--passcode', '');

/**
 * 不发周课表 —— 模拟**旧版本手机**的快照。
 *
 * 网页端据此把「周视图」那一档藏起来、落在「课程」上：
 * 那才是「这一端还不知道周课表」时该有的样子，而不是画一张空表。
 */
const NO_TIMETABLE = argv.includes('--no-timetable');

/**
 * 不自动批准配对 —— 模拟「手机上还没点允许」。
 *
 * 真机上要人点「允许」，这之前网页端一直停在配对页。
 * 自动批准是 mock 的默认（方便截图），但要拍**等待手机确认那张配对卡**就得关掉它。
 */
const NO_AUTO_APPROVE = argv.includes('--no-auto-approve');
/**
 * 模拟**旧版手机**的配对协议（v3）。
 *
 * 现在的协议是 **v6**：DH 协商 + **双方公钥的转录绑定**（配对码 = H(A‖B)），
 * 网页端与手机必须同一版才可能算出同一个码。
 *
 * `--legacy-pairing` 让 mock 报 `pairing: 3`，桌面端会**直接拒绝配对**，
 * 并提示「手机版本太旧，请更新 MiuList App 后再配对」。
 * 与 `--no-timetable` 是同一个思路：**模拟一个旧版本的手机**，
 * 用来验桌面端的版本闸门与提示文案。
 */
const LEGACY_PAIRING = argv.includes('--legacy-pairing');

// ── 假数据 ──────────────────────────────────────────────────

const pad = (n) => String(n).padStart(2, '0');
const ymd = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const todayDate = new Date();
const TODAY = ymd(todayDate);
const dayOffset = (n) => {
  const d = new Date(todayDate);
  d.setDate(d.getDate() + n);
  return ymd(d);
};
const stamp = (day, hhmm) => {
  const [h, m] = hhmm.split(':').map(Number);
  const d = new Date(`${day}T00:00:00`);
  d.setHours(h, m, 0, 0);
  return d.getTime();
};

let revision = 7;
let nextUid = 1;
const uid = () => `mock-${nextUid++}`;

const COURSES = [
  { key: 'MATH2432F|微积分B|李伟', name: '微积分 B(下)', code: 'MATH2432F', teacher: '李伟', category: '公共基础', colorHex: '#7aa2ff', classroom: '教十 201', note: '教材第 3 版', weeklyText: '周一 3-4 节 · 周三 3-4 节', slots: [
    { dayOfWeek: 1, startPeriod: 3, endPeriod: 4, classroom: '教十 201', weeks: [] },
    { dayOfWeek: 3, startPeriod: 3, endPeriod: 4, classroom: '教十 201', weeks: [] },
  ] },
  { key: 'CS2103|数据结构|陈默', name: '数据结构', code: 'CS2103', teacher: '陈默', category: '专业必修', colorHex: '#4ec98d', classroom: '教十 305', note: '', weeklyText: '周二 5-6 节 · 周四 5-6 节', slots: [
    { dayOfWeek: 2, startPeriod: 5, endPeriod: 6, classroom: '教十 305', weeks: [] },
    { dayOfWeek: 4, startPeriod: 5, endPeriod: 6, classroom: '教十 305', weeks: [] },
  ] },
  { key: 'PHYS1001|大学物理|王芳', name: '大学物理', code: 'PHYS1001', teacher: '王芳', category: '公共基础', colorHex: '#f0b429', classroom: '实验楼 A302', note: '带计算器', weeklyText: '周三 7-8 节', slots: [
    { dayOfWeek: 3, startPeriod: 7, endPeriod: 8, classroom: '实验楼 A302', weeks: [] },
  ] },
  { key: 'ENGL2101|学术英语写作|Emily', name: '学术英语写作', code: 'ENGL2101', teacher: 'Emily Zhang', category: '通识公共', colorHex: '#b28cff', classroom: '外语楼 402', note: '', weeklyText: '周五 3-4 节', slots: [
    { dayOfWeek: 5, startPeriod: 3, endPeriod: 4, classroom: '外语楼 402', weeks: [] },
  ] },
];

/* ── 周课表（抄手机那份 WebTimetableBuilder 的产物形状） ───────── */

/** 内置作息表：13 节，与 App 的 PeriodTable.DEFAULT 同值。 */
const PERIODS = [
  [1, 480, 525], [2, 535, 580], [3, 600, 645], [4, 655, 700],
  [5, 840, 885], [6, 895, 940], [7, 960, 1005], [8, 1015, 1060],
  [9, 1110, 1155], [10, 1165, 1210], [11, 1220, 1265], [12, 1275, 1320],
  [13, 1325, 1370],
];

/** 本周一（epoch 毫秒）。 */
const mondayMs = (() => {
  const d = new Date(todayDate);
  const dow = d.getDay() === 0 ? 7 : d.getDay();
  d.setDate(d.getDate() - (dow - 1));
  d.setHours(0, 0, 0, 0);
  return d.getTime();
})();
const DAY_MS = 86400000;

const TOTAL_WEEKS = 16;
/**
 * 学期从 10 周前开始，于是**今天所在的那一周正好是第 11 周** ——
 * 这样截图里能看到「今天」那一列的高亮，而不只是随便某一周。
 */
const WEEKS_BEFORE_TODAY = 10;
const CURRENT_WEEK = WEEKS_BEFORE_TODAY + 1;

/** 时段去重表：周计划里只存下标。按当前这份课表现算，课表换了表也跟着换。 */
function buildSlotList(courses) {
  const list = [];
  const index = new Map();
  for (const c of courses) {
    for (const s of c.slots || []) {
      const key = `${c.key}|${s.dayOfWeek}|${s.startPeriod}|${s.endPeriod}`;
      if (index.has(key)) continue;
      index.set(key, list.length);
      list.push({
        courseKey: c.key,
        startPeriod: s.startPeriod,
        endPeriod: s.endPeriod,
        classroom: s.classroom || c.classroom,
      });
    }
  }
  return { list, index };
}

/** 钟点 → 节次区间（抄 PeriodTable.periodSpanOf）。 */
function periodSpanOf(startMinute, endMinute) {
  let start = null;
  for (const [p, s, e] of PERIODS) {
    if (startMinute >= s && startMinute < e) { start = p; break; }
  }
  if (start === null) {
    const next = PERIODS.find(([, s]) => startMinute < s);
    if (!next) return null;
    start = next[0];
  }
  let end = start;
  for (const [p, s] of PERIODS) if (s < endMinute) end = p;
  return [start, Math.max(start, end)];
}

/**
 * 造一份周课表。
 *
 * 特意造出两种「只有周课表才表达得出来」的情形，好让截图能验到：
 * - **本周周三放假停课**（NO_CLASS）→ 那一列空着，列背景有放假水印；
 * - **本周周五让出课、周六补**（VACATED / 补课日）→ 周五空着，
 *   周六出的是周五那门课。
 */
function buildTimetable(courses = COURSES) {
  const { list: slotList, index: slotIndex } = buildSlotList(courses);
  const slotIndexOf = (courseKey, dow, start, end) =>
    slotIndex.get(`${courseKey}|${dow}|${start}|${end}`);
  const weeks = [];
  // 第 1 周的周一 = 今天所在那周的周一往前推 10 周（学期的起点）
  const semesterStart = mondayMs - WEEKS_BEFORE_TODAY * 7 * DAY_MS;
  for (let w = 1; w <= TOTAL_WEEKS; w++) {
    const monday = semesterStart + (w - 1) * 7 * DAY_MS;
    const days = [];
    for (let i = 0; i < 7; i++) {
      const date = new Date(monday + i * DAY_MS);
      const dow = i + 1;                      // 1=周一 … 7=周日
      const iso = ymd(date);
      let offReason = null;
      let makeupDayOfWeek = null;
      let effectiveDow = dow;

      if (w === CURRENT_WEEK) {
        if (dow === 3) offReason = 'NO_CLASS';                 // 周三放假停课
        if (dow === 5) { offReason = 'VACATED'; }               // 周五让出课
        if (dow === 6) { makeupDayOfWeek = 5; effectiveDow = 5; } // 周六补周五的课
      }

      const slotIndexes = [];
      if (!offReason) {
        for (const c of courses) {
          for (const s of c.slots || []) {
            if (s.dayOfWeek !== effectiveDow) continue;
            const idx = slotIndexOf(c.key, s.dayOfWeek, s.startPeriod, s.endPeriod);
            if (idx !== undefined) slotIndexes.push(idx);
          }
        }
      }

      const exams = [];
      if (!offReason) {
        for (const e of EXAMS) {
          if (e.day !== iso) continue;
          const span = periodSpanOf(minutesOf(e.start), minutesOf(e.end));
          if (!span) continue;
          exams.push({
            courseName: e.courseName,
            colorHex: e.colorHex,
            startPeriod: span[0],
            endPeriod: span[1],
            startMinute: minutesOf(e.start),
            classroom: e.classroom,
            seat: e.seat,
          });
        }
      }

      days.push({
        date: iso,
        dayOfWeek: dow,
        offReason,
        makeupDayOfWeek,
        slotIndexes,
        exams,
      });
    }
    weeks.push({ week: w, days });
  }
  return {
    totalWeeks: TOTAL_WEEKS,
    currentWeek: CURRENT_WEEK,
    periodsPerDay: PERIODS.length,
    periods: PERIODS.map(([period, startMinute, endMinute]) => ({ period, startMinute, endMinute })),
    slotList,
    weeks,
  };
}

/** "HH:mm" → 自 00:00 起的分钟数。 */
function minutesOf(hhmm) {
  const [h, m] = hhmm.split(':').map(Number);
  return h * 60 + m;
}

const TASKS = [
  { title: '微积分 B 作业 · 第 6 章', courseName: '微积分 B(下)', courseColorHex: '#7aa2ff', taskType: '作业', due: dayOffset(0), dueTime: '23:59', done: false, star: true, auto: true },
  { title: '数据结构实验报告 3', courseName: '数据结构', courseColorHex: '#4ec98d', taskType: '实验报告', due: dayOffset(1), dueTime: '18:00', done: false, star: false, auto: true },
  { title: '大学物理预习 · 第8章 电磁场', courseName: '大学物理', courseColorHex: '#f0b429', taskType: '预习', due: dayOffset(0), dueTime: '21:30', done: false, star: false, auto: true },
  { title: '英语写作 essay 初稿', courseName: '学术英语写作', courseColorHex: '#b28cff', taskType: '作业', due: dayOffset(3), dueTime: '23:59', done: false, star: false, auto: false },
  { title: '整理这周的错题', courseName: '', courseColorHex: '#C9D0D8', taskType: '', due: dayOffset(2), dueTime: '20:00', done: false, star: false, auto: false },
  { title: '微积分 B 作业 · 第 5 章', courseName: '微积分 B(下)', courseColorHex: '#7aa2ff', taskType: '作业', due: dayOffset(-2), dueTime: '23:59', done: true, star: false, auto: true },
  { title: '买一支 0.5mm 笔芯', courseName: '', courseColorHex: '#C9D0D8', taskType: '', due: '', dueTime: '', done: true, star: false, auto: false },
];

const state = {
  tasks: TASKS.map((t) => ({
    syncUid: uid(),
    title: t.title,
    description: '',
    taskType: t.taskType,
    taskTypeLabel: t.taskType,
    priority: t.star ? 'STAR' : '',
    isCompleted: t.done,
    completedAt: t.done ? stamp(dayOffset(-1), '09:00') : null,
    startTimeTimestamp: 0,
    dueDateTimestamp: t.due ? stamp(t.due, t.dueTime) : -9223372036854775808,
    dueDateString: t.due ? `${t.due} ${t.dueTime}` : '',
    startTimeString: '',
    isAutoGenerated: t.auto,
    week: 8,
    dayOfWeek: 1,
    courseKey: COURSES.find((c) => c.name === t.courseName)?.key || '',
    courseName: t.courseName,
    courseCategory: '',
    courseColorHex: t.courseColorHex,
    createdAt: stamp(dayOffset(-5), '10:00'),
    updatedAt: stamp(dayOffset(-1), '10:00'),
  })),
};

const EXAMS = [
  { courseName: '微积分 B(下)', kind: 'MIDTERM', day: dayOffset(6), start: '09:00', end: '11:00', classroom: '教十 201', seat: '12', colorHex: '#7aa2ff' },
  { courseName: '数据结构', kind: 'FINAL', day: dayOffset(13), start: '14:00', end: '16:00', classroom: '待学校安排', seat: '', colorHex: '#4ec98d' },
];

/** 考试记录 → 线上 DTO 形状（与手机端 WebExamDto 同字段）。 */
function examDto(e) {
  return {
    id: e.courseName,
    courseCode: '',
    courseName: e.courseName,
    kind: e.kind,
    whenText: `${e.day} ${e.start}-${e.end}`,
    dateLabel: '',
    classroom: e.classroom,
    seat: e.seat,
    credit: 0,
    termLabel: '2026-2027 秋冬',
    colorHex: e.colorHex,
    isToday: e.day === TODAY,
    startAt: stamp(e.day, e.start),
    endAt: stamp(e.day, e.end),
    startMinute: 0,
    endMinute: 0,
    updatedAt: 0,
  };
}

/**
 * 课表 / 考试 / 分类是**配置**，不是待办。
 *
 * mock 跟真机一样：**只有网页端明确把配置推上来（载荷里带
 * `hasCourses` / `hasExams` / `hasTaskTypes` 标记）才替换**。
 * 自动同步不带标记 —— 于是「网页端保留自己的配置」这条规则
 * 在本地联调时也能验出来，而不是只能靠真机验。
 */
state.courses = COURSES.map((c) => ({
  ...c,
  slots: (c.slots || []).map((s) => ({ ...s })),
}));
state.exams = EXAMS.map(examDto);
state.taskTypes = [];

/** 「HH:mm」→ 自 00:00 起的分钟数。 */
function hhmm(minute) {
  const h = Math.floor(minute / 60);
  const m = minute % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

/**
 * 今天要上的课 —— 从周课表里那一列自己取。
 *
 * 跟网页端同一套算法：网页端的配置是独立的，手机那份 `todayCourses`
 * 可能是另一天的，所以两边都自己按日期取。
 */
function deriveTodayCourses(courses) {
  const tt = buildTimetable(courses);
  const day = tt.weeks.flatMap((w) => w.days || []).find((d) => d.date === TODAY);
  if (!day) return [];
  const byKey = new Map(courses.map((c) => [c.key, c]));
  return (day.slotIndexes || [])
    .map((idx) => {
      const slot = tt.slotList[idx];
      if (!slot) return null;
      const course = byKey.get(slot.courseKey);
      if (!course) return null;
      const from = tt.periods.find((p) => p.period === slot.startPeriod);
      const to = tt.periods.find((p) => p.period === slot.endPeriod);
      return {
        courseKey: slot.courseKey,
        name: course.name,
        colorHex: course.colorHex,
        classroom: slot.classroom || course.classroom,
        teacher: course.teacher,
        timeText: `${hhmm(from?.startMinute ?? 0)} - ${hhmm(to?.endMinute ?? 0)}`,
        startPeriod: slot.startPeriod,
        endPeriod: slot.endPeriod,
        state: 'future',
      };
    })
    .filter(Boolean);
}

// ── 配对与密钥 ──────────────────────────────────────────────

const DEVICE_ID = 'mock-device';
let keys = null;
/**
 * 待批准的配对（DH 协商）。
 *
 * 网页端带着自己的**公钥**来，mock 拿它算出一把共享密钥 ——
 * 两边都有同一把共享密钥，而它**从不过网**。
 */
let pendingPair = null;
const usedNonces = new Map();

/**
 * 带签名的接口只放行「已经配对协商出密钥」的请求。
 *
 * 未配对就 401，而不是偷偷给一把固定密钥 —— 悄悄跳过鉴权会让
 * 「配对成功但连不上」这类问题变成玄学。
 */
function ensureKeys(res) {
  if (keys) return true;
  json(res, 401, { error: '还没配对：先让网页端配对' });
  return false;
}

function verifySignature(url, req, body) {
  const deviceId = req.headers['x-ml-device'] || url.searchParams.get('device');
  if (deviceId !== DEVICE_ID) return false;
  const ts = Number(req.headers['x-ml-ts'] || url.searchParams.get('ts'));
  const nonce = req.headers['x-ml-nonce'] || url.searchParams.get('nonce');
  const sig = req.headers['x-ml-sig'] || url.searchParams.get('sig');
  if (!ts || !nonce || !sig) return false;
  if (Math.abs(Date.now() - ts) > 90_000) return false;
  const used = usedNonces.get(nonce) || 0;
  if (used) return false;
  usedNonces.set(nonce, Date.now());
  const expected = signRequest(keys, { method: req.method, path: url.pathname, ts, nonce, body });
  return expected === sig;
}

// ── 快照 ────────────────────────────────────────────────────

function snapshot() {
  return {
    protocol: 1,
    revision,
    generatedAt: Date.now(),
    today: TODAY,
    weekday: todayDate.getDay() === 0 ? 7 : todayDate.getDay(),
    deviceName: 'Mock 手机',
    appVersion: '1.0.4',
    latestEditAt: state.tasks.reduce((m, t) => Math.max(m, t.updatedAt || 0), 0),
    utcOffsetMinutes: 480,
    hasCourses: true,
    hasExams: true,
    hasTaskTypes: true,
    tasks: state.tasks,
    taskTypes: state.taskTypes,
    courses: state.courses,
    exams: state.exams,
    todayCourses: deriveTodayCourses(state.courses),
    // 周课表：随课表一起走；网页端只画，不自己算（手机那边才是权威）
    timetable: NO_TIMETABLE ? null : buildTimetable(state.courses),
    // 原始校历：网页端存下这一份，是为了写进它导出的 JSON
    // （那份文件手机也认得，换机后周次才对得上）。
    calendar: {
      termConfigs: [{ Year: '2026-2027', Term: 0, Begin: 20260907, End: 20261025, FirstWeekNo: 1 }],
      classTerms: ['2026-2027:0'],
      tweaks: [{ TweakType: 'Clear', Description: '国庆', From: 20261001, To: 20261003 }],
      periods: [{ period: 1, startTime: '08:00', endTime: '08:45', section: '大教室' }],
    },
  };
}

function bump(reason) {
  revision += 1;
  for (const c of sseClients) {
    c.write(`event: change\ndata: ${toBase64Url(seal(keys, utf8Bytes(JSON.stringify({ revision, reason }))))}\n\n`);
  }
}

function applyRemoteSnapshot(body) {
  const mode = body.mode === 'replace' ? 'replace' : 'merge';
  const incoming = body.snapshot || {};
  const incomingTasks = (incoming.tasks || []).filter((t) => t.syncUid);
  const applied = [];
  const kept = [];

  // ── 配置：只有带标记才替换（跟真机同一条规则） ──
  //
  // 标记的含义是「这一端**有**配置」，不是「这一端想替换」。
  // 自动同步不带标记 → 手机的课表不动；
  // 「把本页课表推给手机」带标记 → 手机的课表被换成这一份。
  if (incoming.hasCourses) state.courses = (incoming.courses || []).map((c) => ({ ...c }));
  if (incoming.hasExams) state.exams = incoming.exams || [];
  if (incoming.hasTaskTypes) state.taskTypes = incoming.taskTypes || [];

  if (mode === 'replace') {
    const before = state.tasks.length;
    state.tasks = incomingTasks.map((t) => ({ ...t }));
    for (const t of incomingTasks) applied.push(t.syncUid);
    return { applied, kept, removed: Math.max(0, before - state.tasks.length) };
  }

  const byUid = new Map(state.tasks.map((t) => [t.syncUid, t]));
  for (const t of incomingTasks) {
    const local = byUid.get(t.syncUid);
    if (!local) {
      state.tasks.push({ ...t });
      applied.push(t.syncUid);
    } else if ((t.updatedAt || 0) > (local.updatedAt || 0)) {
      Object.assign(local, t);
      applied.push(t.syncUid);
    } else {
      kept.push(t.syncUid);
    }
  }
  return { applied, kept, removed: 0 };
}

// ── HTTP ────────────────────────────────────────────────────

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.png': 'image/png',
};

const sseClients = new Set();

function json(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
    ...corsHeaders(res.req),
  });
  res.end(body);
}

// ── CORS：回显 Origin ────────────────────────────────────────
//
// 网页端跑在电脑上时，页面在 `127.0.0.1`，接口在手机的局域网地址 ——
// **跨源**。浏览器会先发 OPTIONS 预检，预检里没列出的请求头会被直接拦掉。
//
// 这里是 mock，但**要跟真机一样**：真机（App 侧的 LanHttpServer）也是
// 回显 Origin，并把 X-ML-* 那几个签名头列进 Allow-Headers。
// mock 少了这段，跨源那条路就测不出真问题。
function corsHeaders(req) {
  const origin = req && req.headers ? req.headers.origin : null;
  if (!origin) return {};
  const out = {
    'Access-Control-Allow-Origin': origin,
    'Access-Control-Allow-Methods': 'GET, POST, PATCH, DELETE, OPTIONS',
    'Access-Control-Allow-Headers':
      'Authorization, Content-Type, X-ML-Device, X-ML-Ts, X-ML-Nonce, X-ML-Sig, X-ML-Enc',
    // **必须暴露 X-ML-Enc**：跨源时自定义响应头默认对脚本不可见，
    // 而这个头是「这坨是密文」的标记 —— 少了它，网页端会把密文当明文解析。
    'Access-Control-Expose-Headers': 'X-ML-Enc',
    'Access-Control-Max-Age': '600',
    Vary: 'Origin',
    'Access-Control-Allow-Private-Network': 'true',
  };
  if (req.headers['access-control-request-private-network'] === 'true') {
    out['Access-Control-Allow-Private-Network'] = 'true';
  }
  return out;
}

function encrypted(res, payload) {
  const body = toBase64Url(seal(keys, utf8Bytes(payload)));
  res.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'X-ML-Enc': '1',
    'Cache-Control': 'no-store',
    ...corsHeaders(res.req),
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks)));
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://' + (req.headers.host || 'localhost'));
  const p = url.pathname;

  // 所有响应都带上回显的 Origin：哪怕某条分支忘了，浏览器也不会把它
  // 当成「没有 CORS」而掐掉。预检分支会再覆盖一遍完整头。
  if (req.headers.origin) {
    res.setHeader('Access-Control-Allow-Origin', req.headers.origin);
    res.setHeader('Vary', 'Origin');
    res.setHeader('Access-Control-Allow-Private-Network', 'true');
  }

  // 预检：跨源时浏览器会先问一次「这些请求头你允许吗」。
  // 少放行一个签名头，网页端在电脑上就是「永远连不上」。
  if (req.method === 'OPTIONS') {
    res.writeHead(204, { ...corsHeaders(req), 'Content-Length': '0' });
    return res.end();
  }

  try {
    if (p === '/api/health') {
      // `pairing: 6` —— 网页端据此知道这台「手机」支持 **DH 协商**
      // （公钥过网、密钥各自算）**外加转录绑定配对码**（H(A‖B)）。
      // `--legacy-pairing` 时报 3，网页端会直接拒绝配对并提示版本太旧
      // （见那个开关的说明）。
      return json(res, 200, { ok: true, app: 'MiuList', appVersion: '1.1.0', protocol: 3, pairing: LEGACY_PAIRING ? 3 : 6, deviceName: 'Mock 手机', serverTime: Date.now() });
    }

    // ── 调试：换掉这台「手机」的课表 ────────────────────────────
    //
    // 用来验「网页端的配置是独立的」：把手机这边的课表换成另一份，
    // 再看网页端会不会被自动同步改掉（**不该**）。
    //
    // 只在本地 mock 上有这个接口；App 里没有，网页端也永远不会调它。
    if (p === '/__mock/state' && req.method === 'GET') {
      return json(res, 200, {
        courses: state.courses.length,
        courseNames: state.courses.map((c) => c.name),
        exams: state.exams.length,
        taskTypes: state.taskTypes.length,
        tasks: state.tasks.length,
      });
    }

    if (p === '/__mock/courses' && req.method === 'POST') {
      const raw = await readBody(req);
      let body = {};
      try { body = JSON.parse(raw.toString('utf8') || '{}'); } catch (_) { /* 坏体当空 */ }
      const keep = Number(body.keep || 0);
      state.courses = state.courses.slice(0, keep).map((c) => ({ ...c, name: `${c.name}（改）` }));
      bump('mock:courses');
      return json(res, 200, { ok: true, courses: state.courses.length });
    }

    if (p === '/api/pair/request' && req.method === 'POST') {
      const raw = await readBody(req);
      let body = {};
      try { body = JSON.parse(raw.toString('utf8') || '{}'); } catch (_) { /* 坏体当空 */ }

      // **公钥必填。** 配对只有「电脑发请求、手机确认」这一条链路，
      // 公钥就是手机当场算出共享密钥所需的全部信息（它本身不是秘密）。
      if (!body.publicKey || typeof body.publicKey !== 'string') {
        return json(res, 400, { error: '网页端版本太旧：配对请求必须带公钥，请重新下载网页端' });
      }
      let peerPub;
      try {
        peerPub = fromBase64Url(body.publicKey);
      } catch (_) {
        return json(res, 400, { error: 'publicKey 不是合法的 base64url' });
      }
      if (peerPub.length !== 256) {
        return json(res, 400, { error: 'publicKey 长度不对' });
      }
      const keypair = dhGenerateKeypair();
      const shared = dhSharedSecret(keypair.priv, peerPub);
      pendingPair = {
        requestId: body.requestId || '',
        priv: keypair.priv,
        pub: keypair.pub,
        secret: shared,
        // 配对码 = H(A‖B)：A = 电脑的公钥（对端），B = mock 自己的公钥。
        // **顺序与手机端一致** —— 反了两端就永远对不上。
        code: pairCodeOf(peerPub, keypair.pub),
        approved: !NO_AUTO_APPROVE,
      };
      keys = deriveKeys(shared, PASSCODE);
      return json(res, 200, {
        state: pendingPair.approved ? 'approved' : 'pending',
        expiresAt: Date.now() + 300000,
        deviceId: pendingPair.approved ? DEVICE_ID : undefined,
      });
    }

    if (p === '/api/pair/status') {
      const requestId = url.searchParams.get('requestId') || '';
      // 回自己的公钥 + 配对码，网页端拿它们算出同一把密钥并核对。
      if (pendingPair && pendingPair.requestId === requestId) {
        const base = {
          publicKey: toBase64Url(pendingPair.pub),
          pairCode: pendingPair.code,
        };
        if (!pendingPair.approved) return json(res, 200, { state: 'pending', ...base });
        return json(res, 200, {
          state: 'approved',
          deviceId: DEVICE_ID,
          deviceName: 'Mock 手机',
          ...base,
        });
      }
      return json(res, 200, { state: 'expired' });
    }

    // 静态文件（不需要 token）
    if (!p.startsWith('/api/')) {
      const rel = p === '/' ? '/index.html' : p;
      const file = path.join(WEB_DIR, path.normalize(rel).replace(/^([/\\])+/, ''));
      if (!file.startsWith(WEB_DIR) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        return res.end('404');
      }
      res.writeHead(200, {
        'Content-Type': MIME[path.extname(file)] || 'application/octet-stream',
        'Cache-Control': 'no-store',
      });
      return fs.createReadStream(file).pipe(res);
    }

    // 以下都要签名；没协商出密钥就先配对
    if (!ensureKeys(res)) return;
    const rawBody = await readBody(req);
    let bodyBytes = new Uint8Array(rawBody);
    const enc = req.headers['x-ml-enc'];
    // 带 body 的签名请求必须是密文，且密文走 HTTP body（不能走请求头）。
    if (rawBody.length > 0 && enc !== '1') {
      return json(res, 401, { error: '请求体必须是密文' });
    }
    if (enc === '1') {
      try {
        bodyBytes = open(keys, new Uint8Array(rawBody));
      } catch (_) {
        return json(res, 401, { error: '解密失败' });
      }
    }
    if (!verifySignature(url, req, bodyBytes)) {
      return json(res, 401, { error: '签名不对' });
    }

    if (p === '/api/snapshot') {
      return encrypted(res, JSON.stringify(snapshot()));
    }

    if (p === '/api/snapshot/apply' && req.method === 'POST') {
      let body = {};
      try { body = JSON.parse(new TextDecoder().decode(bodyBytes) || '{}'); } catch (_) { /* 坏体当空 */ }
      const result = applyRemoteSnapshot(body);
      bump('apply');
      return encrypted(res, JSON.stringify({
        ok: true,
        mode: body.mode === 'replace' ? 'replace' : 'merge',
        applied: { tasks: result.applied.length, courses: 0, exams: 0, taskTypes: 0, tasksRemoved: result.removed },
        appliedUids: result.applied,
        keptUids: result.kept,
        revision,
      }));
    }

    // ── 网页端「从学在浙大拉课表」：手机代跑 ──────────────────────
    //
    // mock 不连浙大，但它得**说得出协议长什么样** —— 网页端那个面板
    // 要靠这两个接口把学期下拉与结果画出来，
    // 而「网页端能不能直连浙大」本来就是这套架构里最容易被误解的一环。
    if (p === '/api/zju/terms') {
      return encrypted(res, JSON.stringify({
        terms: [
          { year: '2026-2027', term: 0, label: '2026-2027 秋冬' },
          { year: '2025-2026', term: 4, label: '2025-2026 春夏' },
        ],
      }));
    }

    if (p === '/api/zju/sync' && req.method === 'POST') {
      let body = {};
      try { body = JSON.parse(new TextDecoder().decode(bodyBytes) || '{}'); } catch (_) { /* 坏体当空 */ }
      // 假装真的去拉了一次：把手机这边的课表与考试当成刚拉回来的。
      bump('mock:zju-sync');
      return encrypted(res, JSON.stringify({
        ok: true,
        courseCount: state.courses.length,
        slotCount: state.courses.length * 2,
        examCount: state.exams.length,
        termLabel: body.year
          ? `${body.year} ${body.term === 4 ? '春夏' : '秋冬'}`
          : '2026-2027 秋冬',
        savedCredentials: body.remember !== false,
        examError: null,
      }));
    }

    if (p === '/api/events') {
      res.writeHead(200, {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-cache',
        Connection: 'keep-alive',
        // **SSE 也要带 CORS 头。** EventSource 是跨源的，
        // 少了 Access-Control-Allow-Origin 浏览器会直接掐掉这条流。
        ...corsHeaders(req),
      });
      res.write(`event: hello\ndata: ${toBase64Url(seal(keys, utf8Bytes(JSON.stringify({ revision, protocol: 3 }))))}\n\n`);
      sseClients.add(res);
      const ka = setInterval(() => res.write(': keep-alive\n\n'), 15000);
      req.on('close', () => { clearInterval(ka); sseClients.delete(res); });
      return;
    }

    return json(res, 404, { error: '没有这个接口' });
  } catch (e) {
    json(res, 500, { error: String(e && e.message ? e.message : e) });
  }
});

server.listen(PORT, HOST, () => {
  const addrs = [];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const ni of list || []) {
      if (ni.family === 'IPv4' && !ni.internal) addrs.push(ni.address);
    }
  }
  console.log(`[miulist-mock] 本机:   http://127.0.0.1:${PORT}/`);
  for (const a of addrs) console.log(`[miulist-mock] 局域网: http://${a}:${PORT}/`);
  console.log(`[miulist-mock] 静态目录: ${WEB_DIR}`);
  console.log(
    NO_AUTO_APPROVE
      ? '[miulist-mock] 不自动批准：网页端会停在「等手机上点允许」，用来拍配对卡'
      : '[miulist-mock] 自动批准：网页端发来带公钥的配对请求就直接完成配对'
  );
});

// ── 局域网发现：应答 UDP 探测包 ──────────────────────────────
//
// 真机上手机会开一个固定端口的 UDP 探测口（见 App 侧 ServiceDiscovery.kt），
// 电脑端的启动器靠它找到「手机在哪」——因为手机的 HTTP 端口是从固定候选
// （38780–38784）里挑的一个，电脑端事先不知道挑中了哪一个。
//
// mock 把这一段也补上，于是它能完整扮演手机：
// 启动器会广播探测包，mock 应答自己的端口，网页端就能连上来。
const DISCOVERY_PORT = 38777;
const PROBE = 'MIULIST-DISCOVER/1';

const udp = dgram.createSocket('udp4');
udp.on('message', (msg, rinfo) => {
  if (msg.toString().trim() !== PROBE) return;
  const payload = JSON.stringify({
    app: 'MiuList',
    protocol: 3,
    port: PORT,
    deviceName: 'Mock 手机',
    appVersion: '0.0.0-mock',
  });
  udp.send(payload, rinfo.port, rinfo.address);
});
udp.on('error', (e) => {
  console.log(`[miulist-mock] 发现口起不来（${e.message}）：电脑端要手填地址`);
});
udp.bind(DISCOVERY_PORT, () => {
  console.log(`[miulist-mock] 发现口: udp ${DISCOVERY_PORT}`);
});
