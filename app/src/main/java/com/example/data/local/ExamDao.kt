package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 考试的读写。
 *
 * **整表替换**是这里的唯一写入口（见 `ExamRepository.replaceAll`）：
 * 考试完全来自服务端，没有「本地新建的考试」这种东西，
 * 所以不存在「合并两份考试列表」的场景，也就不需要 upsert 的去重规则。
 */
@Dao
interface ExamDao {

    /** 全部考试，按时间升序。日期未知的排在最后（`startAt = 0`）。 */
    @Query("SELECT * FROM exams ORDER BY startAt ASC, startMinute ASC, id ASC")
    fun getAllExams(): Flow<List<ExamEntity>>

    @Query("SELECT * FROM exams ORDER BY startAt ASC, startMinute ASC, id ASC")
    suspend fun getAllExamsOnce(): List<ExamEntity>

    @Query("SELECT COUNT(*) FROM exams")
    suspend fun countExams(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExams(exams: List<ExamEntity>)

    /**
     * 改一场考试的考场、座位与时刻。
     *
     * **只改这几个字段**：课程名、期中考/期末这些是学校给的事实，
     * 本地改了没有意义（下次同步又被覆盖）。
     */
    @Query(
        """
        UPDATE exams SET
            classroom = :classroom,
            seat = :seat,
            startAt = :startAt,
            endAt = :endAt,
            startMinute = :startMinute,
            endMinute = :endMinute,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateExam(
        id: Long,
        classroom: String,
        seat: String,
        startAt: Long,
        endAt: Long,
        startMinute: Int,
        endMinute: Int,
        updatedAt: Long
    )

    @Query("DELETE FROM exams")
    suspend fun clearExams()
}
