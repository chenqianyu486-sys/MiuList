package com.example.data.sync.web

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码生成。
 *
 * ## 为什么用 ZXing 而不是自己写
 *
 * 二维码编码器要处理 Reed-Solomon 纠错、模式选择、掩码评分 ——
 * 那不是「几百行」的活，而且写错了**扫不出来**，是个纯静默的失败。
 * ZXing 的 `core` 是纯 Java、无 Android 依赖的小库（约 600 KB），引进来最省事。
 *
 * ## 为什么输出 SVG 而不是 PNG
 *
 * Android 上**没有 `javax.imageio`**，把位图编码成 PNG 得自己写
 * zlib 压缩 + CRC32 + 分块，那又是一段不产生任何价值的代码。
 *
 * 而二维码本来就是「一堆黑方块」—— SVG 天然就是这个形状，
 * 直接按行输出矩形即可，网页端 `<img src="....svg">` 也能直接用。
 * App 自己要显示的那张（弹层里的地址二维码）走 [bitmap]，直接画像素。
 */
object QrCodes {

    /**
     * 渲染成 SVG。
     *
     * 逐行做**游程合并**（连续的暗模块合成一个矩形）：
     * 一个 33×33 的二维码，逐个模块画是 1089 个 `<rect>`，
     * 合并之后通常只有两三百个 —— 体积差一个数量级。
     */
    fun svg(text: String, sizePx: Int = 512): ByteArray? {
        val matrix = encode(text, sizePx) ?: return null
        val w = matrix.width
        val h = matrix.height
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h" shape-rendering="crispEdges">""")
        // 白底。模块矩形不写 fill，默认就是黑的。
        sb.append("""<rect width="$w" height="$h" fill="#ffffff"/>""")
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                if (!matrix.get(x, y)) { x++; continue }
                var run = 1
                while (x + run < w && matrix.get(x + run, y)) run++
                sb.append("""<rect x="$x" y="$y" width="$run" height="1"/>""")
                x += run
            }
        }
        sb.append("</svg>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** 渲染成位图，给 App 自己的界面用。 */
    fun bitmap(text: String, sizePx: Int = 512): Bitmap? {
        val matrix = encode(text, sizePx) ?: return null
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun encode(text: String, sizePx: Int): com.google.zxing.common.BitMatrix? = try {
        val hints = mapOf(
            // 纠错等级 Q：容错约 25%。配对码只有十几个字符，
            // 就算屏幕反光、拍糊了也扫得出来。
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    } catch (_: Exception) {
        null
    }
}
