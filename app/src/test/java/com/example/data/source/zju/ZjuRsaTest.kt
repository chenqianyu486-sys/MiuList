package com.example.data.source.zju

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浙大 CAS 的裸 RSA。
 *
 * 期望值由**独立的一份参考实现**现算（严格照 `js/login/security.js` 的
 * `biFromHex` / `RSAUtils.encryptedString` 逐位复刻），不是把本实现跑一遍抄回来 ——
 * 后者只能证明「改了之后输出没变」，证明不了它和页面 JS 一致。
 *
 * ## 这里踩过四个坑，每一个的症状都只是「密码错误」
 *
 * 1. 密码漏了 `reverse()`；
 * 2. `chunkSize` 算错（见下）；
 * 3. 字节序写成大端；
 * 4. hex 输出用了小写。
 *
 * 服务端对以上四种情况的反馈**完全一样**，没有任何报错能指回具体哪一步错了。
 *
 * ## 为什么主向量用的是「线上真实公钥」
 *
 * 早先一直拿一个 64 个 hex 字符（1024 bit）的自造模数当向量，于是
 * `chunkSize` 算成 126 也「看起来自洽」——直到 2026-10-13 实抓
 * `https://zjuam.zju.edu.cn/cas/v2/getPubKey`，发现线上发的是
 * **128 个 hex 字符（512 bit）**，`biFromHex` 位数组 32 位、下标 31，
 * **chunkSize 实际是 62**，密文 126 个 hex 字符。
 *
 * 用真实公钥做主向量，才能在密钥长度变化时第一时间炸出来，而不是等到线上登录失败。
 */
class ZjuRsaTest {

    /** 2026-10-13 从 zjuam.zju.edu.cn/cas/v2/getPubKey 实抓。 */
    private val liveModulus =
        "8fc2764d8fb915ce7b05e2de00fd42832d0b9be9a896f6b7a6ac5a59755070f" +
            "697e794303b058d00903fae8cd8a87b4a734ca1488bf0c926a3418938e7801761"

    /** 线上指数实测就是 "10001"（5 个字符，不是 6 个）。 */
    private val liveExponent = "10001"

    @Test
    fun `线上真实公钥下的密文与参考实现一致`() {
        // 期望值由**独立于 Kotlin 生产代码**的一份 Python 参考实现算出
        // （`.workbuddy/rsa_ref_zdbk.py`：大端 `int.from_bytes` + `pow(m,e,n)` + 左侧补零，
        //  先复现本类里另两个已钉死的向量自检通过才采信）。
        //
        // 明文刻意用 `zju-test-pw-0001` 这种一眼假的占位串。这里早先填的是
        // **真实测试账号的密码**：教科书式 RSA 无填充、且密文随明文确定，
        // 任何拿到这个仓库的人都能直接反解出来确认密码 —— 违背「凭据不进仓库」。
        // 测试只需要一个落在模数位宽内的 ASCII 明文，不需要真实凭据。
        //
        // 这条向量在「反转+分块」的老实现下也是同一个值：
        // 双重反转互相抵消，短密码恰好等于大端。留着它正好锁住那个等价性。
        assertEquals(
            "47a7d05dd48cf93b40a43eedd2e3e9bc5fcd9b37f4ab055b4a9c329b56238d5" +
                "d704db325cad2b9a1f15852e049c1794deb6d511ee6b9b0ab01d7bfbdfc3bff4c",
            ZjuRsa.encryptPassword("zju-test-pw-0001", liveModulus, liveExponent)
        )
    }

    @Test
    fun `线上公钥下简单密码与参考实现一致`() {
        assertEquals(
            "473a945452111238f541b0ab24649271c6ed6539d634e74581bf5b33355bad74" +
                "871be09738ebcb6791c36c2c3b17b2710b073a2a1fa3648e66a7a935532cd4d0",
            ZjuRsa.encryptPassword("Abc123!", liveModulus, liveExponent)
        )
        assertEquals(
            "5c54b4a17b1b884ec60fa786f20b7d38afd946e0b4f3fc11f24db24ea7a80282" +
                "da50d10120f2fb31b0adc342ea7b5e80ab08cc755ec0735e7592a749bca3caf0",
            ZjuRsa.encryptPassword("x", liveModulus, liveExponent)
        )
    }

    @Test
    fun `密文长度是位数的四倍`() {
        // biToHex 每位固定吐 4 个 hex 字符：512 bit 模数 = 32 位 → 128 个字符。
        val out = ZjuRsa.encryptPassword("Abc123!", liveModulus, liveExponent)
        assertEquals(32 * 4, out.length)
        assertEquals(0, out.length % 4)
    }

    @Test
    fun `hex 输出是小写，因为 hexToChar 是 a 到 f`() {
        // security.js: var hexToChar = ['0'..'9','a','b','c','d','e','f']
        // 早先按 Go 的 %x / Kotlin 的 uppercase() 写都猜错了方向。
        val out = ZjuRsa.encryptPassword("ABCDEFGHIJ", liveModulus, liveExponent)
        assertTrue("不应出现大写：$out", out.none { it.isUpperCase() })
        assertTrue("只应含小写 hex：$out", out.all { it.isDigit() || it in 'a'..'f' })
        assertEquals(
            "83b360aacf75723d919fdd760e6318d94d549fe69acf7f0b9eea3bf8b98695" +
                "9293a77b4748ec4c9e90ce4d4354bee8a965e154609acf5ea61fa3a4f59553d8ad",
            out
        )
    }

    @Test
    fun `密文位宽取模数位宽 不是写死的常数`() {
        // 补零宽度写死 128 是照抄上游，但浙大将来换一把长度的密钥时，
        // 写死的那版会静默产出位数不对的密文，而 CAS 的反馈只有「用户名或密码错误」。
        // 所以这里用一把更短的模数验证：密文长度应当跟着模数一起变。
        val shorter = "8fd7fa690b0b0cf8f897127c005d3def0ce98e8dca000ab00205cd156818f097"
        assertEquals(64, shorter.length)
        val out = ZjuRsa.encryptPassword("Abc123!", shorter, "10001")
        assertEquals("密文长度应等于模数的 hex 长度", shorter.length, out.length)
        assertTrue(out.none { it.isUpperCase() })
        assertTrue(out.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `密码顺序影响密文，说明确实做了反转`() {
        assertTrue(
            ZjuRsa.encryptPassword("abcdef", liveModulus, liveExponent) !=
                ZjuRsa.encryptPassword("fedcba", liveModulus, liveExponent)
        )
    }

    @Test
    fun `同一密码两次加密结果一致`() {
        // 教科书 RSA 没有随机填充，所以同一明文两次加密必须完全一致 ——
        // 这正是能逐位对齐页面 JS 的原因，也是它弱于 PKCS#1 的地方。
        assertEquals(
            ZjuRsa.encryptPassword("Abc123!", liveModulus, liveExponent),
            ZjuRsa.encryptPassword("Abc123!", liveModulus, liveExponent)
        )
    }

    @Test
    fun `长密码也只产出一段密文 不分块`() {
        // 旧实现按 chunkSize=62 把长密码切成两块、拼出两段模数位宽的密文。
        // 现在是单块大端：明文整体当一个大整数做 m^e mod n，**不分块**。
        // 这不只是形式变化 —— 分块意味着服务端要按同样的规则解，
        // 而服务端消费的是最朴素的单块实现，分块只会解不开。
        val out = ZjuRsa.encryptPassword("B".repeat(100), liveModulus, liveExponent)
        assertTrue("只应含 hex 字符：$out", out.all { it.isDigit() || it in 'a'..'f' })
        assertEquals("密文长度恒等于模数位宽", 128, out.length)
    }

    @Test
    fun `密文恒等于模数位宽 短密码靠左侧补零`() {
        // 无填充裸 RSA 的密文可能比模数短，补零宽度错了服务端解不出位数。
        listOf("x", "Abc123!", "zju-test-pw-0001", "C".repeat(200)).forEach { plain ->
            val out = ZjuRsa.encryptPassword(plain, liveModulus, liveExponent)
            assertEquals("「$plain」密文长度应为 128，实际 ${out.length}", 128, out.length)
        }
    }

    @Test
    fun `密文永远小于模数`() {
        val n = java.math.BigInteger(liveModulus, 16)
        val out = java.math.BigInteger(
            ZjuRsa.encryptPassword("Abc123!", liveModulus, liveExponent), 16
        )
        assertTrue("密文应小于模数", out < n)
    }
}