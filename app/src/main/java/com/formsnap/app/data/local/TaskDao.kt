package com.formsnap.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM digitization_tasks ORDER BY updatedAtEpochMillis DESC, id ASC")
    fun observeTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM digitization_tasks WHERE id = :id")
    fun observeTask(id: String): Flow<TaskEntity?>

    @Insert
    suspend fun insert(task: TaskEntity)

    @Query("SELECT * FROM digitization_tasks WHERE id = :id")
    suspend fun getTask(id: String): TaskEntity?

    @Query("UPDATE digitization_tasks SET status = :status, updatedAtEpochMillis = MAX(updatedAtEpochMillis, :now) WHERE id = :id")
    suspend fun updateSourceCollection(id: String, status: String, now: Long)
}
