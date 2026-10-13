package com.example.data.source.zju

import com.example.data.calendar.ZjuCalendarAdapter
import com.example.data.codec.CalendarBundle
import com.example.data.codec.PeriodBundle
import com.example.data.codec.TermConfigBundle
import com.example.data.codec.TweakBundle
import com.example.data.timetable.PeriodTable
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 公共校历配置来源：zju-ical 的 `configs/config.json`。
 *
 * **这份数据全校通用、人人相同，没有理由让每个用户自己去教务网猜。**
 * zju-ical 把它维护在仓库里并且会定期更新（`lastUpdated` 字段就是这个用途），
 * 所以这里先读它，而不是在 App 里另抄一份。
 *
 * 但**联网不能是唯一出路**，所以有第二层：App 内置一份 `assets/zju-calendar-fallback.json`。
 * 加这一层的直接原因是实测——GitHub 系域名（github.com / raw.githubusercontent /
 * jsdelivr / 各种 gh-proxy 镜像）在很多网络环境下根本连不上：
 * 要么 TLS 握手被中间设备掐断，要么被墙。实测这台开发机上 `SSLHandshakeException`
 * 命中了**全部**镜像源（2026-10-13）。
 *
 * 顺序是「联网 → 内置」，但**不是简单的联网优先**：两份会按学期取并集，
 * 理由见 [merge] 的注释（上游那份可能比内置的旧，直接覆盖会让学期下拉里
 * 只剩过期学期）。
 *
 * 返回的 [CalendarBundle] **就是 v2 文档里的 `calendar` 块**——
 * 字段名与类型和上游逐字段相同，所以这份 config.json 本身也能被当成
 * 「只有校历的 v2 文档」直接导入。
 */
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
     * 它工作的场景（用户网络受限才走兜底）。单测可以改传 [bundledConfigText] 字符串。
     */
    private val assets: android.content.res.AssetManager? = null,
    /** 直接给定内置文本，优先于 [assets]。单测用这个注入，省掉 Android 依赖。 */
    private val bundledText: String? = null
) {

    /**
     * 取一次完整配置。失败抛 [ZjuException]。
     *
     * 返回的 Bundle 里 `periods` 已经填上浙大作息：上游把作息硬编码在 Go 里、
     * config.json 里没有对应字段，而本项目要用它画栅格。
     */
    suspend fun fetch(): CalendarBundle = withContext(Dispatchers.IO) {
        val remote = runCatching { fetchText() }.getOrNull()?.let { runCatching { parse(it) }.getOrNull() }
        val bundled = bundledConfigText()?.let { runCatching { parse(it) }.getOrNull() }

        when {
            remote != null && bundled != null -> merge(remote, bundled)
            remote != null -> remote
            bundled != null -> bundled
            else -> throw ZjuException("校历配置拉取失败，可改用文件导入")
        }
    }

    /** [merge] 的测试入口。这是**唯一**能离线验证合并规则的地方。 */
    internal fun mergeForTest(remote: CalendarBundle, bundled: CalendarBundle) =
        merge(remote, bundled)

    /**
     * 合并联网与内置两份配置：**联网优先，但不让它把内置里更新的学期盖掉。**
     *
     * ## 为什么不能简单「联网优先」
     *
     * 上游 zju-ical 的 `configs/config.json` 只更到 2024-2025（该仓库 2023-11 后
     * 基本停更），而 App 内置的那份有 2026-2027。如果老老实实「联网成功就以它为准」，
     * 学期下拉里就**只剩 2024-2025 的过期学期**——用户会选到一个完全不存在的当前学期，
     * 后面抓回来的课表自然是空的，而且没有任何报错提示。
     * 这个坑是在模拟器上真跑出来的（2026-10-13），不是推演出来的。
     *
     * ## 合并规则
     *
     * - `termConfigs` / `classTerms` / `examTerms`：按学期键**并集**，**联网优先**
     *   ——联网有更新的调休/日期就用联网的，内置只补它没有的学期。
     * - `tweaks`：按 `(from, to)` 去重后**取并集**。两份都是「停课/调休」这一类
     *   事实，缺一份不会让结果错，只会让那一段调休没生效。
     */
    private fun merge(remote: CalendarBundle, bundled: CalendarBundle): CalendarBundle {        // **联网的先占位**：putIfAbsent 只在缺席时写入，所以谁先谁赢。
        // 顺序写成 (bundled + remote) 会让内置反过来盖住联网的更新，那是反的。
        val terms = LinkedHashMap<Pair<String?, Int?>, TermConfigBundle>()
        (remote.termConfigs + bundled.termConfigs).forEach { tc ->
            terms.putIfAbsent(tc.year to tc.term, tc)
        }

        return remote.copy(
            termConfigs = terms.values.toList(),
            classTerms = (bundled.classTerms.orEmpty() + remote.classTerms.orEmpty()).distinct(),
            examTerms = (bundled.examTerms.orEmpty() + remote.examTerms.orEmpty()).distinct(),
            tweaks = (remote.tweaks + bundled.tweaks).distinctBy { it.from to it.to }
        )
    }

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
        }.getOrNull() ?: throw ZjuException("校历配置格式无法解析")

        return CalendarBundle(
            lastUpdated = dto.lastUpdated,
            termConfigs = dto.termConfigs.orEmpty().map {
                TermConfigBundle(it.year, it.term, it.begin, it.end, it.firstWeekNo)
            },
            classTerms = dto.classTerms.orEmpty(),
            examTerms = dto.examTerms.orEmpty(),
            tweaks = dto.tweaks.orEmpty().map {
                TweakBundle(it.tweakType ?: 0, it.description, it.from, it.to)
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
     * 只保留指定学期，并把 tweaks 裁到这一期的区间内。
     *
     * 裁剪的理由：config.json 里通常同时挂着秋冬与春夏两期的 tweaks，
     * 全量带进一份只描述单期的文档，会让另一个学期的停课日也落进库。
     */
    fun selectTerm(calendar: CalendarBundle, year: String, term: Int): CalendarBundle {
        val target = calendar.termConfigs.firstOrNull { it.year == year && it.term == term }
            ?: return calendar
        val begin = ZjuCalendarAdapter.yyyymmddToEpochDay(target.begin)
        val end = ZjuCalendarAdapter.yyyymmddToEpochDay(target.end)
        val keptTweaks = calendar.tweaks.filter { tw ->
            val f = ZjuCalendarAdapter.yyyymmddToEpochDay(tw.from)
            val t = ZjuCalendarAdapter.yyyymmddToEpochDay(tw.to)
            if (begin == null || end == null || f == null || t == null) true
            else t >= begin && f <= end
        }
        return calendar.copy(
            termConfigs = listOf(target),
            classTerms = listOf("$year:$term"),
            tweaks = keptTweaks
        )
    }

    private suspend fun fetchText(): String? {
        for (url in CONFIG_URLS) {
            val ok = runCatching {
                client.newCall(Request.Builder().url(url).get().build()).execute().use { res ->
                    if (res.isSuccessful) res.body?.string() else null
                }
            }.getOrNull()
            if (!ok.isNullOrBlank()) return ok
        }
        return null
    }

    private val moshi = Moshi.Builder().build()

    companion object {
        /**
         * 与 zju-ical `pkg/zjuservice/zjuconst/config.go` 的 `OnlineConfigPath` 一致，
         * 后两条是兜底。
         *
         * 多挂一条 jsDelivr 是因为裸 raw.githubusercontent.com 在不少网络环境里
         * 连不上（本机实测直接超时），而 gh-proxy 本身也是第三方服务、会挂。
         * 校历是全校同一份静态数据，任何一个源返回的内容都一样，
         * 所以逐个试过去比「一个源失败就让用户手动导入」划算得多。
         */
        private val CONFIG_URLS = listOf(
            "https://gh-proxy.com/https://raw.githubusercontent.com/cxz66666/zju-ical/master/configs/config.json",
            "https://fastly.jsdelivr.net/gh/cxz66666/zju-ical@master/configs/config.json",
            "https://raw.githubusercontent.com/cxz66666/zju-ical/master/configs/config.json"
        )

        /**
         * 内置兜底配置文件（assets）。上面三条全部失败时用它。
         *
         * 里面的日期由用户按浙大官方校历提供：2026-2027 秋冬 2026-09-14 ~ 2027-01-04。
         * **联网拿到时永远以联网的为准**，这份只在网络完全不通时才生效。
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
    @Json(name = "TweakType") val tweakType: Int? = null,
    @Json(name = "Description") val description: String? = null,
    @Json(name = "From") val from: Int? = null,
    @Json(name = "To") val to: Int? = null
)

@JsonClass(generateAdapter = true)
data class UpstreamTermConfigDto(
    @Json(name = "Year") val year: String? = null,
    @Json(name = "Term") val term: Int? = null,
    @Json(name = "Begin") val begin: Int? = null,
    @Json(name = "End") val end: Int? = null,
    @Json(name = "FirstWeekNo") val firstWeekNo: Int? = null
)