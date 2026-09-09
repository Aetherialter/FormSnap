package com.formsnap.app.domain.model

/** Ordered fields provide dynamic headers; IDs, not labels or column offsets, identify fields. */
data class TableSchema(val id: String, val taskId: String, val fields: List<FieldDefinition>, val configurationConfirmed: Boolean = true) {
    init {
        require(id.isNotBlank() && taskId.isNotBlank())
        require(fields.map { it.id }.distinct().size == fields.size)
    }
}

data class FieldDefinition(
    val id: String,
    val name: String,
    val type: FieldType = FieldType.TEXT,
    val rules: FieldRules = FieldRules(),
) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
    }
}

enum class FieldType { TEXT, INTEGER, DECIMAL, DATE, BOOLEAN, ID, PHONE, SIGNATURE_PRESENCE }

/** Configuration describes data quality, never business eligibility or approval. */
data class FieldRules(
    val required: Boolean = false,
    val pattern: String? = null,
    val minimum: String? = null,
    val maximum: String? = null,
    val duplicateKey: Boolean = false,
    val detectColumnOutliers: Boolean = false,
) {
    init {
        require(pattern == null || pattern.length <= 240)
        pattern?.let { com.formsnap.app.validation.FieldPattern(it) }
        val min = minimum?.toBigDecimal()
        val max = maximum?.toBigDecimal()
        require(min == null || max == null || min <= max)
    }
}
