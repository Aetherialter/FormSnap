package com.formsnap.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="page_structures", foreignKeys=[ForeignKey(entity=SourceEntity::class,
    parentColumns=["id","taskId"], childColumns=["sourceId","taskId"], onDelete=ForeignKey.CASCADE)],
    indices=[Index(value=["sourceId","taskId"]),Index(value=["taskId"])])
data class PageStructureEntity(@PrimaryKey val sourceId: String,val taskId: String,val payload: String,
    val confirmed: Boolean,val revision: Int,val discardHumanWork: Boolean)

@Dao
interface StructureDao {
    @Query("SELECT p.* FROM page_structures p JOIN source_documents s ON p.sourceId=s.id WHERE p.taskId=:taskId ORDER BY s.pageIndex")
    fun observe(taskId: String): Flow<List<PageStructureEntity>>
    @Query("SELECT * FROM page_structures WHERE sourceId=:sourceId AND taskId=:taskId")
    suspend fun get(taskId: String,sourceId: String): PageStructureEntity?
    @Query("SELECT p.* FROM page_structures p JOIN source_documents s ON p.sourceId=s.id WHERE p.taskId=:taskId AND p.confirmed=1 ORDER BY s.pageIndex LIMIT 1")
    suspend fun template(taskId: String): PageStructureEntity?
    @Upsert suspend fun save(value: PageStructureEntity)
}
