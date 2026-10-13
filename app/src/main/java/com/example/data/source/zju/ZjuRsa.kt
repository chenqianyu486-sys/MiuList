package com.example.data.source.zju

import java.math.BigInteger

/**
 * 浙大 CAS 登录用的 **裸 RSA（教科书式，无填充）**。
 *
 * ## 这里的实现换过一版，别再按 `security.js` 那套改回来
 *
 * 早期版本逐位复刻了 zjuam 页面上的 `js/login/security.js`：先把密码**反转**，
 * 再按 `chunkSize = 2 × biHighIndex(biFromHex(modulus))` 分块，每块当小端的
 * 16 位字序列做 `m^e mod n`。那一套在**当时**能登进去，但它是跟着老页面走的，
 * 页面一改就整体失效 —— 而且失效的表现极其难认（见下）。
 *
 * 现在 zjuam 下发给 zdbk 的公钥实际是按**最简单的教科书式**消费的，
 * 和上游 Go 的 `PubKey.encrypt`、活跃维护的 `zju-ical-py` 的
 * `int.from_bytes(password, "big")` 逐字一致：
 *
 * ```
 * plain = BigInteger(1, password.toByteArray(UTF_8))   // 大端，不反转
 * cipher = plain.modPow(e, n)                            // 无 PKCS#1 / PSS 填充
 * hex    = cipher.toString(16).padStart(len, '0')        // 小写，左侧补零
 * ```
 *
 * 三个容易写错的点，逐条钉在下面的实现与单测里：
 *
 * 1. **大端，不反转**。`BigInteger(1, bytes)` 就是 `int.from_bytes(b, "big")`；
 *    用 `BigInteger(bytes)`（有符号）或 `reversedArray()`（小端）都会算出另一个数。
 * 2. **左侧补零到模数长度**。`modPow` 的结果可能比模数短几位，不补零的话
 *    CAS 解出来的 hex 位数对不上——而 CAS 只会回一句「用户名或密码错误」。
 * 3. **小写**。Kotlin 的 `toString(16)` 本身就是小写，别 `uppercase()`。
 *
 * ## 早先那套「反转 + 分块」实现其实等价，别改回来
 *
 * 老实现逐位复刻了 zjuam 页面上的 `js/login/security.js`：先把密码反转，
 * 再按 `chunkSize = 2 × biHighIndex(biFromHex(modulus))` 分块，每块当小端的
 * 16 位字序列做 `m^e mod n`。它那套推导（反转、chunkSize=62、16 位 digit 小端、
 * hex 小写补齐）单看每一处都对，但**整体结果与这里的大端写法一字不差**——
 * 因为「先 `reversed()` 再 `reversedArray()`」两次反转互相抵消，
 * 末尾的补零在单块密码（远短于 62 字节）里也不参与运算。
 *
 * 实证：`Abc123!`、`x` 两组向量在新旧两套实现下输出完全相同
 * （`473a9454…24ea7a935532cd4d0` / `5c54b4a1…bca3caf0`）。
 *
 * 换版本的理由**不是**旧实现算错，而是它对长密码会真的分块、
 * 而分块宽度依赖那段早已不再维护的页面脚本 —— 现在服务端按最朴素的
 * 教科书式消费，长密码（>62 字节）只有大端这一种解释是对的。
 *
 * 顺带一个排查教训：加密约定错了，CAS 的反馈永远只是「用户名或密码错误」，
 * 和「密码真写错了」在响应里完全一样。所以这类约定不能靠「没报错」反推，
 * 必须对着一个**已知能用的实现**核对向量。
 *
 * 纯 JVM，无 Android 依赖，可直接单测（见 `ZjuRsaTest`，
 * 期望值由独立实现的参考算法现算，不是本实现的输出）。
 */
object ZjuRsa {

    /**
     * 用服务端公钥加密密码，返回提交给 CAS 的那个小写 hex 串。
     *
     * @param modulusHex 公钥模数（hex）
     * @param exponentHex 公钥指数（hex，线上实测是 "10001"，5 个字符不是 6 个）
     */
    fun encryptPassword(
        plain: String,
        modulusHex: String,
        exponentHex: String
    ): String {
        val modulus = BigInteger(modulusHex.trim(), 16)
        val exponent = BigInteger(exponentHex.trim(), 16)

        require(modulus.signum() > 0) { "公钥模数无法解析" }

        // 大端解释 UTF-8 字节，不反转、不分块。
        val m = BigInteger(1, plain.toByteArray(Charsets.UTF_8))

        val cipher = m.modPow(exponent, modulus)

        // 补零宽度取**模数自身的 hex 长度**而不是写死 128：写死是照抄上游，
        // 但浙大将来换一把长度的密钥时，写死的那版会静默产出位数不对的密文。
        // 当前线上是 512 bit → 128 个 hex 字符，两者一致。
        val width = modulusHex.trim().trimStart('0').length.let { if (it % 2 == 0) it else it + 1 }
        return cipher.toString(16).padStart(width, '0')
    }
}
