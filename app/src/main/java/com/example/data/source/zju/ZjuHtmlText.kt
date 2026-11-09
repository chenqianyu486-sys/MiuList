package com.example.data.source.zju

/**
 * 作业说明是 **HTML**（学在浙大的富文本编辑器存下来的），直接塞进待办备注
 * 会显示成一堆尖括号和标签名。这里把它转成纯文本。
 *
 * 刻意**不引第三方 HTML 解析库**：这个 App 只要「把说明读得通顺」，
 * 不需要还原排版；为了一件事加一个依赖不划算。规则全部是纯函数，可以离线单测。
 */
internal fun htmlToPlainText(raw: String): String {
    if (raw.isBlank()) return ""

    var text = raw
    // script / style 整块丢掉：里面写的是给人看的说明，不是给人看的内容。
    text = SCRIPT_STYLE_REGEX.replace(text, " ")
    // 块级标签的**结尾**换成换行：段落、列表项、表格行、标题。
    text = BLOCK_CLOSE_REGEX.replace(text, "\n")
    // <br> 是显式换行，单独处理。
    text = BR_REGEX.replace(text, "\n")
    // 剩下的标签（<b> <p> <span> …）直接删掉，只留文字。
    text = TAG_REGEX.replace(text, "")
    // 实体解码。
    text = decodeHtmlEntities(text)
    // 不换行空格当普通空格：它在 Android 上会渲染成一个空位，看着像缺了字。
    text = text.replace('\u00A0', ' ')
    // 行内连续空白压成一个：HTML 源码为了排版会塞一堆空格与制表符。
    text = INLINE_SPACE_REGEX.replace(text, " ")
    // 每行去首尾空白，再把连续空行压成一行（源码里常见一串空 <p>）。
    text = text.lines().joinToString("\n") { it.trim() }
    text = BLANK_LINES_REGEX.replace(text, "\n\n")
    return text.trim()
}

/**
 * 实体解码。
 *
 * 单趟替换，**不递归**：`&amp;lt;` 只解一次，得到 `&lt;` 就停。
 * 递归解会把上游写好的转义内容还原成真的尖括号，那不是说明里的话。
 */
private fun decodeHtmlEntities(text: String): String {
    var out = text
    out = DECIMAL_ENTITY_REGEX.replace(out) { match ->
        val code = match.groupValues[1].toIntOrNull()
        if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
    }
    out = HEX_ENTITY_REGEX.replace(out) { match ->
        val code = match.groupValues[1].toIntOrNull(16)
        if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
    }
    // 顺序有意义：`lt`/`gt` 排在 `amp` 前面，
    // 这样 "&amp;lt;" 会停在 "&lt;"（上游写好的转义），不会被还原成 "<"。
    for ((name, replacement) in NAMED_ENTITIES) {
        out = out.replace("&$name;", replacement)
    }
    return out
}

/** 常见命名实体。够用即可，不追求 HTML 全集。 */
private val NAMED_ENTITIES: Map<String, String> = linkedMapOf(
    "nbsp" to " ",
    "ensp" to " ",
    "emsp" to " ",
    "thinsp" to " ",
    "shy" to "",
    "zwnj" to "",
    "zwj" to "",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    // amp 排在 lt / gt **之后**：这样 "&amp;lt;" 只解一次，停在 "&lt;"，
    // 不会把上游写好的转义还原成真的尖括号。
    "amp" to "&",
    "ldquo" to "“",
    "rdquo" to "”",
    "lsquo" to "‘",
    "rsquo" to "’",
    "hellip" to "…",
    "mdash" to "—",
    "ndash" to "–",
    "middot" to "·",
    "bull" to "•",
    "times" to "×",
    "divide" to "÷",
    "ne" to "≠",
    "le" to "≤",
    "ge" to "≥",
    "plusmn" to "±",
    "deg" to "°",
    "frac12" to "½",
    "copy" to "©",
    "reg" to "®",
    "trade" to "™",
    "rarr" to "→",
    "larr" to "←",
    "uarr" to "↑",
    "darr" to "↓",
    "harr" to "↔"
)

private val SCRIPT_STYLE_REGEX = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>")
private val BLOCK_CLOSE_REGEX = Regex("(?i)</(p|div|li|ul|ol|tr|table|h1|h2|h3|h4|h5|h6|blockquote|section)>")
private val BR_REGEX = Regex("(?i)<br\\s*/?>")
private val TAG_REGEX = Regex("(?s)<[^>]*>")
private val INLINE_SPACE_REGEX = Regex("[ \\t]+")
private val BLANK_LINES_REGEX = Regex("\\n{3,}")
private val DECIMAL_ENTITY_REGEX = Regex("&#(\\d{1,7});")
private val HEX_ENTITY_REGEX = Regex("&#[xX]([0-9a-fA-F]{1,6});")
