package com.formsnap.app.domain.model

/** Coordinates always address the original, upright source page, not a temporary crop. */
data class CandidateCell(
    val rawValue: String?,
    val region: SourceRegion,
    val reliability: RecognitionReliability = RecognitionReliability.UNKNOWN,
)

data class CandidateRow(val originalRowIndex: Int, val cells: List<CandidateCell>) {
    init { require(originalRowIndex >= 0) }
}

/** Only a successful structural extraction may produce this contract. Failed pages have no rows. */
data class CandidatePage(
    val sourceDocumentId: String,
    val headers: List<String>,
    val rows: List<CandidateRow>,
) {
    init {
        require(sourceDocumentId.isNotBlank())
        require(headers.isNotEmpty() && headers.all { it.isNotBlank() })
        require(rows.isNotEmpty())
        require(rows.map { it.originalRowIndex }.distinct().size == rows.size)
        require(rows.all { it.cells.size == headers.size })
    }
}

data class StructuredDataset(val schema: TableSchema, val rows: List<TableRow>) {
    init {
        val fields = schema.fields.map { it.id }.toSet()
        require(rows.map { it.id }.distinct().size == rows.size)
        require(rows.flatMap { it.cells }.map { it.id }.distinct().size == rows.sumOf { it.cells.size })
        require(rows.all { row ->
            row.taskId == schema.taskId && row.schemaId == schema.id &&
                row.cells.map { it.fieldId }.toSet() == fields
        })
    }
}
