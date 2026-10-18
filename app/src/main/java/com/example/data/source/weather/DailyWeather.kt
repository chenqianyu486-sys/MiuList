package com.example.data.source.weather

/**
 * 一份当天的天气。
 *
 * **只留界面真正用得到的字段。** 天气接口一次给十几项（风速、湿度、气压、
 * 紫外线、生活建议…），这里只留「今天什么天、多少度」——剩下的留着也不会有人读，
 * 反而每次上游改格式都要跟着改 DTO。
 *
 * 这个类型**不属于任何一个数据源**：换源只换「取与解」那一层，
 * 决策层与界面拿到的形状不变，天气模块才不会跟着源一起改。
 *
 * @property cityName 界面上要显示的城市名。它是**我们查的那个地点的名字**，
 *   见 [WeatherLocation.name]——坐标接口不回传地名，显示什么由我们声明。
 * @property epochDay 这一份预报对应的**当地日期**。
 *   缓存能不能用、新旧能不能混，全靠它 —— 昨天的高低温不叫今天的天气。
 * @property aqi 抓取这一份时的**当前**空气质量指数。拉不到就是 null。
 *
 *   **原样存，不做任何换算。** AQI 的换算涉及主污染物与平均时间的口径差异，
 *   不是一条线性公式，换算表只在数据源手里 —— 在客户端猜一个就是凭空造一个数。
 *   等级判定不受影响：见 [describeAqi]，国标与 EPA 的分段一致，
 *   所以那张表对心知、Open-Meteo 两种来源都成立。
 *
 *   它**不是**「当天的 AQI」—— 天气是逐日聚合，空气是抓的那一刻的读数，
 *   两者的有效期本来就不同，所以它可空，而且没有任何「昨天那份」可退回：
 *   拉不到就是拉不到，界面不显示这一段，而不是继续显示几小时前的旧读数。
 *   用户会照着它决定今天戴不戴口罩，旧的比没有更糟。
 * @property aqiQuality **数据源自己给的等级文字**（如「优」「轻度污染」）。
 *
 *   有它就说明这套等级是跟着数值一起从上游来的，配对关系由上游保证；
 *   没有它才退回 [describeAqi] 按 EPA 分段推 —— 那是一张猜出来的表。
 */
data class DailyWeather(
    val cityName: String,
    val epochDay: Long,
    val textDay: String,
    val textNight: String,
    val high: Int?,
    val low: Int?,
    val aqi: Int? = null,
    val aqiQuality: String? = null
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
     * Open-Meteo 的 `daily` 是**整天的聚合**（当日最严重的天气状况），不分昼夜，
     * 所以 [OpenMeteoWeatherSource] 给的是空串而不是把白天的词抄一遍。
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
     * 「空气良 64」。拉不到（[aqi] 为 null）就返回 null，界面直接不画这一段。
     *
     * 等级**优先用 [aqiQuality]**（上游给的，和数值同一份、同一套口径）；
     * 没有才退回 [describeAqi] 那张 EPA 分段表 —— 那是猜的，
     * 所以只在真的没有上游等级时才用。
     *
     * 只写等级 + 数值，不写「AQI」三个字母：那一块就 92dp 宽，
     * 而「良 64」已经足够让人判断今天要不要戴口罩 —— 指数这东西对多数人是陌生的，
     * 等级才是他们真正会用的。
     */
    fun airQualityLabel(): String? {
        val value = aqi ?: return null
        val word = aqiQuality?.takeIf { it.isNotBlank() } ?: describeAqi(value)
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
 * 空气质量指数 → 中文等级。
 *
 * 分段抄自**中国国标 HJ 633-2012 附录 1**：
 * `0-50 优`、`51-100 良`、`101-150 轻度污染`、`151-200 中度污染`、
 * `201-300 重度污染`、`300+ 严重污染`。
 *
 * ## 为什么这张表同时适用于两种口径
 *
 * 美国 EPA 用的分段与国标**在这六个分界点上完全一致**，所以不必问
 * 「当前数据是哪套口径的」—— 换源（心知 → Open-Meteo）不影响结果。
 * 这也是这张表能当兜底的前提。
 *
 * ## 它是兜底，不是首选
 *
 * 数值由数据源给，而**等级优先用 [DailyWeather.aqiQuality]**（上游自己那份）。
 * 上游的等级和数值出自同一份数据、配对关系由它保证；这张表是把一个裸整数
 * 映射成等级。当前上游（Open-Meteo）不给中文等级，走的正是这张兜底表。
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
