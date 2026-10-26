/**
 * 课程色染色 —— 与手机端**同一套算法**的 TypeScript 移植。
 *
 * 手机端的实现在 `app/src/main/java/com/example/ui/theme/Color.kt`。
 * 网页端原先用的是 `hexToRgba(课程色, 0.16)`：把原色薄薄叠一层。
 * 两端看上去不一样，不是「网页端配色随便」，而是**算法不同**：
 *
 *  - **直接叠原色** → 八门课的卡面深浅不齐。黄 `#FDE047` 叠出来很重，
 *    青 `#208E9E` 叠出来很淡；扫列表时重的课比轻的课更抢眼，
 *    而这门课并没有更重要。同一个 alpha 落在不同色相上也不是同一件事：
 *    同一明度下能承载的彩度上限是 `1-|2L-1|`，于是黄被洗成近白、蓝还留着重色，
 *    一屏卡片像出自两套配色。
 *
 *  - **反解**（手机端的做法）→ 先定「卡面最终要长成什么样」（固定的感知明度与彩度），
 *    再按给定的 alpha 反解出「要叠哪支颜色」：
 *
 *        final = α·source + (1-α)·backing
 *        ⇒ source = (final - (1-α)·backing) / α
 *
 *    解不出来的部分（彩度超出 sRGB 色域）二分降彩度 —— 降彩度保色相，
 *    逐通道 clamp 会让颜色偏色相。
 *
 *    于是八门课都是「同一明度、同一彩度上限内的最饱和色」：色相各不相同，重量一致。
 *
 * 两端共用这一份「课程色的身份」，网页端的卡片才会和手机端读起来是同一套设计。
 */

/** 卡面最终落点：OKLab 明度。八门课共用，这是「等重」的唯一来源。 */
const CARD_TARGET_L = 0.905;

/**
 * 课程色的染色强度（对应手机端 `CourseTintAlpha`）。
 *
 * 它同时是 [cardTint] 的**反解输入**：传给卡面 CSS 的 alpha 必须和这里一致，
 * 两者对不上，解出来的颜色就不是想要的那个颜色。
 *
 * 调这一档时先想清楚要的是浓还是淡：0.30 之下，卡面主要靠色相认课；
 * 再往上颜色会压过「清新」，而深色文字的对比度会跟着掉。
 */
export const COURSE_TINT_ALPHA = 0.3;

/** 卡面最终落点：OKLab 彩度。压得很低 —— 卡面要的是暗示色，不是色块。 */
const CARD_TARGET_C = 0.085;

/** 课程卡左侧竖条的落点明度：卡面的「加浓档」，比卡面低一档就该看得清。 */
const ACCENT_BAR_L = 0.7;

/** 竖条的彩度：比卡面高一档，让它是识别线索而不是装饰线。 */
const ACCENT_BAR_C = 0.14;

/**
 * 暖色相（橙→黄）竖条的落点明度，比其余色相高一大截。
 *
 * 原因是黄和橙在低明度下会掉进橄榄/棕褐的死区：同一个 OKLab 明度、同一份彩度请求，
 * 八个色相解出来的结果差别很大 —— 红是红的、黄是土的。**所以明度不能一刀切**。
 */
const ACCENT_BAR_L_WARM = 0.8;

/** 卡面在暖色相上的明度补偿。卡面本来就浅，补偿只需一小档。 */
const CARD_WARM_LIFT = 0.018;

/** 卡面在暖色相上的彩度补偿（往白里减一点彩度）。 */
const CARD_WARM_C_DROP = 0.045;

/** 暖色相带：橙→黄（色相 40°~115°）。 */
const WARM_HUE_LO = 40;
const WARM_HUE_HI = 115;

/** 暖色相带上侧收口：绿（实测 154°）不能被算进暖色，否则它会跟着一起变亮。 */
const WARM_HUE_OUT_HI = 150;

/**
 * 近中性色的彩度阈值：低于它就不参与任何按彩度反解的重建。
 *
 * 反解是「按目标彩度解出源色」，对本来就没有彩度的颜色执行它等于凭空造一个色相：
 * 待办卡里「不关联课程」那一档的 `#C9D0D8` 看着是灰，实际 b > r（彩度约 0.014），
 * 反解后会变成一整块饱和蓝，认起来像有关联课程。0.03 高于任何一支真·粉彩（最低约 0.08）。
 */
const NEUTRAL_CHROMA = 0.03;

/**
 * 染色下面的衬底，用于反解时的底。
 *
 * 真底 = 白衬底叠壁纸，壁纸会变。真底一变卡面就变，而那正是要消除的东西 ——
 * 所以这里**按最坏情况取一个偏暖的近白**当定值。
 */
const CARD_TINT_BACKING = { r: 0xf6 / 255, g: 0xf3 / 255, b: 0xef / 255 };

/** 绿系色相下界：45°。黄（50°）在内、橙（31°）在外。 */
const GREENISH_HUE_MIN = 45;

/** 绿系色相上界：180°。绿（142°）在内、青（188°）在外。 */
const GREENISH_HUE_MAX = 180;

type Rgb = { r: number; g: number; b: number };
type Oklab = { l: number; a: number; b: number };

function clamp01(v: number): number {
  return v < 0 ? 0 : v > 1 ? 1 : v;
}

/** 解析 `#rgb` / `#rrggbb`。认不出来返回 null（调用方据此走「原色」分支）。 */
export function parseHex(hex: string | undefined | null): Rgb | null {
  const raw = String(hex || '').replace('#', '').trim();
  if (raw.length === 3 && /^[0-9a-fA-F]{3}$/.test(raw)) {
    const r = raw[0];
    const g = raw[1];
    const b = raw[2];
    return {
      r: parseInt(r + r, 16) / 255,
      g: parseInt(g + g, 16) / 255,
      b: parseInt(b + b, 16) / 255,
    };
  }
  if (raw.length !== 6 || /[^0-9a-fA-F]/.test(raw)) return null;
  return {
    r: parseInt(raw.slice(0, 2), 16) / 255,
    g: parseInt(raw.slice(2, 4), 16) / 255,
    b: parseInt(raw.slice(4, 6), 16) / 255,
  };
}

function toHex(rgb: Rgb): string {
  const part = (v: number) =>
    Math.round(clamp01(v) * 255)
      .toString(16)
      .padStart(2, '0');
  return `#${part(rgb.r)}${part(rgb.g)}${part(rgb.b)}`;
}

/** 不带透明的 `rgba(...)` 字符串。 */
export function rgba(hex: string, alpha: number): string {
  const rgb = parseHex(hex);
  if (!rgb) return `rgba(201, 208, 216, ${alpha})`;
  return `rgba(${Math.round(rgb.r * 255)}, ${Math.round(rgb.g * 255)}, ${Math.round(rgb.b * 255)}, ${alpha})`;
}

/* ── OKLab / OKLCh ────────────────────────────────────────────── */

function linearize(c: number): number {
  return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

function encodeSrgb(c: number): number {
  return c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
}

/** sRGB（0..1）→ OKLab。矩阵取 Björn Ottosson 的标准值。 */
function toOklab(rgb: Rgb): Oklab {
  const r = linearize(rgb.r);
  const g = linearize(rgb.g);
  const b = linearize(rgb.b);
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return {
    l: 0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
    a: 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    b: 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
  };
}

/** OKLCh → 线性 sRGB（未做 gamma 编码，供色域判定用）。 */
function linearRgbFromOklch(l: number, c: number, h: number): Rgb {
  const a = c * Math.cos(h);
  const bb = c * Math.sin(h);
  const l3 = (l + 0.3963377774 * a + 0.2158037573 * bb) ** 3;
  const m3 = (l - 0.1055613458 * a - 0.0638541728 * bb) ** 3;
  const s3 = (l - 0.0894841775 * a - 1.291485548 * bb) ** 3;
  return {
    r: 4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3,
    g: -1.2684380046 * l3 + 2.6097574012 * m3 - 0.3413193965 * s3,
    b: -0.0041960863 * l3 - 0.7034186147 * m3 + 1.707614701 * s3,
  };
}

function inGamut(l: number, c: number, h: number): boolean {
  const { r, g, b } = linearRgbFromOklch(l, c, h);
  return (
    r >= -0.0008 && r <= 1.0008 && g >= -0.0008 && g <= 1.0008 && b >= -0.0008 && b <= 1.0008
  );
}

/**
 * OKLCh → sRGB，**超出色域就二分降彩度**而不是直接截断。
 *
 * 逐通道 clamp 会让颜色偏色相：请求的蓝紫会被拉成偏红的紫；降彩度则保持色相不变、
 * 只少一点鲜艳度 —— 而彩度本来就是「少一点更好」的那个量。
 */
function oklchToColor(l: number, c: number, h: number): Rgb {
  let lo = 0;
  let hi = c;
  if (inGamut(l, c, h)) {
    lo = c;
  } else {
    for (let i = 0; i < 14; i++) {
      const mid = (lo + hi) / 2;
      if (inGamut(l, mid, h)) lo = mid;
      else hi = mid;
    }
  }
  const { r, g, b } = linearRgbFromOklch(l, lo, h);
  return {
    r: encodeSrgb(clamp01(r)),
    g: encodeSrgb(clamp01(g)),
    b: encodeSrgb(clamp01(b)),
  };
}

/**
 * 取色相（度）。**近中性色返回 null** —— 它没有色相可谈，理由见 [NEUTRAL_CHROMA]。
 */
function hueDegreesOf(rgb: Rgb): number | null {
  const src = toOklab(rgb);
  if (Math.hypot(src.a, src.b) < NEUTRAL_CHROMA) return null;
  return (Math.atan2(src.b, src.a) * 180) / Math.PI;
}

/**
 * 暖色相的抬亮强度，0 = 不抬，1 = 全额抬。**是一个窗口，不是一条半轴**。
 *
 * 形状：40°→75° 线性爬升，75°~115° 满额，115°→150° 线性回落，之后归零。
 * 实测色相（OKLCh 度）：红 19.6 / 粉 -14 / 绿 154.4 / 青 -150 / 蓝 -108 /
 * 紫 -54 / 橙 66.3 / 黄 98.1 —— 窗口正好罩住橙黄两支，其余六支拿 0。
 */
function warmLift(hueDeg: number): number {
  if (hueDeg <= WARM_HUE_LO) return 0;
  if (hueDeg >= WARM_HUE_OUT_HI) return 0;
  if (hueDeg < 75) return (hueDeg - WARM_HUE_LO) / (75 - WARM_HUE_LO);
  if (hueDeg <= WARM_HUE_HI) return 1;
  return (WARM_HUE_OUT_HI - hueDeg) / (WARM_HUE_OUT_HI - WARM_HUE_HI);
}

/* ── 对外接口 ────────────────────────────────────────────────── */

/**
 * 课程色叠到卡片上时要用的那一支**实色**。
 *
 * @param alpha 叠上去的不透明度，和调用处传给卡面 CSS 的 alpha **必须是同一个值** ——
 *   这里是按这个 alpha 反解的，两者对不上，解出来的颜色就不是想要的那个颜色。
 * @return 叠上去之后卡面落在 [CARD_TARGET_L] / [CARD_TARGET_C] 那一档的颜色。
 *   近灰（无色相可解）原样返回。
 */
export function cardTint(courseColor: string, alpha: number): string {
  const rgb = parseHex(courseColor);
  if (!rgb) return courseColor;
  const a = Math.min(1, Math.max(0.02, alpha));
  const hueDeg = hueDegreesOf(rgb);
  if (hueDeg === null) return courseColor;

  const back = toOklab(CARD_TINT_BACKING);
  const backChroma = Math.hypot(back.a, back.b);

  // 暖色相（橙/黄）补偿：卡面已经够浅，所以只抬一小档明度、并减一点彩度。
  const lift = warmLift(hueDeg);
  const targetL = CARD_TARGET_L + lift * CARD_WARM_LIFT;
  const targetC = Math.max(0, CARD_TARGET_C - lift * CARD_WARM_C_DROP);

  const needL = Math.min(1, Math.max(0, (targetL - (1 - a) * back.l) / a));
  const needC = Math.max(0, (targetC - (1 - a) * backChroma) / a);
  return toHex(oklchToColor(needL, needC, (hueDeg * Math.PI) / 180));
}

/**
 * 卡面染色层的 CSS 值：`rgba(反解出来的那支颜色, alpha)`。
 *
 * 直接给 rgba 而不是实色，是因为卡面是**半透的**：白衬底和壁纸要从底下透上来，
 * 它是「染色」而不是实色块。
 */
export function courseFace(courseColor: string, alpha: number): string {
  return rgba(cardTint(courseColor, alpha), alpha);
}

/**
 * 课程卡左侧那根圆角竖条的颜色。
 *
 * 它和卡面走同一个色相、同一套等重建：卡面固定落在 L 0.905，竖条固定落在 L 0.70，
 * 暖色相额外往上抬一档（不然黄色会变成土黄）。
 *
 * 直接画课程原色的话，八门课的竖条**轻重差一大截**：调色板里 `#FDE047` 的明度约 0.86，
 * 压在同样是黄的卡面上几乎看不见；`#208E9E` 约 0.55，就是一道压得很重的深条。
 * 同一条竖条在两个屏幕上一个消失、一个抢眼 —— 那不是设计选择。
 */
export function accentBarColor(courseColor: string): string {
  const rgb = parseHex(courseColor);
  if (!rgb) return courseColor;
  const hueDeg = hueDegreesOf(rgb);
  if (hueDeg === null) return courseColor;
  const lift = warmLift(hueDeg);
  return toHex(
    oklchToColor(
      ACCENT_BAR_L + lift * (ACCENT_BAR_L_WARM - ACCENT_BAR_L),
      ACCENT_BAR_C,
      (hueDeg * Math.PI) / 180,
    ),
  );
}

/** 把颜色朝墨炭压暗（保留色相），得到一版「能当前景用」的颜色。 */
export function readableOn(color: string): string {
  const rgb = parseHex(color);
  if (!rgb) return color;
  const ink = { r: 0x1e / 255, g: 0x23 / 255, b: 0x20 / 255 };
  const lerp = (s: number, t: number) => s + (t - s) * 0.55;
  return toHex({
    r: lerp(rgb.r, ink.r),
    g: lerp(rgb.g, ink.g),
    b: lerp(rgb.b, ink.b),
  });
}

/**
 * 染色玻璃面上的「强调前景」—— 时间图标、+ 添加、备注、编辑按钮都用它。
 *
 * 主题绿 `#BCE472` 压在染色面上只有 1.15:1，等于隐形。处理方式和普通前景一样：压暗。
 * 绿色课沿用深抹茶绿（保住「绿色课就是绿」的身份），其余走 [readableOn]，色相还在。
 */
export function accentOnCourse(color: string): string {
  return isGreenishColor(color) ? '#3F6B1C' : readableOn(color);
}

/**
 * 识别颜色是否属于绿色系（抹茶绿、粉彩绿、黄绿等）。
 *
 * **判据是 HSL 色相区间，不是逐通道阈值。** 逐通道阈值只能问「亮不亮」这类标量属性，
 * 问不了「是什么色相」—— r 高 g 中 b 低，既可能是黄也可能是橙，阈值分不开。
 * 45°~180° 覆盖黄(50°)→黄绿→绿(142°)，排除橙(31°)和青(188°)。
 */
export function isGreenishColor(color: string): boolean {
  const rgb = parseHex(color);
  if (!rgb) return false;
  const maxC = Math.max(rgb.r, rgb.g, rgb.b);
  const minC = Math.min(rgb.r, rgb.g, rgb.b);
  if (maxC === minC) return false;
  const d = maxC - minC;
  const raw =
    maxC === rgb.r
      ? (rgb.g - rgb.b) / d + (rgb.g < rgb.b ? 6 : 0)
      : maxC === rgb.g
        ? (rgb.b - rgb.r) / d + 2
        : (rgb.r - rgb.g) / d + 4;
  const h = (((raw / 6) % 1) + 1) % 1;
  const deg = h * 360;
  return deg >= GREENISH_HUE_MIN && deg <= GREENISH_HUE_MAX;
}
