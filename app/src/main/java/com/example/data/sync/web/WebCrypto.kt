package com.example.data.sync.web

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 局域网同步用的密码学原语（手机端）。
 *
 * ## 为什么和网页端各写一份
 *
 * 网页端跑在**明文 HTTP** 的页面上 —— 那种页面不是 secure context，
 * `crypto.subtle` 直接是 undefined，所以 SHA-256 / HMAC / ChaCha20 只能手写。
 *
 * 这边（Kotlin）本来能用 `javax.crypto` 的标准算法，但**必须和网页端逐字节一致**，
 * 否则同一个 secret 派生出来的密钥会不一样，表现为「配对能过、之后全都解密失败」。
 * 所以这里的算法与 `web/crypto.js` 是**同一份逻辑的两处实现**，
 * 两边都有测试对着同一组向量跑（见 [WebCryptoTest] 与 `web/crypto.test.mjs`）。
 *
 * ## 算法选择
 *
 * **ChaCha20（流密码）+ HMAC-SHA256（完整性）**，先加密再认证：
 * - ChaCha20 的核心只有加法/异或/旋转，没有查表，纯实现容易写对，
 *   而且能用 **RFC 8439 的官方测试向量**逐位验证；
 * - 不用 AES-GCM 是因为它的 GHASH 在 JavaScript 里没有 64 位运算，
 *   只能靠 BigInt 或拆 32 位对，又慢又容易写错；
 * - 手机端用 `javax.crypto` 做不到 ChaCha20（`ChaCha20-Poly1305` 要 API 28+，
 *   本工程 minSdk 24），自己实现反而两边一致。
 */
object WebCrypto {

    /** 派生出来的三把子密钥。 */
    data class Keys(
        val master: ByteArray,
        /** ChaCha20 密钥（32 字节）。 */
        val kEnc: ByteArray,
        /** 请求签名用的 HMAC 密钥。 */
        val kAuth: ByteArray,
        /** 密文 MAC 用的 HMAC 密钥（**必须与 kEnc 不同**）。 */
        val kMac: ByteArray,
    )

    // ── SHA-256 ────────────────────────────────────────────────

    fun sha256(message: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(message)

    // ── HMAC-SHA256 ────────────────────────────────────────────

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    // ── ChaCha20 ───────────────────────────────────────────────

    private fun rotl32(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = rotl32(x[d] xor x[a], 16)
        x[c] += x[d]; x[b] = rotl32(x[b] xor x[c], 12)
        x[a] += x[b]; x[d] = rotl32(x[d] xor x[a], 8)
        x[c] += x[d]; x[b] = rotl32(x[b] xor x[c], 7)
    }

    private fun chacha20Block(key: ByteArray, counter: Int, nonce: ByteArray): IntArray {
        val state = IntArray(16)
        state[0] = 0x61707865; state[1] = 0x3320646e
        state[2] = 0x79622d32; state[3] = 0x6b206574
        for (i in 0 until 8) {
            state[4 + i] = (key[i * 4].toInt() and 0xff) or
                ((key[i * 4 + 1].toInt() and 0xff) shl 8) or
                ((key[i * 4 + 2].toInt() and 0xff) shl 16) or
                ((key[i * 4 + 3].toInt() and 0xff) shl 24)
        }
        state[12] = counter
        for (i in 0 until 3) {
            state[13 + i] = (nonce[i * 4].toInt() and 0xff) or
                ((nonce[i * 4 + 1].toInt() and 0xff) shl 8) or
                ((nonce[i * 4 + 2].toInt() and 0xff) shl 16) or
                ((nonce[i * 4 + 3].toInt() and 0xff) shl 24)
        }
        val x = state.copyOf()
        repeat(10) {
            quarterRound(x, 0, 4, 8, 12)
            quarterRound(x, 1, 5, 9, 13)
            quarterRound(x, 2, 6, 10, 14)
            quarterRound(x, 3, 7, 11, 15)
            quarterRound(x, 0, 5, 10, 15)
            quarterRound(x, 1, 6, 11, 12)
            quarterRound(x, 2, 7, 8, 13)
            quarterRound(x, 3, 4, 9, 14)
        }
        for (i in 0 until 16) x[i] += state[i]
        return x
    }

    /**
     * ChaCha20 流密码。
     *
     * @param key 32 字节
     * @param nonce 12 字节
     * @param counter 起始块计数（RFC 8439 里从 1 开始）
     */
    fun chacha20(key: ByteArray, nonce: ByteArray, counter: Int, data: ByteArray): ByteArray {
        require(key.size == 32) { "ChaCha20 密钥必须是 32 字节" }
        require(nonce.size == 12) { "ChaCha20 nonce 必须是 12 字节" }
        val out = ByteArray(data.size)
        var offset = 0
        var c = counter
        while (offset < data.size) {
            val block = chacha20Block(key, c, nonce)
            c += 1
            val take = minOf(64, data.size - offset)
            for (i in 0 until take) {
                out[offset + i] = (data[offset + i].toInt() xor (block[i shr 2] ushr ((i and 3) * 8) and 0xff)).toByte()
            }
            offset += take
        }
        return out
    }

    // ── 密钥派生 ───────────────────────────────────────────────

    /**
     * 从配对 secret 派生三把子密钥。
     *
     * 标签（label）必须与 `web/crypto.js` **逐字相同**，
     * 否则两端派生出的密钥不同 —— 症状是「配对成功但之后全都解密失败」。
     *
     * @param passcode 同步密码（可空）。它会先混进 secret，
     *   于是「二维码被别人拍到」不足以解密，还得知道这个密码。
     */
    fun deriveKeys(secret: ByteArray, passcode: String = ""): Keys {
        val effective = if (passcode.isEmpty()) {
            secret
        } else {
            hmacSha256(secret, "miulist-pw-v3$passcode".toByteArray(Charsets.UTF_8))
        }
        val master = hmacSha256(effective, "miulist-master-v3".toByteArray(Charsets.UTF_8))
        return Keys(
            master = master,
            kEnc = hmacSha256(master, "miulist-enc-v3".toByteArray(Charsets.UTF_8)),
            kAuth = hmacSha256(master, "miulist-auth-v3".toByteArray(Charsets.UTF_8)),
            kMac = hmacSha256(master, "miulist-mac-v3".toByteArray(Charsets.UTF_8)),
        )
    }

    // ── 密封与开启 ─────────────────────────────────────────────

    /** 加密一段负载：nonce(12) ‖ 密文 ‖ MAC(32)。 */
    fun seal(keys: Keys, plaintext: ByteArray): ByteArray {
        val nonce = randomBytes(12)
        val ciphertext = chacha20(keys.kEnc, nonce, 1, plaintext)
        val mac = hmacSha256(keys.kMac, nonce + ciphertext)
        return nonce + ciphertext + mac
    }

    /** 验签并解密。**先验后解** —— MAC 不对就抛，不要先解再验。 */
    fun open(keys: Keys, sealed: ByteArray): ByteArray {
        require(sealed.size >= 12 + 32) { "密文太短" }
        val nonce = sealed.copyOfRange(0, 12)
        val ciphertext = sealed.copyOfRange(12, sealed.size - 32)
        val mac = sealed.copyOfRange(sealed.size - 32, sealed.size)
        val expect = hmacSha256(keys.kMac, nonce + ciphertext)
        var diff = 0
        for (i in 0 until 32) diff = diff or (mac[i].toInt() xor expect[i].toInt())
        if (diff != 0) throw IllegalArgumentException("校验失败：数据被改过")
        return chacha20(keys.kEnc, nonce, 1, ciphertext)
    }

    // ── 请求签名 ───────────────────────────────────────────────

    /**
     * 签一次请求。覆盖：版本、方法、路径、时间戳、nonce、请求体摘要。
     *
     * **请求体也要进签名** —— 否则抓包的人可以把密文换成另一段密文。
     */
    fun signRequest(
        keys: Keys,
        method: String,
        path: String,
        ts: Long,
        nonce: String,
        body: ByteArray,
    ): String {
        val bodyHash = sha256(body)
        val canonical = buildString {
            append("v3\n")
            append(method.uppercase()).append('\n')
            append(path).append('\n')
            append(ts).append('\n')
            append(nonce).append('\n')
            append(base64Url(bodyHash))
        }.toByteArray(Charsets.UTF_8)
        return base64Url(hmacSha256(keys.kAuth, canonical))
    }

    // ── base64url ──────────────────────────────────────────────

    /**
     * URL 安全的 base64（无 padding）。
     *
     * **自己实现而不用 `java.util.Base64`**：那个类要 API 26+，
     * 而本工程 minSdk 是 24。
     */
    fun base64Url(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xff) shl 16) or
                ((bytes[i + 1].toInt() and 0xff) shl 8) or
                (bytes[i + 2].toInt() and 0xff)
            sb.append(B64[(n shr 18) and 63]).append(B64[(n shr 12) and 63])
            sb.append(B64[(n shr 6) and 63]).append(B64[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xff) shl 16
                sb.append(B64[(n shr 18) and 63]).append(B64[(n shr 12) and 63])
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8)
                sb.append(B64[(n shr 18) and 63]).append(B64[(n shr 12) and 63]).append(B64[(n shr 6) and 63])
            }
        }
        return sb.toString()
    }

    /** base64url → 字节。 */
    fun fromBase64Url(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(text.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (ch in text) {
            if (ch == '=') break
            // **不要再把 '-'/'_' 换成 '+'/'/'** —— [B64] 用的是 **URL 安全**字母表，
            // 它的第 62、63 位就是 '-' 与 '_'。换成标准字母表之后，
            // 查表会全部落空（`v < 0` 被跳过），于是**每两个字符丢一个字节**。
            val v = B64_INV[ch.code]
            if (v < 0) continue
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }

    fun randomBytes(n: Int): ByteArray {
        val out = ByteArray(n)
        val random = java.security.SecureRandom()
        random.nextBytes(out)
        return out
    }

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private val B64_INV = IntArray(128) { -1 }.also { inv ->
        B64.forEachIndexed { index, c -> inv[c.code] = index }
    }
}
