package com.example.data.source.weather

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
            for (url in remoteUrls) {
                when (val result = fetchOne(url)) {
                    is WeatherFetch.Got -> return@withContext result
                    is WeatherFetch.Unavailable -> {
                        val host = url.toHttpUrlOrNull()?.host ?: url
                        reasons += "$host: ${result.reason}"
                    }
                }
            }
            WeatherFetch.Unavailable(
                reasons.joinToString("；").ifEmpty { "没有配置任何天气远程地址" },
                WeatherFailureKind.OFFLINE
            )
        }
    }

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
         * Gitee 国内访问较快，GitHub raw 作为备用。
         */
        val REMOTE_URLS = listOf(
            "https://raw.githubusercontent.com/chenqianyu486-sys/MiuList/main/weather.json",
            "https://ghproxy.net/https://raw.githubusercontent.com/chenqianyu486-sys/MiuList/main/weather.json",
            "https://gitee.com/zjuchenqianyu/MiuList/raw/main/weather.json"
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
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
