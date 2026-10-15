package com.example.data.source.zju

import com.example.data.calendar.TweakKind
import com.example.data.calendar.ZjuCalendarAdapter
import com.example.data.codec.CalendarBundle
import com.example.data.codec.PeriodBundle
import com.example.data.codec.TermConfigBundle
import com.example.data.codec.TweakBundle
import com.example.data.codec.halvesOfAcademicTerm
import com.example.data.codec.academicTermOf
import com.example.data.timetable.PeriodTable
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

/**
 * 校历配置的**取与解**：从远程拿到一段文本、把文本解成 [CalendarBundle]。
 *
 * **这一类里没有「该用哪一份」的决策**，那是 [CalendarUpdateService] 的事。
 * 之前这里同时管着「联网取数」和「联网 vs 内置怎么合并」，于是有两条独立的
 * 取数链路（弹层只读内置、导入才联网），界面上显示的官方校历和实际落库的
 * 那份可以不是同一份。决策与执行分开之后，两边必然走同一份解析。
 *
 * ## 格式
 *
 * 用的是 zju-ical 的 `configs/config.json` 形状，**没有换**。
 * 托管方可以换成任何仓库 / 任何路径，文件本身的格式不变，所以
 * `parse` / `selectTerm` / `ScheduleBundleValidator` 这一整条已验证过的链路原样复用。
 */
sealed interface RemoteFetch {
    data class Got(val text: String) : RemoteFetch
    data class Unavailable(val reason: String) : RemoteFetch
}

class ZjuCalendarSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    /**
     * 读内置 assets 用的句柄。为 null 时不启用内置兜底。
     *
     * **必须走 Context.assets，不能用 `classLoader.getResourceAsStream`**：
     * 后者在 JVM 单测里能工作（assets 被打进 test classpath），但 Android 运行时
     * 的 assets 并不在 JVM classpath 上，那条路在真机上是 null —— 而这恰恰是最需要
     * 它工作的场景（用户网络受限才走兜底）。单测可以改传 [bundledText] 字符串。
     */
    private val assets: android.content.res.AssetManager? = null,
    /** 直接给定内置文本，优先于 [assets]。单测用这个注入，省掉 Android 依赖。 */
    private val bundledText: String? = null,
    /** 远程地址。留空则完全不联网（单测用）。生产由 [CONFIG_URLS] 提供。 */
    private val remoteUrls: List<String> = CONFIG_URLS,
    /**
     * 直接给定「远程拿到的那段文本」，**完全跳过网络**。单测用。
     *
     * 与 [bundledText] 刻意对称：三层优先级里每一层都要能在离线测试里单独驱动，
     * 否则"远程比缓存新 → 写缓存 → 返回 Updated"这条最要紧的路径只能靠真联网验。
     */
    private val remoteText: String? = null
) {

    /**
     * 逐个试远程地址，返回第一份拿到手的**原文**，或**拿不到的真实原因**。
     *
     * 为什么不是 `String?`：拿不到的原因有好几种完全不同的毛病 ——
     * 域名解析不了、TLS 握手被掐、镜像返回 403、地址写错 —— 而它们的处置方式
     * 不一样（换网络、换镜像、改配置）。一律压成一句「连不上校历源」，
     * 用户既没法自查，托管方也没法定位是哪一环坏的，等于把排查成本全推给用户。
     *
     * 全都失败才返回 [RemoteFetch.Unavailable]，由调用方决定回落策略 ——
     * 这里不吞掉"拉不到"这件事，否则上层只能把失败和"没有更新"混为一谈。
     *
     * 多挂几条镜像是因为免费托管（GitHub raw / gitee raw）在不同网络环境下
     * 时通时不通，而校历是全校同一份静态数据，任何一个源返回的内容都一样，
     * 逐个试过去比「一个源失败就让用户手动导入」划算得多。
     */
    suspend fun fetchRemoteText(): RemoteFetch {
        remoteText?.let { return RemoteFetch.Got(it) }
        return withContext(Dispatchers.IO) {
            val reasons = mutableListOf<String>()
            for (url in remoteUrls) {
                when (val r = fetchOne(url)) {
                    is RemoteFetch.Got -> return@withContext r
                    is RemoteFetch.Unavailable -> reasons += "${url.toHttpUrlOrNull()?.host ?: url}: ${r.reason}"
                }
            }
            RemoteFetch.Unavailable(reasons.joinToString("；").ifEmpty { "没有配置任何远程地址" })
        }
    }

    private fun fetchOne(url: String): RemoteFetch = try {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { res ->
            // 2xx 之外一律带状态码：镜像站最常见的两种毛病是 403（签名过期）
            // 和 404（文件没推上去/路径写错），它们在界面上要能被一眼区分。
            if (res.isSuccessful) {
                val body = res.body?.string()
                if (body.isNullOrBlank()) RemoteFetch.Unavailable("返回了空内容")
                else RemoteFetch.Got(body)
            } else {
                RemoteFetch.Unavailable("HTTP ${res.code}")
            }
        }
    } catch (e: Exception) {
        RemoteFetch.Unavailable(e.describe())
    }

    /** 把异常翻成一句用户/托管方看得懂的话，而不是 `java.net` 的类名。 */
    private fun Exception.describe(): String = when (this) {
        is java.net.UnknownHostException -> "域名解析不了（$message）"
        is javax.net.ssl.SSLException -> "TLS 握手失败（${message ?: "被对端中断"}）"
        is java.net.SocketTimeoutException -> "连接超时"
        is java.net.ConnectException -> "连不上服务器"
        else -> message ?: javaClass.simpleName
    }

    /**
     * 取一次远程原文并解析。拉取失败或格式不对都返回 null。
     *
     * 解析失败**不重试下一个源** —— 解析坏的是文件内容而不是连接，
     * 换一条 URL 拿到的是同一份内容，试完只是白等。
     */
    suspend fun fetchRemoteBundle(): CalendarBundle? =
        (fetchRemoteText() as? RemoteFetch.Got)?.text?.let { runCatching { parse(it) }.getOrNull() }

    /**
     * 只读**内置**那份（assets），**完全不联网**。
     *
     * 读不到或解析不了返回 null，由调用方决定是显示「预置不可用」还是别的。
     */
    fun parseBundled(): CalendarBundle? =
        bundledConfigText()?.let { runCatching { parse(it) }.getOrNull() }

    /** 内置兜底：优先用注入的文本，否则读 assets。两个都没有才返回 null。 */
    private fun bundledConfigText(): String? {
        bundledText?.let { return it }
        return runCatching {
            assets?.open(FALLBACK_ASSET)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }

    /** 从一段 config.json 文本解析。抽出来是为了能直接单测。 */
    fun parse(text: String): CalendarBundle {
        val dto = runCatching {
            moshi.adapter(UpstreamConfigDto::class.java).fromJson(text)
        }.getOrNull() ?: throw ZjuException("校历配置格式无法解析", ZjuFailureKind.SERVER)

        return CalendarBundle(
            lastUpdated = dto.lastUpdated,
            termConfigs = dto.termConfigs.orEmpty().map {
                TermConfigBundle(
                    it.year,
                    ZjuCalendarAdapter.termCodeOf(it.term),
                    it.begin,
                    it.end,
                    it.firstWeekNo
                )
            },
            classTerms = dto.classTerms.orEmpty().map { normalizeClassTerm(it) },
            examTerms = dto.examTerms.orEmpty(),
            // 认不出来的 tweakType 留 null（而不是默认成 CLEAR）：校验期会照它报一条
            // 「无法识别，已忽略」，用户看得见；默认成 CLEAR 等于悄悄把人课停掉
            tweaks = dto.tweaks.orEmpty().map {
                TweakBundle(TweakKind.ofRaw(it.tweakType), it.description, it.from, it.to)
            },
            // 上游没有这个字段，用我们自己的 ZJU 作息补上
            periods = PeriodTable.ZJU_PERIODS.map {
                PeriodBundle(
                    period = it.period,
                    startTime = com.example.data.timetable.formatMinute(it.startMinute),
                    endTime = com.example.data.timetable.formatMinute(it.endMinute),
                    section = it.section
                )
            }
        )
    }

    /**
     * 只保留指定学年学期，并把 tweaks 裁到这一期的区间内。
     *
     * **保留的是整个学年学期（秋冬 / 春夏），不是那一条 `termConfigs`。**
     *
     * 浙大一个学期是秋(1–8 周)与冬(9–16 周)两段拼起来的 16 周，而
     * `termConfigs` 一条只描述其中一段。之前这里只留选中那条，于是导入「秋冬」
     * 拿到的学期只有 8 周：冬那 8 周连同冬的停课/调休一起被丢掉，
     * 而课程周号已经按 16 周平移过（第 9–16 周），两端口径对不上，
     * 后半学期整段没课。
     *
     * 裁 tweaks 时也按**整个学年学期**的区间裁。区间外的日期不会被误删：
     * 判断条件是**重叠**（`to >= begin && from <= end`）而不是两端包含 ——
     * 学生节那条 `Move`（12-31 停课、1-4 补课）的补课日落在学期结束日之后一天，
     * 但它的 `from` 在区间内，属于有重叠，照样保留。
     */
    fun selectTerm(calendar: CalendarBundle, year: String, term: Int): CalendarBundle {
        val target = calendar.termConfigs.firstOrNull { it.year == year && it.term == term }
            ?: return calendar
        val halves = calendar.termConfigs.halvesOfAcademicTerm(year, term)
            .ifEmpty { listOf(target) }
        val academic = calendar.termConfigs.academicTermOf(year, term)
        val begin = academic?.startEpochDay ?: ZjuCalendarAdapter.yyyymmddToEpochDay(target.begin)
        val end = academic?.endEpochDay ?: ZjuCalendarAdapter.yyyymmddToEpochDay(target.end)
        val keptTweaks = calendar.tweaks.filter { tw ->
            val f = ZjuCalendarAdapter.yyyymmddToEpochDay(tw.from)
            val t = ZjuCalendarAdapter.yyyymmddToEpochDay(tw.to)
            if (begin == null || end == null || f == null || t == null) true
            else t >= begin && f <= end
        }
        return calendar.copy(
            termConfigs = halves,
            classTerms = halves.mapNotNull { it.year?.let { y -> it.term?.let { t2 -> "$y:$t2" } } },
            tweaks = keptTweaks
        )
    }

    /**
     * `"2026-2027:秋"` → `"2026-2027:0"`；已经是数字的原样返回。
     *
     * 上游的 `classTerms` 与 `termConfigs[].Term` 用的是同一套中文写法，
     * 归一后才能和 `TermConfigBundle.key` 对得上，导出的文档也自洽。
     * 认不出来的整条原样保留 —— 这段只是元数据，不该因为认不出就丢掉。
     */
    private fun normalizeClassTerm(raw: String): String {
        val idx = raw.lastIndexOf(':')
        if (idx <= 0) return raw
        val token = raw.substring(idx + 1).trim()
        val code = ZjuCalendarAdapter.termCodeOf(token.toIntOrNull() ?: token) ?: return raw
        return "${raw.substring(0, idx)}:$code"
    }

    private val moshi = Moshi.Builder().build()

    companion object {
        /**
         * 远程托管的校历配置，逐个镜像试。
         *
         * ## 维护方式
         *
         * 把 `assets/zju-calendar-fallback.json` 的内容 push 到
         * `https://gitee.com/zjuchenqianyu/MiuList` 仓库下的 `config.json`，
         * 之后在 App 的「本学期校历」里点「检查更新」→ 看差异 → 确认应用即可。
         * **不需要改代码，也不需要重装。**
         *
         * Gitee 的 raw 路径形状是 `/raw/<分支>/<路径>`，这个仓库的默认分支是 `main`。
         * 想加镜像就往这个列表里往后追加——取数是**逐个试**，排在后面的只有前面
         * 全挂了才会被请求，所以主源正常时它一次都不会被碰到，纯粹是保险。
         *
         * ## 这个仓库必须是**公开**的
         *
         * 拉取是**匿名**的（只发一个 GET，不带任何凭据）。私有仓库会返回 403，
         * App 就一直拿不到新版，只能退回内置那份。
         *
         * **不要把访问令牌写进这份 URL 常量**：打进 APK 的常量任何人都能解出来，
         * 等于把令牌公开。而且这里的内容本来就是全校公开的校历，没有私有理由。
         * 真要带鉴权，就得先想清楚令牌怎么保管 —— 那不是这里该顺手做的事。
         *
         * ## 曾经指向的 zju-ical 上游
         *
         * 原先这里挂的是 zju-ical 的 `configs/config.json`。那个仓库的
         * `lastUpdated` 停在 `20250213`，`termConfigs` 只到 2024-2025 学年、
         * 调休描述还写着"（待定）"，比 App 内置的 2026-2027 旧了一整个学年。
         * 它当时的作用全靠一套合并逻辑兜着别覆盖内置，是纯延迟。
         * 真源换成自托管后**合并逻辑随之删除** —— 只剩一份真源，
         * 不存在跨源冲突；留着它反而是给人"顺手再接个源"的口子。
         */
        private val CONFIG_URLS = listOf(
            "https://gitee.com/zjuchenqianyu/MiuList/raw/main/config.json"
        )

        /**
         * 内置兜底配置文件（assets）。
         *
         * 它的角色是**最后一道**：远程拉不到、且本机从来没有成功缓存过时才生效，
         * 保证新装用户离线也能有一份能用的学期起止。
         */
        const val FALLBACK_ASSET = "zju-calendar-fallback.json"
    }
}

/**
 * 上游 `configs/config.json` 的形状。
 *
 * 顶层字段是 camelCase（`lastUpdated` / `termConfigs` / `tweaks` …），而数组里的
 * 元素是 PascalCase（`Year` / `Begin` / `FirstWeekNo` …）——上游两种风格混着用，
 * 所以下面每个元素字段都用 [@Json] 显式钉住名字。
 *
 * 别改成「让 Moshi 模糊匹配」：钉死名字之后，上游一改字段名这里就解析成 null，
 * 立刻在真联网冒烟测试里炸出来，而不是悄悄按旧含义读出错的值。
 * （这套 DTO 之前写成全小写、没加 [@Json]，结果 termConfigs 解析成两条全 null，
 * 校历的起止日期全是空 —— 就是靠上面的钉死策略暴露的。）
 */
@JsonClass(generateAdapter = true)
data class UpstreamConfigDto(
    val lastUpdated: Int? = null,
    val lastUpdatedTime: String? = null,
    val tweaks: List<UpstreamTweakDto>? = null,
    val termConfigs: List<UpstreamTermConfigDto>? = null,
    val classTerms: List<String>? = null,
    val examTerms: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class UpstreamTweakDto(
    /**
     * 上游写的是**字符串**（`"Clear"` / `"Move"` / `"Exchange"`），历史版本写过数字。
     * 声明成 [Any] 让两种都收进来，再由 [TweakKind.ofRaw] 归一 ——
     * 声明成 Int 会让 Moshi 在遇到 `"Clear"` 时抛 JsonDataException，
     * 整份校历配置解析失败（学期起止日期、调休一起丢）。
     */
    @Json(name = "TweakType") val tweakType: Any? = null,
    @Json(name = "Description") val description: String? = null,
    @Json(name = "From") val from: Int? = null,
    @Json(name = "To") val to: Int? = null
)

@JsonClass(generateAdapter = true)
data class UpstreamTermConfigDto(
    @Json(name = "Year") val year: String? = null,
    /**
     * 上游写的是**中文**：`"Term": "秋"`。声明成 `Int?` 时 Moshi 遇到字符串抛
     * `JsonDataException`，整份配置解析失败（连 `tweaks` 一起丢）。
     * 数字（0–6）也收，由 [ZjuCalendarAdapter.termCodeOf] 归一。
     */
    @Json(name = "Term") val term: Any? = null,
    @Json(name = "Begin") val begin: Int? = null,
    @Json(name = "End") val end: Int? = null,
    @Json(name = "FirstWeekNo") val firstWeekNo: Int? = null
)
