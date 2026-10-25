package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 引导的进度要跨进程活下来。
 *
 * 引导可以被暂停（去看看某个功能，做完再回来），进程被杀也要接得上 ——
 * 所以「停在哪一步」得落盘。「走过没有」与「停在哪一步」是两件事：
 * 走过 = 那一趟完了（进度清掉）；没走过 = 还在半路（停住的那一步留着）。
 *
 * ## 存的是**名字**而不是序号
 *
 * 引导的步骤数会随「一键生成待办」这个开关变（关着四步、开着六步），
 * 同一个序号在两套清单里指的不是同一步。名字不会认错。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingRepositoryTest {

    private lateinit var repo: OnboardingRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 清空偏好，让每个用例都从「从没记过进度」开始 ——
        // 不清的话上一个用例留下的标记会串到下一个（Robolectric 里
        // 同一个 prefs 文件在同一测试类内是共用的）。
        context.getSharedPreferences("test_onboarding_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
        repo = OnboardingRepository(
            context,
            prefName = "test_onboarding_prefs"
        )
        repo.hasSeenOnboarding = false
    }

    @Test
    fun `默认没停过任何一步`() {
        assertNull("还没走过引导时不该有停住的位置", repo.currentTarget)
    }

    @Test
    fun `停住的那一步能存住`() {
        repo.currentTarget = "WALLPAPER"

        assertEquals("WALLPAPER", repo.currentTarget)
    }

    @Test
    fun `清掉之后回到没停过`() {
        repo.currentTarget = "WALLPAPER"
        repo.currentTarget = null

        assertNull(repo.currentTarget)
    }

    @Test
    fun `清掉进度也算记过了`() {
        // 「走完 / 跳过」= 从头看；而「从没记过」= 老版本那个序号。
        // 这两件事在**值**上都是空，但续看时走的路不一样 ——
        // 分不开的话，老用户走完一遍引导，下次打开会又跳回原来那一步。
        assertFalse(repo.hasTargetProgress)

        repo.currentTarget = "WALLPAPER"
        repo.currentTarget = null

        assertTrue(repo.hasTargetProgress)
    }

    @Test
    fun `老版本存的序号仍然读得到`() {
        // 老版本把进度存成序号（那时引导固定六步、顺序不变）。
        // 这个值只读：换算成目标名之后就走新键了，老键不再读第二遍。
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("test_onboarding_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("onboarding_step", 3).apply()

        assertEquals(3, repo.legacyCurrentStep)
    }

    @Test
    fun `新接口不写老键`() {
        repo.currentTarget = "WALLPAPER"

        assertEquals(
            "停住的位置写的是目标名，不该再动那个序号键",
            0,
            repo.legacyCurrentStep
        )
    }

    @Test
    fun `走过与停在哪一步是两个标记`() {
        repo.currentTarget = "WALLPAPER"
        repo.hasSeenOnboarding = true

        assertTrue(repo.hasSeenOnboarding)
        assertEquals(
            "「走过」不该顺手动到停住的位置（两者要不要一起清掉由 ViewModel 决定）",
            "WALLPAPER",
            repo.currentTarget
        )
    }
}
