package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.WallpaperPresets
import com.example.data.model.WallpaperType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
}
