package com.example

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.example.data.local.CourseEntity
import com.example.data.local.TaskEntity
import com.example.ui.components.TaskEditorSheet
import com.example.ui.theme.OverlayHazeState
import dev.chrisbanes.haze.hazeSource
import kotlin.math.abs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class TaskEditorSheetRenderingTest {
    @get:Rule
    val compose = createComposeRule()

    private val longNotes = (1..80).joinToString("\n") { "第${it}行：整理本章例题并补充推导过程。" }
    private var topInsetPx = 0

    private fun showSheet(
        description: String = "",
        maxHeight: Dp = Dp.Infinity,
        fontScale: Float = 1f,
        onDismiss: () -> Unit = {}
    ) {
        val course = CourseEntity(
            id = 1L,
            name = "高等数学",
            code = "MATH101",
            category = "公共基础",
            teacher = "",
            classroom = "",
            colorHex = "#93C5FD"
        )
        compose.setContent {
            val density = LocalDensity.current
            val safeTop = WindowInsets.safeDrawing.getTop(density)
            SideEffect { topInsetPx = safeTop }
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.heightIn(max = maxHeight).fillMaxSize().testTag("rendering_root")) {
                        Box(
                            Modifier.fillMaxSize()
                                .hazeSource(OverlayHazeState)
                                .background(Color(0xFF304878))
                        )
                        TaskEditorSheet(
                            initialTask = TaskEntity(
                                id = 1L,
                                courseId = course.id,
                                courseName = course.name,
                                courseCategory = course.category,
                                courseColorHex = course.colorHex,
                                title = "章节作业",
                                description = description,
                                taskType = "POST_CLASS",
                                dueDateTimestamp = TaskEntity.NO_DUE_TIMESTAMP
                            ),
                            courses = listOf(course),
                            customTypes = emptyList(),
                            preselectedCourseId = null,
                            onDismiss = onDismiss,
                            onCreateTaskType = {},
                            onDelete = {},
                            onSave = {}
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun courseExpansionKeepsTheGlassBackgroundStable() {
        var dismissed = false
        showSheet(onDismiss = { dismissed = true })
        val initial = capture()
        repeat(3) {
            compose.onNodeWithContentDescription("更换关联课程").performClick()
            compose.waitForIdle()
            assertBackgroundMatches(initial, capture())
            compose.onNodeWithContentDescription("收起课程选择").performClick()
            compose.waitForIdle()
            assertBackgroundMatches(initial, capture())
        }
        compose.runOnIdle { assertFalse(dismissed) }
    }

    @Test
    fun notesGrowAndShrinkTheSheetWithoutChangingItsGlass() {
        val shortNotes = "第一行\n第二行"
        showSheet(description = shortNotes)
        val initial = capture()
        val shortHeight = contentHeight()

        replaceNotes((1..8).joinToString("\n") { "第${it}行备注" })
        val eightLineHeight = contentHeight()
        assertTrue(eightLineHeight > shortHeight)
        replaceNotes((1..12).joinToString("\n") { "第${it}行备注" })
        assertTrue("Notes must keep growing beyond six lines", contentHeight() > eightLineHeight)
        assertBackgroundMatches(initial, capture())

        replaceNotes(longNotes)
        assertContentFits("save_task_button")
        assertTrue(
            compose.onNodeWithTag("task_description_input").fetchSemanticsNode().size.height > contentHeight()
        )
        compose.onNodeWithText("课程").performScrollTo().performClick()
        compose.onNodeWithTag("save_task_button").assertIsDisplayed()
        assertBackgroundMatches(initial, capture())
        compose.onNodeWithText("课程").performClick()

        replaceNotes(shortNotes)
        compose.onNodeWithTag("close_task_button").assertIsDisplayed()
        assertTrue(abs(contentHeight() - shortHeight) < 2f)
        assertBackgroundMatches(initial, capture())
    }

    @Test
    fun existingLongNotesStayScrollableWithAFixedFooter() {
        var dismissed = false
        showSheet(description = longNotes, onDismiss = { dismissed = true })
        assertContentFits("close_task_button")
        val initial = capture()
        val height = contentHeight()
        repeat(3) {
            compose.onNodeWithText("课程").performScrollTo().performClick()
            compose.waitForIdle()
            assertFalse("Clicking the course row dismissed the sheet", dismissed)
            compose.onNodeWithContentDescription("收起课程选择").assertIsDisplayed()
            assertContentFits("close_task_button")
            assertTrue(abs(contentHeight() - height) < 2f)
            assertBackgroundMatches(initial, capture())
            compose.onNodeWithText("课程").performClick()
            compose.waitForIdle()
            assertFalse("Collapsing the course row dismissed the sheet", dismissed)
            assertBackgroundMatches(initial, capture())
        }
    }

    @Test
    fun longNotesFitAShortWindowWithLargeText() {
        showSheet(description = longNotes, maxHeight = 420.dp, fontScale = 1.5f)
        assertContentFits("close_task_button")
        val initial = capture()
        compose.onNodeWithText("课程").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("收起课程选择").assertIsDisplayed()
        compose.onNodeWithTag("close_task_button").assertIsDisplayed()
        assertBackgroundMatches(initial, capture())
    }

    private fun replaceNotes(text: String) {
        compose.onNodeWithTag("task_description_input").performSemanticsAction(SemanticsActions.SetText) {
            it(AnnotatedString(text))
        }
        compose.waitForIdle()
    }

    private fun contentHeight(): Float =
        compose.onNodeWithTag("task_editor_content", useUnmergedTree = true).fetchSemanticsNode().size.height.toFloat()

    private fun assertContentFits(footerTag: String) {
        val root = compose.onNodeWithTag("rendering_root").fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("task_editor_content", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val topGap = with(compose.density) { 24.dp.toPx() }
        assertTrue("Sheet must stay below the top safe area: $content in $root", content.top >= root.top + topInsetPx + topGap)
        assertTrue("Sheet bottom must remain visible: $content in $root", content.bottom <= root.bottom)
        compose.onNodeWithTag(footerTag).assertIsDisplayed()
    }

    private fun capture(): Bitmap =
        compose.onNodeWithTag("rendering_root").captureToImage().asAndroidBitmap()

    private fun assertBackgroundMatches(before: Bitmap, after: Bitmap) {
        // 均匀色源下，按钮下方的空白玻璃带不应因面板高度变化而改变颜色。
        var difference = 0L
        var channels = 0
        for (y in before.height - 12 until before.height - 2) {
            for (x in before.width / 4 until before.width * 3 / 4) {
                val a = before.getPixel(x, y)
                val b = after.getPixel(x, y)
                for (shift in listOf(0, 8, 16)) {
                    difference += abs(((a shr shift) and 255) - ((b shr shift) and 255))
                    channels++
                }
            }
        }
        val meanDifference = difference.toDouble() / channels
        assertTrue("Glass background changed by $meanDifference levels", meanDifference < 3.0)
    }
}
