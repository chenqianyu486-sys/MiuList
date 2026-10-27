package com.example.data.sync.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * DH 密钥协商的自测。
 *
 * ## 这里最要紧的是**跨实现互操作**
 *
 * 密钥协商在网页端（`web/crypto.js`）和手机端（[WebDh]）各有一份实现
 * （JS 用 BigInt，Kotlin 用 BigInteger）。
 * 两边只要有一处对不齐，症状是「配对显示成功、之后每次请求都解密失败」——
 * 而这种错误在界面上看起来像是「同步坏了」，很难往「两边算法不一致」上想。
 *
 * 所以这里的向量与 `web/crypto.test.mjs` 里的是**同一组**，
 * 两边必须逐字节对上。
 */
class WebDhTest {

    private fun b64(s: String): ByteArray = WebCrypto.fromBase64Url(s)

    private fun b64(bytes: ByteArray): String = WebCrypto.base64Url(bytes)

    // ── 跨实现向量（与 web/crypto.test.mjs 同一组） ──────────────

    private val privA = b64("SGwJzYUjf42RFVrrgD7N5YCbKpUpIjYm5hTseHYtiMk")
    private val pubA =
        "oF4x3H1Bih-U0yGAiJupaLn2vQfqafmAa3ngN1jjB5Q7LKtXLcd5M-btsl7q4dSzbntSz6byDBH3HgWT8Zi5nWgHS1kVudDHpAZxjVTqPd96b0B2zXEQv226xKZo3JZJ3VWg5QH1_zFWSYgTsbiOU01CwyAPb5oZyghPOyZyCyYzpfB9jSaG-GBEr2mlBXbX7UnqINHKEs9_qJ4o36EgbGu_okAidtPktho8gIwqewNwU4jH-paD9M-j61AGTDKH3eltwLaDfjzr_39cDxaqKAOR9rHZjgNKAWcsFazzVPC5V258_nTTrhkENzaMHqVPfEsZ_pwous5ogs8H1gijBA"
    private val privB = b64("1oxEBNFNm7FBsy8PheU-raGzlfkKr0kEV5ORJLz31Oo")
    private val pubB =
        "Rn64Z7aPzX3ybA11oK1pAQEDNHvcwEHOhRtHFWb7RcghLds9cjyFxPwCYIgJiB1VpE1hfKMPL1If1__svnZWdfIqFeNt7sKvlPO3ELXF5-R323Y2oHzjvbFS7N2nICYdDSSTFQjRA9-qfCYIRB6aXZdXyBGZG0PQM1dPe6wdO4jRFhkoCA8MFwYCiAY76MGCkABekSOJo5oSOpFxE476KAUj7bFxDKjEg31JAcshWs7AwS1ZFLE7EHmZNzc5kwkER8uJYGVQsyDUCWlSmSyS3mBMCNhkl4HolhLhdcZWizUCIaI9ocYRPVSdi5ROnxY5HRKCXcVg4HpNDqF64jNyEQ"
    private val shared = "mOILLb-KxF2Ne3O_syKW6Qh43qhW0KXTO5Dh7Xk1vEQ"
    /** 协议 v3.2 的配对码向量：H(A‖B)，与 `web/crypto.test.mjs` 同一组。 */
    private val pairCodeAB = "GPP7WE"

    @Test
    fun `模数是 2048 位安全素数`() {
        val hex = WebDh.groupPrimeHex()
        assertEquals(512, hex.length)
        val p = BigInteger(hex, 16)
        assertEquals(BigInteger.valueOf(23), p.mod(BigInteger.valueOf(24)))
    }

    @Test
    fun `由私钥算出的公钥与网页端逐字节一致`() {
        assertEquals(pubA, b64(WebDh.publicKey(privA)))
        assertEquals(pubB, b64(WebDh.publicKey(privB)))
    }

    @Test
    fun `两端算出的共享密钥一致 - 且与网页端向量相同`() {
        val s1 = WebDh.sharedSecret(privA, b64(pubB))
        val s2 = WebDh.sharedSecret(privB, b64(pubA))
        assertEquals(shared, b64(s1))
        assertEquals(shared, b64(s2))
        assertEquals(32, s1.size)
    }

    @Test
    fun `配对码与网页端一致 - 且不含容易混淆的字母`() {
        // 协议 v3.2：配对码是 **H(A‖B)**（双方公钥的转录绑定），
        // 与 `web/crypto.test.mjs` 里那组向量逐字对上。
        val code = WebDh.pairCode(b64(pubA), b64(pubB))
        assertEquals(pairCodeAB, code)
        assertEquals(6, code.length)
        assertTrue(!code.contains('I') && !code.contains('L') && !code.contains('O') && !code.contains('U'))
    }

    @Test
    fun `配对码与公钥顺序有关 - 换序就是另一个码`() {
        assertNotEquals(WebDh.pairCode(b64(pubA), b64(pubB)), WebDh.pairCode(b64(pubB), b64(pubA)))
    }

    @Test
    fun `退化公钥一律被拒 - 否则共享密钥会变成公开常量`() {
        // 值 1：共享值会退化成 1，secret = sha256(定长的 1)，谁都能算出来。
        val one = ByteArray(WebDh.KEY_BYTES).also { it[it.lastIndex] = 1 }
        val zero = ByteArray(WebDh.KEY_BYTES)
        // p-1：同样会退化。
        val pMinus1 = WebDh.groupPrimeHex().let { hex ->
            val p = java.math.BigInteger(hex, 16).subtract(java.math.BigInteger.ONE)
            val out = ByteArray(WebDh.KEY_BYTES)
            val raw = p.toByteArray()
            System.arraycopy(raw, maxOf(0, raw.size - WebDh.KEY_BYTES), out, WebDh.KEY_BYTES - minOf(raw.size, WebDh.KEY_BYTES), minOf(raw.size, WebDh.KEY_BYTES))
            out
        }
        for (bad in listOf(one, zero, pMinus1)) {
            try {
                WebDh.validatePublicKey(bad)
                throw AssertionError("退化公钥必须被拒")
            } catch (e: IllegalArgumentException) {
                // 预期之内
            }
        }
        // 长度不对的也要拒
        try {
            WebDh.validatePublicKey(ByteArray(10))
            throw AssertionError("长度不对的公钥必须被拒")
        } catch (e: IllegalArgumentException) {
            // 预期之内
        }
        // 正常公钥要能过
        WebDh.validatePublicKey(b64(pubA))
    }

    @Test
    fun `随机密钥对也能协商出同一把密钥`() {
        val (priv1, pub1) = WebDh.generateKeypair()
        val (priv2, pub2) = WebDh.generateKeypair()
        assertEquals(32, priv1.size)
        assertEquals(256, pub1.size)
        assertEquals(
            b64(WebDh.sharedSecret(priv1, pub2)),
            b64(WebDh.sharedSecret(priv2, pub1)),
        )
        assertNotEquals(
            b64(WebDh.sharedSecret(priv1, pub2)),
            b64(WebDh.sharedSecret(priv1, pub1)),
        )
    }
}
