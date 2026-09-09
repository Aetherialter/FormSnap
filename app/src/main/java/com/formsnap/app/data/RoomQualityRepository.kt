package com.formsnap.app.data

import androidx.room.withTransaction
import com.formsnap.app.data.local.*
import com.formsnap.app.domain.model.*
import com.formsnap.app.domain.repository.QualityRepository
import com.formsnap.app.review.*
import com.formsnap.app.validation.*
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.flow.map
import org.json.JSONArray

class RoomQualityRepository(
    private val database: FormSnapDatabase,
    private val engine: ValidationEngine = ValidationEngine(),
    private val clock: Clock = Clock.systemUTC(),
) : QualityRepository {
    private val dao = database.qualityDao()
    private val structured = RoomStructuredRepository(database)
    override fun observeCounts() = dao.observeCounts()

    override fun observeQuality(taskId: String) = database.invalidationTracker.createFlow(
        "table_schemas", "field_definitions", "table_rows", "cells", "source_documents", "validation_issues", "page_results",
    ).map { database.withTransaction { QualitySnapshot(structured.getDataset(taskId), dao.issues(taskId).map { it.toDomain() }) } }

    override suspend fun revalidate(taskId: String): QualitySnapshot = database.withTransaction {
        checkNotNull(database.taskDao().getTask(taskId))
        val dataset = structured.getDataset(taskId)
        val sources = database.sourceDao().getSources(taskId)
        val pages = dao.pages(taskId).associateBy { it.sourceId }
        val problems = sources.mapNotNull { source ->
            val page = pages[source.id]
            when {
                source.status == SourceStatus.UNAVAILABLE.name -> PageProblem(source.id, IssueCode.SOURCE_UNAVAILABLE, "来源页面当前无法读取，请恢复访问或移除后重新添加。")
                page?.state != "SUCCEEDED" -> PageProblem(source.id, page?.problemCode?.let { IssueCode.valueOf(it) } ?: IssueCode.STRUCTURE_WARNING,
                    page?.message ?: "此页尚未完成整理，请开始或重新执行整理。")
                else -> null
            }
        }
        val analyzed = engine.validate(dataset ?: StructuredDataset(TableSchema("pending", taskId, emptyList()), emptyList()), problems)
        val prior = dao.issues(taskId).associateBy { it.id }
        val merged = analyzed.issues.map { fresh ->
            val old = prior[fresh.id]
            if (old?.active == true && old.fingerprint == fresh.fingerprint) fresh.copy(status = IssueStatus.valueOf(old.status)) else fresh
        }
        val freshIds = merged.map { it.id }.toSet()
        // Retain disappeared issues as resolved history. Decisions preserve before/after values separately.
        val archived = prior.values.filter { it.id !in freshIds }.map { it.copy(status = IssueStatus.RESOLVED.name, active = false) }
        dao.clearIssues(taskId)
        dao.insertIssues(merged.map { it.toEntity() } + archived)
        val changed = analyzed.cells.associateBy { it.id }
        val storedCells = database.structuredDao().cells(taskId)
        dao.updateCells(storedCells.map { stored ->
            val cell = changed[stored.id] ?: return@map stored
            stored.copy(normalizedValue = cell.normalizedValue, confirmedValue = cell.confirmedValue, reviewStatus = cell.reviewStatus.name)
        })
        val activeRows = dataset?.rows.orEmpty().filterNot { it.excluded }
        val unresolved = merged.any { it.status != IssueStatus.RESOLVED }
        val ready = dataset != null && dataset.schema.fields.isNotEmpty() && activeRows.isNotEmpty() && sources.isNotEmpty() &&
            !unresolved && analyzed.cells.all { it.confirmedValue != null && it.reviewStatus in setOf(CellReviewStatus.AUTO_CONFIRMED, CellReviewStatus.MANUAL_CONFIRMED) }
        val oldStatus = database.taskDao().getTask(taskId)!!.status
        val status = when {
            pages.values.any { it.state == "RUNNING" } -> TaskStatus.PROCESSING
            ready -> if (oldStatus == TaskStatus.EXPORTED.name) TaskStatus.EXPORTED else TaskStatus.READY_TO_EXPORT
            sources.isEmpty() -> TaskStatus.DRAFT
            dataset == null && problems.isEmpty() -> TaskStatus.CAPTURING
            else -> TaskStatus.REVIEW_REQUIRED
        }
        if (oldStatus != status.name) database.taskDao().updateSourceCollection(taskId, status.name, clock.millis())
        QualitySnapshot(structured.getDataset(taskId), dao.issues(taskId).map { it.toDomain() })
    }

    override suspend fun updateFields(taskId: String, fields: List<FieldDefinition>) = database.withTransaction {
        check(dao.pages(taskId).none { it.state == "RUNNING" })
        val existing = database.structuredDao().fields(taskId)
        require(fields.size == existing.size && fields.map { it.id }.toSet() == existing.map { it.id }.toSet())
        val byId = fields.associateBy { it.id }
        dao.updateFields(existing.map { field ->
            val changed = byId.getValue(field.id)
            changed.toEntity(field.schemaId, taskId, field.position).copy(sourceColumnIndex = field.sourceColumnIndex, sourceHeader = field.sourceHeader)
        })
        dao.confirmConfiguration(taskId)
        invalidateExport(taskId)
        revalidate(taskId)
        Unit
    }

    override suspend fun decide(taskId: String, decision: ReviewDecision) = database.withTransaction {
        check(dao.pages(taskId).none { it.state == "RUNNING" }) { "Wait until page processing finishes" }
        val snapshot = revalidate(taskId)
        val issues = snapshot.issues.filter { it.target == decision.target && it.targetId == decision.targetId && it.status != IssueStatus.RESOLVED }
        if (decision.expectedRevision != null && decision.expectedRevision != revisionOf(issues)) throw StaleReviewException()
        val dataset = checkNotNull(snapshot.dataset)
        var raw: String? = null
        var previous: String? = null
        var final: String? = null
        when (decision.target) {
            IssueTarget.CELL -> {
                val stored = database.structuredDao().cells(taskId).single { it.id == decision.targetId }
                val row = dataset.rows.single { it.id == stored.rowId }
                check(!row.excluded)
                raw = stored.rawValue
                val cell = stored.toDomain(row.originalRowIndex!!)
                previous = ValidationEngine.value(cell)
                if (decision.expectedCellValue != null && decision.expectedCellValue != previous) throw StaleReviewException()
                check(decision.action in setOf(ReviewAction.CONFIRM, ReviewAction.EDIT, ReviewAction.KEEP, ReviewAction.UNREADABLE))
                final = when (decision.action) {
                    ReviewAction.EDIT -> requireNotNull(decision.editedValue).also { require(it.length <= 32767) }
                    ReviewAction.UNREADABLE -> null
                    else -> previous
                }
                dao.updateCells(listOf(stored.copy(confirmedValue = final,
                    reviewStatus = if (decision.action == ReviewAction.UNREADABLE) CellReviewStatus.UNREADABLE.name else CellReviewStatus.MANUAL_CONFIRMED.name)))
            }
            IssueTarget.ROW_GROUP -> {
                check(issues.isNotEmpty()) { "This duplicate group is no longer pending" }
                val rows = issues.flatMap { it.relatedRowIds }.toSet()
                check(decision.action in setOf(ReviewAction.KEEP_RECORDS, ReviewAction.DISTINCT_RECORDS, ReviewAction.EXCLUDE_RECORD))
                if (decision.action == ReviewAction.EXCLUDE_RECORD) {
                    require(decision.editedValue in rows)
                    check(dao.excludeRow(taskId, decision.editedValue!!) == 1)
                }
                previous = rows.sorted().joinToString(",")
                final = decision.editedValue
            }
            IssueTarget.SOURCE, IssueTarget.SCHEMA -> error("Page or configuration problems require their specific corrective actions")
        }
        dao.recordDecision(ReviewDecisionEntity(UUID.randomUUID().toString(), taskId, decision.target.name, decision.targetId,
            decision.action.name, raw, previous, final, issues.map { it.code.name }.distinct().joinToString(","), clock.millis()))
        invalidateExport(taskId)
        revalidate(taskId)
        // Confirm/keep explicitly accept the displayed value. An edit must not silently acknowledge
        // fresh format/range/required problems introduced by the new value. Unreadable stays blocking.
        val current = dao.issues(taskId)
        dao.clearIssues(taskId)
        dao.insertIssues(current.map { issue ->
            if (issue.target == decision.target.name && issue.targetId == decision.targetId && decision.action != ReviewAction.EDIT) issue.copy(
                status = if (decision.action == ReviewAction.UNREADABLE) IssueStatus.UNCONFIRMABLE.name else IssueStatus.RESOLVED.name,
            ) else issue
        })
        revalidate(taskId)
        Unit
    }

    override suspend fun exportableDataset(taskId: String): StructuredDataset {
        val ready = database.withTransaction {
            val snapshot = revalidate(taskId)
            if (database.taskDao().getTask(taskId)!!.status in setOf(TaskStatus.READY_TO_EXPORT.name, TaskStatus.EXPORTED.name) &&
                snapshot.issues.none { it.status != IssueStatus.RESOLVED }) snapshot.dataset else null
        }
        // A denied export must still commit the newly discovered blocking state.
        return checkNotNull(ready) { "Important issues or unfinished pages prevent standard export" }
    }

    private suspend fun invalidateExport(taskId: String) {
        database.taskDao().updateSourceCollection(taskId, TaskStatus.REVIEW_REQUIRED.name, clock.millis())
    }
}

internal fun IssueEntity.toDomain(): ValidationIssue {
    val rows = JSONArray(relatedRowIds)
    return ValidationIssue(id, taskId, IssueScope.valueOf(scope), IssueTarget.valueOf(target), targetId, IssueCode.valueOf(code),
        fingerprint, message, (0 until rows.length()).map { rows.getString(it) }, IssueStatus.valueOf(status), active)
}

internal fun ValidationIssue.toEntity() = IssueEntity(id, taskId, scope.name, target.name, targetId, code.name,
    fingerprint, message, JSONArray(relatedRowIds).toString(), status.name, active)
