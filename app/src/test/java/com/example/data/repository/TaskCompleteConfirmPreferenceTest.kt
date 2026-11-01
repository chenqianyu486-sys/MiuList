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
 * 「完成待办前要不要确认」这个开关的持久化。
 *
 * 三条要点：
 *
 * - **默认是开的**：勾掉一条待办是**不可见地丢掉了它**（它从清单里消失），
 *   而「刚才那条是不是真的做完了」只有用户自己能回答。防误操作的默认值
 *   与「一键生成待办」（默认关）方向相反 —— 一个挡的是「多出来的功能」，
 *   一个挡的是「已经发生的误操作」。
 * - **关掉之后记得住**：用户在设置里拨过一次，这个决定要留到下次启动。
 * - **没有「没决定过」这一档**：这个开关没有「按老用户行为推断默认值」的必要
 *   —— 它要么是默认开、要么被用户明确关掉，两种都是确定的值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskCompleteConfirmPreferenceTest {

    private fun prefs(name: String) = TaskCompleteConfirmPreference(
        ApplicationProvider.getApplicationContext<Context>(),
        prefName = name
    )

    @Test
    fun `默认是开的`() {
        assertTrue(
            "误勾一条待办的代价直接落在用户身上，默认要防",
            prefs("test_complete_confirm_default").isEnabled
        )
    }

    @Test
    fun `关掉之后记得住`() {
        val pref = prefs("test_complete_confirm_off")
        pref.isEnabled = false

        assertFalse(pref.isEnabled)
    }

    @Test
    fun `重新打开之后也记得住`() {
        val pref = prefs("test_complete_confirm_on")
        pref.isEnabled = true

        assertTrue(pref.isEnabled)
    }

    @Test
    fun `换个实例读到的还是同一个值`() {
        // 偏好必须**跨实例**活着：ViewModel 与设置页拿的是两个实例
        // （容器里各自 new 出来的），两边得看到同一个值。
        val writer = prefs("test_complete_confirm_shared")
        writer.isEnabled = false

        val reader = prefs("test_complete_confirm_shared")
        assertFalse(reader.isEnabled)
    }
}
