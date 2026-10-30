package com.example.data.sync.web

import com.example.data.security.SecureAccountStorage
import com.example.data.source.zju.ZjuImportService
import com.example.data.sync.ScheduleSyncManager
import com.example.data.sync.SyncResult

/**
 * 网页端「从学在浙大拉课表」的**手机侧代理**。
 *
 * ## 为什么必须是手机代劳
 *
 * 网页端是 `file://` 打开的单文件 HTML（Origin 为 `null`），
 * 而学在浙大的登录链路跨三个域（`zjuam` → `zdbk` → `courses`），
 * 中间要自己读 302 的 `Location` 取 ticket、读登录页的 hidden input 拿 `execution`、
 * 手改 TGC 的 Cookie Domain —— 这三件跨源脚本一件都做不到，
 * 浏览器也拿不到浙大接口的 CORS 头。
 *
 * 而手机这边**已经有整套能力**（`ZjuImportService` → `ScheduleSyncManager`），
 * 凭据还躺在 Keystore 里。所以网页端要做的只是「**请手机去拉一次**」。
 *
 * ## 凭据怎么走
 *
 * 网页端可以把学号密码填进来（用户第一次用时手机可能还没绑过账号）。
 * 那份密码：
 * - **只在这次请求的密文里存在**（会话密钥派生的 ChaCha20，局域网上也解不开）；
 * - **不落浏览器存储** —— 网页端不记密码；
 * - 手机这边**只拿它登录这一次**：不写进 Keystore，也不碰本机已存的那份。
 *
 * 不填就**用手机已存的凭据**拉 —— 那是手机 App 自己的那一份。
 */
class ZjuProxyService(
    private val zjuImportService: ZjuImportService,
    private val syncManager: ScheduleSyncManager,
    private val secureStorage: SecureAccountStorage,
) : ZjuProxy {

    /**
     * 可选的学期。**不需要账号** —— 学期表是公共配置。
     */
    override suspend fun terms(): List<ZjuProxy.TermItem> =
        zjuImportService.availableTerms().map {
            ZjuProxy.TermItem(it.year, it.term, it.label)
        }

    /**
     * 去学在浙大拉一次课表（顺带把考试也拉了）。
     *
     * @param studentId 学号。空 = 用手机已存的凭据。
     * @param password 密码。空 = 用手机已存的凭据。
     * @param year 目标学年（`"2026-2027"`）。空 = 用上次同步的那一期。
     * @param term 目标半学期（0 = 秋，4 = 春）。空 = 用上次同步的那一期。
     * @param remember 已不再用于落盘：远端凭据一律不写回也不清除；
     *   参数保留是为了不改接口。
     */
    override suspend fun sync(
        studentId: String?,
        password: String?,
        year: String?,
        term: Int?,
        remember: Boolean,
    ): ZjuProxy.Outcome {
        val hasCredentials = !studentId.isNullOrBlank() && !password.isNullOrBlank()
        // 只填了学号或只填了密码：当作「没有凭据」，走手机已存的那份。
        // 半填比不填更容易出事 —— 拿旧密码配新学号会得到一个看不懂的失败。
        val useSaved = !hasCredentials

        if (!useSaved) {
            // **远端带来的学号必须与这台手机上已绑定的账号一致。**
            //
            // 不一致意味着：网页端（或冒充网页端的东西）在借这台手机
            // 去登**另一个**账号。后果有两样：
            //
            // - 用户自己在 App 里绑定的那个账号会被顶掉；
            // - 反复拿别人的学号去试，能把**那个账号**刷到锁定
            //   （浙大明确有「密码错误次数过多」的锁定状态）。
            //
            // 手机上还没绑过账号时不拦 —— 那是「第一次用网页端」的正常情况。
            val savedId = secureStorage.getStudentId()?.trim()
            if (!savedId.isNullOrBlank() && savedId != studentId!!.trim()) {
                return ZjuProxy.Outcome.Failed(
                    kind = "ACCOUNT",
                    reason = "网页端带来的学号与这台手机上已绑定的账号不一致，已拒绝",
                    localDataIntact = true,
                )
            }
        }

        val option = resolveTerm(year, term)
            ?: return ZjuProxy.Outcome.Failed(
                kind = "TERM",
                reason = "拿不到浙大的学期列表，暂时无法确定同步哪一学期",
                localDataIntact = true,
            )

        val result = if (useSaved) {
            syncManager.syncWithSavedCredentials(option)
        } else {
            syncManager.sync(
                studentId = studentId!!,
                password = password!!,
                option = option,
                rememberCredentials = remember,
                // 网页端带来的凭据只在这一次登录里用：既不写回，也不清除本机已存的。
                // 清掉本机那份会把「远端学号必须与手机已绑定账号一致」那道闸门废掉，
                // 之后网页端就能借这台手机去登任何账号，没人拦得住。
                preserveCredentials = true,
            )
        }

        return when (result) {
            is SyncResult.Success -> ZjuProxy.Outcome.Ok(
                courseCount = result.courseCount,
                slotCount = result.slotCount,
                examCount = result.examCount,
                termLabel = result.termLabel,
                examError = result.examError,
                savedCredentials = if (useSaved) {
                    // 用的就是手机已存的那份，不用再存一次。
                    true
                } else {
                    remember
                },
            )
            is SyncResult.Failed -> ZjuProxy.Outcome.Failed(
                kind = result.failure.kind.name,
                reason = result.failure.reason,
                localDataIntact = result.failure.localDataIntact,
            )
        }
    }

    /**
     * 定下这次同步哪一学期。
     *
     * 指定了就用它；没指定就用**上次同步的那一期**；
     * 连上次都没有（第一次用）就取浙大学期表里最新的一期。
     */
    private suspend fun resolveTerm(year: String?, term: Int?): ZjuImportService.TermOption? {
        if (!year.isNullOrBlank() && term != null) {
            return ZjuImportService.TermOption(year, term)
        }
        val saved = secureStorage.getLastTerm()
        if (saved != null && (year == null || year == saved.first)) {
            return ZjuImportService.TermOption(saved.first, saved.second)
        }
        return zjuImportService.availableTerms().firstOrNull()
            ?.let { ZjuImportService.TermOption(it.year, it.term) }
    }
}

/**
 * 「网页端请手机去学在浙大拉一次」这件事的**契约**。
 *
 * ## 为什么要有这一层
 *
 * [WebSyncApi] 只关心两件事：「学期列表长什么样」「拉一次的结果是什么」。
 * 它不该被绑死在「必须真的去连浙大」上 ——
 * 接口层要能断言「网页端传来的学号密码被原样转交出去了」、
 * 「拉失败时手机上交的是失败而不是半个成功」，
 * 而这两件事用真连浙大的实现根本测不了（真实现要网络、要 Keystore）。
 *
 * 真实现是 [ZjuProxyService]；测试里用假的顶上。
 */
interface ZjuProxy {

    /** 学期下拉里的一项。 */
    data class TermItem(
        val year: String,
        /** 教务网查询用哪一半：秋冬 → 0(秋)，春夏 → 4(春)。 */
        val term: Int,
        val label: String,
    )

    /** 一次拉取的结果。 */
    sealed interface Outcome {
        data class Ok(
            val courseCount: Int,
            val slotCount: Int,
            val examCount: Int,
            val termLabel: String,
            /** 考试没拉到时的原因；null = 一切正常。 */
            val examError: String?,
            /** 凭据是否被转存进了 Keystore。 */
            val savedCredentials: Boolean,
        ) : Outcome

        data class Failed(
            val kind: String,
            val reason: String,
            /** 失败时本机数据是不是原样保留着。 */
            val localDataIntact: Boolean,
        ) : Outcome
    }

    /** 可选的学期。**不需要账号** —— 学期表是公共配置。 */
    suspend fun terms(): List<TermItem>

    /**
     * 去学在浙大拉一次课表（顺带把考试也拉了）。
     *
     * @param studentId 学号。空 = 用手机已存的凭据。
     * @param password 密码。空 = 用手机已存的凭据。
     * @param year 目标学年（`"2026-2027"`）。空 = 用上次同步的那一期。
     * @param term 目标半学期（0 = 秋，4 = 春）。空 = 用上次同步的那一期。
     * @param remember 已不再用于落盘：远端凭据一律不写回也不清除；
     *   参数保留是为了不改接口。
     */
    suspend fun sync(
        studentId: String?,
        password: String?,
        year: String?,
        term: Int?,
        remember: Boolean,
    ): Outcome
}
