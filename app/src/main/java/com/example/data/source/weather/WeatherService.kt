package com.example.data.source.weather

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.data.calendar.AppZone

/**
 * 天气的**决策层**，也是唯一决定「界面上该显示什么」的地方。
 *
 * ```
 *   ① 本地缓存 filesDir/remote-weather.json（记着两件事：本机什么时候拉的、文件什么时候写的）
 *      ↓ 缓存是今天的、且**这份文件**的年龄不足 [FRESH_WINDOW_MILLIS]
 *        （或本机刚问过网络、距上次拉取不足 [REFETCH_INTERVAL_MILLIS]）
 *   直接用它，一次网络请求都不发
 *      ↓ 否则（没有 / 太旧 / 不是今天的）
 *   ② 远程天气接口（源那边会按文件自己的 lastUpdated 挑最新的镜像）
 *      ↓ 拉不到
 *   退回今天的缓存（可能只是不够新鲜，不是没有）
 *      ↓ 连今天的缓存也没有
 *   ③ 界面显示「读不到天气」+ 原因
 * ```
 *
 * ## 两个时间戳，两件事
 *
 * | 时间戳 | 记的是什么 | 用来判什么 |
 * |---|---|---|
 * | 文件里的 `lastUpdated` | 上游写下这份数据的那一刻 | **这份数据有多旧** |
 * | 缓存外壳里的 `fetchedAt` | 本机把它下载下来的那一刻 | 本机多久没去问过网络 |
 *
 * 以前只有一个时间戳（`fetchedAt`），于是「数据有多旧」被当成了
 * 「本机多久没去拉」——这两件事在托管文件这条路上**根本不是一回事**：
 * 一份 09:40 写下的文件，本机 21:00 才下载，下载时刻很新，数据却已经 11 小时了。
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
    /**
     * 单测注入：固定「今天」，避免跨天时断言对不上。
     *
     * 默认走 [AppZone]（北京时间）：天气是**按天**成立的事实，判「是不是今天」
     * 必须和课表、待办用同一个时区，否则跨时区时缓存会整天判错。
     */
    private val today: () -> Long = { AppZone.todayEpochDay() },
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

    /**
     * 缓存里的一条：天气本体 + **本机**拉取它的那一刻。
     *
     * 天气本体自己带着**文件**的写入时刻（[DailyWeather.updatedAtMillis]），
     * 所以这一条里有两个时刻，两个含义不同：数据多旧看前者，本机多久没问过看后者。
     */
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
        // **注意那个 `?: parsed.aqi`**：空气是可选的额外端点，托管源里它本来就
        // 嵌在天气正文里（airBody 恒为 null）。少了这个回退就会把已经解析出来的
        // 读数覆盖成 null，于是每次命中缓存都把空气抹掉 —— 症状是「联网那一下有，
        // 三小时内再进 App 就没了」。
        val airBody = envelope?.airBody
        val aqi = airBody
            ?.let { runCatching { source.parseAirQuality(it) }.getOrNull() }
            ?: parsed.aqi
        // 只换数值，不动 aqiQuality：等级是跟着 parseToday 一起从上游来的，
        // 单独覆盖数值会让这两个字段来自不同口径。
        return CachedEntry(today.copy(aqi = aqi), fetchedAt)
    }

    /**
     * 这份数据有多旧（毫秒）。
     *
     * **优先用文件自己的 `lastUpdated`**（上游写下它的那一刻）——
     * 那才是「这份天气有多旧」。本机下载时刻只说明「我多久没去问过网络」，
     * 拿它当新鲜度会把一份 8 小时前的文件说成刚下的。
     *
     * 文件没有 `lastUpdated`（老格式）时退回本机下载时刻 —— 那是唯一能拿到的依据。
     *
     * `age < 0`（时间戳在未来：时钟被往回调过、或两端时钟不一致）算**无限旧**：
     * 宁可多拉一次，也不要让一份来路不明的数据一直不过期。
     */
    fun dataAgeMillis(cached: CachedEntry): Long {
        val base = cached.weather.updatedAtMillis ?: cached.fetchedAtMillis
        val age = nowMillis() - base
        return if (age < 0) Long.MAX_VALUE else age
    }

    /**
     * 这份数据是不是还比 [ttlMillis] 新。
     *
     * [ageMillis] 是**已经算好的年龄**，不是时间戳 —— 算年龄的规矩见 [dataAgeMillis]。
     * 负数（一律按 [Long.MAX_VALUE] 传进来）与超时都算**过期**：
     * 宁可多拉一次，也不要让一份可能已经放了一整天的数据一直不过期。
     */
    fun isFresh(ageMillis: Long, ttlMillis: Long): Boolean {
        return ageMillis in 0 until ttlMillis
    }

    /**
     * **进入软件时走这一条**：缓存够新就直接用，否则拉一次。
     *
     * **没有 ttl 参数了**（2026-10-16 用户定）：手动刷新是唯一一个传 0
     * 「无视窗口强制联网」的调用方，它被取消之后这个参数就没有调用方了。
     * 留着它等于留一个没人用的旋钮，下一个人会以为天气还能手动强刷。
     *
     * ## 什么时候才值得再问一次网络
     *
     * 两个理由，任一成立就不问：
     *
     * 1. **这份数据还不够新**（[dataAgeMillis] ≥ [FRESH_WINDOW_MILLIS]）——
     *    上游可能已经写出了更新的文件，值得去拿；
     * 2. **本机刚问过**（距上次下载不足 [REFETCH_INTERVAL_MILLIS]）——
     *    再问一次多半还是同一份文件，白费一次请求。
     *
     * 第 2 条是「复查间隔」，不是新鲜度。少了它，上游一旦停更，
     * 每次进 App 都会去问一次网络（而拿回来的还是那份旧的）。
     */
    suspend fun loadForEntry(): WeatherState {
        val cached = cachedToday()
        if (cached != null && !needsRefetch(cached)) {
            // 不用问：一次网络请求都不发。
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
     * 缓存这份要不要**再去问一次网络**。
     *
     * 两条理由，任一成立就要问：
     * - 这份数据太旧（上游可能已经写出了更新的）；
     * - 本机上次问得够久了（上游可能在这之后更新过）。
     */
    private fun needsRefetch(cached: CachedEntry): Boolean {
        if (isFresh(dataAgeMillis(cached), FRESH_WINDOW_MILLIS)) return false
        val sinceFetch = nowMillis() - cached.fetchedAtMillis
        // 负数（本机时钟被往回调过）**不算「刚问过」**：那种情况下我们其实
        // 不知道上次问是什么时候，多问一次比拿着一份来路不明的缓存强。
        return sinceFetch < 0 || sinceFetch >= REFETCH_INTERVAL_MILLIS
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
                    // **不冒充今天，也不写缓存。**
                    //
                    // 旧做法是照样写缓存 —— 但那份正文下一次就会被 [cachedToday]
                    // 按「不是今天」判掉，等于**用一份用不了的数据把唯一的好缓存擦掉**：
                    // 用户本来存着一份今天的天气，一次失败的拉取之后就没了，
                    // 而下一次进来只能显示「读不到天气」。
                    // 拒绝的数据不进缓存，这条与「拉取失败不动缓存」是同一条原则。
                    parsed.epochDay != today() -> {
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
                        val aqi = airBody?.let(::parseAirQualityOrNull) ?: parsed.aqi
                        WeatherState.Available(
                            parsed.copy(aqi = aqi),
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
         * 一份天气数据算「够新」的门槛：**3 小时**（沿用用户 2026-10-16 定的 3 小时）。
         *
         * ## 它量的现在是**文件年龄**，不是「本机多久没去拉」
         *
         * 以前这个窗口量的是本机下载时刻，而那时候上游是直连 Open-Meteo
         * （全球模式每 6 小时出一轮），下载时刻约等于数据年龄，两边碰巧一样。
         * 换成托管文件之后**两者分家了**：上游的调度会滞后，
         * 一份文件在 GitHub 上放着不动，本机什么时候下载它都不影响它有多旧。
         *
         * 现在量的是文件自己的 `lastUpdated`（见 [dataAgeMillis]）。
         * 这也是为什么这个数字与上游的承诺对得上：
         * 上游的看门狗保证 `weather.json` 的年龄 ≤ 3 小时，
         * 客户端再用同一个 3 小时判，两端说的是同一件事。
         *
         * **代价要说明白：手动刷新已取消。** 一次拉取失败之后，用户在下一次
         * 重新进入软件（[com.example.ui.viewmodel.MainViewModel.syncWeather]
         * 挂在 ON_RESUME 上）之前没有重试手段，只能看到退回的缓存。
         * 天气这件事上这个代价值得，但别假装它不存在。
         */
        const val FRESH_WINDOW_MILLIS = 3L * 60L * 60L * 1000L

        /**
         * 本机**最短多久**才允许再问一次网络：**1 小时**。
         *
         * ## 它管的是请求量，不是新鲜度
         *
         * 上游一旦停更（看门狗也会红），数据年龄就会一直超过 3 小时 ——
         * 那时「数据够不够新」这个理由会一直成立，于是**每次进 App 都要发一次请求**，
         * 而拿回来的还是那份旧的。
         *
         * 这一条把那种情况按住：上次问过不到 1 小时，就不再问。
         *
         * ## 为什么是 1 小时
         *
         * 它是「上游写出一份新文件之后，App 多久能看见」的上限。
         * 上游的目标节奏是 ≤3 小时，客户端 1 小时复查一次，
         * 于是端到端最坏约 4 小时 —— 与「3 小时」同一个量级。
         * 再短（比如 10 分钟）只是反复去拿同一份文件（每次几百字节），
         * 再长（比如 3 小时）会让 App 比上游多落后一整个窗口。
         */
        const val REFETCH_INTERVAL_MILLIS = 1L * 60L * 60L * 1000L

        private val adapter by lazy {
            Moshi.Builder().build().adapter(WeatherCacheEnvelope::class.java)
        }
    }
}

/**
 * 缓存文件的外壳：**拉取时刻 + 天气原文 + 空气质量原文**。
 *
 * 天气原文整体当字符串存，一个字节都不改 —— 解析链路只有
 * [source.parseToday] 这一条，不为了套壳再写一份解析。
 * 空气同理走 [source.parseAirQuality]。
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
