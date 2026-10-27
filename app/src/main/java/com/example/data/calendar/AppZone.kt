package com.example.data.calendar

import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneId
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 全 App **唯一**的时区真相：固定 Asia/Shanghai，**不跟随设备时区**。
 *
 * ## 为什么不能用系统时区
 *
 * MiuList 里的时间事实全都是**浙大校园的墙上时间**：
 *
 *  * 课表上的「08:00–09:35」说的是「在浙大校区的钟面上第几小时」；
 *  * 待办截止「10/20 23:59」说的是「北京时间那天的 23:59」；
 *  * 校历上的「第 3 周 10/12–10/18」说的是北京那几天的日期。
 *
 * 这些都不随用户设备所在的时区改变。设备一旦不在 UTC+8，`Calendar.getInstance()` /
 * `LocalDate.now()` 就会把整套课表整体平移，得到的是一种**看起来正常、实际全错**的结果：
 *
 *  * 界面明明写着 08:00 上课，「还有 3 小时下课」却按设备本地时间算；
 *  * 北京 00:30 时 GMT 还是前一天，「今天」会被认成昨天 —— 今天的课不显示、
 *    一键生成把昨天的课生成为预习；
 *  * 天气缓存按 epochDay 判「是不是今天」，跨时区时整天判错，
 *    于是拿昨天的最高温当今天的，用户照着它决定穿不穿外套。
 *
 * ## 边界：`System.currentTimeMillis()` 不用改
 *
 * 绝对时刻（epochMilli）与时区无关，它只回答「现在几点（绝对）」，不回答「几号」。
 * 所有**比较**（逾期没逾期、还剩几分钟）继续直接用它；凡是**把这个时刻翻译成
 * 哪一天 / 几点几分 / 星期几**的地方，都必须经过这里。
 *
 * 判据一句话：**读到 `Calendar.getInstance()`（无参）、`LocalDate.now()`（无参）、
 * `LocalTime.now()`（无参）、无参 `SimpleDateFormat` 就是漏网**，它们全都在用系统时区。
 *
 * 放在 `data` 层而不是 `ui.components`：课表只是其中一处消费者，
 * 校历判定、待办生成、天气缓存都在 `data` 里，UI 反过来依赖 data 才是既有方向。
 */
object AppZone {

    /** Java 时间（`LocalDate` / `Instant` / `DateTimeFormatter`）统一用它。 */
    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    /** `Calendar` / `SimpleDateFormat` 统一用它（这两套 API 只吃 TimeZone）。 */
    val TIME_ZONE: TimeZone = TimeZone.getTimeZone(ZONE)

    /**
     * 一个带正确时区的 [Calendar]。
     *
     * [millis] 省略时取「现在」。所有 `Calendar.getInstance()` 都该换成它。
     */
    fun calendar(millis: Long = System.currentTimeMillis()): Calendar =
        Calendar.getInstance(TIME_ZONE).apply { timeInMillis = millis }

    /** 今天 00:00（北京时间）的 Calendar。课表列头、总览「今天」的当日锚点。 */
    fun todayStart(): Calendar = calendar().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    /** 北京的今天（epochDay）。校历、待办分组、天气缓存共用这一个判据。 */
    fun todayEpochDay(): Long = LocalDate.now(ZONE).toEpochDay()

    /**
     * 绝对毫秒 → 北京时区的那一天（epochDay）。
     *
     * 考试落在哪一天、周课表的某一列是哪一天，都走这一个换算 ——
     * 跟着设备时区走会让它们落错列。
     */
    fun epochDayOf(millis: Long): Long =
        Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDate().toEpochDay()

    /** 北京的此刻几点（0..23）。只给天气卡片选昼夜图标用。 */
    fun hourOfDayNow(): Int = LocalTime.now(ZONE).hour

    /**
     * 钉住时区的 [SimpleDateFormat]。
     *
     * 无参 `SimpleDateFormat(...)` 拿的是设备时区，而它的产物是要**给人看的墙上时间**
     * （「最近更新 14:30 更新」「截止 2026-10-20 23:59」），所以必须钉死。
     * 一次性建对象、不复用 —— 这几处都是低频调用（每次重组一两次），
     * `SimpleDateFormat` 本来就不是线程安全的，缓存它反而要引入同步。
     */
    fun format(pattern: String, millis: Long, locale: Locale = Locale.getDefault()): String =
        SimpleDateFormat(pattern, locale).apply { timeZone = TIME_ZONE }.format(millis)
}
