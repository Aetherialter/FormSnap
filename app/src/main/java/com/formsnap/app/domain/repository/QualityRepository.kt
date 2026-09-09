package com.formsnap.app.domain.repository

import com.formsnap.app.domain.model.FieldDefinition
import com.formsnap.app.domain.model.StructuredDataset
import com.formsnap.app.domain.model.TaskCounts
import com.formsnap.app.review.ReviewDecision
import com.formsnap.app.validation.QualitySnapshot
import kotlinx.coroutines.flow.Flow

interface QualityRepository {
    fun observeCounts(): Flow<List<TaskCounts>>
    fun observeQuality(taskId: String): Flow<QualitySnapshot>
    suspend fun revalidate(taskId: String): QualitySnapshot
    suspend fun updateFields(taskId: String, fields: List<FieldDefinition>)
    suspend fun decide(taskId: String, decision: ReviewDecision)
    suspend fun exportableDataset(taskId: String): StructuredDataset
}
