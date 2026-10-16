package com.example.data.source.weather

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 天气的**决策层**，也是唯一决定「界面上该显示什么」的地方。
 *
 * ```
 *   ① 本地缓存 filesDir/remote-weather.json（记着它是**什么时候**拉的）
 *      ↓ 缓存是今天的、且距上次拉取不足 [FRESH_WINDOW_MILLIS]
 *   直接用它，一次网络请求都不发
 *      ↓ 否则（没有 / 过期 / 不是今天的）
 *   ② 远程天气接口
 *      ↓ 拉不到
 *   退回今天的缓存（可能只是不够新鲜，不是没有）
 *      ↓ 连今天的缓存也没有
 *   ③ 界面显示「读不到天气」+ 原因
 * ```
 *
 * ## 为什么要给缓存记时间戳
 *
 * 冷启动时界面要立刻有东西显示，但**不该每次都发请求**：
 * 天气是小时级变化的，20 分钟内重复拉十几次拿到的数据完全一样。
 *
 * 时间戳不在上游的响应里（上游给的是**气象数据**的更新时间，
 * 那是说「这份预报什么时候算出来的」，不是说「我多久没去拉」——
 * 拿来判断新鲜度会把所有请求都判成过期），所以只能由本机在写缓存时自己记。
 *
 * ## 为什么缓存还要按「是不是今天」筛
 *
 * 天气是**按天**成立的事实。缓存里那份如果 date 不是今天，
 * 那它显示成「今天的天气」就是在撒谎 —— 用户会照着昨天的最高温决定穿不穿外套。
 * 所以这里宁可显示「读不到」，也不拿旧数据冒充今天。
 */
class WeatherService(
    private val source: WeatherSource,
    private val cache: WeatherCache,
    /** 查询地点。坐标接口不认地名，所以这里是一个「名字 + 坐标」的成对值。 */
    private val location: WeatherLocation = WeatherLocation.DEFAULT,
    /** 单测注入：固定「今天」，避免跨天时断言对不上。 */
    private val today: () -> Long = { LocalDate.now().toEpochDay() },
    /** 单测注入：固定当前时刻，20 分钟窗口才测得准。 */
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * 界面上真正要显示的状态。三个分支**语义互不重叠**，不能合成一个可空字段。
     */
    sealed interface WeatherState {
        /** 正在读，还没有可显示的东西。 */
        data object Loading : WeatherState

        /**
         * 有可显示的天气。
         *
         * @property fromCache true = 这份来自本地缓存，本次没有联网。
         *   20 分钟窗口内命中时会是 true —— 那份数据仍然是真的，只是不必再拉一次。
         */
        data class Available(
            val weather: DailyWeather,
            val fromCache: Boolean
        ) : WeatherState

        /**
         * 真的读不到，且没有今天的缓存可退回。
         *
         * [reason] 是给人排查的技术细节，**界面上不显示它** —— 天气块只有
         * 92.dp 宽、9sp，放一句「TLS 握手失败（被对端中断）」进去只会折成两行
         * 灰字，既不像界面语言，还会把这一格撑得比正常态高一截。
         * 界面显示的是 [kind] 对应的短标签（见 `WeatherUnavailableLabel`）。
         *
         * [kind] 没有默认值：它决定界面劝用户去做什么，漏标就必须编译不过。
         */
        data class Unavailable(
            val reason: String,
            val kind: WeatherFailureKind
        ) : WeatherState
    }

    /** 缓存里的一条：天气本体 + **本机**拉取它的那一刻。 */
    data class CachedEntry(
        val weather: DailyWeather,
        val fetchedAtMillis: Long
    )

    /**
     * 缓存里**今天的**那份；没有、坏了、或者是昨天的，都返回 null。
     *
     * 只读文件，**不联网**。
     */
    fun cachedToday(): CachedEntry? {
        val text = cache.read() ?: return null
        val envelope = runCatching { adapter.fromJson(text) }.getOrNull() ?: return null
        val body = envelope?.body?.takeIf { it.isNotBlank() } ?: return null
        val fetchedAt = envelope?.fetchedAt ?: return null
        val parsed = runCatching { source.parseToday(body, location) }.getOrNull() ?: return null
        // 日期对不上 = 昨天的那份，不能当今天显示。
        val today = parsed.takeIf { it.epochDay == today() } ?: return null
        // 空气质量解不出来就当没有这一项 —— 天气本身仍然是今天的，照常显示。
        val airBody = envelope?.airBody
        val aqi = airBody
            ?.let { runCatching { source.parseAirQuality(it) }.getOrNull() }
            ?: parsed.usAqi
        return CachedEntry(today.copy(usAqi = aqi), fetchedAt)
    }

    /**
     * 距上次拉取是否还不到 [ttlMillis]。
     *
     * `age < 0`（时钟被往回调过）算**过期**：那种情况下宁可多拉一次，
     * 也不要让一份可能已经放了一整天的数据一直不过期。
     */
    fun isFresh(fetchedAtMillis: Long, ttlMillis: Long): Boolean {
        val age = nowMillis() - fetchedAtMillis
        return age in 0 until ttlMillis
    }

    /**
     * **进入软件时走这一条**：缓存够新就直接用，否则拉一次。
     *
     * **没有 ttl 参数了**（2026-10-16 用户定）：手动刷新是唯一一个传 0
     * 「无视窗口强制联网」的调用方，它被取消之后这个参数就没有调用方了。
     * 留着它等于留一个没人用的旋钮，下一个人会以为天气还能手动强刷。
     */
    suspend fun loadForEntry(): WeatherState {
        val cached = cachedToday()
        if (cached != null && isFresh(cached.fetchedAtMillis, FRESH_WINDOW_MILLIS)) {
            // 够新：一次网络请求都不发。
            return WeatherState.Available(cached.weather, fromCache = true)
        }
        return when (val fresh = refresh()) {
            is WeatherState.Available -> fresh
            is WeatherState.Unavailable ->
                // 过期不等于没有。缓存里有今天的就继续显示它，只是这次没刷上。
                cached?.let { WeatherState.Available(it.weather, fromCache = true) } ?: fresh
            WeatherState.Loading -> WeatherState.Loading
        }
    }

    /**
     * 联网取今天的天气；成功就写缓存（连同本机拉取时刻）。
     *
     * **拉取失败不动缓存** —— 拉不到时把旧值清掉，等于让用户连最后那份都失去。
     */
    suspend fun refresh(): WeatherState = withContext(Dispatchers.IO) {
        when (val fetched = source.fetchToday(location)) {
            // 类别原样透传：数据源最清楚「这是没连上」还是「答复不能用」。
            is WeatherFetch.Unavailable -> WeatherState.Unavailable(fetched.reason, fetched.kind)
            is WeatherFetch.Got -> {
                val parsed = runCatching { source.parseToday(fetched.text, location) }.getOrNull()
                when {
                    // 拿到了但解不出来：这是服务端改了格式，不能当成「今天没天气」。
                    // 答复确实到手了，所以是 REJECTED —— 界面不该让用户去查网络。
                    parsed == null -> WeatherState.Unavailable(
                        "天气接口返回的内容无法解析",
                        WeatherFailureKind.REJECTED
                    )
                    // 拿到的是别的日期（时区传错、或服务端改了 forecast_days 的语义）：
                    // 照样缓存，但不冒充今天。
                    parsed.epochDay != today() -> {
                        writeCache(fetched.text, airBody = null)
                        WeatherState.Unavailable(
                            "天气接口返回的不是今天的数据",
                            WeatherFailureKind.REJECTED
                        )
                    }
                    else -> {
                        // 空气是**另一个端点**，单独拉、单独成败。它挂了不该把
                        // 整块天气变成「读不到」，所以失败就是 null，不进 Unavailable。
                        // 天气照常显示，只是城市名旁边少一段。
                        val airBody = fetchAirQualityBody()
                        writeCache(fetched.text, airBody)
                        val aqi = airBody?.let(::parseAirQualityOrNull) ?: parsed.usAqi
                        WeatherState.Available(
                            parsed.copy(usAqi = aqi),
                            fromCache = false
                        )
                    }
                }
            }
        }
    }

    /** 拉空气。拉不到就返回 null，**永远不抛** —— 空气不是天气的前提。 */
    private suspend fun fetchAirQualityBody(): String? =
        when (val air = source.fetchAirQuality(location)) {
            is WeatherFetch.Got -> air.text
            is WeatherFetch.Unavailable -> null
        }

    private fun parseAirQualityOrNull(body: String): Int? =
        runCatching { source.parseAirQuality(body) }.getOrNull()

    private fun writeCache(body: String, airBody: String?) {
        val stamp = nowMillis()
        val ok = runCatching {
            adapter.toJson(WeatherCacheEnvelope(fetchedAt = stamp, body = body, airBody = airBody))
        }.getOrNull()
        // 写不进去不算失败：天气已经拿到了，缓存只是下次快一点。
        if (ok != null) cache.write(ok)
    }

    companion object {
        /**
         * 冷启动时直接用缓存的时间窗：**3 小时**（用户 2026-10-16 定，此前是 20 分钟）。
         *
         * 为什么是 3 小时：Open-Meteo 的全球模式基本每 6 小时出一轮，
         * 区域模式 1~3 小时一轮。窗口比这更短只是反复去拿同一份数据，
         * 而 20 分钟那种窗口挡的其实是「切出去看一眼别的 App 就回来」这种高频重入 ——
         * 反正那一两分钟里拿到的天气完全一样。
         *
         * 3 小时还顺带把请求量压到**每天至多 8 次**（还不含缓存命中的那几次）。
         *
         * **代价要说明白：手动刷新已取消。** 一次拉取失败之后，用户在下一次
         * 重新进入软件（[com.example.ui.viewmodel.MainViewModel.syncWeather]
         * 挂在 ON_RESUME 上）之前没有重试手段，只能看到退回的缓存。
         * 天气这件事上这个代价值得，但别假装它不存在。
         */
        const val FRESH_WINDOW_MILLIS = 3L * 60L * 60L * 1000L

        private val adapter by lazy {
            Moshi.Builder().build().adapter(WeatherCacheEnvelope::class.java)
        }
    }
}

/**
 * 缓存文件的外壳：**拉取时刻 + 天气原文 + 空气质量原文**。
 *
 * 天气原文整体当字符串存，一个字节都不改 —— 解析链路只有
 * [OpenMeteoWeatherSource.parseToday] 这一条，不为了套壳再写一份解析。
 * 空气同理走 [OpenMeteoWeatherSource.parseAirQuality]。
 *
 * [airBody] 可空且**默认可空**：写这份缓存时还没有空气这一项，
 * 老缓存解出来是 null，界面照样显示天气 —— 不需要数据迁移，也不该因为
 * 少一个可选字段就把用户已有的一份天气作废。
 *
 * 外壳不匹配时（老版本存的是裸响应）解出来是 null，按「没有缓存」处理并重新拉一次，
 * 自愈成新格式，不需要专门写数据迁移。
 *
 * 换源之前存的是另一种格式的裸响应，同样落在这条规则里：
 * 外壳认得，里面解不出来的按「没有缓存」处理。旧格式的缓存会自然作废一次。
 */
@JsonClass(generateAdapter = true)
internal data class WeatherCacheEnvelope(
    @Json(name = "fetchedAt") val fetchedAt: Long = 0L,
    @Json(name = "body") val body: String? = null,
    @Json(name = "airBody") val airBody: String? = null
)
