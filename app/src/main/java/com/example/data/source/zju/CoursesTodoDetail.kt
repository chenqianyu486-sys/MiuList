package com.example.data.source.zju

/**
 * 学在浙大待办的**详情**：点开那张小卡时弹出来的那一份东西。
 *
 * ## 为什么和 [CoursesTodoItem] 分开
 *
 * 列表里那份是**离线缓存**的（只有标题、课程、截止时间，存进 SharedPreferences，
 * 冷启动就能画出来）；这份说明和附件要现网拉，两者的新鲜度与代价完全不同。
 * 混在一个类里的话，缓存就得把说明也存下来 —— 而说明是每次刷新都可能变的正文，
 * 缓存一份陈旧的正文比不缓存更糟。
 */
data class CoursesTodoDetail(
    /** 活动 id。与待办列表里那条的 [CoursesTodoItem.id] 是同一个。 */
    val activityId: String,
    val title: String,
    /** 作业说明（已经从 HTML 转成纯文本）。空串 = 老师没写说明。 */
    val description: String,
    /** 完成条件（例如「提交一次实验报告即算完成」）。空串 = 没有。 */
    val completionCriterion: String,
    /** 附件清单。 */
    val attachments: List<Attachment>,
    /** 截止时间毫秒时间戳，null = 无截止时间。 */
    val dueTimestamp: Long?
) {
    val hasDescription: Boolean get() = description.isNotBlank()
    val hasCompletionCriterion: Boolean get() = completionCriterion.isNotBlank()
    val hasAttachments: Boolean get() = attachments.isNotEmpty()
}

/**
 * 一个附件。
 *
 * 只留文件名：学在浙大的附件本体要走单独的上传通道才能拿到字节，
 * 这里只解决「这条作业带了哪些文件」这个问题，够用户判断要不要去学在浙大上打开。
 */
data class Attachment(
    val name: String
)
