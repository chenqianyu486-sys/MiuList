package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「下一件事」是谁：课与考试抢同一个加高位置。
 *
 * ## 为什么这条规则要钉住
 *
 * 它决定屏幕上**哪一张卡被加高、哪一个倒计时被报出来**。
 * 判错的后果很具体：用户盯着「还有 5 小时 20 分」那个读数，
 * 而真正先来的是一场考试 —— 他会按错的时间出门。
 */
class FeaturedPickTest {

    private val now = 1_000_000L

    @Test
    fun `正在上的课优先于任何其它事`() {
        assertEquals(
            FeaturedPick.CLASS,
            pickFeatured(
                currentClassStart = now,
                currentExamStart = null,
                nextClassStart = now + 3_600_000L,
                nextExamStart = now + 600_000L
            )
        )
    }

    @Test
    fun `正在考的考试优先于下一节课`() {
        assertEquals(
            FeaturedPick.EXAM,
            pickFeatured(
                currentClassStart = null,
                currentExamStart = now,
                nextClassStart = now + 600_000L,
                nextExamStart = now + 3_600_000L
            )
        )
    }

    @Test
    fun `都没在进行时更早开始的胜出`() {
        assertEquals(
            FeaturedPick.EXAM,
            pickFeatured(
                currentClassStart = null,
                currentExamStart = null,
                nextClassStart = now + 3_600_000L,
                nextExamStart = now + 600_000L
            )
        )
        assertEquals(
            FeaturedPick.CLASS,
            pickFeatured(
                currentClassStart = null,
                currentExamStart = null,
                nextClassStart = now + 600_000L,
                nextExamStart = now + 3_600_000L
            )
        )
    }

    /** 同时开始：课优先 —— 它是默认项，用户对「下一节课」的预期更强。 */
    @Test
    fun `同一时刻开始时课优先`() {
        assertEquals(
            FeaturedPick.CLASS,
            pickFeatured(
                currentClassStart = null,
                currentExamStart = null,
                nextClassStart = now + 600_000L,
                nextExamStart = now + 600_000L
            )
        )
    }

    @Test
    fun `只有考试时考试上`() {
        assertEquals(
            FeaturedPick.EXAM,
            pickFeatured(
                currentClassStart = null,
                currentExamStart = null,
                nextClassStart = null,
                nextExamStart = now + 600_000L
            )
        )
    }

    @Test
    fun `什么都没有时不加高`() {
        assertEquals(
            FeaturedPick.NONE,
            pickFeatured(
                currentClassStart = null,
                currentExamStart = null,
                nextClassStart = null,
                nextExamStart = null
            )
        )
    }
}
