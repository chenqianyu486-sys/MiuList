package com.example.data.update

import com.example.BuildConfig
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 发布页。**发现新版本时把用户送到这里** —— 它是 App 唯一的对外门面，
 * 上面有 Android 版与电脑版的下载入口。
 *
 * 它同时也是**版本号的来源**（见 [AppUpdateChecker]）：判断新旧与下载新版本
 * 说的是同一处，不会出现「提示有新版、点进去却是旧版」。
 */
const val MIULIST_RELEASE_PAGE_URL = "https://miulist-1yor6f7gw00.qoder.zone/"

/**
 * 发布页上的版本清单。**一个静态 JSON 文件**，随站点一起发布。
 *
 * ```json
 * { "version": "1.1.0" }
 * ```
 */
const val MIULIST_VERSION_MANIFEST_URL = "https://miulist-1yor6f7gw00.qoder.zone/version.json"

/**
 * 「有更新了」这件事本身。
 *
 * 放在 state 里而不是让界面各记一个局部 flag：检查入口在设置页，
 * 而弹出来的新版本提示可能出现在任何一页上。
 */
data class AppUpdateAvailable(
    /** 最新版的版本号，如 `1.2.0`。 */
    val latestVersion: String,
    /** 去哪儿下载。 */
    val releasePageUrl: String = MIULIST_RELEASE_PAGE_URL
)

/**
 * 检查 App 有没有新版本。
 *
 * ## 版本号从哪儿来
 *
 * **从发布页自己身上来**，两个来源都在同一个站点上：
 *
 * 1. `version.json`：发布页旁边的版本清单，一行 JSON，机器读。
 * 2. 发布页首页：清单还没上线时，从页面上认出版本号。
 *
 * 两个都在**国内直连**的站点上 —— App 的用户绝大多数在国内，
 * 拿一个时通时不通的源去判断新旧，等于让检查功能在最需要的时候失灵。
 *
 * ## 为什么不用 GitHub / Gitee 的 release 接口
 *
 * 它们的接口确实更「机器可读」，但**版本号与下载页会分家**：
 * 接口说 v1.2.0、页面却还摆着 v1.1.0 的下载链接时，用户会点进一个
 * 「有更新但下不到」的页面。而发布页既说版本、也给下载，两边天然一致。
 */
class AppUpdateChecker(
    private val client: OkHttpClient = defaultClient(),
    private val manifestUrl: String = MIULIST_VERSION_MANIFEST_URL,
    private val releasePageUrl: String = MIULIST_RELEASE_PAGE_URL,
    private val currentVersion: String = BuildConfig.VERSION_NAME
) {

    /** 检查的结果。三个分支**语义互不重叠**，界面上必须分开显示。 */
    sealed interface Result {
        /** 已经是最新版（或者本地比线上还新，比如装了开发版）。 */
        data class UpToDate(val latestVersion: String) : Result

        /** 有新版本了。 */
        data class UpdateAvailable(val latestVersion: String) : Result

        /** 没查成。**绝不显示成「已是最新」** —— 网络受限时那正是最该说实话的时候。 */
        data class Failed(val reason: String) : Result
    }

    /**
     * 查一次。
     *
     * 先问版本清单，读不到就退到发布页首页；两个都拿不到才算失败，
     * 失败原因**原样往上带**（域名解析不了、连接超时、404 —— 处置方式完全不同，
     * 压成一句「连不上」对用户和托管方都没用）。
     */
    suspend fun check(): Result = withContext(Dispatchers.IO) {
        when (val manifest = fetchText(manifestUrl)) {
            is TextResult.Got -> {
                val version = parseManifestVersion(manifest.text)
                if (version != null) {
                    return@withContext decide(currentVersion, version)
                }
                // 清单在，但里面没有版本号 → 当它没有，退到页面里认。
            }
            is TextResult.Unavailable -> Unit
        }

        when (val page = fetchText(releasePageUrl)) {
            is TextResult.Got -> {
                val version = extractVersionFromPage(page.text)
                if (version != null) {
                    decide(currentVersion, version)
                } else {
                    Result.Failed("发布页上没找到版本号")
                }
            }
            is TextResult.Unavailable -> Result.Failed("连不上发布页：${page.reason}")
        }
    }

    /** 一次取数的结果。 */
    private sealed interface TextResult {
        data class Got(val text: String) : TextResult
        data class Unavailable(val reason: String) : TextResult
    }

    /** 拉一次某个地址的正文。 */
    private fun fetchText(url: String): TextResult = try {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/html;q=0.9")
            .header("User-Agent", "MiuList/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                TextResult.Unavailable("HTTP ${response.code}")
            } else {
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    TextResult.Unavailable("返回了空内容")
                } else {
                    TextResult.Got(body)
                }
            }
        }
    } catch (e: Exception) {
        TextResult.Unavailable(e.describe())
    }

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * 从版本清单里取版本号。读不出来返回 null —— **不猜**：猜错了会把用户
 * 引到不存在的版本上去。
 */
internal fun parseManifestVersion(json: String): String? = runCatching {
    Moshi.Builder().build()
        .adapter(VersionManifestDto::class.java)
        .fromJson(json)
        ?.version
}.getOrNull()

/** 版本清单里我们只关心版本号那一个字段。 */
@JsonClass(generateAdapter = true)
internal data class VersionManifestDto(
    @Json(name = "version") val version: String? = null
)

/**
 * 从发布页 HTML 里认出版本号。
 *
 * ## 为什么要有这一条兜底
 *
 * 版本清单是站点上的一个**额外文件**：站点还没重新发布时它不存在，
 * 而功能不该因为「少一个文件」就整个不工作 —— 页面本来就把版本写着。
 *
 * ## 认哪几处（按可靠程度排）
 *
 * 1. **APK 文件名**（`MiuList-v1.2.0.apk`）：它就是安装包的名字，
 *    与真正能下到的那个版本是一回事，最不会认错。
 * 2. **下载链接里的 tag**（`.../releases/download/v1.2.0/…`、
 *    `.../releases/tag/v1.2.0`）：发布页每个版本都会带一个。
 * 3. **版本徽标**（`v1.2.0 · 正式版`）：页面上给用户看的那一处。
 * 4. **兜底**：页面上第一个 `v1.2.0` 形状的字符串。
 *
 * 认不出来返回 null —— **宁可说「没查到」，也不猜一个版本号**。
 */
internal fun extractVersionFromPage(html: String): String? {
    val patterns = listOf(
        Regex("""MiuList-v(\d+(?:\.\d+)+)\.apk"""),
        Regex("""releases/(?:tag|download)/v(\d+(?:\.\d+)+)"""),
        Regex("""v(\d+(?:\.\d+)+)\s*[·・]?\s*正式版"""),
        Regex("""v(\d+(?:\.\d+)+)""")
    )
    for (pattern in patterns) {
        val match = pattern.find(html) ?: continue
        val version = match.groupValues.getOrNull(1)?.trim()
        if (!version.isNullOrEmpty()) return version
    }
    return null
}

/**
 * 拿到版本号之后做决定。**纯函数**：不碰网络，单测直接钉住。
 *
 * - 远端比本地新 → [AppUpdateChecker.Result.UpdateAvailable]
 * - 否则（一样，或者本地反而更新）→ [AppUpdateChecker.Result.UpToDate]
 * - **版本号读不出来** → [AppUpdateChecker.Result.Failed]：说不上它是新是旧，
 *   就不该拿它去打扰用户，更不能假装「已是最新」。
 */
internal fun decide(currentVersion: String, latestTag: String): AppUpdateChecker.Result {
    val latest = parseAppVersion(latestTag)
        ?: return AppUpdateChecker.Result.Failed("看不懂最新版本号「$latestTag」")
    val comparison = compareAppVersions(currentVersion, latestTag)
        ?: return AppUpdateChecker.Result.Failed("看不懂当前版本号「$currentVersion」")

    return if (comparison < 0) {
        AppUpdateChecker.Result.UpdateAvailable(latest.display())
    } else {
        AppUpdateChecker.Result.UpToDate(latest.display())
    }
}

/** 把异常翻成一句用户看得懂的话，而不是 `java.net` 的类名。 */
private fun Exception.describe(): String = when (this) {
    is java.net.UnknownHostException -> "域名解析不了"
    is javax.net.ssl.SSLException -> "连接被中断"
    is java.net.SocketTimeoutException -> "连接超时"
    is java.net.ConnectException -> "连不上服务器"
    else -> message ?: javaClass.simpleName
}
