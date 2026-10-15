package com.example.ui.components

import com.example.data.source.zju.ZjuFailureKind
import com.example.data.sync.SyncFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败提示的**文案**单测。
 *
 * 这段文案是用户同步失败时唯一能看到的排障指引，而且它不经过任何逻辑分支，
 * 「改坏了」在编译期与运行期都不会报错 —— 只看截图也不容易逐条读完。
 *
 * 钉的是**约束**而不是逐字文案：措辞可以改，但下面几条不能破。
 */
class SyncFailureAdviceTest {

    private fun failure(
        kind: ZjuFailureKind,
        intact: Boolean = true,
        count: Int? = 12
    ) = SyncFailure(kind = kind, reason = "上游原因", localDataIntact = intact, localCourseCount = count)

    /**
     * 锁定态**不能**劝用户重试。
     *
     * 这是全部约束里最要紧的一条：连续输错会延长锁定期，
     * 而「同步失败 → 再点一次」是最自然的用户反应。
     */
    @Test
    fun `locked advice tells the user to stop retrying`() {
        val advice = SyncFailureAdvice.adviceOf(ZjuFailureKind.LOCKED)
        assertTrue("必须明确劝阻：$advice", advice.contains("不要再试") || advice.contains("先不要"))
        assertTrue("必须说明继续试会加重：$advice", advice.contains("锁定期"))
        assertEquals("账号被暂时锁定", ZjuFailureKind.LOCKED.title)
    }

    /**
     * 服务端的锅不能甩到密码上。
     *
     * 浙大侧 5xx 时让用户去改密码，是这套提示早先最大的坑 ——
     * 用户改了密码、又去重登、再失败，锁号就是这么来的。
     */
    @Test
    fun `server side failure does not send the user to change their password`() {
        val advice = SyncFailureAdvice.adviceOf(ZjuFailureKind.SERVER)
        assertTrue("要明说是浙大那边的问题：$advice", advice.contains("浙大"))
        assertTrue("要说明改密码没用：$advice", advice.contains("改密码没有用") || advice.contains("改密码没用"))
    }

    /** 重试没用的类别，不许用「请重试」收尾。 */
    @Test
    fun `failures that cannot be fixed by retrying do not tell the user to retry`() {
        listOf(ZjuFailureKind.TERM, ZjuFailureKind.DATA_INVALID).forEach { kind ->
            val advice = SyncFailureAdvice.adviceOf(kind)
            assertTrue(
                "$kind 不该劝用户干等重试：$advice",
                advice.contains("文件导入")
            )
        }
    }

    /** 每一类都得有标题、图标与建议，漏一个就是界面上少一块。 */
    @Test
    fun `every failure kind has a title an icon and an advice`() {
        val seen = mutableSetOf<String>()
        ZjuFailureKind.entries.forEach { kind ->
            assertTrue("$kind 缺标题", kind.title.isNotBlank())
            assertNotNull("$kind 缺图标", SyncFailureAdvice.iconOf(kind))
            assertTrue("$kind 缺建议", SyncFailureAdvice.adviceOf(kind).isNotBlank())
            // 标题必须逐类唯一：重复的名字会让用户以为两件事其实是一件事。
            assertTrue("标题重复：${kind.title}", seen.add(kind.title))
        }
    }

    /**
     * 本地数据状态必须在「已经变了」的时候明说。
     *
     * 只在一切正常时说「本地没事」是容易的，难的是 commit 已经写进去了、
     * 用户却以为什么都没变，于是把生效中的课表删掉重导。
     */
    @Test
    fun `local status distinguishes intact data from data that already changed`() {
        assertEquals(
            "本地 12 门课程仍然照常可用",
            SyncFailureAdvice.localStatusOf(failure(ZjuFailureKind.NETWORK))
        )
        assertEquals(
            "本地暂时还没有课表数据",
            SyncFailureAdvice.localStatusOf(failure(ZjuFailureKind.NETWORK, count = 0))
        )
        assertNull(
            "查不到门数时别硬凑",
            SyncFailureAdvice.localStatusOf(failure(ZjuFailureKind.NETWORK, count = null))
        )
        val changed = SyncFailureAdvice.localStatusOf(
            failure(ZjuFailureKind.UNKNOWN, intact = false, count = 12)
        )
        assertNotNull(changed)
        assertTrue("本地已改动时必须说清楚：$changed", changed!!.contains("已"))
        assertTrue("本地已改动时不能还说「照常可用」：$changed", !changed.contains("照常可用"))
    }

    /** 只有凭据类失败才该把用户送到输入框前。 */
    @Test
    fun `only credential related kinds ask the user to re-enter the account`() {
        assertTrue(ZjuFailureKind.CREDENTIAL.needsCredentials)
        assertTrue(ZjuFailureKind.LOCKED.needsCredentials)
        assertTrue(ZjuFailureKind.SESSION.needsCredentials)
        assertTrue(!ZjuFailureKind.NETWORK.needsCredentials)
        assertTrue(!ZjuFailureKind.SERVER.needsCredentials)
        assertTrue(!ZjuFailureKind.DATA_INVALID.needsCredentials)
    }
}
