package com.example.data.source.zju

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

/**
 * 学在浙大 (courses.zju.edu.cn) 的待办作业项。
 * 纯只读展示，按截止时间升序排序（离今天最近的最靠左）。
 */
data class CoursesTodoItem(
    val id: String,
    val title: String,
    val courseName: String,
    /** 截止时间毫秒时间戳，null 表示无截止时间 */
    val dueTimestamp: Long?,
    /** 格式化后的展示文本，如 "今天 23:59", "明天 12:00", "10月25日 23:59" */
    val dueFormatted: String,
    /** 倒计时状态标签，如 "今天截止", "2天后", "已逾期" */
    val daysRemainingText: String,
    /** 是否属于紧急待办（24小时内或已逾期） */
    val isUrgent: Boolean
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("title", title)
        obj.put("courseName", courseName)
        if (dueTimestamp != null) {
            obj.put("dueTimestamp", dueTimestamp)
        }
        obj.put("dueFormatted", dueFormatted)
        obj.put("daysRemainingText", daysRemainingText)
        obj.put("isUrgent", isUrgent)
        return obj
    }

    companion object {
        private val ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai")

        fun fromJson(obj: JSONObject): CoursesTodoItem {
            return CoursesTodoItem(
                id = obj.optString("id", ""),
                title = obj.optString("title", "未命名作业"),
                courseName = obj.optString("courseName", "未知课程"),
                dueTimestamp = if (obj.has("dueTimestamp")) obj.optLong("dueTimestamp") else null,
                dueFormatted = obj.optString("dueFormatted", "无截止"),
                daysRemainingText = obj.optString("daysRemainingText", ""),
                isUrgent = obj.optBoolean("isUrgent", false)
            )
        }

        fun fromRawApi(
            id: String,
            title: String,
            courseName: String,
            rawEndTime: String?
        ): CoursesTodoItem {
            val dueTimestamp = parseIsoTimestamp(rawEndTime)
            val (dueFormatted, daysRemainingText, isUrgent) = formatDueStatus(dueTimestamp)
            return CoursesTodoItem(
                id = id,
                title = title.ifBlank { "未命名作业" },
                courseName = courseName.ifBlank { "通用课程" },
                dueTimestamp = dueTimestamp,
                dueFormatted = dueFormatted,
                daysRemainingText = daysRemainingText,
                isUrgent = isUrgent
            )
        }

        /** 解析学在浙大返回的 ISO-8601 字符串 (如 "2026-10-25T23:59:59+08:00" 或 "2026-10-25T15:59:59Z") */
        private fun parseIsoTimestamp(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                Instant.parse(raw).toEpochMilli()
            }.getOrElse {
                // 兼容带空格或不带时区的格式
                runCatching {
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                    sdf.parse(raw)?.time
                }.getOrNull()
            }
        }

        private fun formatDueStatus(dueTimestamp: Long?): Triple<String, String, Boolean> {
            if (dueTimestamp == null) {
                return Triple("无截止", "无截止", false)
            }
            val nowMs = System.currentTimeMillis()
            val dueDate = Instant.ofEpochMilli(dueTimestamp).atZone(ZONE_SHANGHAI).toLocalDate()
            val nowDate = LocalDate.now(ZONE_SHANGHAI)
            val daysDiff = ChronoUnit.DAYS.between(nowDate, dueDate)

            val timeSdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val timeStr = timeSdf.format(Date(dueTimestamp))

            val monthDaySdf = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
            val monthDayStr = monthDaySdf.format(Date(dueTimestamp))

            return when {
                dueTimestamp < nowMs -> {
                    Triple(monthDayStr, "已逾期", true)
                }
                daysDiff == 0L -> {
                    Triple("今天 $timeStr", "今天截止", true)
                }
                daysDiff == 1L -> {
                    Triple("明天 $timeStr", "明天截止", true)
                }
                daysDiff in 2..7 -> {
                    Triple(monthDayStr, "${daysDiff}天后", false)
                }
                else -> {
                    Triple(monthDayStr, "${daysDiff}天后", false)
                }
            }
        }

        fun listToJsonString(items: List<CoursesTodoItem>): String {
            val array = JSONArray()
            items.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun listFromJsonString(jsonStr: String): List<CoursesTodoItem> {
            if (jsonStr.isBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(jsonStr)
                val list = ArrayList<CoursesTodoItem>(array.length())
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    list.add(fromJson(obj))
                }
                list
            }.getOrDefault(emptyList())
        }
    }
}
