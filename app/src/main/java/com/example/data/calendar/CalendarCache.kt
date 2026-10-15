package com.example.data.calendar

import java.io.File

/**
 * 远程校历配置的本地缓存，存的是**拉下来的原文**，一个字节都不加工。
 *
 * ## 为什么存文件而不是塞进 Room
 *
 * 这份数据是**远程原文的副本**，不是用户的数据。而 `no_class_days` / `makeup_days`
 * 恰好就在 Room 里，并且被 REPLACE 导入的「整表替换」语义覆盖着 —— 那条语义下
 * 「空列表 = 全删」（见 `ScheduleImportService.commit`）。缓存绝不能落进那个范围：
 * 一份校验不过的远程配置如果能被清空路径顺手抹掉，恢复点也就没了。
 *
 * 存文件的另外两个好处：零 DB 迁移（不用动 `AppDatabase` 与迁移链），
 * 以及 `adb pull` 出来就能直接看。
 *
 * ## `file` 传 null = 明确不缓存
 *
 * 不是「忘了配」而是「这一份配置就不需要留存」，例如单测与只想读一次的地方。
 * 这时候退化成「远程 → 内置」两层，读写都直接返回。
 * 生产上真正的缓存在 [com.example.data.di.AppContainer] 里注入，别用默认值。
 *
 * ## 写的方式
 *
 * 先写同目录下的临时文件再 [File.renameTo]。直接写目标文件的话，进程正好在
 * 写到一半被杀，缓存里会留下一段截断的 JSON —— 而下次启动时它是「存在但解析
 * 失败」，比「不存在」更难排查。临时文件改名是原子的（同一文件系统内）。
 */
class CalendarCache(private val file: File?) {

    /**
     * 上次成功拉到的原文。没有缓存位置、文件不存在、为空或读失败都返回 null。
     *
     * **不判断内容是否合法** —— 那属于解析层的职责，这里只负责字节搬运。
     * 读到一个坏文件时，调用方会拿它去解析、失败、然后顺手 [clear] 掉，
     * 于是自愈成「退回内置」。
     */
    fun read(): String? {
        val f = file ?: return null
        if (!f.exists()) return null
        return runCatching {
            f.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * 写缓存。写不进去（磁盘满 / 只读 / 没配缓存位置）**不抛异常** ——
     * 缓存写失败只是"这次更新没有留存到本地"，不该让整个「检查更新」失败。
     */
    fun write(text: String) {
        val f = file ?: return
        if (text.isBlank()) return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            // 删掉旧的目标再改名：部分平台上 renameTo 不会覆盖已存在的文件，
            // 直接改名会静默失败，缓存就永远停在旧版本。
            f.delete()
            tmp.renameTo(f)
        }
    }

    /** 清掉缓存。下次只能退回内置。 */
    fun clear() {
        val f = file ?: return
        runCatching { f.delete() }
        runCatching { File(f.parentFile, "${f.name}.tmp").delete() }
    }

    companion object {
        const val FILE_NAME = "remote-calendar.json"
    }
}
