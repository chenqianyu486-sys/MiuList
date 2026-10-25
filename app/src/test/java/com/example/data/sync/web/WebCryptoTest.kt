package com.example.data.sync.web

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密码学原语的自测。
 *
 * ## 这里最要紧的是**跨实现互操作**
 *
 * 密钥派生与密封格式在网页端（`web/crypto.js`）和手机端（[WebCrypto]）各有一份实现。
 * 两边只要有一处对不齐，症状是「配对能过、之后每次请求都解密失败」——
 * 而这种错误在界面上看起来像是「同步坏了」，很难往「两边算法不一致」上想。
 *
 * 所以这里的向量是**用网页端那份实现生成**的（Node 跑 `web/crypto.test.mjs` 旁边
 * 那个脚本即可复现），Kotlin 侧必须逐字节对上。
 */
class WebCryptoTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ── 网页端生成的向量 ───────────────────────────────────────

    private val secret = unhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    private val masterHex = "b65c93e93131c33417e831749a3bf05bae0977b2b4001de1da2a24b201046e3b"
    private val kEncHex = "935899da05761fc500a98b3fc982f1e2e2b5365300143d755463e6584bf711d6"
    private val kAuthHex = "05a755e6fb2f4c4b20780d47d08d0e8ebb8699c4451a3689943e49bff696c4c7"
    private val kMacHex = "49e161f5c53b97b03064dfe984cd3e22bc9969ca6bfe0c11d71236e515471191"
    private val plainHex = "7b2268656c6c6f223a22e4b896e7958c222c226e223a31323334352c226c697374223a5b312c322c335d7d"
    private val sealedHex =
        "0188efeaa70bfdd6d58335706c6be519d42f37862cf4eb1da166a80ad465a0200496a498a3732e72b63ec6e25602c79ecff0b40553c43b711855a2301f5c337b0edfbe17f5300ac2877a5f347c9ad7153e9394d78a8e11"
    private val signature = "wH08SdAVv9bq9MyBECWVvFVqEZu8Q_aM7ki_fECa6mI"

    @Test
    fun `派生出的三把子密钥与网页端逐字节一致`() {
        val keys = WebCrypto.deriveKeys(secret)
        assertEquals(masterHex, hex(keys.master))
        assertEquals(kEncHex, hex(keys.kEnc))
        assertEquals(kAuthHex, hex(keys.kAuth))
        assertEquals(kMacHex, hex(keys.kMac))
    }

    @Test
    fun `网页端封的密文这里能解开`() {
        val keys = WebCrypto.deriveKeys(secret)
        val opened = WebCrypto.open(keys, unhex(sealedHex))
        assertEquals(plainHex, hex(opened))
    }

    @Test
    fun `请求签名与网页端一致`() {
        val keys = WebCrypto.deriveKeys(secret)
        val sig = WebCrypto.signRequest(
            keys = keys,
            method = "POST",
            path = "/api/snapshot/apply",
            ts = 1792936000000L,
            nonce = "AbCdEf1234567890",
            body = "{\"mode\":\"merge\"}".toByteArray(),
        )
        assertEquals(signature, sig)
    }

    @Test
    fun `密封往返一致 - 且 nonce 每次都不同`() {
        val keys = WebCrypto.deriveKeys(secret)
        val plain = "同一段明文".toByteArray()
        val a = WebCrypto.seal(keys, plain)
        val b = WebCrypto.seal(keys, plain)
        assertTrue("两次密封不应产生相同密文（nonce 必须随机）", !a.contentEquals(b))
        assertArrayEquals(plain, WebCrypto.open(keys, a))
        assertArrayEquals(plain, WebCrypto.open(keys, b))
    }

    @Test
    fun `改一个字节就会被验出来`() {
        val keys = WebCrypto.deriveKeys(secret)
        val sealed = WebCrypto.seal(keys, "内容".toByteArray())
        val tampered = sealed.copyOf()
        tampered[15] = (tampered[15].toInt() xor 0x01).toByte()
        var caught = false
        try {
            WebCrypto.open(keys, tampered)
        } catch (_: Exception) {
            caught = true
        }
        assertTrue("篡改必须被发现", caught)
    }

    // ── 官方向量 ───────────────────────────────────────────────

    @Test
    fun `ChaCha20 对上 RFC 8439 的测试向量`() {
        val key = ByteArray(32) { it.toByte() }
        // RFC 8439 §2.4.2 的 nonce：00×7 + 4a + 00×4
        val nonce = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0x4a, 0, 0, 0, 0)
        val plain = (
            "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
            ).toByteArray()
        val ct = WebCrypto.chacha20(key, nonce, 1, plain)
        assertEquals(
            "6e2e359a2568f98041ba0728dd0d6981",
            hex(ct.copyOfRange(0, 16)),
        )
        assertEquals(plain.size, ct.size)
    }

    @Test
    fun `HMAC-SHA256 对上 RFC 4231 的用例 1`() {
        val key = ByteArray(20) { 0x0b.toByte() }
        val mac = WebCrypto.hmacSha256(key, "Hi There".toByteArray())
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", hex(mac))
    }

    @Test
    fun `SHA-256 对上已知向量`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hex(WebCrypto.sha256(ByteArray(0))),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex(WebCrypto.sha256("abc".toByteArray())),
        )
    }

    @Test
    fun `base64url 往返 - 且没有 padding 与 URL 危险字符`() {
        val data = ByteArray(40) { (it * 7).toByte() }
        val encoded = WebCrypto.base64Url(data)
        assertTrue(!encoded.contains('='))
        assertTrue(!encoded.contains('+'))
        assertTrue(!encoded.contains('/'))
        assertArrayEquals(data, WebCrypto.fromBase64Url(encoded))
    }

    @Test
    fun `随机字节每次都不一样`() {
        val a = WebCrypto.randomBytes(32)
        val b = WebCrypto.randomBytes(32)
        assertNotNull(a)
        assertTrue(!a.contentEquals(b))
    }
}
