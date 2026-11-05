package com.example.data.source.weather

/**
 * 一份当天的天气。
 *
 * **只留界面真正用得到的字段。** 天气接口一次给十几项（风速、湿度、气压、
 * 紫外线、生活建议…），这里只留「今天什么天、多少度」——剩下的留着也不会有人读，
 * 反而每次上游改格式都要跟着改 DTO。
 *
 * 唯一的例外是 [updatedAtMillis]：它界面上不显示，但决策层要靠它判断新鲜度
 * ——理由见那个字段自己的说明。
 *
 * 这个类型**不属于任何一个数据源**：换源只换「取与解」那一层，
 * 决策层与界面拿到的形状不变，天气模块才不会跟着源一起改。
 *
 * @property cityName 界面上要显示的城市名。它是**我们查的那个地点的名字**，
 *   见 [WeatherLocation.name]——坐标接口不回传地名，显示什么由我们声明。
 * @property epochDay 这一份预报对应的**当地日期**。
 *   缓存能不能用、新旧能不能混，全靠它 —— 昨天的高低温不叫今天的天气。
 * @property aqi **中国国标 HJ 633-2012** 的 AQI。服务端用 Open-Meteo 的
 *   六污染物按国标断点算出（PM / NO₂ / SO₂ / CO 用 24h 均值，O₃ 用 8h 均值），
 *   拉不到就是 null。
 *
 *   旧格式的 `weather.json`（2026-11 之前）里这个键装的是美国 `us_aqi`。
 *   数据源解析时会把那种文件搬到 [usAqi]，并把这里的国标值留空 ——
 *   宁可这一段不显示，也不把美国数当国标显示。
 *
 *   它**不是**「当天的 AQI」—— 天气是逐日聚合，空气是抓的那一刻的读数，
 *   两者的有效期本来就不同，所以它可空，而且没有任何「昨天那份」可退回：
 *   拉不到就是拉不到，界面不显示这一段，而不是继续显示几小时前的旧读数。
 *   用户会照着它决定今天戴不戴口罩，旧的比没有更糟。
 * @property aqiQuality 服务端给的**国标等级**（如「优」「轻度污染」），
 *   与 [aqi] 出自同一份数据；为空时才退回 [describeAqi]。
 * @property usAqi **美国 EPA** 口径的 AQI，Open-Meteo 原生 `us_aqi`。
 *
 *   它按 PM2.5 的 24h 均值算，不是「当前小时浓度」的指数；和 [aqi] 是两个
 *   不同的数，不是同一个数的两种写法。只在美国模式下显示。
 * @property usAqiQuality 美国口径的等级（如「良」「轻度污染」）。
 *   它和 [usAqi] 出自同一份数据；为空时才用 [describeAqi] 兜底。
 *   两套的等级名一样、数值不同；当前显示哪套由设置决定，不在这行文案里区分。
 */
data class DailyWeather(
    val cityName: String,
    val epochDay: Long,
    val textDay: String,
    val textNight: String,
    val high: Int?,
    val low: Int?,
    val aqi: Int? = null,
    val aqiQuality: String? = null,
    val usAqi: Int? = null,
    val usAqiQuality: String? = null,
    /**
     * 这一份数据是**上游文件写下来的那一刻**（epoch 毫秒）。
     *
     * ## 它和「本机多久没去拉」不是一回事
     *
     * 托管文件 `weather.json` 里带 `lastUpdated`，那是 CI 写文件时打的戳。
     * 它才是「这份天气有多旧」——一个 09:40 写下的文件，到 21:00 还是 09:40
     * 写下的那份，**本机下载时刻再新也改变不了这一点**。
     *
     * ## 为什么必须有它
     *
     * 托管文件的调度会滞后（GitHub 的定时任务在高峰期会跳过整点，
     * 实测 93 次调度平均 4.45 小时、最长 10.77 小时）。于是同一份 `date`（今天）
     * 下可能藏着差别巨大的两份数据：一个 20 分钟前的、一个 8 小时前的。
     * 只看 `date` 分不开它们 —— **只有这个时间戳能分开**。
     *
     * 界面上不显示它：用户看到的是「今天多云 18°」，不是「这份数据 4 小时前的」。
     * 它是决策层的判据，不是给人读的。
     */
    val updatedAtMillis: Long? = null
) {
    /**
     * 现在该显示白天还是夜间的天气文字。
     *
     * 早晚两段的事实确实不一样（白天「小雨」入夜转「多云」），而这一块就那么点地方，
     * 白天夜里都写同一个词，等于有一半时间是错的。
     *
     * [hourOfDay] 传进来是为了让单测能钉住边界（几点算夜里），不直接读系统时间。
     *
     * ## 当前的 [textNight] 是空的
     *
     * 托管文件里的天气本体来自心知的**逐日**预报，只有白天的那个词；
     * 空气质量那一份来自 Open-Meteo，而它的 `daily` 是**整天的聚合**
     * （当日最严重的天气状况），同样不分昼夜。
     *
     * 所以夜间值是空串而不是把白天的词抄一遍。
     * 空着比抄一遍诚实：抄一遍会让「白天夜间本来就是同一个词」这件事
     * 在数据里看不出来，而空串正好走这条已测过的退路。
     */
    fun textAt(hourOfDay: Int): String {
        val night = textNight.takeIf { it.isNotBlank() }
        return if (hourOfDay >= NIGHT_START_HOUR || hourOfDay < NIGHT_END_HOUR) {
            night ?: textDay
        } else {
            textDay
        }
    }

    /** 「19° ~ 25°」。任一端缺失就只显示有的那端，不补 0 也不写「--」。 */
    fun temperatureRange(): String? = when {
        high != null && low != null && high != low -> "$low° ~ $high°"
        high != null -> "$high°"
        low != null -> "$low°"
        else -> null
    }

    /**
     * 「空气良 64」或「空气轻度污染 121」。两侧的等级名一样，数值不同。
     * 所选口径没有读数就返回 null，界面直接不画这一段。
     *
     * **两套不互相回退**：选了国标但文件里只有美国值，就当作没有；
     * 选了美国但只有国标值，同理。混着显示比少一段更坏 ——
     * 用户会以为两个数可以直接比较。
     *
     * **两套共用同一种自然写法。** 不在这一行里写「美国」：
     * 「美国严重污染」不是正常中文，而那一格只有 92dp 宽，字号又是 9sp。
     * 当前标准由设置页那颗开关说明；这里只负责把等级和数值说清楚。
     */
    fun airQualityLabel(standard: AqiStandard = AqiStandard.CN): String? {
        val value = when (standard) {
            AqiStandard.CN -> aqi
            AqiStandard.US -> usAqi
        } ?: return null
        val quality = when (standard) {
            AqiStandard.CN -> aqiQuality
            AqiStandard.US -> usAqiQuality
        }
        val word = quality?.takeIf { it.isNotBlank() } ?: describeAqi(value)
        return "空气$word $value"
    }

    companion object {
        /** 18:00 起算夜间。 */
        const val NIGHT_START_HOUR = 18

        /** 06:00 前仍是夜里。 */
        const val NIGHT_END_HOUR = 6
    }
}

/** 拉取结果。「拿不到」与「拿到了」必须能一眼分开，不许合成 null。 */
sealed interface WeatherFetch {
    data class Got(val text: String) : WeatherFetch

    /**
     * @property kind 界面上显示哪一类。**没有默认值**：漏标就得编译不过，
     *   因为把一个网络异常默认成 [WeatherFailureKind.REJECTED] 会让界面
     *   劝用户去查网络，而那时他查什么网络都没用。
     * @property reason 技术细节。仍然完整保留（排查问题的第一手线索），
     *   但界面那一格只有 92.dp 宽、9sp，放不下它 —— 见
     *   `com.example.ui.components.WeatherUnavailableLabel`。
     */
    data class Unavailable(
        val reason: String,
        val kind: WeatherFailureKind
    ) : WeatherFetch
}

/**
 * 拉不到的两类，**按「用户该做什么」切，不按「技术上为什么」切**。
 *
 * 这是界面上能显示的全部信息量（92.dp 宽、9sp、一行），所以分类标准必须是
 * 处置方式而不是故障原因：「DNS 解析失败」和「TLS 被掐」在技术上不同，
 * 对用户却是同一件事 —— 检查网络。
 *
 * - [OFFLINE]：请求压根没拿到答复。断网、域名解析不了、TLS、超时。
 * - [REJECTED]：答复拿到了但不能用。HTTP 错误、`error:true`、空正文、
 *   字段改名了、日期不是今天。
 *
 * 与 `ZjuFailureKind` 的 `NETWORK` / `SERVER` 是同一套思路，
 * 只是那边还要区分账号与课表，没法只分两类。
 */
enum class WeatherFailureKind { OFFLINE, REJECTED }

/**
 * AQI 指数 → 中文等级。
 *
 * 分段抄自**中国国标 HJ 633-2012 附录 1**：
 * `0-50 优`、`51-100 良`、`101-150 轻度污染`、`151-200 中度污染`、
 * `201-300 重度污染`、`300+ 严重污染`。
 *
 * ## 这张表只管「指数 → 等级」，不管「浓度 → 指数」
 *
 * 美国 EPA 与国标在**这六个指数分界**上完全一致，所以同一个指数
 * （比如 64）在两套口径下都叫「良」。但这**不表示两套指数可以互换**：
 * 浓度断点不一样 —— PM2.5 35 µg/m³ 国标算出 50，美国算出 99；
 * 75 µg/m³ 国标 100，美国约 161。把美国指数套上这张表，
 * 等于用一把尺子的刻度去读另一把尺子的长度。
 *
 * 浓度→指数只由数据源/服务端做：国标在 `scripts/sync_weather.py`，
 * 美国值直接用 Open-Meteo 的 `us_aqi`。客户端不猜、不换算。
 *
 * ## 它是兜底，不是首选
 *
 * **等级优先用 [DailyWeather.aqiQuality]**（服务端算出的国标等级，
 * 与数值同一份数据）。为空时才用这张表把一个裸整数映射成等级。
 *
 * 超上限一律算「严重污染」：真出现 >500 说明这一片已经不能呼吸了，
 * 给它一个单独的名字没有意义，它该触发的是别的东西（不是本项目的活）。
 */
internal fun describeAqi(aqi: Int): String = when {
    aqi <= 50 -> "优"
    aqi <= 100 -> "良"
    aqi <= 150 -> "轻度污染"
    aqi <= 200 -> "中度污染"
    aqi <= 300 -> "重度污染"
    else -> "严重污染"
}
