package com.formsnap.app.processing

import com.formsnap.app.domain.model.SourceDocument

interface StructureRecognizer : PageRecognizer {
    suspend fun inspect(source: SourceDocument): StructureProposal
    suspend fun revise(source: SourceDocument,grid: TableGrid,text: List<TextEvidence>): StructureProposal
    override suspend fun recognize(source: SourceDocument)=inspect(source).let {
        if(it.grid.outcome!=StructureOutcome.AUTO_ACCEPTED)throw StructureReviewException(it)
        TableCandidateAssembler().candidates(it)
    }
}
