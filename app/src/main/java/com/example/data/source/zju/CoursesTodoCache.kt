package com.example.data.source.zju

import android.content.Context
import android.content.SharedPreferences

/**
 * 学在浙大待办事项的轻量本地缓存。
 * 遵循离线优先原则，App 启动时秒级渲染上一次拉取的结果，断网依然可用。
 */
open class CoursesTodoCache(
    context: Context,
    prefName: String = PREF_NAME
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefName, Context.MODE_PRIVATE)

    @Synchronized
    open fun save(items: List<CoursesTodoItem>, timestamp: Long = System.currentTimeMillis()) {
        val json = CoursesTodoItem.listToJsonString(items)
        prefs.edit()
            .putString(KEY_ITEMS, json)
            .putLong(KEY_TIMESTAMP, timestamp)
            .apply()
    }

    @Synchronized
    open fun load(): Pair<List<CoursesTodoItem>, Long> {
        val json = prefs.getString(KEY_ITEMS, null) ?: return Pair(emptyList(), 0L)
        val timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)
        val items = CoursesTodoItem.listFromJsonString(json)
        return Pair(items, timestamp)
    }

    @Synchronized
    open fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREF_NAME = "miulist_courses_todo_cache"
        private const val KEY_ITEMS = "cached_todo_items"
        private const val KEY_TIMESTAMP = "cached_todo_timestamp"
    }
}
