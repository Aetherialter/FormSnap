package com.formsnap.app.data

import androidx.room.withTransaction
import com.formsnap.app.data.local.*
import com.formsnap.app.domain.model.*
import com.formsnap.app.domain.repository.HumanWorkExistsException
import com.formsnap.app.domain.repository.SchemaMismatchException
import com.formsnap.app.domain.repository.StructuredRepository
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.flow.map
import org.json.JSONArray

class RoomStructuredRepository(
    private val database: FormSnapDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val nextId: () -> String = { UUID.randomUUID().toString() },
) : StructuredRepository {
    private val dao = database.structuredDao()

    override fun observeDataset(taskId: String) = database.invalidationTracker.createFlow(
        "table_schemas", "field_definitions", "table_rows", "cells", "source_documents",
    ).map { getDataset(taskId) }

    override suspend fun getDataset(taskId: String): StructuredDataset? = database.withTransaction {
        val schema = dao.schema(taskId) ?: return@withTransaction null
        val fields = dao.fields(taskId)
        val cells = dao.cells(taskId).groupBy { it.rowId }
        StructuredDataset(
            TableSchema(schema.id, taskId, fields.map { it.toDomain() }, schema.configurationConfirmed),
            dao.rows(taskId).map { row ->
                val byField = cells[row.id].orEmpty().associateBy { it.fieldId }
                TableRow(
                    row.id, taskId, schema.id,
                    fields.map { field -> checkNotNull(byField[field.id]).toDomain(row.originalRowIndex) },
                    row.sourceDocumentId, row.originalRowIndex, row.excluded,
                )
            },
        )
    }

    override suspend fun createSchema(taskId: String, fields: List<FieldDefinition>, configurationConfirmed: Boolean): TableSchema = database.withTransaction {
        checkNotNull(database.taskDao().getTask(taskId))
        check(dao.schema(taskId) == null) { "A task already owns a schema" }
        require(fields.isNotEmpty())
        val schema = TableSchema(nextId(), taskId, fields.toList(), configurationConfirmed)
        dao.insertSchema(SchemaEntity(schema.id, taskId, configurationConfirmed))
        dao.insertFields(fields.mapIndexed { index, field -> field.toEntity(schema.id, taskId, index) })
        schema
    }

    override suspend fun reorderFields(taskId: String, fieldIds: List<String>) = database.withTransaction {
        val fields = dao.fields(taskId)
        require(fieldIds.size == fields.size && fieldIds.toSet() == fields.map { it.id }.toSet())
        // Vacate current positions before assigning the requested permutation.
        fields.forEachIndexed { index, field -> dao.fieldPosition(taskId, field.id, -index - 1) }
        fieldIds.forEachIndexed { index, id -> dao.fieldPosition(taskId, id, index) }
    }

    override suspend fun replacePage(taskId: String, page: CandidatePage, discardHumanWork: Boolean) = database.withTransaction {
        val schema = checkNotNull(dao.schema(taskId)) { "Create the task schema before storing candidates" }
        val source = database.sourceDao().getSources(taskId).singleOrNull { it.id == page.sourceDocumentId }
        require(source != null && source.status == SourceStatus.AVAILABLE.name) { "Source must belong to this task and be available" }
        val fields = dao.fields(taskId).sortedBy { it.sourceColumnIndex }
        if (fields.map { it.sourceHeader.trim() } != page.headers.map { it.trim() }) throw SchemaMismatchException()
        val oldRows = dao.rows(taskId).filter { it.sourceDocumentId == source.id }
        val oldCells = dao.cells(taskId).filter { it.sourceDocumentId == source.id }
        if (!discardHumanWork && (oldRows.any { it.excluded } || oldCells.any {
                it.reviewStatus == CellReviewStatus.MANUAL_CONFIRMED.name || it.reviewStatus == CellReviewStatus.UNREADABLE.name
            })) throw HumanWorkExistsException()
        val oldByIndex = oldRows.associateBy { it.originalRowIndex }
        val oldCellIds = oldCells.associate { (it.rowId to it.fieldId) to it.id }
        val rows = page.rows.sortedBy { it.originalRowIndex }.map { candidate ->
            RowEntity(oldByIndex[candidate.originalRowIndex]?.id ?: nextId(), taskId, schema.id, source.id, candidate.originalRowIndex, false)
        }
        val candidates = page.rows.associateBy { it.originalRowIndex }
        val cells = rows.flatMap { row ->
            candidates.getValue(row.originalRowIndex).cells.mapIndexed { index, candidate ->
                val field = fields[index]
                CellEntity(
                    oldCellIds[row.id to field.id] ?: nextId(), row.id, field.id, schema.id, taskId, source.id, index,
                    candidate.rawValue, null, null, CellReviewStatus.UNVALIDATED.name, candidate.reliability.name,
                    candidate.region.left, candidate.region.top, candidate.region.right, candidate.region.bottom,
                )
            }
        }
        dao.deletePageRows(taskId, source.id)
        database.qualityDao().invalidatePageIssues(taskId, source.id, oldCells.map { it.id })
        dao.insertRows(rows)
        dao.insertCells(cells)
        database.qualityDao().savePage(PageResultEntity(source.id, taskId, "SUCCEEDED", null, null, clock.millis()))
        // Candidate completion is not validation completion or readiness to export.
        database.taskDao().updateSourceCollection(taskId, TaskStatus.PROCESSING.name, clock.millis())
    }
}

internal fun FieldEntity.toDomain() = FieldDefinition(id, name, FieldType.valueOf(type), FieldRules(
    required, pattern, minimum, maximum, duplicateKey, detectColumnOutliers,
), JSONArray(headerPath).let { array -> if(array.length()==0)listOf(sourceHeader) else (0 until array.length()).map { array.getString(it) } })

internal fun FieldDefinition.toEntity(schemaId: String, taskId: String, index: Int) = FieldEntity(
    id, schemaId, taskId, index, name, type.name, rules.required, rules.pattern, rules.minimum,
    rules.maximum, rules.duplicateKey, rules.detectColumnOutliers, index, name, JSONArray(headerPath).toString(),
)

internal fun CellEntity.toDomain(rowIndex: Int) = Cell(
    id, rowId, fieldId, rawValue, normalizedValue, confirmedValue, CellReviewStatus.valueOf(reviewStatus),
    CellSource(sourceDocumentId, rowIndex, originalColumnIndex, SourceRegion(regionLeft, regionTop, regionRight, regionBottom)),
    RecognitionReliability.valueOf(reliability),
)
