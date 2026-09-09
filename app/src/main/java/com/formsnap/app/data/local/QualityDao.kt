package com.formsnap.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert

@Dao
interface QualityDao {
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
    @Query("UPDATE table_rows SET excluded = 1 WHERE id = :rowId AND taskId = :taskId")
    suspend fun excludeRow(taskId: String, rowId: String): Int
    @Insert suspend fun recordDecision(decision: ReviewDecisionEntity)
    @Query("SELECT * FROM review_decisions WHERE taskId = :taskId ORDER BY createdAtEpochMillis, id")
    suspend fun decisions(taskId: String): List<ReviewDecisionEntity>
}
