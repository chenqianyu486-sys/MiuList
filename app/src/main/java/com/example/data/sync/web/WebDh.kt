package com.example.data.sync.web

import java.math.BigInteger

/**
 * 配对用的 DH 密钥协商（RFC 3526 group 14，手机端）。
 *
 * ## 它解决什么
 *
 * v3 的配对 secret 是**电脑生成、手机从二维码里拿到**的 ——
 * secret 只走「二维码 → 手机相机」这条视觉通道，从不经过网络。
 * 这条性质很好，但它让「手动填地址」这条路走不通：
 * 光有地址，电脑拿不到 secret，配对就停在半路。
 *
 * 换成 DH 之后，两端各自生成一对临时密钥，交换**公钥**，
 * 各自算出**同一把共享密钥**：
 *
 * ```
 * 电脑                              手机
 *  │  a 随机私钥                    │  b 随机私钥
 *  │  A = g^a mod p                 │  B = g^b mod p
 *  │  ──────── A ─────────────────► │  S = A^b mod p
 *  │  ◄─────── B ────────────────── │  S = B^a mod p
 *  │  secret = sha256(S)            │  secret = sha256(S)
 * ```
 *
 * **过网的只有公钥** —— 公钥本来就是公开的东西，抓包的人看到它也
 * 算不出共享密钥（DH 的单向性）。
 *
 * ## 为什么是 MODP 群而不是 ECDH
 *
 * `KeyAgreement("ECDH")` 在各家 Android 厂商的 JCE 上行为并不一致
 * （老版本 AndroidOpenSSL 干脆不注册 ECDH），而网页端那边
 * `crypto.subtle` 又只在 secure context 里存在。
 *
 * `BigInteger` 两边都有（网页端对应 BigInt），实现只有一份逻辑，
 * 两端逐字节一致很容易验证（见 [WebDhTest] 与 `web/crypto.test.mjs`
 * 里**同一组测试向量**）。
 *
 * ## 定长编码
 *
 * 共享值 S 是一个 2048 位的大整数。**必须定长编码成 256 字节**
 * （大端、高位补零）再交给 sha256 —— 否则两端会因为「前导零有几个」
 * 不一样而算出不同的 secret，症状是「配对显示成功，之后全都解密失败」。
 */
object WebDh {

    /** RFC 3526 §3 的 2048 位 MODP 素数（group 14）。 */
    private const val P_HEX =
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
            "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
            "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
            "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
            "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
            "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
            "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
            "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
            "15728E5A8AACAA68FFFFFFFFFFFFFFFF"

    private val P = BigInteger(P_HEX, 16)
    private val G = BigInteger.valueOf(2)

    /** 公钥 / 共享值的定长字节数。2048 位 = 256 字节。 */
    const val KEY_BYTES = 256

    /** 私钥指数的字节数。 */
    const val PRIV_BYTES = 32

    /**
     * 配对码的字母表：去掉 I / L / O / U，避免和 1 / 0 混淆。
     *
     * **必须与 `web/crypto.js` 里的 `PAIR_ALPHABET` 逐字相同。**
     */
    private const val PAIR_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** 配对码的标签。**必须与网页端逐字相同**（协议 v3.2）。 */
    private const val PAIR_CODE_LABEL = "miulist-paircode-v3.2"

    /**
     * 生成一对临时密钥。
     *
     * @return `私钥(32 字节)` 到 `公钥(256 字节)`
     */
    fun generateKeypair(): Pair<ByteArray, ByteArray> {
        val priv = WebCrypto.randomBytes(PRIV_BYTES)
        return priv to publicKey(priv)
    }

    /**
     * 由私钥算出公钥（A = g^a mod p）。
     *
     * 单独暴露是为了**能对着固定向量测**（见 [WebDhTest]）——
     * 随机生成的一对密钥没法写进测试里，而「给定私钥 → 确定的公钥」可以。
     */
    fun publicKey(priv: ByteArray): ByteArray {
        require(priv.size == PRIV_BYTES) { "私钥必须是 $PRIV_BYTES 字节" }
        return toFixedBytes(G.modPow(BigInteger(1, priv), P), KEY_BYTES)
    }

    /**
     * 算出共享密钥。
     *
     * @param priv 本端私钥（32 字节）
     * @param peerPub 对端公钥（256 字节）
     * @return 32 字节的 secret（= sha256(共享值)）
     */
    fun sharedSecret(priv: ByteArray, peerPub: ByteArray): ByteArray {
        require(priv.size == PRIV_BYTES) { "私钥必须是 $PRIV_BYTES 字节" }
        require(peerPub.size == KEY_BYTES) { "公钥必须是 $KEY_BYTES 字节" }
        validatePublicKey(peerPub)
        val shared = BigInteger(1, peerPub).modPow(BigInteger(1, priv), P)
        // 兜底：共享值同样不能是 1 或 p-1（正常情况下前面那步已经挡住了）。
        require(shared > BigInteger.ONE && shared < P - BigInteger.ONE) { "共享密钥退化，配对中止" }
        return WebCrypto.sha256(toFixedBytes(shared, KEY_BYTES))
    }

    /**
     * **公钥合法性校验**（协议 v3.2 起强制）。
     *
     * ## 为什么必须查
     *
     * 只查长度（256 字节）是不够的 —— 下面几个值长度都对，
     * 却会让共享密钥退化成**全世界都知道的常量**：
     *
     * | 对端公钥 | 共享值 S | 后果 |
     * |---|---|---|
     * | `0` | 无意义 | 无法协商 |
     * | `1` | `1` | secret = sha256(定长的 1)，谁都能算出来 |
     * | `p-1` | `p-1` | 同上 |
     *
     * ## 为什么只查这一条就够了
     *
     * group 14 的 p 是**安全素数**（p 与 (p-1)/2 都是素数，RFC 3526 §3），
     * 这个群里的子群只有阶 1 与阶 2 两种 —— 也就是「1」与「p-1」本身。
     * 把这两个（以及 0）排除掉，就不存在别的「小阶公钥」可用。
     */
    fun validatePublicKey(pub: ByteArray) {
        require(pub.size == KEY_BYTES) { "公钥必须是 $KEY_BYTES 字节" }
        val v = BigInteger(1, pub)
        require(v > BigInteger.ONE && v < P - BigInteger.ONE) {
            "公钥不在合法范围（退化公钥会让共享密钥变成公开常量）"
        }
    }

    /**
     * **配对码**：两端公钥的 6 位短码，给用户肉眼核对。
     *
     * ## 为什么是「双方公钥」的哈希（协议 v3.2 改过）
     *
     * v3.1 的配对码是 `H(A)` —— **只有发起方（电脑）的公钥**。
     * 它能证明「我看到的 A 跟你看到的一样」，但**证明不了对面是谁**：
     * 因为对面报上来的本来就是从 A 算出来的码。
     *
     * v3.2 改成 `H(A‖B)`（A = 电脑公钥，B = 手机公钥）—— **转录绑定**。
     * 两端各自都拿得到 A 与 B，所以都能算；一旦中间人换掉其中任何一个，
     * 两端算出来的码就对不上。
     *
     * **顺序不能反** —— 与 `web/crypto.js` 里的 `pairCodeOf(pubA, pubB)` 一致。
     *
     * @param pubA 发起方（电脑）的公钥（256 字节）
     * @param pubB 响应方（手机）的公钥（256 字节）
     */
    fun pairCode(pubA: ByteArray, pubB: ByteArray): String {
        require(pubA.size == KEY_BYTES && pubB.size == KEY_BYTES) { "公钥必须是 $KEY_BYTES 字节" }
        val digest = WebCrypto.sha256(PAIR_CODE_LABEL.toByteArray(Charsets.UTF_8) + pubA + pubB)
        val sb = StringBuilder(6)
        for (i in 0 until 6) {
            sb.append(PAIR_ALPHABET[(digest[i].toInt() and 0xff) % 32])
        }
        return sb.toString()
    }

    /**
     * 大整数 → 定长字节（大端、高位补零）。
     *
     * `BigInteger.toByteArray()` 给的是**最小的补码表示** ——
     * 正数最高位是 1 时会多带一个 0x00 的符号位（257 字节）。
     * 所以从尾部往前拷，多的那个前导零自然被丢掉。
     */
    private fun toFixedBytes(value: BigInteger, size: Int): ByteArray {
        val out = ByteArray(size)
        val raw = value.toByteArray()
        val take = minOf(size, raw.size)
        System.arraycopy(raw, raw.size - take, out, size - take, take)
        return out
    }

    /** 群的参数（给测试用：模数长度、是不是安全素数）。 */
    fun groupPrimeHex(): String = P_HEX
}
