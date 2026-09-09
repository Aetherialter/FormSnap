package com.formsnap.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface StructuredDao {
    @Query("SELECT * FROM table_schemas WHERE taskId = :taskId")
    suspend fun schema(taskId: String): SchemaEntity?

    @Query("SELECT * FROM field_definitions WHERE taskId = :taskId ORDER BY position")
    suspend fun fields(taskId: String): List<FieldEntity>

    @Query("SELECT r.* FROM table_rows r JOIN source_documents s ON s.id = r.sourceDocumentId WHERE r.taskId = :taskId ORDER BY s.pageIndex, r.originalRowIndex")
    suspend fun rows(taskId: String): List<RowEntity>

    @Query("SELECT * FROM cells WHERE taskId = :taskId")
    suspend fun cells(taskId: String): List<CellEntity>

    @Insert suspend fun insertSchema(schema: SchemaEntity)
    @Insert suspend fun insertFields(fields: List<FieldEntity>)
    @Insert suspend fun insertRows(rows: List<RowEntity>)
    @Insert suspend fun insertCells(cells: List<CellEntity>)

    @Query("DELETE FROM table_rows WHERE taskId = :taskId AND sourceDocumentId = :sourceId")
    suspend fun deletePageRows(taskId: String, sourceId: String)

    @Query("UPDATE field_definitions SET position = :position WHERE id = :id AND taskId = :taskId")
    suspend fun fieldPosition(taskId: String, id: String, position: Int)
}
