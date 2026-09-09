package com.formsnap.app.processing

import com.formsnap.app.domain.model.SourceRegion
import com.formsnap.app.validation.IssueCode
import kotlin.math.max

class GrayImage(val width: Int, val height: Int, private val luminance: ByteArray) {
    init { require(width > 0 && height > 0 && width.toLong() * height == luminance.size.toLong()) }
    fun dark(x: Int, y: Int): Boolean = (luminance[y * width + x].toInt() and 255) < 155
    fun ink(region: SourceRegion): Double {
        val left = (region.left * width).toInt().coerceIn(0, width - 1)
        val right = (region.right * width).toInt().coerceIn(left + 1, width)
        val top = (region.top * height).toInt().coerceIn(0, height - 1)
        val bottom = (region.bottom * height).toInt().coerceIn(top + 1, height)
        var dark = 0
        var pixels = 0
        for (y in top until bottom) for (x in left until right) { if (dark(x, y)) dark++; pixels++ }
        return dark.toDouble() / pixels
    }
}

data class TableGrid(val xs: List<Int>, val ys: List<Int>, val width: Int, val height: Int) {
    val columns get() = xs.size - 1
    val rows get() = ys.size - 1
    fun region(row: Int, column: Int): SourceRegion {
        // Exclude grid strokes from cell ink measurements and source snippets.
        val inset = 4f
        return SourceRegion((xs[column] + inset) / width, (ys[row] + inset) / height,
            (xs[column + 1] - inset) / width, (ys[row + 1] - inset) / height)
    }
}

/** Conservative support for an axis-aligned, fully ruled rectangular table. Reject ambiguity. */
class GridDetector {
    fun detect(image: GrayImage): TableGrid {
        val horizontal = (0 until image.height).filter { y -> longestRun(image.width) { x -> image.dark(x, y) } >= image.width * 0.45 }
        val vertical = (0 until image.width).filter { x -> longestRun(image.height) { y -> image.dark(x, y) } >= image.height * 0.30 }
        val xs = centers(vertical)
        val ys = centers(horizontal)
        if (xs.size !in 3..25 || ys.size !in 3..151 || xs.zipWithNext().any { (a, b) -> b - a < 16 } || ys.zipWithNext().any { (a, b) -> b - a < 16 }) unsupported()
        if (xs.first() <= 3 || ys.first() <= 3 || xs.last() >= image.width - 4 || ys.last() >= image.height - 4) {
            throw PageRecognitionException(IssueCode.STRUCTURE_WARNING, "表格边缘贴近图片边界，可能未拍完整。请检查整页并重新添加。")
        }
        for (x in xs) {
            val count = (ys.first()..ys.last()).count { y -> (-2..2).any { dx -> (x + dx) in 0 until image.width && image.dark(x + dx, y) } }
            if (count.toDouble() / (ys.last() - ys.first() + 1) < 0.92) unsupported()
        }
        for (y in ys) {
            val count = (xs.first()..xs.last()).count { x -> (-2..2).any { dy -> (y + dy) in 0 until image.height && image.dark(x, y + dy) } }
            if (count.toDouble() / (xs.last() - xs.first() + 1) < 0.92) unsupported()
        }
        // A short missing segment can be a merged cell even when overall coverage is high.
        for (x in xs) for ((top, bottom) in ys.zipWithNext()) {
            val covered = (top..bottom).count { y -> (-2..2).any { dx -> image.dark((x + dx).coerceIn(0, image.width - 1), y) } }
            if (covered.toDouble() / (bottom - top + 1) < 0.85) unsupported()
        }
        for (y in ys) for ((left, right) in xs.zipWithNext()) {
            val covered = (left..right).count { x -> (-2..2).any { dy -> image.dark(x, (y + dy).coerceIn(0, image.height - 1)) } }
            if (covered.toDouble() / (right - left + 1) < 0.85) unsupported()
        }
        return TableGrid(xs, ys, image.width, image.height)
    }

    private fun longestRun(length: Int, dark: (Int) -> Boolean): Int {
        var start = 0
        var gap = 0
        var longest = 0
        for (index in 0 until length) {
            if (dark(index)) gap = 0 else gap++
            if (gap > 2) start = index + 1
            longest = max(longest, index - start + 1 - gap)
        }
        return longest
    }
    private fun centers(points: List<Int>): List<Int> {
        if (points.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<Int>>()
        for (point in points) {
            if (groups.isEmpty() || point - groups.last().last() > 4) groups += mutableListOf(point)
            else groups.last() += point
        }
        return groups.map { it.average().toInt() }
    }
    private fun unsupported(): Nothing = throw PageRecognitionException(IssueCode.STRUCTURE_WARNING,
        "未能可靠确认表格行列。当前支持边框清晰、横平竖直且没有合并单元格的表格，请检查是否模糊、倾斜或缺边。")
}
