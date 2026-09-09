package com.formsnap.app.processing

import com.formsnap.app.domain.model.*
import com.formsnap.app.validation.IssueCode

data class TextEvidence(val text: String,val region: SourceRegion,val confidence: Float)

/** Geometry owns logical cells; OCR supplies text and reliability, including for merged cells. */
class TableCandidateAssembler {
    fun propose(sourceId: String,image: GrayImage,grid: TableGrid,text: List<TextEvidence>, inferHeader: Boolean=true): StructureProposal {
        val shapes=grid.cells
        val assigned=mutableMapOf<GridCell,MutableList<TextEvidence>>()
        val uncertain=mutableSetOf<GridCell>(); var errors=0
        for(word in text) {
            val center=grid.fromImage(TablePoint((word.region.left+word.region.right)/2,(word.region.top+word.region.bottom)/2))
            val cell=shapes.firstOrNull { center.x>=grid.xs[it.column] && center.x<=grid.xs[it.column+it.colSpan] &&
                center.y>=grid.ys[it.row] && center.y<=grid.ys[it.row+it.rowSpan] } ?: continue // Titles/metadata outside the selected table.
            val corners=listOf(TablePoint(word.region.left,word.region.top),TablePoint(word.region.right,word.region.top),
                TablePoint(word.region.right,word.region.bottom),TablePoint(word.region.left,word.region.bottom)).map(grid::fromImage)
            if(corners.any { it.x<grid.xs[cell.column]-3 || it.x>grid.xs[cell.column+cell.colSpan]+3 ||
                    it.y<grid.ys[cell.row]-3 || it.y>grid.ys[cell.row+cell.rowSpan]+3 }) { errors++; uncertain+=cell }
            assigned.getOrPut(cell){mutableListOf()}+=word
        }
        val logical=shapes.map { cell ->
            val words=assigned[cell].orEmpty().sortedWith(compareBy<TextEvidence> { ((it.region.top+it.region.bottom)*image.height/16).toInt() }.thenBy { it.region.left })
            val raw=joinWords(words); val region=grid.region(cell); val ink=image.ink(region)
            val reliability=when {
                raw.isEmpty() && ink>.008 -> RecognitionReliability.UNREADABLE
                raw.isEmpty() -> RecognitionReliability.HIGH
                cell in uncertain || ink>.35 || words.any { !it.confidence.isFinite() || it.confidence<.90f } -> RecognitionReliability.LOW
                else -> RecognitionReliability.HIGH
            }
            LogicalCell(cell,CandidateCell(raw,region,reliability))
        }
        var depth=grid.headerEnd
        val reasons=grid.reasons.toMutableList()
        if(inferHeader) {
            // Text may suggest the header depth, but never creates or shifts a separator.
            val firstData=(1..minOf(6,grid.rows-1)).firstOrNull { row -> logical.any { it.shape.row==row && it.candidate.rawValue.orEmpty().any(Char::isDigit) } }
            if(firstData!=null && firstData>depth)depth=firstData
            if(depth>1 || firstData==null)reasons+="表头层级或数据起始行需要核对。"
        }
        if(grid.merged.any { it.row<depth && it.row+it.rowSpan>depth })reasons+="合并区域跨越表头与数据区，请调整表头范围。"
        if(errors>0)reasons+="部分文字靠近或跨越边线，确认归属后仍需核对其值。"
        val paths=(0 until grid.columns).map { col ->
            logical.filter { it.shape.contains(it.shape.row,col) && it.shape.row<depth && it.shape.row+it.shape.rowSpan>grid.headerStart }
                .sortedBy { it.shape.row }.mapNotNull { it.candidate.rawValue?.replace("\n","")?.trim()?.takeIf(String::isNotEmpty) }.distinct()
                .ifEmpty { listOf("第${col+1}列") }
        }
        if(logical.any { it.shape.row<depth && it.candidate.reliability!=RecognitionReliability.HIGH } ||
            paths.any { it.singleOrNull()?.matches(Regex("第[0-9]+列"))==true })reasons+="部分表头文字不清楚，请在字段设置中核对名称。"
        val resultGrid=grid.copy(headerEnd=depth,outcome=if(reasons.isEmpty())StructureOutcome.AUTO_ACCEPTED else StructureOutcome.STRUCTURE_REVIEW_REQUIRED,reasons=reasons.distinct())
        return StructureProposal(sourceId,resultGrid,text,logical,paths,errors)
    }

    fun assemble(sourceId: String,image: GrayImage,grid: TableGrid,text: List<TextEvidence>): CandidatePage {
        val proposal=propose(sourceId,image,grid,text)
        if(proposal.grid.outcome!=StructureOutcome.AUTO_ACCEPTED)throw StructureReviewException(proposal)
        return candidates(proposal)
    }

    /** Caller must persist explicit structure confirmation before using an ambiguous proposal. */
    fun candidates(proposal: StructureProposal): CandidatePage {
        val grid=proposal.grid
        require(grid.merged.none { it.row<grid.headerEnd && it.row+it.rowSpan>grid.headerEnd }) { "Header must not cut through a merged cell" }
        val rows=(grid.headerEnd until grid.rows).mapNotNull { r ->
            val cells=(0 until grid.columns).map { c ->
                val logical=proposal.logicalCells.single { it.shape.contains(r,c) }
                val shape=logical.shape
                if(shape.rowSpan>1 || shape.colSpan>1)logical.candidate.copy(
                    rawValue=if(shape.row==r && shape.column==c)logical.candidate.rawValue else "",
                    reliability=RecognitionReliability.LOW,
                ) else logical.candidate
            }
            if(cells.all { it.rawValue.isNullOrEmpty() && it.reliability==RecognitionReliability.HIGH })null else CandidateRow(r,cells)
        }
        if(rows.isEmpty())throw PageRecognitionException(IssueCode.STRUCTURE_WARNING,"表格结构已保留，但数据区没有可整理记录。请核对数据起始行。")
        return CandidatePage(proposal.sourceId,proposal.headerPaths.map { it.joinToString(" / ") },rows,proposal.headerPaths)
    }

    private fun joinWords(words: List<TextEvidence>): String=buildString {
        words.forEachIndexed { index,word ->
            if(index>0) {
                val previous=words[index-1]; val lineHeight=maxOf(previous.region.bottom-previous.region.top,word.region.bottom-word.region.top)
                val nextLine=word.region.top-previous.region.top>lineHeight*.6f
                val cjk=previous.text.lastOrNull()?.code in 0x3400..0x9FFF || word.text.firstOrNull()?.code in 0x3400..0x9FFF
                if(nextLine)append('\n')else if(!cjk)append(' ')
            }
            append(word.text)
        }
    }.trim()
}
