package com.example.data.repository

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.example.data.model.WallpaperConfig
import com.example.data.model.WallpaperPresets
import com.example.data.model.WallpaperType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 壁纸数据仓储：负责壁纸配置的持久化存储与自定义图片的导入处理。
 *
 * ## 设计考量
 * 1. **独立私有存储**：用户从系统相册选取图片后，自动复制一份到应用私有目录 (`filesDir/wallpapers/`)。
 *    Android SAF 的 content:// Uri 在应用重启后权限容易失效（即使调用 takePersistableUriPermission），
 *    落盘为私有文件后读取快速且永久有效。
 * 2. **自适应降采样与 EXIF 方向纠正**：用户相机拍出的几十兆大图在解码时做等比安全降采样
 *    （最长边不超过 2560px）并根据 EXIF 旋转矩阵矫正方向，彻底杜绝 OOM 与照片颠倒。
 * 3. **轻量持久化**：模式与路径写入 SharedPreferences，随应用冷启动即时读取。
 */
class WallpaperRepository(
    private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _wallpaperConfig = MutableStateFlow(loadConfigFromPrefs())
    val wallpaperConfig: StateFlow<WallpaperConfig> = _wallpaperConfig.asStateFlow()

    private fun loadConfigFromPrefs(): WallpaperConfig {
        val typeStr = prefs.getString(KEY_TYPE, WallpaperType.DEFAULT.name) ?: WallpaperType.DEFAULT.name
        val type = runCatching { WallpaperType.valueOf(typeStr) }.getOrDefault(WallpaperType.DEFAULT)
        val presetId = prefs.getString(KEY_PRESET_ID, WallpaperPresets.DEFAULT.id) ?: WallpaperPresets.DEFAULT.id
        val customPath = prefs.getString(KEY_CUSTOM_PATH, null)
        val dimOpacity = prefs.getFloat(KEY_DIM_OPACITY, 0f)

        // 验证自定义路径是否存在，若文件已被清理则优雅回退
        val validCustomPath = if (!customPath.isNullOrBlank() && File(customPath).exists()) {
            customPath
        } else null

        val finalType = if (type == WallpaperType.CUSTOM && validCustomPath == null) {
            WallpaperType.DEFAULT
        } else {
            type
        }

        return WallpaperConfig(
            type = finalType,
            presetId = presetId,
            customPath = validCustomPath,
            dimOpacity = dimOpacity
        )
    }

    private fun persistConfig(config: WallpaperConfig) {
        prefs.edit()
            .putString(KEY_TYPE, config.type.name)
            .putString(KEY_PRESET_ID, config.presetId)
            .putString(KEY_CUSTOM_PATH, config.customPath)
            .putFloat(KEY_DIM_OPACITY, config.dimOpacity)
            .apply()
        _wallpaperConfig.value = config
    }

    /**
     * 导入用户从系统相册选取的壁纸图片
     *
     * @return 成功落盘后的绝对文件路径，失败返回 null
     */
    suspend fun setCustomWallpaperFromUri(uri: Uri, resolver: ContentResolver): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "wallpapers").apply { mkdirs() }
            val targetFile = File(dir, "custom_wallpaper_${System.currentTimeMillis()}.jpg")

            // 1. 读取边界信息，防止大图解码 OOM
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            } ?: return@runCatching null

            val srcWidth = boundsOptions.outWidth
            val srcHeight = boundsOptions.outHeight
            if (srcWidth <= 0 || srcHeight <= 0) return@runCatching null

            val maxDimension = 2560
            var inSampleSize = 1
            var w = srcWidth
            var h = srcHeight
            while (w > maxDimension || h > maxDimension) {
                inSampleSize *= 2
                w /= 2
                h /= 2
            }

            // 2. 读取 EXIF 旋转信息
            var rotationDegrees = 0f
            runCatching {
                resolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    val orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                    rotationDegrees = when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                }
            }

            // 3. 安全解码
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val decodedBitmap = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return@runCatching null

            // 4. 处理旋转
            val finalBitmap = if (rotationDegrees != 0f) {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                val rotated = Bitmap.createBitmap(
                    decodedBitmap, 0, 0, decodedBitmap.width, decodedBitmap.height, matrix, true
                )
                if (rotated != decodedBitmap) {
                    decodedBitmap.recycle()
                }
                rotated
            } else {
                decodedBitmap
            }

            // 5. 保存到目标文件
            FileOutputStream(targetFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            finalBitmap.recycle()

            // 6. 清理旧自定义壁纸文件（保留最新一份）
            cleanUpOldWallpaperFiles(dir, targetFile)

            val absolutePath = targetFile.absolutePath
            val newConfig = _wallpaperConfig.value.copy(
                type = WallpaperType.CUSTOM,
                customPath = absolutePath
            )
            persistConfig(newConfig)
            absolutePath
        }.getOrNull()
    }

    /**
     * 切换为预设壁纸
     */
    fun setPresetWallpaper(presetId: String) {
        val newConfig = _wallpaperConfig.value.copy(
            type = WallpaperType.PRESET,
            presetId = presetId
        )
        persistConfig(newConfig)
    }

    /**
     * 恢复为默认壁纸 (Cloudy Knoxville)
     */
    fun resetToDefault() {
        val newConfig = _wallpaperConfig.value.copy(
            type = WallpaperType.DEFAULT,
            presetId = WallpaperPresets.DEFAULT.id,
            dimOpacity = 0f
        )
        persistConfig(newConfig)
    }

    /**
     * 设置内容保护遮罩透明度（0f ~ 0.5f）
     */
    fun setDimOpacity(opacity: Float) {
        val clamped = opacity.coerceIn(0f, 0.5f)
        val newConfig = _wallpaperConfig.value.copy(dimOpacity = clamped)
        persistConfig(newConfig)
    }

    private fun cleanUpOldWallpaperFiles(dir: File, currentFile: File) {
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.absolutePath != currentFile.absolutePath) {
                    file.delete()
                }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "miulist_wallpaper_prefs"
        private const val KEY_TYPE = "wallpaper_type"
        private const val KEY_PRESET_ID = "wallpaper_preset_id"
        private const val KEY_CUSTOM_PATH = "wallpaper_custom_path"
        private const val KEY_DIM_OPACITY = "wallpaper_dim_opacity"
    }
}
