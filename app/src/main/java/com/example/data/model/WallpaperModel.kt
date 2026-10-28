package com.example.data.model

import androidx.compose.ui.graphics.Color

/**
 * 壁纸类型
 */
enum class WallpaperType {
    /** 内置默认壁纸 (Cloudy Knoxville) */
    DEFAULT,
    /** 内置精选预设壁纸 */
    PRESET,
    /** 用户从本地相册导入的自定义壁纸 */
    CUSTOM
}

/**
 * 一条线性渐变的完整描述。
 *
 * ## 为什么壁纸是「参数」而不是「图片」
 *
 * 八张预设原本是外部站点（pxlab.cn / 破晓壁纸）的 CSS 线性渐变渲染图，
 * 以 2400×2000 的 PNG 位图形式打进 APK。位图有三个问题：
 *
 * 1. **授权**：那些 PNG 是别人的素材，把它们塞进 APK 随应用分发＝再分发。
 * 2. **体积**：八张位图共 8.3 MB，占了 25.7 MB APK 的三分之一 ——
 *    而它们的内容本来只是几行颜色。
 * 3. **清晰度**：位图放大就糊。
 *
 * 但它们**本来就是渐变**。参数化之后，一张壁纸就是十几行数据：
 * 授权问题归零、包体少三分之一、任意分辨率都不糊。
 *
 * ## 角度沿用 CSS 的约定
 *
 * **0deg = 朝上**，90deg = 朝右，顺时针为正。
 * 这跟数学里「0 度朝右、逆时针为正」相反，弄反了整张图的颜色就换了位置。
 *
 * ## 长度与偏移为什么不是 CSS 那套公式
 *
 * CSS 的渐变线长度是 `|W·sin θ| + |H·cos θ|`，且渐变线必定穿过画布中心。
 * 本地实测：pxlab 自己发的 PNG **并不满足这两条** ——
 * 它标在页面上的 CSS 和它发的图对不上（见 [WallpaperPresets] 的注释）。
 *
 * 所以这里只借 CSS 的「角度」这个说法，长度与偏移都按实测值给：
 *
 * - [lengthRatio]：渐变线长度 ÷ 画布高度。1.0 表示正好等于画布高
 *   （竖直渐变的情形）。竖直渐变的线长与角度无关，所以单列一个。
 * - [offsetRatio]：渐变线的中点（t = 0.5 处）相对画布中心的偏移，
 *   沿渐变方向、以画布高度为单位。换句话说：**渐变线不穿过画布中心**。
 *
 * ## 颜色标的位置
 *
 * [stops] 是 0~1 的比例，0 在渐变线的**起点**、1 在**终点**。
 */
data class WallpaperGradient(
    /** 渐变方向：0deg 朝上，90deg 朝右，顺时针为正。 */
    val angleDeg: Float,
    /** 渐变线长度 ÷ 画布高度。1.0 = 正好等于画布高。 */
    val lengthRatio: Float,
    /** 渐变线中点相对画布中心的偏移（沿方向，画布高度为单位）。 */
    val offsetRatio: Float,
    /** 颜色，按渐变线起点 → 终点排列。 */
    val colors: List<Color>,
    /** 每个颜色的位置（0~1），与 [colors] 一一对应。 */
    val stops: List<Float>,
    /**
     * 渐变被创作时的画布宽高（像素）。
     *
     * 留着它是为了**复现位图版的观感**：位图是按 cover 铺满屏幕的
     * （等比放大铺满、溢出裁掉），横向会被裁掉一截。
     * 少了这两个数，斜向渐变会变成整条色带铺开 —— 明显更饱和、更跳。
     */
    val canvasWidth: Float,
    val canvasHeight: Float
)

/**
 * 预设壁纸元数据
 */
data class PresetWallpaper(
    val id: String,
    val name: String,
    val subtitle: String,
    val gradient: WallpaperGradient
)

/**
 * 官方精选预设壁纸集合
 *
 * ## 这八张的来源
 *
 * 配色取自 [破晓壁纸 (PXLab)](https://pxlab.cn/index.html) 的 CSS 线性渐变合集，
 * 该站以「完全免费复制下载」提供渐变代码。本项目只取了**颜色与角度**，
 * 自己用 [WallpaperGradient] 描述并由 Compose 绘制 ——
 * **不再分发对方的位图**，所以许可与包体两个问题一起解决了。
 *
 * 下面每张都标了原始 CSS，改配色时照着改角度与色标即可。
 *
 * ## 副标题是**按实际像素写**的
 *
 * 八张都是**竖向渐变**（不是纯色块），副标题必须说得出「从上到下是什么色」，
 * 只报一个颜色就等于漏掉一半信息。
 *
 * 这张表是每张图上 / 中 / 下三段取样带的实测值
 * （源图 2400×2000，24% 带平均，HSV 的 H/S/V）：
 *
 * - 灰蓝、青黛这类词是**有彩度**的（`青黛` 至少 s≈0.3），
 *   拿它去说一张 s≤0.01 的近白图就是直接说错。
 * - 「深邃 / 暗彩」只能给真的暗下去的那张，不能给一张通体浅粉。
 *
 * 改副标题前先取一次色，别凭名字和印象写。
 *
 * | 预设 | 站上标的 CSS | 实测方向 | 上 | 中 | 下 | 旧副标题错在哪 |
 * | --- | --- | --- | --- | --- | --- | --- |
 * | Cloudy Knoxville | `120deg, #fdfbfb 0%, #ebedee 100%` | 113.0° | #F5F5F6 s.00 | #F3F4F4 s.00 | #F1F2F3 s.01 | 写「灰蓝」，实测近乎无彩 |
 * | Kind Steel | `-20deg, #e9defa 0%, #fbfcdb 100%` | 320.5° | #F5F3E3 暖奶黄 | #F2EDEA | #EEE6F1 藕紫 | 写「冷灰」，顶是暖奶黄 |
 * | Soft Grass | `to top, #c1dfc4 0%, #deecdd 100%` | 0° | #DAEAD9 浅绿 | #CFE5D0 | #C4E0C7 | ✓ 只是要说明「浅」 |
 * | Rare Wind | `to top, #a8edea 0%, #fed6e3 100%` | 180° | #B2EAE9 青 | #D2E1E6 | #F3D8E3 粉 | 只说了顶部的青 |
 * | Teen Notebook | `to top, #9795f0 0%, #fbc8d4 100%` | 0° | #EEC1D7 粉 | #C9AEE1 紫 | #A39BEC 蓝紫 | 写「暖调」，底是冷蓝紫 |
 * | Sweet Period | `to top, #3f51b1 0% … #f7c978 100%` | 0° | #F2A070 橙 | #A36AA5 紫 | #5755AE 深靛 | 写「温柔」，实为高饱和 |
 * | Nega | `45deg, #ee9ca7 0%, #ffdde1 100%` | 37.5° | #F8C5CC 浅玫红 | #F4B6BE | #F1A8B2 | 写「深邃暗彩」，实为通体暖粉 |
 * | Salt Mountain | `-225deg, #FFFEFF 0%, #D7FFFE 100%` | 130.4° | #F1FEFE s.05 | #EAFEFE | #E4FEFE 淡青 | 写「青黛」，实为近白淡青 |
 *
 * ## 「实测方向」为什么和站上标的不一样
 *
 * 站上标的那条 CSS 和它自己发的 PNG **对不上**，而且是系统性的：
 *
 * - `Rare Wind` 标 `to top`（0% 在下），PNG 里 0% 的青色却在**上面** ——
 *   方向整个反了（实测 180°）；
 * - `Nega` 标 `45deg`，PNG 的实际方向是 37.5°；
 * - `Sweet Period` 标了 9 个色标的位置，PNG 里那 9 个位置也不是标的那些；
 * - 而且它 PNG 的渐变线**不穿过画布中心**（CSS 规范要求穿过）。
 *
 * 也就是说它用的是自己的一套几何，不是 CSS 规范那套。
 * 站上的标注自相矛盾时，**以实际发出去的那张图为准** —— 那才是用户手机上看到的东西。
 *
 * 所以下面每张的角度 / 长度 / 偏移都是从原图反解出来的，颜色则用站上发布的原色、一字不改。
 * 反解结果与原图在 5 种屏幕尺寸上逐点比对，**最大色差 ≤ 1.6/255**（位图自身的抖动噪声量级），
 * 等于逐像素复刻 —— 换成渐变后用户看不出任何区别。
 *
 * 要改配色就改 `Color(0x…)` 与色标；要改走向就改 `angleDeg`。
 */
object WallpaperPresets {
    /** 渐变被创作时的画布尺寸（破晓壁纸给的原图尺寸）。 */
    private const val CANVAS_W = 2400f
    private const val CANVAS_H = 2000f

    /** 破晓壁纸那一张的画布略高（源图 2400×2040）。 */
    private const val CANVAS_H_SALT = 2040f

    private fun gradient(
        angleDeg: Float,
        lengthRatio: Float,
        offsetRatio: Float,
        vararg stops: Pair<Float, Color>,
        canvasWidth: Float = CANVAS_W,
        canvasHeight: Float = CANVAS_H
    ) = WallpaperGradient(
        angleDeg = angleDeg,
        lengthRatio = lengthRatio,
        offsetRatio = offsetRatio,
        colors = stops.map { it.second },
        stops = stops.map { it.first },
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight
    )

    val CLOUDY_KNOXVILLE = PresetWallpaper(
        id = "cloudy_knoxville",
        name = "Cloudy Knoxville",
        // s ≤ 0.008 = 几乎无彩，色相还在 180~240 之间跳（那是取样噪声，不是真的蓝）。
        // 旧文案「极简云烟灰蓝」里的「灰蓝」是明显偏蓝的，说它等于说这张是蓝的。
        subtitle = "极简云烟近乎纯白（默认）",
        gradient = gradient(
            113.02f, 1.3837f, -0.0424f,
            0f to Color(0xFFFDFBFB), 1f to Color(0xFFEBEDEE)
        )
    )

    val KIND_STEEL = PresetWallpaper(
        id = "kind_steel",
        name = "Kind Steel",
        // 顶 #F5F3E3 是暖奶黄，不是冷灰；底 #EEE6F1 是藕紫。
        subtitle = "奶油暖黄渐染藕紫",
        gradient = gradient(
            320.54f, 1.3129f, -0.0009f,
            0f to Color(0xFFE9DEFA), 1f to Color(0xFFFBFCDB)
        )
    )

    val SOFT_GRASS = PresetWallpaper(
        id = "soft_grass",
        name = "Soft Grass",
        // 整张是同一个薄荷绿（h≈120），只是往下深了一点。
        subtitle = "清新草木浅淡绿意",
        gradient = gradient(
            0.06f, 0.9998f, -0.0001f,
            0f to Color(0xFFC1DFC4), 1f to Color(0xFFDEECDD)
        )
    )

    val RARE_WIND = PresetWallpaper(
        id = "rare_wind",
        name = "Rare Wind",
        // 顶 #B2EAE9 是青，底 #F3D8E3 是粉。旧文案「空灵青蓝」只说了顶部那一条。
        // 方向是 180°（朝下）而不是站上标的 `to top` —— 见类注释里那段「实测方向」。
        subtitle = "青蓝风息渐染浅粉",
        gradient = gradient(
            179.98f, 1.0f, 0.0001f,
            0f to Color(0xFFA8EDEA), 1f to Color(0xFFFED6E3)
        )
    )

    val TEEN_NOTEBOOK = PresetWallpaper(
        id = "teen_notebook",
        name = "Teen Notebook",
        // 顶粉、中紫、底蓝紫（#A39BEC）—— 底是冷的，「暖调粉彩」说反了。
        subtitle = "手账粉紫渐变蓝调",
        gradient = gradient(
            0f, 0.9999f, -0.0003f,
            0f to Color(0xFF9795F0), 1f to Color(0xFFFBC8D4)
        )
    )

    val SWEET_PERIOD = PresetWallpaper(
        id = "sweet_period",
        name = "Sweet Period",
        // 顶橙、中紫、底深靛，s 高达 0.54：这是一张饱和的落日，不是「温柔梦幻」。
        // 九个色标，是八张里唯一一条多段的 —— 少一个色标，中间那段紫就断了。
        // 色标位置也是实测的（站上标的 0/13%/25%… 与 PNG 里的真实位置对不上）。
        subtitle = "暖橙沉入深靛蓝调",
        gradient = gradient(
            0f, 1.002f, -0.0013f,
            0f to Color(0xFF3F51B1),
            0.1528f to Color(0xFF5A55AE),
            0.271f to Color(0xFF7B5FAC),
            0.3978f to Color(0xFF8F6AAE),
            0.5326f to Color(0xFFA86AA4),
            0.6789f to Color(0xFFCC6B8E),
            0.7911f to Color(0xFFF18271),
            0.8843f to Color(0xFFF3A469),
            1f to Color(0xFFF7C978)
        )
    )

    val NEGA = PresetWallpaper(
        id = "nega",
        name = "Nega",
        // 通体浅玫红（#F8C5CC → #F1A8B2），v 高达 0.95~0.97。
        // 旧文案「深邃底色暗彩光泽」说的正相反，是八张里错得最彻底的一条。
        subtitle = "柔粉一片暖意渐深",
        gradient = gradient(
            37.46f, 1.2929f, 0.115f,
            0f to Color(0xFFEE9CA7), 1f to Color(0xFFFFDDE1)
        )
    )

    val SALT_MOUNTAIN = PresetWallpaper(
        id = "salt_mountain",
        name = "Salt Mountain",
        // 八张里最白的一张：顶 #F1FEFE，底 #E2FEFE 只是极淡的青。
        // 「青黛」是深蓝灰，和这张差了两个明度档。
        subtitle = "盐山素白一抹淡青",
        gradient = gradient(
            130.36f, 1.5446f, 0.0004f,
            0f to Color(0xFFFFFEFF), 1f to Color(0xFFD7FFFE),
            canvasHeight = CANVAS_H_SALT
        )
    )

    val DEFAULT: PresetWallpaper = CLOUDY_KNOXVILLE

    val ALL: List<PresetWallpaper> = listOf(
        CLOUDY_KNOXVILLE,
        KIND_STEEL,
        SOFT_GRASS,
        RARE_WIND,
        TEEN_NOTEBOOK,
        SWEET_PERIOD,
        NEGA,
        SALT_MOUNTAIN
    )

    fun find(id: String): PresetWallpaper? = ALL.find { it.id == id }

    /** 找不到就回默认那张 —— 预设 id 存进 SharedPreferences，版本升级后可能对不上。 */
    fun getGradientOrDefault(id: String): WallpaperGradient =
        find(id)?.gradient ?: DEFAULT.gradient
}

/**
 * 壁纸当前配置状态
 *
 * @param type 壁纸类型：默认 / 预设 / 自定义相册
 * @param presetId 若为预设，对应的预设标识符
 * @param customPath 若为自定义相册，复制到应用内部私有存储的文件绝对路径
 * @param dimOpacity 可选的浅色保护遮罩（0f ~ 0.5f），保障较深或对比度过高的壁纸上的文字可读性
 * @param softFocus 背景柔焦（0f ~ 1f），只对自定义照片生效；用户可自己调，
 *   0 = 原图清晰，1 = 最柔。它不是深色模式，只是把照片细节退成颜色。
 */
data class WallpaperConfig(
    val type: WallpaperType = WallpaperType.DEFAULT,
    val presetId: String = WallpaperPresets.DEFAULT.id,
    val customPath: String? = null,
    val dimOpacity: Float = 0f,
    val softFocus: Float = 0f
)
