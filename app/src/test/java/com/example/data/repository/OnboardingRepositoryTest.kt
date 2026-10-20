package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 走过 = 那一趟完了（步数归零）；没走过 = 还在半路（步数留着）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingRepositoryTest {

    private lateinit var repo: OnboardingRepository

    @Before
    fun setup() {
        repo = OnboardingRepository(
            ApplicationProvider.getApplicationContext(),
            prefName = "test_onboarding_prefs"
        )
        repo.hasSeenOnboarding = false
        repo.currentStep = 0
    }

    @Test
    fun `步数默认是零`() {
        assertEquals(0, repo.currentStep)
    }

    @Test
    fun `步数能存住`() {
        repo.currentStep = 2
        assertEquals(2, repo.currentStep)
    }

    @Test
    fun `负步数被夹回零`() {
        repo.currentStep = -3
        assertEquals(0, repo.currentStep)
    }

    @Test
    fun `走过与停在哪一步是两个标记`() {
        repo.currentStep = 1
        repo.hasSeenOnboarding = true

        assertTrue(repo.hasSeenOnboarding)
        assertEquals(
            "「走过」不该顺手动到步数（两者要不要一起归零由 ViewModel 决定）",
            1,
            repo.currentStep
        )
    }
}
