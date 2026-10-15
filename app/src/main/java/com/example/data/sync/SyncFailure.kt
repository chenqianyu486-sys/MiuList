package com.example.data.sync

import com.example.data.source.zju.ZjuFailureKind

/**
 * 一次同步失败**说清楚了三件事**：出了什么类别的事、具体原因、以及本地数据怎么样。
 *
 * ## 为什么不是一条字符串
 *
 * 早先 [SyncResult] 把失败拆成 `AuthError` / `NetworkError` / `Failure` 三个分支，
 * 每个分支各自带一句 `message`。那种形状的代价不在于「多写几个 when」，
 * 而在于**三份拷贝会长歪**：
 * 事实上确实是 `syncFromCloud` 给全部三种失败都设了 `zjuError`，
 * 而走「已存凭据」那条入口的 `syncWithSavedCredentials` 只设了 `userMessage`。
 * 结果就是：在教务账号弹层里点「立即同步」，失败了**弹层里什么都不显示**
 * （Snackbar 在 Scaffold 上，而弹层画在 Scaffold 之上），只有消息本身被吃掉。
 *
 * 合并成单一的 [SyncResult.Failed] 之后，「漏设一次」这个 bug 就不存在了 ——
 * 没有第二个失败分支可以漏。
 */
data class SyncFailure(
    /** 失败类别。界面据此决定「该让用户去做什么」，而不是据此选文案。 */
    val kind: ZjuFailureKind,

    /** 具体原因，一句话，来自上游或底层异常。 */
    val reason: String,

    /**
     * 失败发生时，本地课表是否**原样**可用。
     *
     * 这不是保守的默认值，是真的会变：同步流程在 `commit` 之后就换了阶段，
     * 那一段里抛的异常（凭据落盘、缓存元信息…）意味着**新数据已经写进去了**。
     * 早先一律报「已保留本地缓存」，那种情况下是假的 —— 说「本地没事」而实际有变化，
     * 比不说更糟：用户会以为完全没变，于是把已经生效的课表当成旧数据删掉重导。
     */
    val localDataIntact: Boolean,

    /** 失败时本地仍可用的课程数。查不到就留 null，界面别硬凑。 */
    val localCourseCount: Int? = null
) {
    /** 一句话版本，给 Snackbar 用。弹层里的完整提示走 [com.example.ui.components.SyncFailureCard]。 */
    fun headline(): String = "${kind.title}：$reason"
}
