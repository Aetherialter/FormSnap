package com.formsnap.app.processing

import kotlinx.coroutines.flow.Flow

data class StoredStructure(val proposal: StructureProposal,val confirmed: Boolean,val revision: Int)
interface StructureRepository {
    fun observe(taskId: String): Flow<List<StoredStructure>>
    suspend fun saveDraft(taskId: String,sourceId: String,grid: TableGrid,paths: List<List<String>>,revision: Int): StoredStructure
    suspend fun confirm(taskId: String,sourceId: String,grid: TableGrid,paths: List<List<String>>,revision: Int,discardHumanWork: Boolean=false)
}
