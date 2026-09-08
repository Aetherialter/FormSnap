package com.formsnap.app.data

import androidx.room.withTransaction
import com.formsnap.app.data.local.FormSnapDatabase
import com.formsnap.app.data.local.SourceEntity
import com.formsnap.app.data.local.toDomain
import com.formsnap.app.data.source.SourceAccess
import com.formsnap.app.data.source.SourceAccessException
import com.formsnap.app.domain.model.SourceStatus
import com.formsnap.app.domain.model.TaskStatus
import com.formsnap.app.domain.repository.SourceImportFailure
import com.formsnap.app.domain.repository.SourceImportResult
import com.formsnap.app.domain.repository.SourceRejection
import com.formsnap.app.domain.repository.SourceRemovalResult
import com.formsnap.app.domain.repository.SourceRepository
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One application-scoped instance serializes URI grant ownership and database mutations. */
class RoomSourceRepository(
    private val database: FormSnapDatabase,
    private val access: SourceAccess,
    private val clock: Clock = Clock.systemUTC(),
    private val nextId: () -> String = { UUID.randomUUID().toString() },
) : SourceRepository {
    private val sources = database.sourceDao()
    private val tasks = database.taskDao()
    private val operations = Mutex()

    override fun observeSources(taskId: String) = sources.observeSources(taskId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun addSources(taskId: String, uris: List<String>): SourceImportResult = operations.withLock {
        if (uris.isEmpty()) return@withLock SourceImportResult(0, 0, emptyList())
        requireEditableTask(taskId)
        cleanupUnusedGrants()
        val existing = sources.getSources(taskId).map { it.sourceUri }.toSet()
        val unique = uris.distinct()
        val duplicates = uris.size - unique.size + unique.count { it in existing }
        val rejected = mutableListOf<SourceRejection>()
        val accepted = mutableListOf<SourceEntity>()
        var cleanupPending: Boolean
        try {
            for (uri in unique.filterNot { it in existing }) {
                try {
                    access.retainRead(uri)
                    val metadata = access.inspectImage(uri)
                    accepted += SourceEntity(
                        nextId(), taskId, uri, 0, SourceStatus.AVAILABLE.name,
                        clock.millis(), metadata.displayName,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: SourceAccessException) {
                    rejected += SourceRejection(uri, failure.reason)
                } catch (_: Exception) {
                    rejected += SourceRejection(uri, SourceImportFailure.UNREADABLE)
                }
            }
            // Android may evict older grants while a large selection takes new ones. Verify the
            // final set, rather than treating each earlier successful take as permanently valid.
            val durable = access.persistedReadUris()
            accepted.filter { it.sourceUri !in durable }.forEach {
                rejected += SourceRejection(it.sourceUri, SourceImportFailure.PERMISSION_NOT_RETAINED)
            }
            accepted.removeAll { it.sourceUri !in durable }
            database.withTransaction {
                requireEditableTask(taskId)
                val current = sources.getSources(taskId)
                for (source in current) {
                    if (source.sourceUri !in durable && source.status != SourceStatus.UNAVAILABLE.name) {
                        sources.updateStatus(source.id, SourceStatus.UNAVAILABLE.name)
                    }
                }
                if (accepted.isNotEmpty()) {
                    sources.insert(accepted.mapIndexed { index, row -> row.copy(pageIndex = current.size + index) })
                    touchTask(taskId, hasSources = true)
                }
            }
        } finally {
            // A failed/cancelled transaction must not leave newly taken, unreferenced grants behind.
            // Referenced grants (including other tasks) are never released. Retry failures next time.
            cleanupPending = withContext(NonCancellable) { !cleanupUnusedGrants() }
        }
        SourceImportResult(accepted.size, duplicates, rejected, cleanupPending)
    }

    override suspend fun removeSource(taskId: String, sourceId: String): SourceRemovalResult = operations.withLock {
        val removed = database.withTransaction {
            requireEditableTask(taskId)
            val deleted = sources.delete(taskId, sourceId) > 0
            if (deleted) {
                val remaining = sources.getSources(taskId)
                // Ascending updates move into the just-freed position without unique-index collisions.
                remaining.forEachIndexed { index, source ->
                    if (source.pageIndex != index) sources.updatePosition(source.id, index)
                }
                touchTask(taskId, remaining.isNotEmpty())
            }
            deleted
        }
        val cleaned = withContext(NonCancellable) { cleanupUnusedGrants() }
        SourceRemovalResult(removed, permissionCleanupPending = !cleaned)
    }

    override suspend fun refreshAvailability(taskId: String) = operations.withLock {
        val persisted = access.persistedReadUris()
        for (source in sources.getSources(taskId)) {
            val available = if (source.sourceUri !in persisted) false else try {
                access.inspectImage(source.sourceUri)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            val status = if (available) SourceStatus.AVAILABLE else SourceStatus.UNAVAILABLE
            if (source.status != status.name) sources.updateStatus(source.id, status.name)
        }
        cleanupUnusedGrants()
        Unit
    }

    private suspend fun requireEditableTask(taskId: String) {
        val task = checkNotNull(tasks.getTask(taskId)) { "Task does not exist" }
        check(task.status == TaskStatus.DRAFT.name || task.status == TaskStatus.CAPTURING.name) {
            "Task is not collecting sources"
        }
    }

    private suspend fun touchTask(taskId: String, hasSources: Boolean) {
        tasks.updateSourceCollection(
            taskId,
            if (hasSources) TaskStatus.CAPTURING.name else TaskStatus.DRAFT.name,
            clock.millis(),
        )
    }

    /** All persistable read grants in this app belong to source documents. Also recovers crash orphans. */
    private suspend fun cleanupUnusedGrants(): Boolean = try {
        val referenced = sources.referencedUris().toSet()
        var complete = true
        for (uri in access.persistedReadUris() - referenced) {
            try {
                access.releaseRead(uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                complete = false
            }
        }
        complete
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}
