package com.formsnap.app.ui

import com.formsnap.app.processing.*
import java.util.Locale

/** Small explicit editor contract; no free-form image editor or alternative table model. */
internal data class StructureEdits(val columns: String,val rows: String,val corners: String,val merges: String,val start: String,val end: String,val paths: String) {
    fun grid(): TableGrid {
        fun cuts(text: String): List<Int> {
            val numbers=text.split(',', '，').map { it.trim().toFloat() }
            require(numbers.first()==0f && numbers.last()==100f && numbers.all { it.isFinite() && it in 0f..100f })
            return numbers.map { (it*10).toInt() }
        }
        val points=corners.split(';','；').map { pair -> pair.split(',', '，').map { it.trim().toFloat()/100f }.also { require(it.size==2 && it.all { n -> n.isFinite() && n in 0f..1f }) }.let { TablePoint(it[0],it[1]) } }
        require(points.size==4)
        // Convex clockwise quad, with useful area. Crossing or reversed corners are invalid.
        require(points.indices.all { i ->
            val a=points[i]; val b=points[(i+1)%4]; val c=points[(i+2)%4]
            (b.x-a.x)*(c.y-b.y)-(b.y-a.y)*(c.x-b.x)>.0001f
        })
        val merged=merges.split(';','；').filter(String::isNotBlank).map { entry ->
            val n=entry.split(',', '，').map { it.trim().toInt() }; require(n.size==4)
            GridCell(n[0]-1,n[1]-1,n[2],n[3])
        }
        return TableGrid(cuts(columns),cuts(rows),1000,1000,merged,start.toInt()-1,end.toInt(),
            TableQuad(points[0],points[1],points[2],points[3]),StructureOutcome.STRUCTURE_REVIEW_REQUIRED)
    }
    fun headerPaths()=if(paths.isBlank())emptyList() else paths.lines().filter(String::isNotBlank).map { it.split('/').map(String::trim) }
    companion object {
        fun from(p: StructureProposal): StructureEdits {
            fun number(n: Float)=String.format(Locale.ROOT,"%.2f",n)
            fun cuts(values: List<Int>)=values.joinToString(", ") { number((it-values.first())*100f/(values.last()-values.first())) }
            val g=p.grid
            return StructureEdits(cuts(g.xs),cuts(g.ys),g.corners(GridCell(0,0,g.rows,g.columns)).joinToString("; ") { "${number(it.x*100)}, ${number(it.y*100)}" },
                g.merged.joinToString("; "){ "${it.row+1}, ${it.column+1}, ${it.rowSpan}, ${it.colSpan}" },"${g.headerStart+1}","${g.headerEnd}",p.headerPaths.joinToString("\n"){ it.joinToString(" / ") })
        }
    }
}
