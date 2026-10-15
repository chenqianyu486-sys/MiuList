package com.example.data.calendar

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter

/**
 * 调休类型。
 *
 * ## 为什么是枚举而不是裸 Int
 *
 * 上游 zju-ical 的 `configs/config.json` 里 `TweakType` **写的是字符串**：
 *
 * ```json
 * { "TweakType": "Move",     "From": 20261002, "To": 20261017 }
 * { "TweakType": "Exchange", "From": 20261006, "To": 20260920 }
 * ```
 *
 * 之前 DTO 把它声明成 `Int?`，而 Moshi 遇到 `"Clear"` 这种字符串会直接抛
 * `JsonDataException`（不是容错返回 null）。`ZjuCalendarSource.parse` 把整个
 * 反序列化包在 `runCatching` 里，于是**整份校历配置解析失败**——学期起止日期、
 * 调休一起没了，界面还只是「校历拉取失败」，看不出是格式问题。
 *
 * 所以这里同时认两种写法：[label]（`"Move"`）与 [code]（`1`），
 * 见 [TweakKindJsonAdapter]。导出统一写字符串形态，与上游一致。
 *
 * ## 三种语义
 *
 * | 类型 | From 那天的课 | To 那天的课 |
 * |------|---------------|-------------|
 * | [CLEAR] | 没有课 | 不变 |
 * | [MOVE] | **没有课**（课搬走了） | 上 From 那天的课 |
 * | [EXCHANGE] | 上 To 那天的课 | 上 From 那天的课 |
 *
 * [MOVE] 会把 From 停掉，这是「哪一天调到哪一天」的关键：描述写的是
 * 「10月2日（周五）停课，安排在10月17日（周六）补课」。只搬不空，
 * 等于同一份周五的课在两天都出现。
 *
 * 注意 [MOVE] 只停 **From 这一天**，不是 From..To 整段。真实配置里
 * `From=10-02 → To=10-17` 跨了两周，中间还夹着两次 `Exchange`
 * （10-06↔09-20、10-07↔10-10）。按区间清会把那两天一起停掉，
 * 对调就整个失效了。
 */
enum class TweakKind(val code: Int, val label: String) {
    CLEAR(0, "Clear"),
    MOVE(1, "Move"),
    EXCHANGE(2, "Exchange");

    /** 这条调休是否要让 From 那天没课。 */
    val vacatesFromDay: Boolean get() = this == CLEAR || this == MOVE

    companion object {
        fun ofCode(code: Int?): TweakKind? =
            if (code == null) null else entries.firstOrNull { it.code == code }

        /** 认已经反序列化成对象的值：字符串 / 数字都收。 */
        fun ofRaw(raw: Any?): TweakKind? = when (raw) {
            is String -> ofToken(raw)
            is Number -> ofCode(raw.toInt())
            else -> null
        }

        /** 认字符串写法。英文用上游的 [label]（大小写不敏感），
         * 顺带认中文——手写导入文档时更可能写「停课」而不是 `Clear`。
         */
        fun ofToken(token: String?): TweakKind? {
            val t = token?.trim().orEmpty()
            if (t.isEmpty()) return null
            entries.firstOrNull { it.label.equals(t, ignoreCase = true) }?.let { return it }
            // 早期文档与手写文档用过数字字符串，一并认掉
            t.toIntOrNull()?.let { return ofCode(it) }
            return when (t) {
                "停课", "放假" -> CLEAR
                "补课", "调休" -> MOVE
                "对调", "调换", "互换" -> EXCHANGE
                else -> null
            }
        }
    }
}

/**
 * [TweakKind] 的 Moshi 适配器：**读**时字符串与数字都收，**写**时统一出字符串。
 *
 * 读宽松是为了不挑食：上游历史版本、以及 v1/v2 老文档里都出现过 `0/1/2`。
 * 写成字符串是因为那就是上游 `configs/config.json` 的形态，
 * 导出的文件拿回 zju-ical 或别的工具也能直接用。
 */
class TweakKindJsonAdapter : JsonAdapter<TweakKind>() {

    override fun fromJson(reader: JsonReader): TweakKind? = TweakKind.ofRaw(reader.readJsonValue())

    override fun toJson(writer: JsonWriter, value: TweakKind?) {
        if (value == null) writer.nullValue() else writer.value(value.label)
    }
}
