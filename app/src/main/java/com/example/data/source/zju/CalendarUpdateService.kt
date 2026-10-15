package com.example.data.source.zju

import com.example.data.calendar.CalendarCache
import com.example.data.calendar.CalendarOrigin
import com.example.data.codec.CalendarBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 校历的**三层来源优先级**，也是唯一决定「该用哪一份」的地方。
 *
 * ```
 *   ① 远程托管（你 push 的 config.json）    唯一真源，可不发版更新
 *      ↓ 拉取失败 / 无网 / 内容解析不了
 *   ② 本地缓存 filesDir/remote-calendar.json  上次成功拉到的原文
 *      ↓ 从没成功拉过 / 缓存本身坏了
 *   ③ 内置 assets                             随版本走的最后兜底
 * ```
 *
 * ## 为什么分层，而不是「联网优先」
 *
 * 校历是**全校同一份、人人相同**的静态数据，没有理由让每个用户自己去猜。
 * 但免费托管的 raw 地址在不同网络环境下时通时不通，所以联网不能是唯一出路。
 *
 * 之前还有第四层：zju-ical 上游。它的 `lastUpdated` 停在 20250213、比内置旧一整个
 * 学年，靠一套 `merge()` 兜着不覆盖内置。换成自托管后只剩一份真源，
 * 不再需要任何合并逻辑。
 *
 * ## 分工
 *
 * 这里管决策，[ZjuCalendarSource] 管取与解。分成两个是因为之前两者混在一起，
 * 结果长出两条互相独立的取数链路 —— 弹层只读内置、导入才联网，
 * 于是界面上显示的官方校历和真正落库的未必是同一份。
 */
class CalendarUpdateService(
    private val source: ZjuCalendarSource,
    private val cache: CalendarCache
) {

    /** 读到手的一份校历，连同它的来源。 */
    data class Loaded(
        val bundle: CalendarBundle,
        val origin: CalendarOrigin
    )

    /** 「检查更新」的结果。三个分支**语义互不重叠**，界面上必须分开显示。 */
    sealed interface UpdateOutcome {
        /**
         * 远程那份不比缓存新，什么都没变。
         *
         * **注意这只在"确实拉到了远程内容"之后才可能出现。**
         * 拉不到一律是 [Failed] —— 把"拉不到"渲染成"已是最新"等于骗用户。
         */
        data class UpToDate(val version: Int?) : UpdateOutcome

        /** 远程比缓存新，已写入缓存，等用户看差异后确认应用。 */
        data class Updated(
            val bundle: CalendarBundle,
            val version: Int?
        ) : UpdateOutcome

        /** 拉不到，或拉到了但内容不可用。绝不改缓存，也绝不改当前数据。 */
        data class Failed(val reason: String) : UpdateOutcome
    }

    /**
     * 拿到当前可用的校历，**完全不联网**。
     *
     * 弹层打开走这条：它必须秒开、无网可用。远程更新是显式点「检查更新」才发生的
     * （见 [checkForUpdate]），不该拖住打开弹层的那一帧。
     *
     * 两层都没有时返回 null，调用方自己决定怎么显示 —— 「读不到」和「正在读」
     * 是两件事，不该共用一个状态位。
     */
    suspend fun loadOffline(): Loaded? = withContext(Dispatchers.IO) {
        cachedBundle()?.let { Loaded(it, CalendarOrigin.CACHED) }
            ?: source.parseBundled()?.let { Loaded(it, CalendarOrigin.BUNDLED) }
    }

    /**
     * 课表导入 / 同步走这条。
     *
     * **也不联网**：校历换没换是用户自己在校历弹层里决定的，
     * 把它和"能不能登录教务网"绑在一起会让一次同步因为校历网络不通而整条失败。
     * 用户点过「检查更新」的话，缓存里已经是最新那份，这里自然读到它。
     */
    suspend fun loadForImport(): CalendarBundle =
        loadOffline()?.bundle ?: throw ZjuException(
            "校历配置拉取失败，可改用文件导入",
            // 联网那份拿不到**不等于**用户网络有问题：三层都用不上时才会走到这里，
            // 更像是配置本身出了岔子。归到 SERVER 而不是 NETWORK，
            // 是为了不让界面把矛头指向用户的网络设置。
            ZjuFailureKind.SERVER
        )

    /**
     * 显式检查更新：拉远程、比版本、可能写缓存。**不碰库里的校历。**
     *
     * 写缓存与「应用」是**两件独立的事**：这里只把新的一份留在本机，
     * 要不要落到用户正在用的学期上，由差异预览确认后决定。
     */
    suspend fun checkForUpdate(): UpdateOutcome = withContext(Dispatchers.IO) {
        // 失败原因原样往上带：域名解析不了、TLS 被掐、403 签名过期，处置方式完全不同，
        // 压成一句「连不上校历源」会让用户和托管方都无从下手。
        val text = when (val r = source.fetchRemoteText()) {
            is RemoteFetch.Got -> r.text
            is RemoteFetch.Unavailable -> return@withContext UpdateOutcome.Failed(r.reason)
        }
        if (text.isBlank()) {
            return@withContext UpdateOutcome.Failed("校历源返回了空内容")
        }
        val remote = runCatching { source.parse(text) }.getOrNull()
            ?: return@withContext UpdateOutcome.Failed("校历配置格式无法解析")

        // 缓存本身可能是坏的（写了一半被杀、托管方改坏过内容）。
        // 它坏了不能连累这次检查 —— 当作没有缓存，照常比。
        val cached = cachedBundle()

        if (!isNewer(remote.lastUpdated, cached?.lastUpdated)) {
            return@withContext UpdateOutcome.UpToDate(cached?.lastUpdated ?: remote.lastUpdated)
        }

        cache.write(text)
        UpdateOutcome.Updated(remote, remote.lastUpdated)
    }

    /**
     * 远程是否比缓存新。
     *
     * `lastUpdated` 是 `yyyyMMdd` 整数，直接比大小。
     *
     * **两边都是 null 时一律判"不新"**：没法证明它新，就不该覆盖用户正在用的
     * 东西。代价是托管方忘了写这个字段时，用户永远看不到更新提示 ——
     * 那会在界面上表现成"检查了但没反应"，比覆盖掉正确数据安全得多。
     */
    private fun isNewer(remote: Int?, cached: Int?): Boolean {
        if (remote == null) return false
        if (cached == null) return true
        return remote > cached
    }

    /** 读缓存并解析；读不到或内容坏了都当作没有缓存。 */
    private fun cachedBundle(): CalendarBundle? =
        cache.read()?.let { runCatching { source.parse(it) }.getOrNull() }
}
