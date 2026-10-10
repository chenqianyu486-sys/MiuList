package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// =========================================================================
// MiuList Natural Refreshing Palette
// 主题色：青竹绿/抹茶嫩绿 (#BCE472)
// 背景色：米白（温润护眼雅致）
// 点缀色：湖蓝（静谧澄澈点缀）
// 课程可选颜色：8 种粉彩色（红、粉、绿、青、蓝、紫、橙、黄）
// =========================================================================

// 1. 主题色：绿 (#BCE472 - Fresh Bamboo Lime / Matcha Green)
val LimeGreenPrimary = Color(0xFFBCE472)             // 用户指定主题色绿 (#BCE472)
val LimeGreenDark = Color(0xFFA6CD56)                // 稍深绿色调 (#A6CD56)
val LimeGreenContainer = Color(0xFF726D51)           // 茶色背景容器底色 (#2E261F)，绿作前景时使用
val LimeGreenOnContainer = Color(0xFFBCE472)         // 绿作前景高对比色 (#BCE472)
val LimeGreenOnPrimary = Color(0xFF1E2D0E)           // 浅绿底上的高对比墨炭深色文字 (#1E2D0E)

val LinenGreenPrimary = LimeGreenPrimary
val LinenGreenDark = LimeGreenDark
val LinenGreenContainer = LimeGreenContainer
val LinenGreenOnContainer = LimeGreenOnContainer

// 2. 点缀色：湖蓝 (Lake Blue - Tranquil, Serene Alpine Water)
val LakeBlueAccent = Color(0xFF387B9B)               // 湖蓝点缀色 (#387B9B)
val LakeBlueContainer = Color(0xFFE8F2F7)            // 浅湖蓝微容器色 (#E8F2F7)
val LakeBlueOnContainer = Color(0xFF1E526D)          // 湖蓝容器深色文字 (#1E526D)

// 3. 背景色与基底：米白 (Rice White / Cream / Warm Off-White)
val WarmRiceWhite = Color(0xFFFAF8F5)                // 柔和米白背景底色 (#FAF8F5)
val WarmCreamSurface = Color(0xFFFFFFFF)             // 卡片纯白浮层 (#FFFFFF)
val WarmCreamSurfaceVariant = Color(0xFFF3EFE8)      // 微米灰容器/分界底色 (#F3EFE8)
val WarmCreamBorder = Color(0xFFE6E1D6)              // 细腻温润米灰边框线 (#E6E1D6)
val WarmCreamBorderLight = Color(0xFFEDE9E1)         // 浅淡米灰细边框 (#EDE9E1)

// 4. 文字颜色体系 (墨炭深色与温和次级字)
val TextPrimary = Color(0xFF1E2320)                  // 极深炭青/墨炭主要标题与正文 (#1E2320)
val TextSecondary = Color(0xFF4A524D)                // 深石墨次要文字 (#4A524D)
val TextMuted = Color(0xFF78827B)                    // 柔和次级辅助文字 (#78827B)

// 5. 茶色体系 (Tea Color - 沉稳温润深茶色，用于绿作前景时的护眼高对比底色)
// 当抹茶嫩绿 (#BCE472) 作为前景（文字/图标/标号）时，以茶色作底色，对比度 > 11:1，典雅纯粹美观
val TeaColor = Color(0xFF726D51)                     // 柔和低饱和茶色 (#726D51)
val TeaColorDark = Color(0xFF241D17)                 // 深焙茶色 (#241D17)
val TeaColorLight = Color(0xFF3E332B)                // 柔和微浅茶色 (#3E332B)
val TeaColorBorder = Color(0xFF4A3E34)               // 茶色温润细边框 (#4A3E34)

// Helper: 识别颜色是否属于亮色/高明度颜色
fun isLightColor(color: Color): Boolean {
    val r = color.red
    val g = color.green
    val b = color.blue
    val luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b
    return luminance > 0.55f
}

// Helper: 识别是否为绿色系（抹茶绿、粉彩绿、青绿等）
fun isGreenishColor(color: Color): Boolean {
    val r = color.red
    val g = color.green
    val b = color.blue
    return (g > 0.60f && g >= r && g > b) || (g > 0.65f && r > 0.55f && b < 0.60f)
}

// 6. 课程粉彩色系 (8 种粉彩色：红、粉、绿、青、蓝、紫、橙、黄)
val PastelRed = Color(0xFFFCA5A5)                    // 粉彩红 (Pastel Red #FCA5A5)
val PastelPink = Color(0xFFF9A8D4)                   // 粉彩粉 (Pastel Pink #F9A8D4)
val PastelGreen = Color(0xFF86EFAC)                  // 粉彩绿 (Pastel Green #86EFAC)
val PastelCyan = Color(0xFF208E9E)                   // 粉彩青 (Pastel Cyan #208E9E 降亮沉静清雅微调)
val PastelBlue = Color(0xFF93C5FD)                   // 粉彩蓝 (Pastel Blue #93C5FD)
val PastelPurple = Color(0xFFD8B4FE)                 // 粉彩紫 (Pastel Purple #D8B4FE)
val PastelOrange = Color(0xFFFDBA74)                 // 粉彩橙 (Pastel Orange #FDBA74)
val PastelYellow = Color(0xFFFDE047)                 // 粉彩黄 (Pastel Yellow #FDE047)

// 6. 辅助状态颜色
val AlertRed = Color(0xFFEF4444)                     // 紧急截止/告警

// =========================================================================
// Backward Compatibility Mappings
// =========================================================================
val CyanAccent = LinenGreenPrimary
val PaletteCyanPrimary = LinenGreenPrimary
val PaletteCyanVivid = LakeBlueAccent
val PaletteCyanDark = LinenGreenDark
val PaletteCyanContainer = LinenGreenContainer
val PaletteCyanOnContainer = LinenGreenOnContainer

val PaletteWhite = WarmCreamSurface
val PaletteWhiteSoft = WarmRiceWhite
val PaletteWhiteSurface = WarmCreamSurface
val PaletteGrayLight = WarmCreamSurfaceVariant
val PaletteGrayBorder = WarmCreamBorder
val PaletteGrayMuted = TextMuted
val PaletteBlackSecondary = TextSecondary
val PaletteBlackPrimary = TextPrimary
val PaletteBlack = Color(0xFF000000)

val SkyBluePrimary = LinenGreenPrimary
val SkyBluePrimaryContainer = LinenGreenContainer
val SkyBlueOnPrimaryContainer = LinenGreenPrimary
val SkyBlueSecondary = LakeBlueAccent
val SkyBlueSecondaryContainer = LakeBlueContainer
val SkyBlueOnSecondaryContainer = LakeBlueOnContainer
val SkyBlueTertiary = LakeBlueAccent
val SkyBlueTertiaryContainer = LakeBlueContainer
val SkyBlueBackground = WarmRiceWhite
val SkyBlueSurface = WarmCreamSurface
val SkyBlueSurfaceVariant = WarmCreamSurfaceVariant
val SkyBlueOnSurface = TextPrimary
val SkyBlueOnSurfaceVariant = TextSecondary
val SkyBlueOutline = WarmCreamBorder
val SkyBlueOutlineVariant = WarmCreamBorderLight

val SoftBluePrimary = SkyBluePrimary
val SoftBluePrimaryContainer = SkyBluePrimaryContainer
val SoftBlueOnPrimaryContainer = SkyBlueOnPrimaryContainer
val SoftBlueSecondary = SkyBlueSecondary
val SoftBlueSecondaryContainer = SkyBlueSecondaryContainer
val SoftBlueOnSecondaryContainer = SkyBlueOnSecondaryContainer
val SoftBlueTertiary = SkyBlueTertiary
val SoftBlueTertiaryContainer = SkyBlueTertiaryContainer
val SoftBlueBackground = SkyBlueBackground
val SoftBlueSurface = SkyBlueSurface
val SoftBlueSurfaceVariant = SkyBlueSurfaceVariant
val SoftBlueOnSurface = SkyBlueOnSurface
val SoftBlueOnSurfaceVariant = SkyBlueOnSurfaceVariant
val SoftBlueOutline = SkyBlueOutline
val SoftBlueOutlineVariant = SkyBlueOutlineVariant
