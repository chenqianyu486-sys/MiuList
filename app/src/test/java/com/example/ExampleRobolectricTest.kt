package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.CourseTodoRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("MiuList", appName)
    }

    @Test
    fun `test repository initialization and multi-slot course schedule`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = AppDatabase.getDatabase(context)
        val repository = CourseTodoRepository(
            database.courseDao(),
            database.courseSlotDao(),
            database.taskDao()
        )

        // Initialize with realistic demo data
        repository.initializeIfEmpty()

        val coursesWithSlots = repository.getAllCoursesWithSlots().first()
        assertTrue("Courses should be pre-seeded", coursesWithSlots.isNotEmpty())

        val allSlots = repository.getAllSlots().first()
        assertTrue("Slots should be pre-seeded", allSlots.isNotEmpty())

        // Check if Higher Math has multiple slots per week (周一 1-2节 and 周四 3-5节)
        val higherMath = coursesWithSlots.find { it.course.name.contains("高等数学") }
        assertNotNull(higherMath)
        assertTrue("Higher Math should have at least 2 weekly slots", (higherMath?.slots?.size ?: 0) >= 2)

        // Test custom 1-13 period range (e.g. slot with 3-5 or 11-13)
        val customSpanSlot = allSlots.find { (it.endPeriod - it.startPeriod) >= 2 }
        assertNotNull("Should contain custom span slot (like 3-5节 or 11-13节)", customSpanSlot)

        // Test task generation with course start time
        val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, Calendar.THURSDAY) }
        val candidateTasks = repository.previewCandidateTasks(cal)
        assertNotNull(candidateTasks)
        assertTrue(candidateTasks.all { it.startTimeString.isNotBlank() })
    }

    @Test
    fun `test tasks sorted by course time`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = AppDatabase.getDatabase(context)
        val repository = CourseTodoRepository(
            database.courseDao(),
            database.courseSlotDao(),
            database.taskDao()
        )

        repository.initializeIfEmpty()
        val tasks = repository.getAllTasks().first()
        assertTrue("Tasks should be populated", tasks.isNotEmpty())

        // Sort by start time (course time)
        val sorted = tasks.sortedBy { it.startTimeTimestamp }
        assertTrue("Tasks should be sorted", sorted.first().startTimeTimestamp <= sorted.last().startTimeTimestamp)
    }
}
