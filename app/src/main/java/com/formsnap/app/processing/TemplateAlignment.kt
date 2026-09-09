package com.formsnap.app.processing

import com.formsnap.app.domain.repository.SchemaMismatchException
import kotlin.math.abs
import kotlin.math.roundToInt

/** Reuses topology inside each page's locally recovered quadrilateral, never fixed image pixels. */
object TemplateAlignment {
    fun grid(reference: StructureProposal, page: StructureProposal): TableGrid {
        val a=reference.grid; val b=page.grid
        fun fractions(xs: List<Int>)=xs.map { (it-xs.first()).toFloat()/(xs.last()-xs.first()) }
        val expected=fractions(a.xs); val observed=fractions(b.xs)
        if(b.rows<=a.headerEnd || observed.size>expected.size || observed.size<expected.size*.7 ||
            observed.any { x -> expected.minOf { abs(it-x) }>.025f })throw SchemaMismatchException()
        // Close columns must also retain order; multiple observed lines cannot match one template edge.
        if(observed.map { x -> expected.indices.minBy { abs(expected[it]-x) } }.distinct().size!=observed.size)throw SchemaMismatchException()
        val xs=expected.map { (b.xs.first()+it*(b.xs.last()-b.xs.first())).roundToInt() }
        val headerMerges=a.merged.filter { it.row<a.headerEnd }
        val bodyMerges=if(a.columns==b.columns)b.merged.filter { it.row>=a.headerEnd } else emptyList()
        return b.copy(xs=xs,headerStart=a.headerStart,headerEnd=a.headerEnd,merged=headerMerges+bodyMerges)
    }
    fun sameHeaders(a: List<List<String>>,b: List<List<String>>): Boolean {
        fun normalized(paths: List<List<String>>)=paths.map { it.map { value -> value.filterNot(Char::isWhitespace) } }
        return normalized(a)==normalized(b)
    }
}
