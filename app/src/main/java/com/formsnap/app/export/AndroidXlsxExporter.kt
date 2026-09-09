package com.formsnap.app.export

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import com.formsnap.app.data.RoomQualityRepository
import com.formsnap.app.data.RoomStructuredRepository
import com.formsnap.app.data.local.FormSnapDatabase
import com.formsnap.app.domain.model.TaskStatus
import com.formsnap.app.domain.repository.SourceRepository
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface TaskExporter {
    suspend fun checkReady(taskId: String)
    suspend fun export(taskId: String, uri: String): ExportOutcome
}
enum class ExportOutcome { EXPORTED, DATA_CHANGED }

class AndroidXlsxExporter(
    private val database: FormSnapDatabase,
    private val resolver: ContentResolver,
    private val sources: SourceRepository,
    private val clock: Clock = Clock.systemUTC(),
) : TaskExporter {
    override suspend fun checkReady(taskId: String) {
        sources.refreshAvailability(taskId)
        RoomQualityRepository(database).exportableDataset(taskId)
    }

    override suspend fun export(taskId: String, uri: String): ExportOutcome = withContext(Dispatchers.IO) {
        val quality = RoomQualityRepository(database)
        checkReady(taskId)
        val (snapshot, decisions) = database.withTransaction {
            quality.exportableDataset(taskId) to database.qualityDao().decisions(taskId)
        }
        val cells = snapshot.rows.flatMap { row -> row.cells.map { it.id to (row to it) } }.toMap()
        val fields = snapshot.schema.fields.associateBy { it.id }
        val reviews = decisions.map { decision ->
            val pair = cells[decision.targetId]
            ExportReviewRecord(
                pair?.first?.let { (snapshot.rows.indexOf(it) + 1).toString() } ?: decision.targetId,
                pair?.second?.let { fields[it.fieldId]?.name }.orEmpty(), decision.rawValue, decision.finalValue,
                decision.issueCodes, decision.action,
            )
        }
        val bytes = ByteArrayOutputStream().use { output -> XlsxWriter().write(snapshot, reviews, output); output.toByteArray() }
        val target = Uri.parse(uri)
        require(target.scheme == ContentResolver.SCHEME_CONTENT)
        currentCoroutineContext().ensureActive()
        resolver.openOutputStream(target, "wt")?.use { stream -> stream.write(bytes); stream.flush() }
            ?: throw IOException("Export destination cannot be opened")
        currentCoroutineContext().ensureActive()
        database.withTransaction {
            val current = quality.revalidate(taskId)
            if (current.dataset == snapshot && database.taskDao().getTask(taskId)!!.status in setOf(TaskStatus.READY_TO_EXPORT.name, TaskStatus.EXPORTED.name)) {
                database.taskDao().updateSourceCollection(taskId, TaskStatus.EXPORTED.name, clock.millis())
                ExportOutcome.EXPORTED
            } else ExportOutcome.DATA_CHANGED
        }
    }
}
