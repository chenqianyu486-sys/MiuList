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
 * 所以这里直接读它，而不是在 App 里另抄一份——另抄一份就意味着每次学校改校历
 * 都要等 App 发版。
 *
 * 拉取顺序与 zju-ical 自身一致：先走它的 gh-proxy 镜像，失败再退回裸的 raw 地址。
 *
 * 返回的 [CalendarBundle] **就是 v2 文档里的 `calendar` 块**——
 * 字段名与类型和上游逐字段相同，所以这份 config.json 本身也能被当成
 * 「只有校历的 v2 文档」直接导入。
 */
class ZjuCalendarSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    /**
     * 取一次完整配置。失败抛 [ZjuException]。
     *
     * 返回的 Bundle 里 `periods` 已经填上浙大作息：上游把作息硬编码在 Go 里、
     * config.json 里没有对应字段，而本项目要用它画栅格。
     */
    suspend fun fetch(): CalendarBundle = withContext(Dispatchers.IO) {
        val text = fetchText() ?: throw ZjuException("校历配置拉取失败，可改用文件导入")
        parse(text)
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