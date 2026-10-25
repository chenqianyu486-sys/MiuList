package com.example.ui.screens

import com.example.data.local.TaskEntity
import com.example.data.model.STAR_MARKED
import com.example.data.model.STAR_UNMARKED
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 清单页**组内**的排序：标星的在前，其余按截止时间。
 *
 * ## 为什么这条值得单独钉住
 *
 * 星标是用户**手动挑出来**的那几条 —— `TaskStar` 的注释里写着它唯一真正好用的
 * 那档用法是「手动挑出几条重要的」，而且「那是列表排序给不了的（排序每天都在变）」。
 * 所以它一旦被标上，就得在这一组里排在最前面。
 *
 * 截止时间退成**第二把键**：星标之内仍然按截止靠前的排。
 *
 * ## 这条规则只动「星标」这一层
 *
 * 稳定排序保证标星的那几条内部顺序不变、没标星的也不变 ——
 * 「星标优先」是**唯一**的改动，截止时间的排法一个字都没动。
 * 这一条要有用例守着：哪天有人把排序换成非稳定的，
 * 或者顺手重排了截止时间，受影响的不是「看起来有点乱」，而是**截止靠前**这个
 * 全 App 都在依赖的读法。
 */
class ChecklistStarSortTest {

    /**
     * 造一条待办。排序只看三样东西：**星标**、**截止**、**id**，
     * 其余字段填什么都不影响 —— 但仍然填齐，免得以后实体加字段时编译不过。
     */
    private fun task(
        id: Long,
        title: String,
        due: Long = 100L,
        starred: Boolean = false,
        priority: String = if (starred) STAR_MARKED else STAR_UNMARKED
    ) = TaskEntity(
        id = id,
        courseId = 1L,
        courseName = "高等数学A（上）",
        courseCategory = "必修",
        courseColorHex = "#FDBA74",
        title = title,
        taskType = "HOMEWORK",
        dueDateTimestamp = due,
        priority = priority
    )

    @Test
    fun `标星的排在没标星的前面`() {
        val sorted = listOf(
            task(1L, "没标星的，截止最早", due = 10L),
            task(2L, "标了星的，截止最晚", due = 999L, starred = true)
        ).starredFirstWithinGroup()

        assertEquals(listOf("标了星的，截止最晚", "没标星的，截止最早"), sorted.map { it.title })
    }

    /**
     * 星标之内仍按截止靠前的排。
     *
     * 这一条是「星标优先」**不是**「星标随便排」的意思：
     * 标星只是把「我挑出来的」提到前面，它不改变这些待办彼此之间谁更急。
     *
     * 输入照 DAO 给的样子喂（截止靠前）—— 真实链路就是这样：
     * `groupBy` 保留入参顺序，组内拿到的本来就是「截止靠前」的那一串。
     */
    @Test
    fun `星标内部仍按截止时间排`() {
        val sorted = listOf(
            task(1L, "星标·截止早", due = 100L, starred = true),
            task(2L, "没标星·截止更早", due = 50L),
            task(3L, "星标·截止晚", due = 300L, starred = true)
        ).starredFirstWithinGroup()

        // 两条星标的被提到前面，且彼此之间仍是「截止靠前」。
        assertEquals(listOf("星标·截止早", "星标·截止晚", "没标星·截止更早"), sorted.map { it.title })
    }

    /**
     * 没标星的那几条之间顺序不变 —— 稳定排序的另一半。
     */
    @Test
    fun `没标星的内部顺序不变`() {
        val sorted = listOf(
            task(1L, "普通·最先", due = 100L),
            task(2L, "普通·其次", due = 200L),
            task(3L, "普通·最后", due = 300L)
        ).starredFirstWithinGroup()

        assertEquals(listOf("普通·最先", "普通·其次", "普通·最后"), sorted.map { it.title })
    }

    /**
     * 一条都没标星时，顺序**一字不变**。
     *
     * 排序不能凭空重排本来就对的列表 —— 这一条是「星标优先」不会误伤的兜底。
     */
    @Test
    fun `没有星标时顺序完全不变`() {
        val original = listOf(
            task(3L, "第三条", due = 300L),
            task(1L, "第一条", due = 100L),
            task(2L, "第二条", due = 200L)
        )

        assertEquals(
            original.map { it.title },
            original.starredFirstWithinGroup().map { it.title }
        )
    }

    /**
     * 旧三级值 `HIGH` 也要算标星。
     *
     * `TaskStar` 的兼容表把 `HIGH` 读成「标了星」（旧体系里唯一被认真挑出来的一档），
     * 排序必须走 `TaskStar.isMarked` 而不是自己判字符串 —— 否则升级来的老数据
     * 在排序里会被当成没标星，于是「标了星的排在前面」对它们不成立。
     */
    @Test
    fun `旧值 HIGH 也算标星`() {
        val sorted = listOf(
            task(1L, "普通", due = 10L),
            task(2L, "旧 HIGH", due = 999L, priority = "HIGH")
        ).starredFirstWithinGroup()

        assertEquals(listOf("旧 HIGH", "普通"), sorted.map { it.title })
    }

    /**
     * 一条标星、一条没标星，交替排进去，结果只该是「标星的提到最前」，
     * 其余保持原样 —— 不能因为中间插了一条就把别的也带乱。
     */
    @Test
    fun `交替出现的星标只影响星标那一层`() {
        val sorted = listOf(
            task(1L, "A", due = 100L),
            task(2L, "B", due = 200L, starred = true),
            task(3L, "C", due = 300L),
            task(4L, "D", due = 400L, starred = true)
        ).starredFirstWithinGroup()

        assertEquals(listOf("B", "D", "A", "C"), sorted.map { it.title })
    }
}
