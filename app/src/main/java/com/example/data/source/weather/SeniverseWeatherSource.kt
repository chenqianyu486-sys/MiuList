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
 * 静态托管的心知天气 (Seniverse) 数据源。
 *
 * ## 为什么不直连心知天气
 *
 * 直连心知天气需要拿私钥签名，私钥会明文编译进 APK，任何人拿到都能提取盗用额度。
 * 现采用方案二（静态托管）：
 * 1. 由 GitHub Actions 定时任务在云端读取环境变量 `SENIVERSE_KEY`，拉取心知天气后更新 `weather.json`。
 * 2. 客户端只拉取公开的 `weather.json`（支持 Gitee 与 GitHub 多镜像回退）。
 * 3. 客户端**没有任何密钥**，零安全风险、零被刷配额风险。
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
            // **等级要连着数值一起用。** 托管文件里的 aqi 是心知口径，
            // 拿 describeAqi 那张 EPA 表去解释它，等于凭空造一个等级
            // （同一片空气两边能差一整档）。心知自己给的 quality 才是配套的。
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
