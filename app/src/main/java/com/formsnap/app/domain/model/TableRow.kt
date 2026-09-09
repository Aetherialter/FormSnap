package com.formsnap.app.domain.model

data class TableRow(
    val id: String,
    val taskId: String,
    val schemaId: String,
    val cells: List<Cell>,
    val sourceDocumentId: String? = null,
    val originalRowIndex: Int? = null,
    val excluded: Boolean = false,
) {
    init {
        require(id.isNotBlank() && taskId.isNotBlank() && schemaId.isNotBlank())
        require(cells.all { it.rowId == id })
        require(cells.map { it.id }.distinct().size == cells.size)
        require(cells.map { it.fieldId }.distinct().size == cells.size)
        require(sourceDocumentId == null || sourceDocumentId.isNotBlank())
        require(originalRowIndex == null || originalRowIndex >= 0)
        require(sourceDocumentId == null || cells.all { it.source?.documentId == sourceDocumentId })
    }
}
