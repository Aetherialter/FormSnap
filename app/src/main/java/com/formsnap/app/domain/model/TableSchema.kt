package com.formsnap.app.domain.model

/** Ordered fields provide dynamic headers; IDs, not labels or column offsets, identify fields. */
data class TableSchema(val id: String, val taskId: String, val fields: List<FieldDefinition>) {
    init {
        require(id.isNotBlank() && taskId.isNotBlank())
        require(fields.map { it.id }.distinct().size == fields.size)
    }
}

data class FieldDefinition(val id: String, val name: String, val type: FieldType = FieldType.TEXT) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
    }
}

enum class FieldType { TEXT, INTEGER, DECIMAL, DATE, BOOLEAN }
