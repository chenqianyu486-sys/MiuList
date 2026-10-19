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
 */
object WallpaperPresets {
    val CLOUDY_KNOXVILLE = PresetWallpaper(
        id = "cloudy_knoxville",
        name = "Cloudy Knoxville",
        subtitle = "极简云烟灰蓝（默认）",
        resId = R.drawable.wp_default_cloudy_knoxville
    )

    val KIND_STEEL = PresetWallpaper(
        id = "kind_steel",
        name = "Kind Steel",
        subtitle = "极简冷灰柔调粉彩",
        resId = R.drawable.wp_default_kind_steel
    )

    val SOFT_GRASS = PresetWallpaper(
        id = "soft_grass",
        name = "Soft Grass",
        subtitle = "清新草木自然绿意",
        resId = R.drawable.wp_preset_soft_grass
    )

    val RARE_WIND = PresetWallpaper(
        id = "rare_wind",
        name = "Rare Wind",
        subtitle = "澄澈风息空灵青蓝",
        resId = R.drawable.wp_preset_rare_wind
    )

    val TEEN_NOTEBOOK = PresetWallpaper(
        id = "teen_notebook",
        name = "Teen Notebook",
        subtitle = "青涩手账暖调粉彩",
        resId = R.drawable.wp_preset_teen_notebook
    )

    val SWEET_PERIOD = PresetWallpaper(
        id = "sweet_period",
        name = "Sweet Period",
        subtitle = "甜蜜花期温柔梦幻",
        resId = R.drawable.wp_preset_sweet_period
    )

    val NEGA = PresetWallpaper(
        id = "nega",
        name = "Nega",
        subtitle = "深邃底色暗彩光泽",
        resId = R.drawable.wp_preset_nega
    )

    val SALT_MOUNTAIN = PresetWallpaper(
        id = "salt_mountain",
        name = "Salt Mountain",
        subtitle = "盐山苍茫青黛旷远",
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
