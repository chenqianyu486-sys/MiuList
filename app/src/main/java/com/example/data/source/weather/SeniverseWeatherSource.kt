package com.example.data.source.weather

import com.example.data.calendar.AppZone
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * 静态托管的天气数据源。
 *
 * ## 为什么不直连心知天气
 *
 * 直连心知天气需要拿私钥签名，私钥会明文编译进 APK，任何人拿到都能提取盗用额度。
 * 现采用方案二（静态托管）：
 * 1. 由 GitHub Actions 定时任务在云端读取环境变量 `SENIVERSE_KEY`，拉取心知天气后更新 `weather.json`。
 * 2. 客户端只拉取公开的 `weather.json`（支持 Gitee 与 GitHub 多镜像回退）。
 * 3. 客户端**没有任何密钥**，零安全风险、零被刷配额风险。
 *
 * ## 天气来自心知，空气来自 Open-Meteo
 *
 * `weather.json` 是**两个上游拼**的：天气本体走心知，空气质量走 Open-Meteo。
 *
 * 原因是心知把「天气类」和「空气类」做成两个并列的产品，
 * 只开通天气类的 key 调 `air/now.json` 会返回 `AP010002`（没有权限访问这个 API 接口）。
 * Open-Meteo 的空气端点不要 key，配额也不绑定账号。
 *
 * 所以这个类名里的 Seniverse 只对**天气**成立；空气是同一份文件里的另一个字段，
 * 它的口径与上游等级的处理见 [DailyWeather.aqi] 与 [DailyWeather.airQualityLabel]。
 *
 * ## 为什么不改成客户端直连 Open-Meteo 拿天气
 *
 * 试过，那套实现是完整的，但它给的是**数值模式**（ECMWF / GFS / ICON），
 * 而用户要的是**地面观测**。同一时刻两边的数值会有出入。
 * 空气则两边都无所谓 —— 托管那份本来就是 Open-Meteo 的。
 * 所以天气留在托管这一侧。
 */
class SeniverseWeatherSource(
    private val client: OkHttpClient = defaultClient(),
    /** 静态文件远程地址列表，逐个尝试。 */
    private val remoteUrls: List<String> = REMOTE_URLS,
    /** 单测注入：给定文本完全跳过网络。 */
    private val responseText: String? = null,
    /**
     * 单测注入：固定「现在」，这样「这份文件有多旧」是确定的值而不是碰运气。
     *
     * 默认走系统时钟。这个时刻有两个用途：算文件年龄，以及判断**够不够新**
     * （见 [FRESH_FILE_WINDOW_MILLIS]）。
     */
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) : WeatherSource {

    override suspend fun fetchToday(location: WeatherLocation): WeatherFetch {
        if (responseText != null) return classify(responseText, 200)
        return withContext(Dispatchers.IO) {
            val reasons = mutableListOf<String>()
            // 拿到了、但日期不是今天的那些。留着当兜底：万一所有镜像都只给得出
            // 昨天的文件，把它交上去，好让决策层说「不是今天的数据」，
            // 而不是一句笼统的「拉取失败」。
            var notToday: WeatherFetch.Got? = null
            // 今天的那几份里**最新的**那个（按文件自己的 lastUpdated 比）。
            var best: WeatherFetch.Got? = null
            var bestAge = Long.MAX_VALUE
            // 「找更新的那份」的时间预算。见 [SEARCH_BUDGET_MILLIS]。
            var searchDeadline = 0L

            for (url in remoteUrls) {
                // 已经有一份今天的了，后面的镜像就只是「去看看有没有更新的」——
                // 那就不值得再等满一整个超时，超时上限压到剩余预算里。
                val searching = searchDeadline > 0L
                val callClient = if (searching) {
                    val remaining = searchDeadline - nowMillis()
                    if (remaining <= 0L) {
                        reasons += "${hostOf(url)}: 找更新的时间预算用完了"
                        break
                    }
                    client.newBuilder().callTimeout(Duration.ofMillis(remaining)).build()
                } else {
                    client
                }

                when (val result = fetchOne(url, callClient)) {
                    is WeatherFetch.Got -> {
                        val parsed = parseToday(result.text, location)
                        if (parsed == null || parsed.epochDay != AppZone.todayEpochDay()) {
                            if (notToday == null) notToday = result
                            reasons += if (parsed == null) {
                                "${hostOf(url)}: 正文解析不出来"
                            } else {
                                "${hostOf(url)}: 文件不是今天的"
                            }
                            continue
                        }
                        val age = ageOf(parsed.updatedAtMillis)
                        // 已经选过一份、而这份不比它新 → 不用再管这个镜像。
                        if (best != null && age >= bestAge) {
                            reasons += "${hostOf(url)}: 比已选中的那份还旧"
                            continue
                        }
                        best = result
                        bestAge = age
                        // 从这一刻起才算「找更新的」：预算从此刻开始走。
                        if (searchDeadline == 0L) {
                            searchDeadline = nowMillis() + SEARCH_BUDGET_MILLIS
                        }
                        // **够新就收手**：后面的镜像推的是同一份文件，只是推送时刻不同，
                        // 再问下去只是白等。
                        if (age in 0 until FRESH_FILE_WINDOW_MILLIS) {
                            return@withContext result
                        }
                    }
                    is WeatherFetch.Unavailable -> {
                        reasons += "${hostOf(url)}: ${result.reason}"
                    }
                }
            }

            best ?: notToday ?: WeatherFetch.Unavailable(
                reasons.joinToString("；").ifEmpty { "没有配置任何天气远程地址" },
                WeatherFailureKind.OFFLINE
            )
        }
    }

    /**
     * 这份文件有多旧（毫秒）。
     *
     * **没有 `lastUpdated` 的文件按「无限旧」算**，而不是按「刚刚」算：
     * 判不出年龄就等于判不出新旧，而把它当成新的会正好毁掉这个机制
     * （第一个没有时间戳的镜像会把后面更新的那份比下去）。
     *
     * 时间戳在未来（时钟不一致）同样按「无限旧」算 —— 宁可多问一个镜像，
     * 也不要让一份来路不明的时间戳把顺序搅乱。
     */
    private fun ageOf(updatedAtMillis: Long?): Long {
        if (updatedAtMillis == null) return Long.MAX_VALUE
        val age = nowMillis() - updatedAtMillis
        return if (age < 0) Long.MAX_VALUE else age
    }

    private fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host ?: url

    private fun fetchOne(url: String, callClient: OkHttpClient = client): WeatherFetch = try {
        callClient.newCall(Request.Builder().url(url).get().build()).execute().use { res ->
            classify(res.body?.string(), res.code)
        }
    } catch (e: Exception) {
        WeatherFetch.Unavailable(e.describe(), WeatherFailureKind.OFFLINE)
    }

    private fun classify(body: String?, httpCode: Int): WeatherFetch {
        if (body.isNullOrBlank()) {
            return WeatherFetch.Unavailable("天气托管文件返回了空内容", WeatherFailureKind.REJECTED)
        }
        if (httpCode !in 200..299) {
            return WeatherFetch.Unavailable("HTTP $httpCode", WeatherFailureKind.REJECTED)
        }
        return WeatherFetch.Got(body)
    }

    override fun parseToday(text: String, location: WeatherLocation): DailyWeather? {
        val dto = runCatching { adapter.fromJson(text) }.getOrNull() ?: return null
        val epochDay = parseEpochDay(dto.date) ?: return null

        return DailyWeather(
            cityName = dto.cityName?.takeIf { it.isNotBlank() } ?: location.name,
            epochDay = epochDay,
            textDay = dto.textDay.orEmpty(),
            textNight = dto.textNight.orEmpty(),
            high = dto.high,
            low = dto.low,
            aqi = dto.aqi,
            // **有上游等级就用上游那份。** 当前托管文件里这个字段是 null
            // （空气来自 Open-Meteo，它只给 us_aqi、不给中文等级），
            // 于是走 describeAqi 兜底。字段本身留着：换回心知时它就有值了，
            // 而那时它比本地那张表更权威（等级和数值出自同一份数据）。
            aqiQuality = dto.aqiQuality?.takeIf { it.isNotBlank() },
            // **文件自己的写入时刻**。这是判断「这份数据有多旧」的唯一依据，
            // 少了它，客户端只能靠 `date`（是不是今天）去猜新鲜度 ——
            // 而 `date` 分不开「今天早上的」和「今天晚上的」。
            updatedAtMillis = dto.lastUpdated
        )
    }

    /**
     * 空气质量已合并在静态 `weather.json` 中，由 [parseToday] 一并解析。
     * 无需单独发网络请求。
     */
    override suspend fun fetchAirQuality(location: WeatherLocation): WeatherFetch =
        WeatherFetch.Unavailable("空气质量已内嵌于天气文件中", WeatherFailureKind.REJECTED)

    override fun parseAirQuality(text: String): Int? =
        runCatching { adapter.fromJson(text)?.aqi }.getOrNull()

    companion object {
        /**
         * 一份托管文件「够新」的门槛：**3 小时**。
         *
         * ## 这个数字从哪来
         *
         * 上游的调度目标是「`weather.json` 的年龄 ≤ 3 小时」，看门狗工作流
         * （`.github/workflows/weather-watchdog.yml`）每 30 分钟查一次，
         * 超过 3 小时就补跑一次同步并把结论亮出来。
         * 所以「3 小时」在这里不是一句愿望，而是**上游在维持的承诺**。
         *
         * ## 它在这里干什么用
         *
         * 只决定**要不要继续试下一个镜像**：手上这份够新就不问了
         * （快路径，国内直连的 Gitee 0.4 秒就能拿到）；
         * 不够新才把剩下的镜像挨个试一遍，最后取 `lastUpdated` 最大的那份。
         *
         * ## 为什么不能只看 `date`
         *
         * `date` 只能分出「今天 / 不是今天」。同一个今天里可以藏着差别巨大的
         * 两份数据：一份 20 分钟前的，一份 8 小时前的（Gitee 那次推送失败，
         * GitHub 上已经是新的，它还停在 8 小时前 —— 实测 2026-11-02 就这样）。
         * 「第一个 date 是今天的就收手」会把那份旧的当成现状显示，
         * 而后面那个镜像上更新的那份**根本读不到**。
         */
        const val FRESH_FILE_WINDOW_MILLIS = 3L * 60L * 60L * 1000L

        /**
         * 「再去找一份更新的」这件事**最多花多久**：**3 秒**。
         *
         * ## 为什么要有这个上限
         *
         * 客户端只有在**自己那份缓存已经旧了**（≥ [FRESH_FILE_WINDOW_MILLIS]）时才会联网，
         * 而镜像推的是同一份文件 —— 所以**每次联网，手上那份几乎必然也是旧的**，
         * 「够新就收手」那条快路径在日常里基本不触发。
         * 不设上限的话，一次冷启动就要把三个镜像挨个走完（每个 5 秒连接超时、
         * 8 秒读取超时），实测能到 20 秒以上 —— 那是天气块那种次要信息
         * 拖慢整屏的代价。
         *
         * ## 3 秒怎么来的
         *
         * 实测国内直连：Gitee 0.4 秒、ghproxy 1.5 秒、raw.githubusercontent 连不上。
         * 也就是说**有用的是前两个**，第三个基本是必挂的 —— 3 秒预算够问完
         * 前两个，而问第三个的那部分被预算自动截断。
         *
         * 预算**从拿到第一份今天的文件那一刻开始走**，所以：
         * 第一个镜像（它可能本来就是唯一能通的那个）仍然用完整的超时；
         * 后面的才是「找更新的」，超时就走人，拿到手上有几份算几份。
         */
        const val SEARCH_BUDGET_MILLIS = 3L * 1000L

        /**
         * 静态天气数据的托管地址，逐个镜像回退。
         *
         * **顺序按「国内能直连」排，不按「离源近」排。**
         * 原来第一顺位是 `raw.githubusercontent.com` —— 实测在国内直连必然超时
         * （12s 挂掉），而客户端的连接超时也是 15s，于是每个缓存未命中的用户
         * 都要先白等满一个超时，才轮得到第二个能通的镜像。
         * 实测（不走代理）：Gitee 0.4s、ghproxy 1.5s、raw.githubusercontent 超时。
         *
         * 排在最后的 raw.githubusercontent 仍要留着：它在墙外网络上反而最稳，
         * 而 App 只服务国内这件事不该写死成一个「只有这几个能用」的假设。
         *
         * **顺序不再决定「谁被采纳」。** 采纳的是 `lastUpdated` 最大的那份
         * （见 [FRESH_FILE_WINDOW_MILLIS]）—— 排在前面的镜像只是**先被问**，
         * 它排第一不代表它更权威。这也正是 Gitee 排第一却曾经拖累一整天
         * 天气的原因：它恰好是那个会推送失败的镜像。
         */
        val REMOTE_URLS = listOf(
            "https://gitee.com/zjuchenqianyu/MiuList/raw/main/weather.json",
            "https://ghproxy.net/https://raw.githubusercontent.com/chenqianyu486-sys/MiuList/main/weather.json",
            "https://raw.githubusercontent.com/chenqianyu486-sys/MiuList/main/weather.json"
        )

        /**
         * 超时**按「这份文件有多小」定，不按「网络通常多快」定。
         *
         * 这份文件只有几百字节。连不上时多等一会儿毫无意义 —— 挂着的镜像
         * 不会因为再等 10 秒就活过来，只会让用户对着「读不到」多等一轮。
         * 5 秒够覆盖正常网络（Gitee 实测 0.4s、ghproxy 1.5s），也够慢网一把；
         * 而三个镜像全挂时，15s×3 变 5s×3，用户少等 30 秒。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

        private val adapter by lazy {
            Moshi.Builder().build().adapter(StaticWeatherDto::class.java)
        }
    }
}

/**
 * 静态托管的天气 JSON 格式（由 scripts/sync_weather.py 生成）。
 */
@JsonClass(generateAdapter = true)
data class StaticWeatherDto(
    @Json(name = "lastUpdated") val lastUpdated: Long? = null,
    @Json(name = "cityName") val cityName: String? = null,
    @Json(name = "date") val date: String? = null,
    @Json(name = "textDay") val textDay: String? = null,
    @Json(name = "textNight") val textNight: String? = null,
    @Json(name = "high") val high: Int? = null,
    @Json(name = "low") val low: Int? = null,
    @Json(name = "aqi") val aqi: Int? = null,
    @Json(name = "aqiQuality") val aqiQuality: String? = null
)
