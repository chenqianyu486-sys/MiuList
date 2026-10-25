package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「一键生成待办」这个开关的持久化。
 *
 * 两条要点：
 *
 * - **默认是关的**：想要轻量清单的人不该先被推去配一遍规则；
 * - **「没决定过」和「决定关掉」是两件事**：前者要靠「这台设备用过没有」
 *   来定默认值，后者就是用户的明确选择，不能被默认值覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickGeneratePreferenceTest {

    private fun prefs(name: String) = QuickGeneratePreference(
        ApplicationProvider.getApplicationContext<Context>(),
        prefName = name
    )

    @Test
    fun `默认是关的`() {
        assertFalse(
            "新装的设备默认走轻量体验",
            prefs("test_quick_generate_default").isEnabled
        )
    }

    @Test
    fun `没决定过不等于决定关掉`() {
        // 这两者的区别决定了老用户的默认值怎么定：
        // 「没决定过」→ 查库看他用过没有；「决定关掉」→ 就是关。
        assertFalse(prefs("test_quick_generate_undecided").hasDecision)
    }

    @Test
    fun `开过之后记得住`() {
        val pref = prefs("test_quick_generate_on")
        pref.isEnabled = true

        assertTrue(pref.isEnabled)
        assertTrue(pref.hasDecision)
    }

    @Test
    fun `关过之后也是决定过了`() {
        // 写下去就等于决定过：哪怕值是「关」。
        // 这一条是为了不让「默认值判定」把用户明确关掉的开关又打开。
        val pref = prefs("test_quick_generate_off")
        pref.isEnabled = false

        assertTrue(pref.hasDecision)
        assertFalse(pref.isEnabled)
    }
}
