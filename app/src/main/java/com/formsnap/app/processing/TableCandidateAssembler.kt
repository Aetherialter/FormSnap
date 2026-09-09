package com.formsnap.app.processing

import com.formsnap.app.domain.model.*
import com.formsnap.app.validation.IssueCode

data class TextEvidence(val text: String, val region: SourceRegion, val confidence: Float)

/** Uses actual text boxes inside detected grid cells; never invents text for empty/unreadable ink. */
class TableCandidateAssembler {
    fun assemble(sourceId: String, image: GrayImage, grid: TableGrid, text: List<TextEvidence>): CandidatePage {
        if (text.any { word ->
                val y = (word.region.top + word.region.bottom) / 2 * image.height
                val x = (word.region.left + word.region.right) / 2 * image.width
                y > grid.ys.first() && y < grid.ys.last() && (x < grid.xs.first() || x > grid.xs.last())
            }) throw PageRecognitionException(IssueCode.STRUCTURE_WARNING, "表格范围外还有文字，可能有缺失边框或裁切。请检查完整页面后重新添加。")
        val cells = (0 until grid.rows).map { row -> (0 until grid.columns).map { col ->
            val region = grid.region(row, col)
            val words = text.filter { word ->
                val x = (word.region.left + word.region.right) / 2
                val y = (word.region.top + word.region.bottom) / 2
                x in region.left..region.right && y in region.top..region.bottom
            }.sortedWith(compareBy<TextEvidence> { ((it.region.top + it.region.bottom) * image.height / 16).toInt() }.thenBy { it.region.left })
            val crossing = words.any { it.region.left < region.left - 0.003f || it.region.right > region.right + 0.003f ||
                it.region.top < region.top - 0.003f || it.region.bottom > region.bottom + 0.003f }
            if (crossing) throw PageRecognitionException(IssueCode.STRUCTURE_WARNING, "文字跨越表格边界，无法可靠确认列归属，请检查页面。")
            val raw = joinWords(words)
            val ink = image.ink(region)
            val reliability = when {
                raw.isEmpty() && ink > 0.008 -> RecognitionReliability.UNREADABLE
                raw.isEmpty() -> RecognitionReliability.HIGH
                ink > 0.35 || words.any { !it.confidence.isFinite() || it.confidence < 0.90f } -> RecognitionReliability.LOW
                else -> RecognitionReliability.HIGH
            }
            CandidateCell(raw, region, reliability)
        } }
        if (cells[0].any { it.rawValue.isNullOrBlank() || it.reliability != RecognitionReliability.HIGH }) {
            throw PageRecognitionException(IssueCode.STRUCTURE_WARNING, "表头无法清楚辨认，请重新选择清晰完整的页面。")
        }
        if (cells.drop(1).all { row -> row.all { it.rawValue.isNullOrEmpty() && it.reliability == RecognitionReliability.HIGH } }) {
            throw PageRecognitionException(IssueCode.STRUCTURE_WARNING, "没有找到可整理的数据记录，请检查是否为空表或图片不清晰。")
        }
        // Completely blank ruled slots are not records. Partially filled rows retain all empty cells.
        val rows = cells.drop(1).mapIndexedNotNull { index, row ->
            if (row.all { it.rawValue.isNullOrEmpty() && it.reliability == RecognitionReliability.HIGH }) null
            else CandidateRow(index + 1, row)
        }
        return CandidatePage(sourceId, cells[0].map { it.rawValue!! }, rows)
    }

    private fun joinWords(words: List<TextEvidence>): String = buildString {
        words.forEachIndexed { index, word ->
            if (index > 0) {
                val previous = words[index - 1]
                val lineHeight = maxOf(previous.region.bottom - previous.region.top, word.region.bottom - word.region.top)
                val nextLine = word.region.top - previous.region.top > lineHeight * 0.6f
                val cjkBoundary = previous.text.lastOrNull()?.code in 0x3400..0x9FFF || word.text.firstOrNull()?.code in 0x3400..0x9FFF
                if (nextLine) append('\n') else if (!cjkBoundary) append(' ')
            }
            append(word.text)
        }
    }.trim()
}
