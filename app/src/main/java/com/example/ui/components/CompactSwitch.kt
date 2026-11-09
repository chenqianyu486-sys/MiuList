package com.example.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

/**
 * 缩放系数。
 *
 * Material 的开关是 52×32dp —— 摆在一行 40dp 的图标方块旁边，它比方块还宽，
 * 一行里最重的东西反而是那颗开关。0.7 之后是 36.4×22.4dp：比图标方块窄一档，
 * 高度也正好跟一行标题的字高齐平，比例就回来了。
 */
private const val CompactSwitchScale = 0.7f

/**
 * 比 Material 默认小一号的开关。
 *
 * App 里凡是「一行：图标 + 标题 + 状态」的行都用它 —— 设置页的「待办完成确认」、
 * 抽屉里的开关行、新建待办的「截止」开关。三处用同一个，才不会一个胖一个瘦。
 *
 * **为什么用 scale 而不是换控件**：
 *
 * - scale 只改**画出来**的大小，开关的动画、语义（`Role.Switch` / 开 / 关）全是
 *   Material 自己的那份，一行代码都不用重写；
 * - 手指的目标区一点没缩水：命中判定按布局算，不按缩放后的画幅算。
 *
 * **但占位必须跟着缩**（[layout] 那一段）：只缩画幅的话，这一行右边会空出原来
 * 开关那么宽的一块，缩小的开关就跟在别处的箭头对不齐了。占位缩到画幅一样宽，
 * 开关的右沿才跟同一行其它记号（比如那个「>」）落在同一条线上。
 *
 * **高度不缩**：画幅矮了，但占位高度照旧（48dp），手指照样好按。
 */
@Composable
fun CompactSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary
        ),
        modifier = modifier
            .graphicsLayer {
                scaleX = CompactSwitchScale
                scaleY = CompactSwitchScale
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                // 画幅宽（占位跟着缩到一样宽，行尾才对得齐）。
                val visualWidth = (placeable.width * CompactSwitchScale).roundToInt()
                layout(visualWidth, placeable.height) {
                    // 缩放是绕着节点中心做的，所以画幅比节点窄、左右各空一截；
                    // 往左挪那么一截，画幅才正好贴住占位框的左沿。
                    placeable.placeRelative(
                        -(placeable.width * (1f - CompactSwitchScale) / 2f).roundToInt(),
                        0
                    )
                }
            }
    )
}
