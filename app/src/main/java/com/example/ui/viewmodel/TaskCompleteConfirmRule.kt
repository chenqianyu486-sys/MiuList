package com.example.ui.viewmodel

import com.example.data.local.TaskEntity

/**
 * 勾掉一条待办**要不要先问一句**。
 *
 * ## 为什么抽成纯函数
 *
 * 这条规则决定的是「用户点那颗圆圈之后发生什么」，而它有两处容易出错的边界：
 *
 * - **取消完成不问**。取消完成是一次撤销：把待办放回清单里没有风险，
 *   而问一句只会让人多按一次。把「勾上」和「取消」一起拦住的写法
 *   （「只要是切换就问」）看起来对称，实际是给最没有风险的动作也加了一道阻力。
 * - **开关关着就不问**。用户在设置里明确关掉了它，界面就必须立刻照办 ——
 *   再弹一次弹窗，用户会觉得「这个开关不起作用」。
 *
 * 两个边界各写各的很容易漏一个，所以写成纯函数：输入是
 * 「这条待办现在是什么状态」与「开关开没开」，输出是「要不要问」。
 *
 * @param task 即将被切换的那条待办。读它 [TaskEntity.isCompleted] 就知道
 *   这次点击是**勾上**还是**取消**。
 * @param confirmEnabled 设置里那个「待办完成确认」开没开。
 */
internal fun shouldConfirmTaskComplete(task: TaskEntity, confirmEnabled: Boolean): Boolean =
    !task.isCompleted && confirmEnabled
