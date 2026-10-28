package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import com.example.data.model.WallpaperGradient
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 把一条渐变参数变成 Compose 的 [Brush]。
 *
 * ## 这里的几何是**照抄位图版**的
 *
 * 原来的预设壁纸是 pxlab 的 CSS 渐变在 2400×2000 画布上的渲染位图，
 * 用 `ContentScale.Crop` 铺满屏幕 —— 也就是**等比放大铺满、溢出部分裁掉**。
 *
 * 换成 Brush 之后如果不把这套几何复现出来，壁纸会「变脸」：
 *
 * - 竖向渐变（`to top`）：裁切不影响竖直方向，颜色看起来一样；
 * - 斜向渐变（45deg / 120deg / -20deg / -225deg）：横向被裁掉的越多，
 *   屏幕上露出的颜色区间就越窄。直接按**屏幕尺寸**算 CSS 渐变，
 *   等于把整条色带全铺开 —— 比位图版明显**更饱和、更跳**。
 *
 * 所以这里的算法是：先按**画布**把 CSS 渐变线算出来，
 * 再乘上 cover 的缩放比搬到屏幕上。
 *
 * ## 公式
 *
 * - 方向：`d = (sin θ, -cos θ)`。θ=0 时指向屏幕上方，所以 y 取负
 *   （屏幕坐标 y 向下）。
 * - cover 缩放比：`scale = max(目标宽 / 画布宽, 目标高 / 画布高)`。
 * - 渐变线长度：`长度比 × 画布高 × scale`。
 * - 渐变线中点（t = 0.5 处）：目标区域中心沿方向再偏 `偏移比 × 画布高 × scale`。
 *
 * 画布尺寸与缩放比一起抵消掉了 —— 位图是画布等比缩出来的，
 * 所以「用画布算」与「用位图算」得到的是同一条线。
 *
 * ## 为什么长度与偏移要单独给，而不是按 CSS 公式算
 *
 * CSS 规定渐变线长度 = `|W·sin θ| + |H·cos θ|`，且必定穿过画布中心。
 * 但这些壁纸的**原始位图并不满足**（站上标的那条 CSS 和它发的图对不上，
 * 详见 [com.example.data.model.WallpaperPresets] 的注释），
 * 所以长度与偏移都按实测值给，而不是套 CSS 公式。
 *
 * @param widthPx 目标绘制区域宽度（像素）
 * @param heightPx 目标绘制区域高度（像素）
 */
fun wallpaperBrush(gradient: WallpaperGradient, widthPx: Float, heightPx: Float): Brush {
    val rad = Math.toRadians(gradient.angleDeg.toDouble())
    val sin = sin(rad).toFloat()
    val cos = cos(rad).toFloat()

    // 渐变线方向：0deg 朝上。
    val dx = sin
    val dy = -cos

    // cover：等比放大铺满，溢出部分裁掉。
    val scale = max(widthPx / gradient.canvasWidth, heightPx / gradient.canvasHeight)

    // 渐变线长度：画布高度 × 长度比（竖直渐变时正好等于画布高）。
    val length = gradient.lengthRatio * gradient.canvasHeight * scale

    // 渐变线的中点（t = 0.5 处）不与画布中心重合，要沿方向偏一段。
    val shift = gradient.offsetRatio * gradient.canvasHeight * scale
    val center = Offset(
        widthPx / 2f + dx * shift,
        heightPx / 2f + dy * shift
    )
    val half = Offset(dx * length / 2f, dy * length / 2f)

    return Brush.linearGradient(
        // 用 vararg 那个重载：这个 Compose 版本没有「colors + stops 列表」的重载，
        // 而 vararg 的每个 Pair 就是 (位置, 颜色)，语义完全一致。
        //
        // 注意 zip 的顺序：**位置在前、颜色在后**（反了就是类型不匹配）。
        *gradient.stops.zip(gradient.colors).toTypedArray(),
        start = center - half,
        end = center + half
    )
}

/**
 * 在当前绘制区域上铺一条渐变壁纸。
 *
 * 用 [drawBehind] 而不是 `background(brush)`：前者在绘制阶段就能拿到
 * **这一帧的最终尺寸**（`DrawScope.size`），不用等布局回填状态 ——
 * 所以第一帧就是对的，不会闪一下空白。
 *
 * @param gradient 渐变描述
 */
@Composable
fun Modifier.wallpaperGradient(gradient: WallpaperGradient): Modifier {
    val cache = remember { WallpaperBrushCache() }
    return this.drawBehind {
        drawRect(cache.brushFor(gradient, size))
    }
}

/**
 * Brush 缓存。
 *
 * 壁纸是全屏背景，还被录进 haze 的源层里（见 [AppBackgroundLayer]），
 * 一次滚动能触发很多次重绘 —— 每次都新建一个 shader 没必要。
 */
private class WallpaperBrushCache {
    private var lastKey: Pair<WallpaperGradient, Size>? = null
    private var lastBrush: Brush? = null

    fun brushFor(gradient: WallpaperGradient, size: Size): Brush {
        val cached = lastBrush
        if (cached != null && lastKey == gradient to size) return cached
        val brush = wallpaperBrush(gradient, size.width, size.height)
        lastKey = gradient to size
        lastBrush = brush
        return brush
    }
}
