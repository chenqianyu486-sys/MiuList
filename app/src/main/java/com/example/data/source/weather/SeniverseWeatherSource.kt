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
    private val responseText: String? = null
) : WeatherSource {

    override suspend fun fetchToday(location: WeatherLocation): WeatherFetch {
        if (responseText != null) return classify(responseText, 200)
        return withContext(Dispatchers.IO) {
            val reasons = mutableListOf<String>()
            // 拿到了、但日期不是今天的那些。留着当兜底：万一所有镜像都只给得出
            // 昨天的文件，把它交上去，好让决策层说「不是今天的数据」，
            // 而不是一句笼统的「拉取失败」。
            var notToday: WeatherFetch.Got? = null

            for (url in remoteUrls) {
                when (val result = fetchOne(url)) {
                    is WeatherFetch.Got -> {
                        // **关键：日期不对就继续试下一个镜像。**
                        //
                        // 镜像之间的新鲜度**不一致**，而且不一致是常态：
                        // Gitee 那份靠 CI 另推一次，而 CI 那一步的推送是
                        // `|| true` 兜底的 —— token 没配/过期时它**静默失败**，
                        // 于是 Gitee 上的 weather.json 可以比 GitHub 上那份
                        // 旧上一天。实测就撞到过：Gitee 是 10-18、ghproxy 是 10-19。
                        //
                        // 旧的做法是「第一个能通的就算」，于是一个陈旧镜像排在前面，
                        // 就能把一整天的天气变成「读不到」—— 而它自己明明是好的。
                        if (isToday(result.text, location)) return@withContext result
                        if (notToday == null) notToday = result
                        reasons += "${hostOf(url)}: 文件不是今天的"
                    }
                    is WeatherFetch.Unavailable -> {
                        reasons += "${hostOf(url)}: ${result.reason}"
                    }
                }
            }

            notToday ?: WeatherFetch.Unavailable(
                reasons.joinToString("；").ifEmpty { "没有配置任何天气远程地址" },
                WeatherFailureKind.OFFLINE
            )
        }
    }

    /** 这份文件是不是**今天**的。解不出来也算「不是今天」—— 交给决策层去报错。 */
    private fun isToday(text: String, location: WeatherLocation): Boolean {
        val parsed = parseToday(text, location) ?: return false
        return parsed.epochDay == AppZone.todayEpochDay()
    }

    private fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host ?: url

    private fun fetchOne(url: String): WeatherFetch = try {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { res ->
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
            aqiQuality = dto.aqiQuality?.takeIf { it.isNotBlank() }
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
