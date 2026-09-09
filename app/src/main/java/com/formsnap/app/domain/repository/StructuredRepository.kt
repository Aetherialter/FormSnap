package com.formsnap.app.domain.repository

import com.formsnap.app.domain.model.CandidatePage
import com.formsnap.app.domain.model.FieldDefinition
import com.formsnap.app.domain.model.StructuredDataset
import com.formsnap.app.domain.model.TableSchema
import kotlinx.coroutines.flow.Flow

interface StructuredRepository {
    fun observeDataset(taskId: String): Flow<StructuredDataset?>
    suspend fun getDataset(taskId: String): StructuredDataset?
    suspend fun createSchema(taskId: String, fields: List<FieldDefinition>, configurationConfirmed: Boolean = true): TableSchema
    suspend fun reorderFields(taskId: String, fieldIds: List<String>)
    suspend fun replacePage(taskId: String, page: CandidatePage, discardHumanWork: Boolean = false)
}

class SchemaMismatchException : IllegalArgumentException("Page headers do not match the task schema")
class HumanWorkExistsException : IllegalStateException("Reprocessing requires an explicit decision about human work")
