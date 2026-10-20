package com.example.data.model

import androidx.annotation.DrawableRes
import com.example.R

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
 * 预设壁纸元数据
 */
data class PresetWallpaper(
    val id: String,
    val name: String,
    val subtitle: String,
    @DrawableRes val resId: Int
)

/**
 * 官方精选预设壁纸集合
 *
 * ## 副标题是**按实际像素写**的
 *
 * 八张都是**竖向渐变**（不是纯色块），副标题必须说得出「从上到下是什么色」，
 * 只报一个颜色就等于漏掉一半信息。
 *
 * 下面这张表是每张图上 / 中 / 下三段取样带的实测值
 * （源图 2400×2000，24% 带平均，HSV 的 H/S/V）：
 *
 * - 灰蓝、青黛这类词是**有彩度**的（`青黛` 至少 s≈0.3），
 *   拿它去说一张 s≤0.01 的近白图就是直接说错。
 * - 「深邃 / 暗彩」只能给真的暗下去的那张，不能给一张通体浅粉。
 *
 * 改副标题前先取一次色，别凭名字和印象写：
 * `PIL` 读 `D:\MiuList\*.png` 原图分三段取平均即可。
 *
 * | 预设 | 上 | 中 | 下 | 旧副标题错在哪 |
 * | --- | --- | --- | --- | --- |
 * | Cloudy Knoxville | #F5F5F6 s.00 | #F3F4F4 s.00 | #F1F2F3 s.01 | 写「灰蓝」，实测近乎无彩 |
 * | Kind Steel | #F5F3E3 暖奶黄 | #F2EDEA | #EEE6F1 藕紫 | 写「冷灰」，顶是暖奶黄 |
 * | Soft Grass | #DAEAD9 浅绿 | #CFE5D0 | #C4E0C7 | ✓ 只是要说明「浅」 |
 * | Rare Wind | #B2EAE9 青 | #D2E1E6 | #F3D8E3 粉 | 只说了顶部的青 |
 * | Teen Notebook | #EEC1D7 粉 | #C9AEE1 紫 | #A39BEC 蓝紫 | 写「暖调」，底是冷蓝紫 |
 * | Sweet Period | #F2A070 橙 | #A36AA5 紫 | #5755AE 深靛 | 写「温柔」，实为高饱和 |
 * | Nega | #F8C5CC 浅玫红 | #F4B6BE | #F1A8B2 | 写「深邃暗彩」，实为通体暖粉 |
 * | Salt Mountain | #F1FEFE s.05 | #EAFEFE | #E4FEFE 淡青 | 写「青黛」，实为近白淡青 |
 */
object WallpaperPresets {
    val CLOUDY_KNOXVILLE = PresetWallpaper(
        id = "cloudy_knoxville",
        name = "Cloudy Knoxville",
        // s ≤ 0.008 = 几乎无彩，色相还在 180~240 之间跳（那是取样噪声，不是真的蓝）。
        // 旧文案「极简云烟灰蓝」里的「灰蓝」是明显偏蓝的，说它等于说这张是蓝的。
        subtitle = "极简云烟近乎纯白（默认）",
        resId = R.drawable.wp_default_cloudy_knoxville
    )

    val KIND_STEEL = PresetWallpaper(
        id = "kind_steel",
        name = "Kind Steel",
        // 顶 #F5F3E3 是暖奶黄，不是冷灰；底 #EEE6F1 是藕紫。
        subtitle = "奶油暖黄渐染藕紫",
        resId = R.drawable.wp_default_kind_steel
    )

    val SOFT_GRASS = PresetWallpaper(
        id = "soft_grass",
        name = "Soft Grass",
        // 整张是同一个薄荷绿（h≈120），只是往下深了一点。
        subtitle = "清新草木浅淡绿意",
        resId = R.drawable.wp_preset_soft_grass
    )

    val RARE_WIND = PresetWallpaper(
        id = "rare_wind",
        name = "Rare Wind",
        // 顶 #B2EAE9 是青，底 #F3D8E3 是粉。旧文案「空灵青蓝」只说了顶部那一条。
        subtitle = "青蓝风息渐染浅粉",
        resId = R.drawable.wp_preset_rare_wind
    )

    val TEEN_NOTEBOOK = PresetWallpaper(
        id = "teen_notebook",
        name = "Teen Notebook",
        // 顶粉、中紫、底蓝紫（#A39BEC）—— 底是冷的，「暖调粉彩」说反了。
        subtitle = "手账粉紫渐变蓝调",
        resId = R.drawable.wp_preset_teen_notebook
    )

    val SWEET_PERIOD = PresetWallpaper(
        id = "sweet_period",
        name = "Sweet Period",
        // 顶橙、中紫、底深靛，s 高达 0.54：这是一张饱和的落日，不是「温柔梦幻」。
        subtitle = "暖橙沉入深靛蓝调",
        resId = R.drawable.wp_preset_sweet_period
    )

    val NEGA = PresetWallpaper(
        id = "nega",
        name = "Nega",
        // 通体浅玫红（#F8C5CC → #F1A8B2），v 高达 0.95~0.97。
        // 旧文案「深邃底色暗彩光泽」说的正相反，是八张里错得最彻底的一条。
        subtitle = "柔粉一片暖意渐深",
        resId = R.drawable.wp_preset_nega
    )

    val SALT_MOUNTAIN = PresetWallpaper(
        id = "salt_mountain",
        name = "Salt Mountain",
        // 八张里最白的一张：顶 #F1FEFE，底 #E2FEFE 只是极淡的青。
        // 「青黛」是深蓝灰，和这张差了两个明度档。
        subtitle = "盐山素白一抹淡青",
        resId = R.drawable.wp_preset_salt_mountain
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

    fun getResOrDefault(id: String): Int = find(id)?.resId ?: DEFAULT.resId
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
