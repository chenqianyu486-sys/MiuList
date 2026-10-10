package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// MiuList Theme: 抹茶嫩绿 #BCE472（主题色）+ 米白（背景色）+ 浓茶色 #2E261F（绿作前景时的底色）+ 湖蓝（点缀色）
private val LightColorScheme = lightColorScheme(
    primary = LimeGreenPrimary,                          // 主题色：#BCE472
    onPrimary = LimeGreenOnPrimary,                      // 深色文字 (#1E2D0E)，确保浅绿底色上拥有极高清晰度与对比度
    primaryContainer = LimeGreenContainer,               // 茶色底色 (#2E261F)，绿作前景时的背景容器
    onPrimaryContainer = LimeGreenOnContainer,           // 绿作前景 (#BCE472)，在茶色底上拥有 >11:1 的极高清晰对比度！

    secondary = LakeBlueAccent,                           // 点缀色：湖蓝 (#387B9B)
    onSecondary = Color.White,                            // 白色 (#FFFFFF)
    secondaryContainer = LakeBlueContainer,               // 浅湖蓝容器底色 (#E8F2F7)
    onSecondaryContainer = LakeBlueOnContainer,           // 湖蓝深色文字 (#1E526D)

    tertiary = LakeBlueAccent,                            // 点缀色：湖蓝
    onTertiary = Color.White,
    tertiaryContainer = LakeBlueContainer,
    onTertiaryContainer = LakeBlueOnContainer,

    background = WarmRiceWhite,                           // 背景色：米白 (#FAF8F5)
    onBackground = TextPrimary,                           // 墨炭深色标题正文 (#1E2320)
    surface = WarmCreamSurface,                           // 纯白浮层卡片 (#FFFFFF)
    onSurface = TextPrimary,                              // 墨炭文字 (#1E2320)
    surfaceVariant = WarmCreamSurfaceVariant,             // 温润浅米底色 (#F3EFE8)
    onSurfaceVariant = TextMuted,                         // 柔和次级辅助字 (#78827B)
    outline = WarmCreamBorder,                            // 细腻温润米灰边框 (#E6E1D6)
    outlineVariant = WarmCreamBorderLight,                // 超细浅边框线 (#EDE9E1)

    error = AlertRed,                                     // 告警/紧急红色 (#EF4444)
    onError = Color.White,
    errorContainer = Color(0xFFFEF2F2),
    onErrorContainer = AlertRed
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}
