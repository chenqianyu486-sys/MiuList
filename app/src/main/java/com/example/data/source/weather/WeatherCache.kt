package com.example.data.source.weather

import java.io.File

/**
 * 天气的本地缓存，存**拉下来的原文**，一个字节都不加工。
 *
 * ## 为什么不放 Room
 *
 * 这份数据是远程原文的副本，不是用户的数据。天气的时效性以**小时**计，
 * 而 Room 里全是用户内容；把一个几分钟就过期的缓存混进用户数据库，
 * 换来的是迁移链变长、备份文件里混进无用数据。
 * 存文件的另外两个好处：零 DB 迁移，`adb pull` 出来就能看。
 *
 * 写法与 [com.example.data.calendar.CalendarCache] 一致：先写临时文件再改名，
 * 避免进程正好在写到一半被杀时留下一段截断的 JSON。
 */
class WeatherCache(private val file: File?) {

    /** 上次成功拉到的原文。没配缓存位置、文件不存在、为空或读失败都返回 null。 */
    fun read(): String? {
        val f = file ?: return null
        if (!f.exists()) return null
        return runCatching { f.readText(Charsets.UTF_8).takeIf { it.isNotBlank() } }.getOrNull()
    }

    /** 写缓存。写不进去**不抛异常** —— 缓存失败只是这次没留存，不该让天气取不到。 */
    fun write(text: String) {
        val f = file ?: return
        if (text.isBlank()) return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            // 先删再改名：部分平台上 renameTo 不覆盖已存在的文件，
            // 直接改名会静默失败，缓存就永远停在旧值上。
            f.delete()
            tmp.renameTo(f)
        }
    }

    fun clear() {
        val f = file ?: return
        runCatching { f.delete() }
        runCatching { File(f.parentFile, "${f.name}.tmp").delete() }
    }

    companion object {
        const val FILE_NAME = "remote-weather.json"
    }
}
