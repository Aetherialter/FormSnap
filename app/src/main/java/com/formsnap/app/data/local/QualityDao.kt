package com.formsnap.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.formsnap.app.domain.model.TaskCounts
import kotlinx.coroutines.flow.Flow

@Dao
interface QualityDao {
    @Query("""
        SELECT t.id AS taskId,
        (SELECT COUNT(*) FROM table_rows r WHERE r.taskId = t.id AND r.excluded = 0) AS recordCount,
        (SELECT COUNT(DISTINCT i.target || ':' || i.targetId) FROM validation_issues i WHERE i.taskId = t.id AND i.active = 1 AND i.status != 'RESOLVED') AS reviewCount,
        (SELECT COUNT(*) FROM validation_issues i WHERE i.taskId = t.id AND i.active = 1 AND i.status != 'RESOLVED' AND i.code = 'DUPLICATE_RECORD') AS duplicateCount
        FROM digitization_tasks t
    """)
    fun observeCounts(): Flow<List<TaskCounts>>
    @Query("SELECT * FROM page_results WHERE taskId = :taskId")
    suspend fun pages(taskId: String): List<PageResultEntity>
    @Upsert suspend fun savePage(page: PageResultEntity)

    @Query("SELECT * FROM validation_issues WHERE taskId = :taskId ORDER BY scope, targetId, code")
    suspend fun issues(taskId: String): List<IssueEntity>
    @Query("DELETE FROM validation_issues WHERE taskId = :taskId")
    suspend fun clearIssues(taskId: String)
    @Query("DELETE FROM validation_issues WHERE taskId = :taskId AND ((target = 'CELL' AND targetId IN (:cellIds)) OR target = 'ROW_GROUP' OR (target = 'SOURCE' AND targetId = :sourceId))")
    suspend fun invalidatePageIssues(taskId: String, sourceId: String, cellIds: List<String>)
    @Insert suspend fun insertIssues(issues: List<IssueEntity>)
    @Update suspend fun updateCells(cells: List<CellEntity>)
    @Update suspend fun updateFields(fields: List<FieldEntity>)
    @Query("UPDATE table_schemas SET configurationConfirmed = 1 WHERE taskId = :taskId")
    suspend fun confirmConfiguration(taskId: String)
    @Query("UPDATE table_rows SET excluded = 1 WHERE id = :rowId AND taskId = :taskId")
    suspend fun excludeRow(taskId: String, rowId: String): Int
    @Insert suspend fun recordDecision(decision: ReviewDecisionEntity)
    @Query("SELECT * FROM review_decisions WHERE taskId = :taskId ORDER BY createdAtEpochMillis, id")
    suspend fun decisions(taskId: String): List<ReviewDecisionEntity>
}
