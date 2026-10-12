package com.example.data.source.zju

import java.math.BigInteger

/**
 * 浙大 CAS 登录用的**裸 RSA**。
 *
 * 逐位复刻 zju-ical `pkg/zjuam/zjuam.go` 的 `encrypt`：
 *
 * ```
 * hex.Encode(dst, []byte(payload))     // 明文 UTF-8 字节 → hex 字符串
 * m.SetString(string(dst), 16)         // hex 字符串 → 大整数
 * c.Exp(m, big.NewInt(p.E), p.N)       // c = m^e mod n
 * r := fmt.Sprintf("%x", c)            // 结果 → 小写 hex
 * r = strings.Repeat("0", 128-len(r)) + r   // 左侧补零到 128 字符
 * ```
 *
 * **注意它没有 PKCS#1 / PSS 填充**，就是教科书式的 `m^e mod n`。所以：
 * - 不能用 `Cipher.getInstance("RSA")`，那会自己加填充，格式和服务器对不上；
 * - 明文必须短到 `m < n`，正常密码长度远小于 1024 位模数，没问题。
 *
 * 纯 JVM，无 Android 依赖，可直接单测（见 `ZjuRsaTest`）。
 */
object ZjuRsa {

    /** 上游固定补零到 128 个 hex 字符（1024 位模数）。 */
    private const val PADDED_HEX_LENGTH = 128

    /**
     * 用服务端公钥加密密码，返回提交给 CAS 的那个 128 位 hex 串。
     *
     * @param modulusHex 公钥模数（hex）
     * @param exponentHex 公钥指数（hex，常见是 "010001"）
     */
    fun encryptPassword(
        plain: String,
        modulusHex: String,
        exponentHex: String
    ): String {
        val hex = plain.toByteArray(Charsets.UTF_8).joinToString("") {
            // Go 的 hex.Encode 是小写两位
            "%02x".format(it.toInt() and 0xFF)
        }
        val m = BigInteger(hex, 16)
        val n = BigInteger(modulusHex, 16)
        val e = BigInteger(exponentHex, 16)
        val c = m.modPow(e, n)
        val encoded = c.toString(16)
        return if (encoded.length >= PADDED_HEX_LENGTH) {
            encoded
        } else {
            "0".repeat(PADDED_HEX_LENGTH - encoded.length) + encoded
        }
    }
}