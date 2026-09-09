package com.formsnap.app.processing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.formsnap.app.FormSnapApplication
import kotlinx.coroutines.CancellationException

class FormProcessingWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString("taskId") ?: return Result.failure()
        return try {
            val request = ProcessingRequest(taskId, inputData.getString("sourceId"), inputData.getBoolean("discardHumanWork", false))
            val result = (applicationContext as FormSnapApplication).processingRunner.run(request) { completed, total ->
                setProgress(workDataOf("completed" to completed, "total" to total))
            }
            Result.success(workDataOf("successfulPages" to result.successfulPages, "failedPages" to result.failedPages, "structureReviewPages" to result.structureReviewPages))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(workDataOf("message" to "整理未完成，请重新进入任务检查后重试。"))
        }
    }
}
