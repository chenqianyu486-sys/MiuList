package com.example.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 远程原文的文件缓存。
 *
 * 全部离线：只碰 [TemporaryFolder] 里的真实文件，不涉及网络与 Android。
 */
class CalendarCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun cacheIn(dir: File = temp.newFolder()) =
        CalendarCache(File(dir, CalendarCache.FILE_NAME))

    @Test
    fun `写完能原样读回`() {
        val cache = cacheIn()
        val text = """{"lastUpdated": 20261120, "termConfigs": []}"""

        cache.write(text)

        assertEquals(text, cache.read())
    }

    @Test
    fun `文件不存在时返回 null 而不是抛`() {
        assertNull(cacheIn().read())
    }

    @Test
    fun `空内容不算缓存`() {
        val file = File(temp.newFolder(), CalendarCache.FILE_NAME)
        file.writeText("   \n  ", Charsets.UTF_8)

        assertNull("只有空白的文件等同于没有缓存", CalendarCache(file).read())
    }

    /** 传 null = 明确不缓存，读写都直接返回，不碰磁盘。 */
    @Test
    fun `不配缓存位置时读写都是空操作`() {
        val cache = CalendarCache(null)

        cache.write("""{"lastUpdated": 1}""")
        assertNull(cache.read())
        cache.clear()
    }

    /**
     * 写的时候不留临时文件。
     *
     * 直接写目标文件的话，进程正好在写到一半被杀，缓存里会留下一段截断的 JSON ——
     * 而下次启动时它是「存在但解析失败」，比「不存在」更难排查。
     * 所以这里断言写完之后目录里只有目标文件。
     */
    @Test
    fun `写完不留临时文件`() {
        val dir = temp.newFolder()
        val cache = cacheIn(dir)

        cache.write("""{"lastUpdated": 20261120}""")

        val leftovers = dir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue("不该留下 .tmp：${dir.list()?.toList()}", leftovers.isEmpty())
    }

    /** 连续写两次，第二次要真的覆盖第一次 —— 内容停在旧版本是最难发现的一种 bug。 */
    @Test
    fun `重复写会覆盖旧内容`() {
        val cache = cacheIn()

        cache.write("""{"lastUpdated": 20260101}""")
        cache.write("""{"lastUpdated": 20261120}""")

        assertTrue(
            "第二次写没生效，缓存停在旧版本：${cache.read()}",
            cache.read()!!.contains("20261120")
        )
        assertFalse(cache.read()!!.contains("20260101"))
    }

    @Test
    fun `clear 之后读不到了`() {
        val cache = cacheIn()
        cache.write("""{"lastUpdated": 1}""")

        cache.clear()

        assertNull(cache.read())
    }

    /** 目录还不存在时要自己建，否则第一次写入会静默失败（runCatching 吞掉了异常）。 */
    @Test
    fun `目录不存在时自动创建`() {
        val nested = File(temp.newFolder(), "a/b/c")
        val cache = CalendarCache(File(nested, CalendarCache.FILE_NAME))

        cache.write("""{"lastUpdated": 1}""")

        assertTrue("目录没建起来，缓存静默写失败了", nested.exists())
        assertTrue(cache.read()!!.contains("lastUpdated"))
    }
}
