package com.example.data.source.weather

import java.time.LocalDate

/**
 * 天气模块里**两个以上数据源共用**的助手。
 *
 * 之前它们住在 `OpenMeteoWeatherSource.kt` 里（那个类后来被删了），
 * 于是「删掉一个数据源」差点连带删掉静态源还在用的两样东西。
 * 共用的东西就该待在共用的地方。
 */

/** 把 `2026-10-16` 这样的日期转成 epochDay。解析不了返回 null，不抛。 */
internal fun parseEpochDay(text: String?): Long? {
    val t = text?.trim().orEmpty()
    if (t.length < 10) return null
    return runCatching { LocalDate.parse(t.substring(0, 10)).toEpochDay() }.getOrNull()
}

/**
 * 把异常翻成人 / 运维看得懂的一句话，而不是 `java.net` 的类名。
 *
 * **地址要从消息里抹掉。** 这里没有密钥可泄（这正是静态托管的收益之一），
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
