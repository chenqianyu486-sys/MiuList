package com.example.data.update

/**
 * 一个版本号：几段数字，外加可选的预发布标记。
 *
 * @param parts 数字段，从大到小（`1.2.0` → `[1, 2, 0]`）。
 * @param prerelease 预发布标记（`1.2.0-beta` → `"beta"`）；正式版为 null。
 */
internal data class AppVersion(
    val parts: List<Int>,
    val prerelease: String?
) {
    /** 给人看的写法：数字段用点连起来，预发布标记跟在后面。 */
    fun display(): String =
        parts.joinToString(".") + (prerelease?.let { "-$it" } ?: "")
}

/**
 * 解析一个版本号。**读不出来就返回 null**（`nightly`、`1.2.x`、空串都算读不出来）。
 *
 * ## 认得的形状
 *
 * - `v1.2.0` / `1.2.0` / `V1.2.0`：开头的 `v` 是 tag 的习惯写法，去掉。
 * - `1.2.0-beta`：`-` 后面是预发布标记。
 * - `1.2`：段数不齐没关系，比大小的时候缺的段当 0（见 [compareAppVersions]）。
 *
 * ## 为什么不直接比字符串
 *
 * 版本号看着像数字，用字符串比大小**会错**：`"1.10.0" > "1.9.0"` 在字符串里是**假**
 * （第二个字符 `'1' < '9'`）。等到第十版，用户明明还是旧版，App 却说「已是最新」。
 */
internal fun parseAppVersion(raw: String): AppVersion? {
    val trimmed = raw.trim()
    val withoutPrefix = when {
        trimmed.startsWith("v") || trimmed.startsWith("V") -> trimmed.substring(1).trim()
        else -> trimmed
    }
    if (withoutPrefix.isEmpty()) return null

    val dashIndex = withoutPrefix.indexOf('-')
    val core = if (dashIndex >= 0) withoutPrefix.substring(0, dashIndex) else withoutPrefix
    val prerelease = if (dashIndex >= 0) {
        withoutPrefix.substring(dashIndex + 1).trim().ifEmpty { null }
    } else {
        null
    }

    val parts = core.split('.').map { it.trim().toIntOrNull() ?: return null }
    if (parts.isEmpty()) return null
    return AppVersion(parts, prerelease)
}

/**
 * 比两个版本号。
 *
 * @return >0 表示 [current] 更新，<0 表示 [latest] 更新，0 表示一样；
 *   **有一边读不出来时返回 null** —— 没法证明它新，就不该拿它去打扰用户。
 */
internal fun compareAppVersions(current: String, latest: String): Int? {
    val a = parseAppVersion(current) ?: return null
    val b = parseAppVersion(latest) ?: return null

    // 段数不齐时缺的段当 0：1.2 与 1.2.0 是同一个版本。
    val size = maxOf(a.parts.size, b.parts.size)
    for (index in 0 until size) {
        val left = a.parts.getOrElse(index) { 0 }
        val right = b.parts.getOrElse(index) { 0 }
        if (left != right) return left.compareTo(right)
    }

    // 数字段一样：正式版比预发布版**新**（1.2.0 > 1.2.0-beta）。
    return when {
        a.prerelease == null && b.prerelease == null -> 0
        a.prerelease == null -> 1
        b.prerelease == null -> -1
        else -> a.prerelease.compareTo(b.prerelease)
    }
}
