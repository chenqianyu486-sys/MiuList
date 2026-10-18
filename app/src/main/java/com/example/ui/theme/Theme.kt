package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// MiuList Theme：默认壁纸底 + 抹茶绿强调 + 粉彩染色卡片
//
// 页面底不再由 colorScheme 决定，而是 AppBackgroundLayer 铺的那张默认壁纸（见 Mica.kt）。
// colorScheme.background 仍留作纯白，给真正需要实底的地方（弹层背后的独立窗口）。
// 卡片面的颜色也不走这里——glassSurface 自己带 tint，课程卡传课程粉彩，其余用中性色。
//
// 所以这一组 surface 全部改成**不透明**的冷灰阶：它们真正服务的是对话框和底部弹层，
// 那些东西跑在独立窗口里压在浅色壁纸上，半透明的浅色会直接和底融掉、看不出是弹层。
private val LightColorScheme = lightColorScheme(
    primary = LimeGreenPrimary,                          // 主题色：#BCE472
    onPrimary = LimeGreenOnPrimary,                      // 深色文字 (#1E2D0E)，确保浅绿底色上拥有极高清晰度与对比度
    primaryContainer = Color(0xFFEAF6D8),                // 绿作容器底：极淡的抹茶绿
    onPrimaryContainer = LimeGreenOnContainer,           // 深抹茶绿前景 (#4A6B2A)

    secondary = LakeBlueAccent,                           // 点缀色：湖蓝 (#387B9B)
    onSecondary = Color.White,                            // 白色 (#FFFFFF)
    secondaryContainer = LakeBlueContainer,               // 浅湖蓝容器底色 (#E8F2F7)
    onSecondaryContainer = LakeBlueOnContainer,           // 湖蓝深色文字 (#1E526D)

    tertiary = LakeBlueAccent,                            // 点缀色：湖蓝
    onTertiary = Color.White,
    tertiaryContainer = LakeBlueContainer,
    onTertiaryContainer = LakeBlueOnContainer,

    background = Color(0xFFFFFFFF),                       // 背景：纯白
    onBackground = TextPrimary,                           // 墨炭深色标题正文 (#1E2320)
    surface = Color(0xFFFAFBFC),
    onSurface = TextPrimary,                              // 墨炭文字 (#1E2320)
    surfaceVariant = Color(0xFFF1F5F9),                   // 次级底：浅冷灰
    onSurfaceVariant = TextMuted,                         // 柔和次级辅助字 (#78827B)
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBFC),              // 底部弹层默认容器
    surfaceContainer = Color(0xFFF8FAFB),
    surfaceContainerHigh = Color(0xFFF6F8FA),             // AlertDialog 容器（见 CourseEditDialog 等）
    surfaceContainerHighest = Color(0xFFF2F5F8),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFEDF1F5),
    outline = Color(0xFFDDE6E2),                         // 冷调分隔线（暖米灰边框会显旧）
    outlineVariant = Color(0xFFE7EEEB),                   // 更淡的冷调发丝线

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
