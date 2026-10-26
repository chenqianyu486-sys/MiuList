package com.example.data.sync.web

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 局域网发现：电脑端广播探测包，手机应答自己那个**随机**的 HTTP 端口。
 *
 * 这些用例守的是「应答里那点东西」——
 * 电脑端完全靠它才知道该往哪儿发请求。
 */
class ServiceDiscoveryTest {

    @Test
    fun `只认探测包 - 局域网里别的程序也会往这个端口发包`() {
        val probe = ServiceDiscovery.PROBE.toByteArray()
        assertTrue(ServiceDiscovery.isProbe(probe, probe.size))
        // 前后带空白也认（有些实现会在包尾补换行）。
        val padded = "  MIULIST-DISCOVER/1 \n".toByteArray()
        assertTrue(ServiceDiscovery.isProbe(padded, padded.size))
    }

    @Test
    fun `不认识的一律不认 - 认宽松了就会去回不相干的请求`() {
        val wrong = "MIULIST-DISCOVER/2".toByteArray()
        assertFalse(ServiceDiscovery.isProbe(wrong, wrong.size))
        val noise = "hello".toByteArray()
        assertFalse(ServiceDiscovery.isProbe(noise, noise.size))
        assertFalse(ServiceDiscovery.isProbe(ByteArray(0), 0))
        // 长度越界也不能当成探测包（否则会读到旧数据）。
        val probe = ServiceDiscovery.PROBE.toByteArray()
        assertFalse(ServiceDiscovery.isProbe(probe, probe.size + 10))
    }

    @Test
    fun `应答里带着 HTTP 端口 - 手机那个端口是随机的`() {
        val json = ServiceDiscovery.replyPayload(41234, "我的手机", "1.0.5")
        assertTrue(json, json.contains("\"port\":41234"))
        assertTrue(json, json.contains("\"app\":\"MiuList\""))
        assertTrue(json, json.contains("我的手机"))
        assertTrue(json, json.contains("1.0.5"))
    }

    @Test
    fun `应答必须是合法 JSON - 设备名里有引号也不能把 JSON 弄坏`() {
        val json = ServiceDiscovery.replyPayload(1, "小\"明\"的手机", "1.0.5")
        // **必须用参数化的 Map 类型**：原始的 `Map::class.java` 拿不到值类型，
        // Moshi 会抛 JsonDataException（"Expected a string but was..."）——
        // 那说的是「类型信息不足」，不是「JSON 坏了」，两回事。
        val adapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        )
        val parsed = adapter.fromJson(json)
        assertEquals("小\"明\"的手机", parsed?.get("deviceName"))
        assertEquals(1, (parsed?.get("port") as Double).toInt())
    }

    @Test
    fun `应答里不放任何密钥 - 它只是「手机在哪」`() {
        val json = ServiceDiscovery.replyPayload(41234, "我的手机", "1.0.5")
        for (forbidden in listOf("secret", "kEnc", "kAuth", "kMac", "token", "key")) {
            assertFalse("应答里不该出现 $forbidden：$json", json.contains(forbidden))
        }
    }
}
