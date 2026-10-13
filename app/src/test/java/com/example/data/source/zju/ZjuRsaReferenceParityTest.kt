package com.example.data.source.zju

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

/**
 * 生产 [ZjuRsa] 与一份**独立写出来的参考实现**并排比对。
 *
 * 这份参考实现是照 zjuam 当前的消费方式另写一遍的，刻意不复用生产代码的任何
 * 私有辅助函数。它存在的意义是把「算法本身对不对」和「生产实现有没有抄错」分成两件事：
 *
 * - [ZjuRsaTest] 用固定向量钉住结果（向量由这份参考实现算出）；
 * - 这里直接让两者同输入同输出，防止以后有人「顺手优化」其中一边时只改了一处。
 *
 * ## 与早期版本的差别
 *
 * 早先这里比对的是「密码反转 + chunkSize 分块 + 16 位 digit 小端」那套
 * `security.js` 复刻。现在生产实现已换成朴素的**大端教科书式**
 * （与上游 Go 的 `PubKey.encrypt` 和 `zju-ical-py` 的
 * `int.from_bytes(password, "big")` 一致），参考实现同步换成同一套。
 *
 * 顺带记一笔当时查明的等价性：老实现**对短密码输出与新实现完全相同**，
 * 因为「先 `reversed()` 再 `reversedArray()`」两次反转互相抵消 ——
 * 这解释了为什么当时能登进去，也说明那种写法只是「碰巧对」，
 * 碰上超过 62 字节的长密码就会分块而分错。
 */
class ZjuRsaReferenceParityTest {

    /** 线上真实公钥，2026-10-13 实抓。 */
    private val modulus =
        "8fc2764d8fb915ce7b05e2de00fd42832d0b9be9a896f6b7a6ac5a59755070f" +
            "697e794303b058d00903fae8cd8a87b4a734ca1488bf0c926a3418938e7801761"
    private val exponent = "10001"

    /**
     * 参考实现：与大端教科书式的生产代码同算法，但独立成一份。
     *
     * 关键点是**大端**且**不填充**：`int.from_bytes` 就是 `BigInteger(1, bytes)`。
     * 用 `BigInteger(bytes)`（有符号）或 `reversedArray()`（小端）都算出另一个数。
     */
    private fun reference(plain: String, modulusHex: String, exponentHex: String): String {
        val n = BigInteger(modulusHex, 16)
        val e = BigInteger(exponentHex, 16)
        val m = BigInteger(1, plain.toByteArray(Charsets.UTF_8))
        val cipher = m.modPow(e, n)

        // 左侧补零到模数 hex 长度（补到奇数长度时进位成偶数，与 Kotlin 侧一致）
        val width = modulusHex.trimStart('0').length.let { if (it % 2 == 0) it else it + 1 }
        return cipher.toString(16).padStart(width, '0')
    }

    @Test
    fun `生产实现与参考实现对同一批输入输出完全一致`() {
        val inputs = listOf(
            "Abc123!",
            "p@ssw0rd2026",
            "x",
            "ABCDEFGHIJ",
            "1",
            "Aa1!Bb2@Cc3#",
            "zju-test-pw-0001",
            "含中文的密码",
            "带 空格 与~符号",
            "A".repeat(62),     // 恰好落在老 chunkSize 的边界上
            "B".repeat(63),     // 跨过那条边界
            "C".repeat(200)     // 远超那条边界，长密码只有大端一种解释
        )

        inputs.forEach { plain ->
            assertEquals(
                "参考实现与生产实现对 \"${plain.take(12)}\" 的输出不一致",
                reference(plain, modulus, exponent),
                ZjuRsa.encryptPassword(plain, modulus, exponent)
            )
        }
    }

    @Test
    fun `密文永远落在模数范围内`() {
        // 无填充的裸 RSA 里，密文必然小于模数；越界说明指数或模数取错了。
        val n = BigInteger(modulus, 16)
        listOf("Abc123!", "zju-test-pw-0001", "C".repeat(200)).forEach { plain ->
            val out = BigInteger(ZjuRsa.encryptPassword(plain, modulus, exponent), 16)
            assertTrue("密文应小于模数", out < n)
        }
    }

    @Test
    fun `密文长度等于模数的 hex 长度且全为小写`() {
        val out = ZjuRsa.encryptPassword("Abc123!", modulus, exponent)
        assertEquals(128, out.length)
        assertEquals("必须全为小写 hex", out.lowercase(), out)
    }

    @Test
    fun `加密是确定性的 同一明文两次得到同一密文`() {
        // 无填充的裸 RSA 没有随机性，所以可以拿固定向量做断言；
        // 一旦有人改成带 PKCS#1 填充的 Cipher，这条会立刻炸掉。
        assertEquals(
            ZjuRsa.encryptPassword("zju-test-pw-0001", modulus, exponent),
            ZjuRsa.encryptPassword("zju-test-pw-0001", modulus, exponent)
        )
    }

    @Test
    fun `线上指数就是 10001 而不是 010001`() {
        assertEquals("10001", exponent)
        assertEquals(5, exponent.length)
    }

    private fun assertTrue(message: String, condition: Boolean) =
        org.junit.Assert.assertTrue(message, condition)
}
