package com.example.data.source.weather

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Open-Meteo 天气与空气质量的**取与解**。不决定「该信哪一份」，那是
 * [WeatherService] 的事 —— 与 [com.example.data.source.zju.ZjuCalendarSource]
 * 的分工一样：执行与决策分开，才不会出现两条互相独立的取数链路。
 *
 * ## 两个注入参数都是「null = 真联网」，这是个陷阱
 *
 * [responseText] 与 [airResponseText] 为 null 时，[fetchToday] / [fetchAirQuality]
 * 会**真的发请求**。写单测时漏传一个参数，于是那条用例会发一次真请求出去 ——
 * 表现是断言红在一个与它要验的东西毫无关系的地方（实测：断言写着
 * `usAqi=null`，拿回来的却是杭州当天的真实读数 150）。
 *
 * 所以测试里的构造函数默认参数必须是「一份确定的正文」，而不是 null。
 * 真要模拟拉不到，就注入一段明确的错误正文。
 *
 * ## 换成 Open-Meteo，是因为不要密钥
 *
 * 上一版用心知天气，请求得由客户端拿私钥做 HMAC-SHA1 签名。签名必须在
 * 客户端算，私钥就必须在客户端的内存里出现一次 —— 于是它以明文常量躺在
 * dex 里（实测 `classes11.dex` 能直接搜到），任何人拿到 APK 都能取出来盗用额度。
 * 混淆、字符串加密、挪进 NDK 都只是把「一条命令」变成「一条麻烦一点的命令」。
 *
 * Open-Meteo 根本没有这个概念：**不要 key、不要注册、没有配额绑定到某个账号**。
 * 于是这份代码里一个秘密都没有，也就没有需要保护的东西。
 *
 * 代价是数据来自数值模式（ECMWF / GFS / ICON 等）而非地面观测，
 * 精度略逊于国内商业接口；界面上的措辞也必须如实反映这一点（见 [describeWeatherCode]）。
 *
 * ## 署名是许可证义务，不是可选项
 *
 * 数据为 CC BY 4.0，要求署名。所以抽屉底部有一行「天气数据 · Open-Meteo」。
 * 删它之前先想清楚：那是把别人的数据拿来用时该做的事，不是装饰。
 *
 * ## 只取今天，且**按当地时间**取
 *
 * `forecast_days=1` 只要今天（多取纯属浪费）。
 * `timezone` 更关键：`daily` 的日期按它给的时区切分，缺省是 GMT，
 * 在东八区会把「今天」算成昨天或前天，于是缓存判重全错、
 * 界面上会显示一份昨天的温度冒充今天。
 */
class OpenMeteoWeatherSource(
    private val client: OkHttpClient = defaultClient(),
    /** 单测注入：给定一段**天气**响应文本，完全跳过网络。 */
    private val responseText: String? = null,
    /**
     * 单测注入：给定一段**空气质量**响应文本。
     *
     * 与 [responseText] 分开而不是共用一个 —— 两者打的是不同端点，
     * 共用会让「天气成功、空气失败」这种最该被测到的组合根本构造不出来。
     */
    private val airResponseText: String? = null
) : WeatherSource {

    /**
     * 取今天的逐日预报。
     *
     * 返回 [WeatherFetch] 而不是 `DailyWeather?`：拉不到有好几种完全不同的毛病
     * （域名解析不了、TLS 被掐、服务端改了参数名），处置方式各不相同，
     * 压成 null 会让界面无从显示、排查也没线索。
     */
    override suspend fun fetchToday(location: WeatherLocation): WeatherFetch {
        val url = requestUrl(location)
        // 注入的文本当成「服务端返回的正文」走**同一条**判定路径，
        // 否则单测里的成功分支和线上走的是两段代码。
        if (responseText != null) return classify(responseText, 200)
        return try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { res ->
                classify(res.body?.string(), res.code)
            }
        } catch (e: Exception) {
            // 抛异常 = 一个字节的答复都没拿到。这是唯一一处该标 OFFLINE 的地方。
            WeatherFetch.Unavailable(e.describe(), WeatherFailureKind.OFFLINE)
        }
    }

    /**
     * 把一段正文 + 状态码判成成功或失败。
     *
     * **先看正文再看状态码**：参数写错时 Open-Meteo 会回 HTTP 400 加上
     * `{"error":true,"reason":"Latitude must be in range…"}`。
     * 只报「HTTP 400」等于把服务端已经把答案写好的那句话扔掉。
     *
     * 这里三种失败一律 [WeatherFailureKind.REJECTED]：答复**收到了**，
     * 只是内容不能用。查网络对它们没有帮助。
     */
    private fun classify(body: String?, httpCode: Int): WeatherFetch {
        if (body.isNullOrBlank()) {
            return WeatherFetch.Unavailable("Open-Meteo 返回了空内容", WeatherFailureKind.REJECTED)
        }
        // 只解一次：解两遍在成功响应上纯属白干，在错误响应上也是。
        val reason = errorReasonOf(body)
        if (reason != null) return WeatherFetch.Unavailable(reason, WeatherFailureKind.REJECTED)
        if (httpCode !in 200..299) {
            return WeatherFetch.Unavailable("HTTP $httpCode", WeatherFailureKind.REJECTED)
        }
        return WeatherFetch.Got(body)
    }

    /** `{"error":true,"reason":"…"}`。成功响应没有 `error` 键，取不到就是成功。 */
    private fun errorReasonOf(body: String): String? =
        runCatching {
            adapter.fromJson(body)
                ?.takeIf { it.error == true }
                ?.reason?.takeIf { it.isNotBlank() }
        }.getOrNull()

    /** 拼出请求地址。抽出来是为了能直接单测。 */
    fun requestUrl(location: WeatherLocation): String =
        BASE_URL.toHttpUrl().newBuilder()
            .addPathSegments("v1/forecast")
            .addQueryParameter("latitude", location.latitude.toString())
            .addQueryParameter("longitude", location.longitude.toString())
            .addQueryParameter("daily", DAILY_VARIABLES)
            // 不给 timezone，daily 的日期按 GMT 切，东八区会整体错一天。
            .addQueryParameter("timezone", TIMEZONE)
            .addQueryParameter("forecast_days", "1")
            .addQueryParameter("temperature_unit", "celsius")
            .build()
            .toString()

    /**
     * 取**当前**空气质量（美国 EPA 口径的 AQI）。
     *
     * 走的是另一个端点：[AIR_QUALITY_BASE_URL]。与天气分开拉、分开失败 ——
     * 空气这一项缺失不该让天气也跟着变成「读不到」。
     *
     * 仍然不要 key。空气质量与天气同属 CC BY 4.0（署名义务因此扩到
     * 「天气与空气质量 · Open-Meteo / CAMS」，见设置页）。
     */
    override suspend fun fetchAirQuality(location: WeatherLocation): WeatherFetch {
        val url = airQualityUrl(location)
        if (airResponseText != null) return classify(airResponseText, 200)
        return try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { res ->
                classify(res.body?.string(), res.code)
            }
        } catch (e: Exception) {
            // 与天气同一个道理：没拿到答复就是 OFFLINE。
            // 这个类别到这里就止住了 —— 空气拉不到不会变成 WeatherState.Unavailable。
            WeatherFetch.Unavailable(e.describe(), WeatherFailureKind.OFFLINE)
        }
    }

    fun airQualityUrl(location: WeatherLocation): String =
        AIR_QUALITY_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegments("v1/air-quality")
            .addQueryParameter("latitude", location.latitude.toString())
            .addQueryParameter("longitude", location.longitude.toString())
            .addQueryParameter("current", CURRENT_AIR_VARIABLES)
            .addQueryParameter("timezone", TIMEZONE)
            .build()
            .toString()

    /**
     * 从空气质量响应里取出 `current.us_aqi`。
     *
     * **取不到就返回 null，不猜。** 空气质量是可空的：它没有「今天的昨天」
     * 这种说法（它是抓取那一刻的读数），拿不到就该在界面上不显示，
     * 而不是拿缓存里的旧值继续显示 —— 那会让用户按一个几小时前的空气
     * 状况决定今天戴不戴口罩。
     */
    override fun parseAirQuality(text: String): Int? {
        val current = runCatching { airAdapter.fromJson(text)?.current }.getOrNull() ?: return null
        val raw = current?.usAqi ?: return null
        return raw.roundToInt().takeIf { it >= 0 }
    }

    /**
     * 从一段响应文本解析出**第一条**（就是今天，因为请求只给了 forecast_days=1）。
     *
     * [location] 传进来是因为响应里**没有城市名** —— 坐标接口不回传地名，
     * 显示什么只能由我们声明，详见 [WeatherLocation.name]。
     */
    override fun parseToday(text: String, location: WeatherLocation): DailyWeather? {
        val root = runCatching { adapter.fromJson(text) }.getOrNull() ?: return null
        val daily = root?.daily ?: return null
        val date = daily.time?.firstOrNull { !it.isNullOrBlank() } ?: return null
        // 缺日期就无法判断是不是今天，不能放行。
        val epochDay = parseEpochDay(date) ?: return null

        val word = describeWeatherCode(daily.weatherCode?.firstOrNull())
        return DailyWeather(
            cityName = location.name,
            epochDay = epochDay,
            textDay = word,
            // 逐日聚合不分昼夜：宁可留空让 textAt() 退回白天，
            // 也不把白天的词抄成「夜间的天气」——那会让数据看起来有昼夜区分，
            // 而它其实没有。
            textNight = "",
            high = daily.high?.firstOrNull()?.roundToInt(),
            low = daily.low?.firstOrNull()?.roundToInt()
        )
    }

    companion object {
        const val BASE_URL = "https://api.open-meteo.com"

        /** 空气质量是**另一个端点**，不是 forecast 的一个参数。 */
        const val AIR_QUALITY_BASE_URL = "https://air-quality-api.open-meteo.com"

        /** 固定按东八区切日界线。这个 App 只服务国内，写死比让用户选更省事。 */
        const val TIMEZONE = "Asia/Shanghai"

        /** 界面上只用到「今天什么天 + 最高最低温」，其余变量一律不取。 */
        const val DAILY_VARIABLES = "weather_code,temperature_2m_max,temperature_2m_min"

        /**
         * 只要 `us_aqi`，不要 PM2.5 / PM10 的原值。
         *
         * 界面上写的是「空气良 64」这样一个指数 + 等级，不是浓度。
         * 顺手把原始浓度也取回来，就等于多两个**读者根本不会去看**的数字，
         * 而且一旦界面上并排显示，用户会开始比较 µg/m³ 与别的软件里的数字 ——
         * 不同数据源的同一时刻本来就不能直接比。
         *
         * 选 `us_aqi` 而不是 `european_aqi`：两套分段完全不同（同一片空气能差出一整个等级），
         * 界面上写「良」必须配得上中国人习惯的那套分级（0-50 优 / 51-100 良 / …）。
         */
        const val CURRENT_AIR_VARIABLES = "us_aqi"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        private val adapter by lazy {
            Moshi.Builder().build().adapter(OpenMeteoResponseDto::class.java)
        }

        private val airAdapter by lazy {
            Moshi.Builder().build().adapter(OpenMeteoAirQualityDto::class.java)
        }
    }
}

/**
 * WMO 天气代码 → 中文。
 *
 * 表来自 WMO 4677，经 Open-Meteo 文档收录的那一版。用「中文」而不是图标编码，
 * 是因为界面那一行的图标是靠 `glyphFor()` 读关键词挑的
 * （`com.example.ui.components`）—— 换成别的词就得连那套判定一起重写。
 *
 * 措辞上刻意保留量级（小雨 / 中雨 / 大雨）：心知那版也是这么写的，
 * 「今天下小雨」和「今天下大雨」该让人一眼看出区别。
 *
 * **认不出来的码返回「未知」而不是猜一个。** 界面认不出就画中性图标，
 * 总比把没见过的情况编成「晴」强。
 */internal fun describeWeatherCode(code: Int?): String = when (code) {
    0 -> "晴"
    1 -> "晴间多云"
    2 -> "多云"
    3 -> "阴"
    45 -> "雾"
    48 -> "雾凇"
    51, 53 -> "毛毛雨"
    55 -> "浓毛毛雨"
    56, 57 -> "冻毛毛雨"
    61 -> "小雨"
    63 -> "中雨"
    65 -> "大雨"
    66, 67 -> "冻雨"
    71 -> "小雪"
    73 -> "中雪"
    75 -> "大雪"
    77 -> "米雪"
    80, 81 -> "阵雨"
    82 -> "强阵雨"
    85 -> "小阵雪"
    86 -> "大阵雪"
    95 -> "雷阵雨"
    96 -> "雷阵雨伴冰雹"
    99 -> "强雷阵雨伴冰雹"
    else -> "未知"
}

/** 把 `2026-10-16` 这样的日期转成 epochDay。解析不了返回 null，不抛。 */
internal fun parseEpochDay(text: String?): Long? {
    val t = text?.trim().orEmpty()
    if (t.length < 10) return null
    return runCatching { LocalDate.parse(t.substring(0, 10)).toEpochDay() }.getOrNull()
}

/**
 * 把异常翻成人 / 运维看得懂的一句话，而不是 `java.net` 的类名。
 *
 * **地址要从消息里抹掉。** 这里没有密钥可泄（这正是换源的收益之一），
 * 但这句话一路显示到界面上、会被截图转发，而一个几百字符的地址
 * 对用户没有任何用处。抹掉之后留下的部分仍然能定位问题。
 */
internal fun Exception.describe(): String {
    val bare = when (this) {
        is java.net.UnknownHostException -> "域名解析不了（$message）"
        is javax.net.ssl.SSLException -> "TLS 握手失败（${message ?: "被对端中断"}）"
        is java.net.SocketTimeoutException -> "连接超时"
        is java.net.ConnectException -> "连不上天气服务器"
        else -> message ?: javaClass.simpleName
    }
    return bare.replace(URL_PATTERN, "天气接口")
}

/** 匹配 `https://…` 直到空白为止，用来把 URL 从错误消息里抹掉。 */
private val URL_PATTERN = Regex("""https?://\S+""")

// 字段对照上游文档，供改 DTO 时核对：
//   daily.time[i]                    该条预报对应的当地日期（forecast_days=1 时只有一个）
//   daily.weather_code[i]            WMO 天气代码，见 describeWeatherCode 的表
//   daily.temperature_2m_max[i]      最高温，unit=celsius 时是摄氏度
//   daily.temperature_2m_min[i]      最低温
//   error / reason                   只在出错时出现，成功响应没有这两个键

@JsonClass(generateAdapter = true)
internal data class OpenMeteoResponseDto(
    @Json(name = "daily") val daily: OpenMeteoDailyDto? = null,
    @Json(name = "error") val error: Boolean? = null,
    @Json(name = "reason") val reason: String? = null
)

@JsonClass(generateAdapter = true)
internal data class OpenMeteoDailyDto(
    @Json(name = "time") val time: List<String>? = null,
    /**
     * 声明成可空的元素列表而不是 `List<Int>`：数组里任何一个值缺失，
     * 都只该让那一个数字变成 null，不该让整份响应解析失败 ——
     * 那样连日期和另一端温度一起丢，界面就从「少一个数字」变成「天气不可用」。
     */
    @Json(name = "weather_code") val weatherCode: List<Int?>? = null,
    @Json(name = "temperature_2m_max") val high: List<Double?>? = null,
    @Json(name = "temperature_2m_min") val low: List<Double?>? = null
)

// 字段对照上游文档，供改 DTO 时核对：
//   current.us_aqi                    美国 EPA 口径的空气质量指数（0-500）
//   current.time / current.interval   这一读数对应的时间与时间步长
//   error / reason                   只在出错时出现，成功响应没有这两个键

@JsonClass(generateAdapter = true)
internal data class OpenMeteoAirQualityDto(
    @Json(name = "current") val current: OpenMeteoAirCurrentDto? = null,
    @Json(name = "error") val error: Boolean? = null,
    @Json(name = "reason") val reason: String? = null
)

@JsonClass(generateAdapter = true)
internal data class OpenMeteoAirCurrentDto(
    @Json(name = "us_aqi") val usAqi: Double? = null
)
