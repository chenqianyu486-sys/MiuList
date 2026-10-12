package com.example.data.source.zju

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浙大 CAS 的裸 RSA。
 *
 * 期望值是用**独立的一份参考实现**（Python 的 `pow(m, e, n)`）现算出来的，
 * 不是把本实现跑一遍抄回来 —— 后者只能证明「改了之后输出没变」，
 * 证明不了它和 Go 上游一致。
 */
class ZjuRsaTest {

    private val modulus =
        "8fd7fa690b0b0cf8f897127c005d3def0ce98e8dca000ab00205cd156818f097"
    private val exponent = "010001"

    @Test
    fun `a simple password matches the reference vector`() {
        assertEquals(
            "0000000000000000000000000000000000000000000000000000000000000000" +
                "6f13bbcd7fd9d1088a78e5583780e360d76de49fb74e0f84c1fa3d9ffa079925",
            ZjuRsa.encryptPassword("Abc123!", modulus, exponent)
        )
    }

    @Test
    fun `a long password with symbols also matches the reference vector`() {
        assertEquals(
            "0000000000000000000000000000000000000000000000000000000000000000" +
                "86e98d8753abfc48d4bd49b7a5a8e107df964a9f52c73430ea16caa54a71a05d",
            ZjuRsa.encryptPassword("p@ssw0rd2026", modulus, exponent)
        )
    }

    @Test
    fun `the result is always padded to the modulus width`() {
        // 单字符密文只有 1 个有效位，不补零的话长度随明文变化，
        // 服务端按固定 128 位解析就会直接失败。
        val out = ZjuRsa.encryptPassword("x", modulus, exponent)
        assertEquals(128, out.length)
        assertEquals(
            "0000000000000000000000000000000000000000000000000000000000000000" +
                "1314f8f467863f39931b8ec31f7502e1bc8c1a09ab74f28a6efe8bff816a1ff9",
            out
        )
    }

    @Test
    fun `the hex encoding is lowercase two-digit per byte like the Go original`() {
        // Go 的 hex.Encode 输出小写、每字节两位；`%02x` 在 Kotlin 里对负数字节
        // 会变成 8 位，若不用 and 0xFF 就会把密文撑长一倍。
        val upper = ZjuRsa.encryptPassword("ABCDEFGHIJ", modulus, exponent)
        assertTrue("应全为小写：$upper", upper.none { it.isUpperCase() })
        assertEquals(128, upper.length)
    }

    @Test
    fun `the same password always encrypts to the same string`() {
        // 教科书 RSA 没有随机填充，所以同一明文两次加密必须完全一致 ——
        // 这正是能逐位对齐上游的原因，也是它弱于 PKCS#1 的地方。
        assertEquals(
            ZjuRsa.encryptPassword("Abc123!", modulus, exponent),
            ZjuRsa.encryptPassword("Abc123!", modulus, exponent)
        )
    }
}