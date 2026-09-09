package com.formsnap.app.processing

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.Executor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class ProcessingActivity(val busy: Boolean, val completed: Int = 0, val total: Int = 0)
interface ProcessingGateway {
    fun observe(taskId: String): Flow<ProcessingActivity>
    suspend fun enqueue(request: ProcessingRequest)
}

class ProcessingScheduler(context: Context) : ProcessingGateway {
    private val work by lazy { WorkManager.getInstance(context.applicationContext) }
    override fun observe(taskId: String) = work.getWorkInfosForUniqueWorkFlow(name(taskId)).map { entries ->
        val active = entries.firstOrNull { !it.state.isFinished }
        ProcessingActivity(active != null, active?.progress?.getInt("completed", 0) ?: 0, active?.progress?.getInt("total", 0) ?: 0)
    }

    override suspend fun enqueue(request: ProcessingRequest) {
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
