package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.WallpaperPresets
import com.example.data.model.WallpaperType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WallpaperRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: WallpaperRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // 清除旧测试数据
        context.getSharedPreferences("miulist_wallpaper_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        repository = WallpaperRepository(context)
    }

    @Test
    fun default_config_is_cloudy_knoxville_without_dim() {
        val config = repository.wallpaperConfig.value
        assertEquals(WallpaperType.DEFAULT, config.type)
        assertEquals(WallpaperPresets.DEFAULT.id, config.presetId)
        assertEquals(0f, config.dimOpacity, 0.001f)
    }

    @Test
    fun set_preset_wallpaper_persists_and_updates_flow() {
        repository.setPresetWallpaper(WallpaperPresets.SOFT_GRASS.id)
        val config = repository.wallpaperConfig.value
        assertEquals(WallpaperType.PRESET, config.type)
        assertEquals(WallpaperPresets.SOFT_GRASS.id, config.presetId)

        // 重新创建仓储验证持久化
        val reloaded = WallpaperRepository(context).wallpaperConfig.value
        assertEquals(WallpaperType.PRESET, reloaded.type)
        assertEquals(WallpaperPresets.SOFT_GRASS.id, reloaded.presetId)
    }

    @Test
    fun set_dim_opacity_clamps_to_range_and_persists() {
        repository.setDimOpacity(0.35f)
        assertEquals(0.35f, repository.wallpaperConfig.value.dimOpacity, 0.001f)

        repository.setDimOpacity(0.9f) // 超过上限 0.5f
        assertEquals(0.5f, repository.wallpaperConfig.value.dimOpacity, 0.001f)

        repository.setDimOpacity(-0.1f) // 低于下限 0f
        assertEquals(0f, repository.wallpaperConfig.value.dimOpacity, 0.001f)
    }

    @Test
    fun reset_to_default_restores_default_cloudy_knoxville_and_resets_dim() {
        repository.setPresetWallpaper(WallpaperPresets.SOFT_GRASS.id)
        repository.setDimOpacity(0.3f)

        repository.resetToDefault()

        val config = repository.wallpaperConfig.value
        assertEquals(WallpaperType.DEFAULT, config.type)
        assertEquals(WallpaperPresets.DEFAULT.id, config.presetId)
        assertEquals(0f, config.dimOpacity, 0.001f)
    }

    // ------------------------------------------------------------ 自定义壁纸导入

    /**
     * 导入自定义壁纸的回归测试。
     *
     * 之前这条路径一行测试都没有，所以下面那个 bug 一直没被发现：
     * 读尺寸用的是 `inJustDecodeBounds = true`，而这种模式下 decodeStream 按约定
     * 只填 outWidth/outHeight 并**返回 null**；原代码把这个 null 当成「流没打开」，
     * 于是每一次导入都直接判失败，界面上只显示「导入壁纸失败，请重试」。
     */
    @Test
    fun import_custom_wallpaper_writes_jpeg_and_switches_config_to_custom() = runTest {
        val source = writeTestPng(width = 120, height = 80)
        FakeImageProvider.file = source
        Robolectric.setupContentProvider(FakeImageProvider::class.java, FakeImageProvider.AUTHORITY)

        val path = repository.setCustomWallpaperFromUri(fakeImageUri(), context.contentResolver)

        assertNotNull("自定义壁纸导入应成功返回落盘路径，实际为 null", path)
        val saved = File(path!!)
        assertTrue("落盘文件应存在", saved.exists())
        assertTrue("落盘文件不该是 0 字节", saved.length() > 0)

        val config = repository.wallpaperConfig.value
        assertEquals(WallpaperType.CUSTOM, config.type)
        assertEquals(path, config.customPath)
        // 自定义照片默认给「柔和」档
        assertEquals(0.5f, config.softFocus, 0.001f)

        // 落盘的必须是能被壁纸层 decodeFile 读回来的真图
        val decoded = BitmapFactory.decodeFile(saved.absolutePath)
        assertNotNull("落盘文件应能被解码", decoded)
        assertEquals(120, decoded.width)
        assertEquals(80, decoded.height)
    }

    @Test
    fun import_custom_wallpaper_returns_null_and_keeps_default_when_stream_unavailable() = runTest {
        FakeImageProvider.file = null
        Robolectric.setupContentProvider(FakeImageProvider::class.java, FakeImageProvider.AUTHORITY)

        val path = repository.setCustomWallpaperFromUri(fakeImageUri(), context.contentResolver)

        assertNull("拿不到图片流时应返回 null", path)
        assertEquals(WallpaperType.DEFAULT, repository.wallpaperConfig.value.type)
    }

    /** 换一张自定义壁纸时，旧的落盘文件要清掉，只保留最新一份。 */
    @Test
    fun import_custom_wallpaper_replaces_previous_file() = runTest {
        val source = writeTestPng(width = 100, height = 100)
        FakeImageProvider.file = source
        Robolectric.setupContentProvider(FakeImageProvider::class.java, FakeImageProvider.AUTHORITY)

        val first = repository.setCustomWallpaperFromUri(fakeImageUri(), context.contentResolver)
        val second = repository.setCustomWallpaperFromUri(fakeImageUri(), context.contentResolver)

        assertNotNull(first)
        assertNotNull(second)
        assertTrue("旧壁纸文件应被清掉", !File(first!!).exists())
        assertTrue("新壁纸文件应存在", File(second!!).exists())
    }

    private fun fakeImageUri(): Uri = Uri.parse("content://${FakeImageProvider.AUTHORITY}/1")

    private fun writeTestPng(width: Int, height: Int): File {
        val file = File(context.cacheDir, "src_${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        bitmap.recycle()
        return file
    }
}
