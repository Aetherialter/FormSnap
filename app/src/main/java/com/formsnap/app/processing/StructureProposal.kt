package com.formsnap.app.processing

import com.formsnap.app.domain.model.CandidateCell

data class StructureProposal(
    val sourceId: String,
    val grid: TableGrid,
    val text: List<TextEvidence>,
    val logicalCells: List<LogicalCell>,
    val headerPaths: List<List<String>>,
    val assignmentErrors: Int,
)
data class LogicalCell(val shape: GridCell, val candidate: CandidateCell)
class StructureReviewException(val proposal: StructureProposal) : Exception("Structure needs confirmation")
