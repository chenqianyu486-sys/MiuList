package com.example.data.calendar

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 承载 `TweakKind` 的最小信封。Moshi 的代码生成不接受私有/嵌套类，所以放文件级。 */
@JsonClass(generateAdapter = true)
internal data class TweakHolder(val type: TweakKind?)

/**
 * [TweakKind] 的取值解析。
 *
 * 上游 `configs/config.json` 里 `TweakType` **写的是字符串**。
 * 之前 DTO 声明成 `Int?`，Moshi 遇到 `"Clear"` 会抛 `JsonDataException`，
 * 而 `ZjuCalendarSource.parse` 把整个反序列化包在 `runCatching` 里 ——
 * 于是**整份校历配置解析失败**，学期起止日期与调休一起消失，
 * 界面上只剩一句「校历配置拉取失败」，完全看不出是格式问题。
 *
 * 所以这条用例守的是「两种写法都必须收」，其中字符串是上游的实际形态。
 */
class TweakKindTest {

    private val moshi = Moshi.Builder()
        .add(TweakKind::class.java, TweakKindJsonAdapter())
        .build()

    private fun read(json: String): TweakKind? = moshi.adapter(TweakHolder::class.java).fromJson(json)!!.type

    @Test
    fun `上游的字符串写法必须收`() {
        assertEquals(TweakKind.CLEAR, read("""{"type":"Clear"}"""))
        assertEquals(TweakKind.MOVE, read("""{"type":"Move"}"""))
        assertEquals(TweakKind.EXCHANGE, read("""{"type":"Exchange"}"""))
    }

    /** 历史版本与 v1/v2 老文档写的是数字，两边都不能挑食。 */
    @Test
    fun `历史版本的数字写法也收`() {
        assertEquals(TweakKind.CLEAR, read("""{"type":0}"""))
        assertEquals(TweakKind.MOVE, read("""{"type":1}"""))
        assertEquals(TweakKind.EXCHANGE, read("""{"type":2}"""))
    }

    @Test
    fun `大小写与空白不敏感`() {
        assertEquals(TweakKind.MOVE, read("""{"type":" move "}"""))
        assertEquals(TweakKind.EXCHANGE, read("""{"type":"EXCHANGE"}"""))
    }

    /** 手写文档更可能写中文；认一认总比报「无法识别」友好。 */
    @Test
    fun `中文写法也认`() {
        assertEquals(TweakKind.CLEAR, read("""{"type":"停课"}"""))
        assertEquals(TweakKind.MOVE, read("""{"type":"补课"}"""))
        assertEquals(TweakKind.EXCHANGE, read("""{"type":"对调"}"""))
    }

    /**
     * 认不出来必须是 **null**，绝不能回落成某个已知类型。
     *
     * 回落成 [TweakKind.CLEAR] 等于把一条看不懂的规则直接执行成「停课」，
     * 用户会莫名其妙少掉几天课，而且没有任何提示。
     */
    @Test
    fun `认不出来的类型是 null 而不是猜一个`() {
        assertNull(read("""{"type":"Frobnicate"}"""))
        assertNull(read("""{"type":9}"""))
        assertNull(read("""{"type":null}"""))
        assertNull(read("""{}"""))
        assertNull(TweakKind.ofRaw(true))
    }

    @Test
    fun `写出时统一用上游的字符串形态`() {
        val json = moshi.adapter(TweakHolder::class.java).toJson(TweakHolder(TweakKind.EXCHANGE))
        assertEquals("""{"type":"Exchange"}""", json)
    }
}
