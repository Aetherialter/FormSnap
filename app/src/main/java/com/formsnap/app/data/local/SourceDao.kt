package com.formsnap.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM source_documents WHERE taskId = :taskId ORDER BY pageIndex ASC")
    fun observeSources(taskId: String): Flow<List<SourceEntity>>

    @Query("SELECT * FROM source_documents WHERE taskId = :taskId ORDER BY pageIndex ASC")
    suspend fun getSources(taskId: String): List<SourceEntity>

    @Query("SELECT DISTINCT sourceUri FROM source_documents")
    suspend fun referencedUris(): List<String>

    @Insert
    suspend fun insert(sources: List<SourceEntity>)

    @Query("DELETE FROM source_documents WHERE id = :sourceId AND taskId = :taskId")
    suspend fun delete(taskId: String, sourceId: String): Int

    @Query("UPDATE source_documents SET pageIndex = :position WHERE id = :id")
    suspend fun updatePosition(id: String, position: Int)

    @Query("UPDATE source_documents SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)
}
