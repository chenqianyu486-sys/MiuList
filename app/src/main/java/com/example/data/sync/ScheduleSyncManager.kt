package com.example.data.sync

import com.example.data.repository.AcademicCalendarRepository
import com.example.data.repository.CourseRepository
import com.example.data.repository.ExamRepository
import com.example.data.security.SecureAccountStorage
import com.example.data.source.zju.ZjuException
import com.example.data.source.zju.ZjuFailureKind
import com.example.data.source.zju.ZjuImportService
import com.example.data.transfer.ImportReport
import com.example.data.transfer.ImportStrategy
import com.example.data.transfer.PreviewResult
import com.example.data.transfer.ScheduleImportService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface SyncResult {
    data class Success(
        val courseCount: Int,
        val slotCount: Int,
        val termLabel: String,
        val report: ImportReport,
        /**
         * 本次同步到的考试场次。
         *
         * 0 有两种含义：**学校还没公布考试**（正常）与**这次没拉到**（异常），
         * 两种要靠 [examError] 区分 —— 只报一个 0，用户会以为「我一场考试都没有」。
         */
        val examCount: Int = 0,
        /** 考试没拉到时的原因；null = 一切正常。 */
        val examError: String? = null
    ) : SyncResult

    /**
     * 失败**只有这一种形状**。早先拆成 AuthError / NetworkError / Failure 三个分支，
     * 代价是每个分支各带一句 message、各自记得要设哪些 state ——
     * 结果「已存凭据」那条入口就漏了一处，失败在弹层里完全不显示。
     * 合并之后没有第二个分支可以漏，见 [SyncFailure]。
     */
    data class Failed(val failure: SyncFailure) : SyncResult
}

/**
 * 云端同步管理器。
 *
 * 贯通「云端抓取 → 整表替换 → 本地 Room 缓存落盘 → 安全凭据更新 → 离线保全」。
 */
class ScheduleSyncManager(
    private val zjuImportService: ZjuImportService,
    private val importService: ScheduleImportService,
    private val courseRepo: CourseRepository,
    private val calendarRepo: AcademicCalendarRepository,
    private val secureStorage: SecureAccountStorage,
    private val examRepo: ExamRepository
) {

    /**
     * 从浙大教务云端拉取课表并落盘本地缓存。
     *
     * @param studentId 学号
     * @param password 密码
     * @param option 目标学期
     * @param rememberCredentials 是否安全保存凭据到 AndroidKeyStore
     * @param preserveCredentials 远端带来的凭据只用于这一次登录：**不写、也不删**。
     *   删掉本机已存的会连带废掉账号一致性闸门。
     */
    suspend fun sync(
        studentId: String,
        password: String,
        option: ZjuImportService.TermOption,
        rememberCredentials: Boolean = true,
        preserveCredentials: Boolean = false
    ): SyncResult = withContext(Dispatchers.IO) {
        if (studentId.isBlank() || password.isBlank()) {
            return@withContext fail(
                ZjuFailureKind.CREDENTIAL,
                "请填写学号与密码",
                committed = false
            )
        }

        // 跨过 commit 之后，本地数据就换过了。此后任何异常都不能再说「本地原样保留」。
        var committed = false

        try {
            // 1. 云端拉取
            val scheduleJson = zjuImportService.fetchScheduleJson(
                studentId = studentId,
                password = password,
                option = option
            )

            // 1.5 考试。**同一个账号、同一个会话**：zjuImportService 长期持有那个
            //      cas 客户端，刚才取课表时建立的 zdbk 会话还在，
            //      所以这里不会再输一次密码、也不会第二次握手。
            //
            //      **写在这里而不是 commit 之后**，是因为考试是**独立于课表**的数据：
            //      抓到就落库，不受课表落库那一步的成败影响。
            //      反过来（课表落库失败就当考试也没拉到）会让一次课表校验失败
            //      顺手把已经抓到的考试也吞掉。
            //
            //      拉不到考试**不算同步失败**：教务网在还没到考试季时本来就回空，
            //      那是正常状态。真正的原因带在 [SyncResult.Success.examError] 里，
            //      如实告诉用户，而不是悄悄吞掉。
            val examOutcome = runCatching {
                val rows = zjuImportService.fetchExams(studentId, password)
                examRepo.replaceAll(rows)
            }
            val examCount = examOutcome.getOrDefault(0)
            val examError = examOutcome.exceptionOrNull()?.message

            // 2. 整表替换。
            //    早先这里写的是 `if (isDemo) REPLACE else REPLACE` —— 两个分支一样，
            //    于是读代码的人会以为「非演示数据其实还有另一条更温和的路」，去查一个不存在的分支。
            //    现在示例数据功能已取消，只剩一条路：云端那份就是权威，整表换过去。

            // 3. 校验并落库到本地 Room 缓存（单事务安全提交）
            val previewResult = importService.preview(scheduleJson, ImportStrategy.REPLACE)
            val ready = when (previewResult) {
                is PreviewResult.Ready -> previewResult
                is PreviewResult.Failed -> return@withContext fail(
                    ZjuFailureKind.DATA_INVALID,
                    previewResult.message,
                    committed = false
                )
            }

            val report = importService.commit(ready)
            committed = true

            // 4. 更新安全凭据与缓存元信息
            if (preserveCredentials) {
                // 远端凭据用完即弃，凭据存储一行都不碰。
                // 早先这里走的是 saveMaskedStudentIdOnly，而它会把本机已存的
                // 学号密码删掉 —— 远端一次同步就把「学号必须与手机已绑定账号一致」
                // 那道闸门废掉了，之后谁都拿远端的学号来借这台手机都没人拦。
            } else if (rememberCredentials) {
                secureStorage.saveCredentials(studentId, password, remember = true)
            } else {
                secureStorage.saveMaskedStudentIdOnly(studentId)
            }
            secureStorage.saveLastTerm(option.year, option.term)

            // 5. 校历后置处理
            //
            // 这里**不自动生成本周待办**。同步是「把课表换成服务器上的那份」，
            // 不是「替用户安排这一周」：用户手写的待办要留着，自己点「生成本周待办」
            // 才是他主动要新增。之前自动生成的那批会在下一次同步时被整表清掉，
            // 用户既没要过、也留不下，纯属白做。
            val semester = calendarRepo.semesterOnce()
            if (semester != null) {
                courseRepo.clampSlotWeeksTo(semester.totalWeeks)
            }

            // 「同步成功」必须记在**整条管线跑完之后**。
            // 早先它记在保存凭据之后，于是第 5 步一炸，界面上就同时存在
            // 「上次成功同步」与「最近一次尝试失败」两条自相矛盾的历史。
            secureStorage.markSyncSuccess(System.currentTimeMillis())

            val finalCourses = courseRepo.getAllCoursesOnce()
            val finalSlots = courseRepo.getAllSlotsOnce()

            SyncResult.Success(
                courseCount = finalCourses.size,
                slotCount = finalSlots.size,
                termLabel = option.label,
                report = report,
                examCount = examCount,
                examError = examError
            )
        } catch (e: ZjuException) {
            fail(e.kind, e.message ?: "取课表失败", committed)
        } catch (e: IOException) {
            // 走到了这里说明异常不是经由 ZjuCasClient 抛的，
            // 那就是校历配置或编码环节的网络调用断了 —— 仍然是本机到浙大这条路不通。
            fail(ZjuFailureKind.NETWORK, "连不上浙大服务，请检查网络或代理", committed)
        } catch (e: Exception) {
            fail(ZjuFailureKind.UNKNOWN, e.message ?: "同步发生异常", committed)
        }
    }

    /**
     * 组装失败结果，并把这次失败**记下来**。
     *
     * 记录这件事以前是缺的：界面上的「上次同步：」只写成功时间，
     * 于是刚失败过一次的用户看到的还是一条看上去很健康的旧时间戳，
     * 完全看不出「刚才那次没成」。失败和成功一样是状态，值得占一行。
     */
    private suspend fun fail(
        kind: ZjuFailureKind,
        reason: String,
        committed: Boolean
    ): SyncResult {
        val localCount = courseRepo.getAllCoursesOnce().size
        val failure = SyncFailure(
            kind = kind,
            reason = reason,
            localDataIntact = !committed,
            localCourseCount = localCount
        )
        secureStorage.markSyncFailure(kind)
        return SyncResult.Failed(failure)
    }

    /**
     * 使用已安全存储的凭据快速同步。
     */
    suspend fun syncWithSavedCredentials(
        option: ZjuImportService.TermOption? = null
    ): SyncResult = withContext(Dispatchers.IO) {
        val studentId = secureStorage.getStudentId()
        val password = secureStorage.getPassword()
        if (studentId.isNullOrBlank() || password.isNullOrBlank()) {
            return@withContext fail(
                ZjuFailureKind.CREDENTIAL,
                "本机没有可用的学号与密码，请先填写",
                committed = false
            )
        }

        val targetTerm = option ?: run {
            val savedTerm = secureStorage.getLastTerm()
            if (savedTerm != null) {
                ZjuImportService.TermOption(savedTerm.first, savedTerm.second)
            } else {
                val terms = runCatching { zjuImportService.availableTerms() }.getOrNull()
                terms?.firstOrNull()
                    ?: return@withContext fail(
                        ZjuFailureKind.SERVER,
                        "拿不到浙大的学期列表，暂时无法确定同步哪一学期",
                        committed = false
                    )
            }
        }

        sync(
            studentId = studentId,
            password = password,
            option = targetTerm,
            rememberCredentials = true
        )
    }
}
