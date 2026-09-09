package com.formsnap.app.processing

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.Executor
import kotlinx.coroutines.suspendCancellableCoroutine

class ProcessingScheduler(context: Context) {
    private val work by lazy { WorkManager.getInstance(context.applicationContext) }
    fun observe(taskId: String) = work.getWorkInfosForUniqueWorkFlow(name(taskId))

    suspend fun enqueue(request: ProcessingRequest) {
        val item = OneTimeWorkRequestBuilder<FormProcessingWorker>().setInputData(workDataOf(
            "taskId" to request.taskId, "sourceId" to request.sourceId, "discardHumanWork" to request.discardHumanWork,
        )).build()
        val operation = work.enqueueUniqueWork(name(request.taskId), ExistingWorkPolicy.KEEP, item)
        suspendCancellableCoroutine<Unit> { continuation ->
            operation.result.addListener({
                val result = runCatching { operation.result.get(); Unit }
                if (continuation.isActive) continuation.resumeWith(result)
            }, Executor { it.run() })
        }
    }

    private fun name(taskId: String) = "formsnap-processing-$taskId"
}
