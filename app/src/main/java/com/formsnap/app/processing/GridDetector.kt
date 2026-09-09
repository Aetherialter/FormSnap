package com.formsnap.app.processing

import com.formsnap.app.domain.model.SourceRegion
import com.formsnap.app.validation.IssueCode
import kotlin.math.*

class GrayImage(val width: Int, val height: Int, private val luminance: ByteArray) {
    init { require(width > 0 && height > 0 && width.toLong() * height == luminance.size.toLong()) }
    fun value(x: Int, y: Int) = luminance[y.coerceIn(0,height-1)*width+x.coerceIn(0,width-1)].toInt() and 255
    fun dark(x: Int,y: Int) = value(x,y)<205
    fun ink(region: SourceRegion): Double {
        var count=0; var total=0
        for(y in (region.top*height).toInt() until (region.bottom*height).toInt())
            for(x in (region.left*width).toInt() until (region.right*width).toInt()) { if(value(x,y)<155)count++; total++ }
        return if(total==0)0.0 else count.toDouble()/total
    }
}

enum class StructureOutcome { AUTO_ACCEPTED, STRUCTURE_REVIEW_REQUIRED, SOURCE_UNUSABLE }
data class GridCell(val row: Int,val column: Int,val rowSpan: Int=1,val colSpan: Int=1) {
    init { require(row>=0 && column>=0 && rowSpan>0 && colSpan>0) }
    fun contains(r: Int,c: Int)=r in row until row+rowSpan && c in column until column+colSpan
}

/** Existing grid extended with logical spans, header range and original-image geometry. */
data class TableGrid(
    val xs: List<Int>,val ys: List<Int>,val width: Int,val height: Int,
    val merged: List<GridCell> = emptyList(),val headerStart: Int=0,val headerEnd: Int=1,
    val quad: TableQuad?=null,val outcome: StructureOutcome=StructureOutcome.AUTO_ACCEPTED,val reasons: List<String> = emptyList(),
) {
    val columns get()=xs.size-1
    val rows get()=ys.size-1
    init {
        require(width>0 && height>0 && xs.size in 2..129 && ys.size in 3..501)
        require(xs.zipWithNext().all { it.second>it.first } && ys.zipWithNext().all { it.second>it.first })
        require(xs.first()>=0 && xs.last()<=width && ys.first()>=0 && ys.last()<=height)
        require(headerStart>=0 && headerEnd>headerStart && headerEnd<rows)
        require(merged.all { it.row+it.rowSpan<=rows && it.column+it.colSpan<=columns })
        require(merged.indices.all { a -> (a+1 until merged.size).all { b ->
            val x=merged[a]; val y=merged[b]
            x.row>=y.row+y.rowSpan || y.row>=x.row+x.rowSpan || x.column>=y.column+y.colSpan || y.column>=x.column+x.colSpan
        } })
    }
    val cells: List<GridCell> get()=(0 until rows).flatMap { r -> (0 until columns).mapNotNull { c ->
        val mergedCell=merged.firstOrNull { it.contains(r,c) }
        if(mergedCell==null)GridCell(r,c) else mergedCell.takeIf { it.row==r && it.column==c }
    } }
    fun cellAt(r: Int,c: Int)=merged.firstOrNull { it.contains(r,c) } ?: GridCell(r,c)
    fun toImage(x: Float,y: Float)=quad?.map(x/width,y/height) ?: TablePoint(x/width,y/height)
    fun fromImage(p: TablePoint)=(quad?.inverse(p) ?: p).let { TablePoint(it.x*width,it.y*height) }
    fun corners(cell: GridCell,inset: Float=0f): List<TablePoint> {
        val dx=min(inset,(xs[cell.column+cell.colSpan]-xs[cell.column])/4f)
        val dy=min(inset,(ys[cell.row+cell.rowSpan]-ys[cell.row])/4f)
        val l=xs[cell.column]+dx; val t=ys[cell.row]+dy; val r=xs[cell.column+cell.colSpan]-dx; val b=ys[cell.row+cell.rowSpan]-dy
        return listOf(toImage(l,t),toImage(r,t),toImage(r,b),toImage(l,b))
    }
    fun region(row: Int,column: Int)=region(cellAt(row,column))
    fun region(cell: GridCell): SourceRegion {
        val p=corners(cell,4f)
        return SourceRegion(p.minOf { it.x }.coerceIn(0f,.99999f),p.minOf { it.y }.coerceIn(0f,.99999f),p.maxOf { it.x }.coerceIn(.00001f,1f),p.maxOf { it.y }.coerceIn(.00001f,1f))
    }
}

data class StructureDetection(val outcome: StructureOutcome,val grid: TableGrid?,val reasons: List<String>)

/** Missing edge segments form graph evidence instead of immediately rejecting the source. */
class GridDetector {
    fun recover(image: GrayImage): StructureDetection {
        val base=LineGeometry().frame(image) ?: return StructureDetection(StructureOutcome.SOURCE_UNUSABLE,null,listOf("未找到可用表格区域，请选择清晰完整的图片。"))
        val grid=graph(image,base)
        return StructureDetection(grid.outcome,grid,grid.reasons)
    }
    fun detect(image: GrayImage)=recover(image).grid ?: throw PageRecognitionException(IssueCode.UNREADABLE,"未找到有效表格区域，请检查原图。")

    private fun graph(image: GrayImage,base: TableGrid): TableGrid {
        val count=base.rows*base.columns; val parent=IntArray(count) { it }
        fun root(value: Int): Int { var x=value; while(parent[x]!=x) { parent[x]=parent[parent[x]]; x=parent[x] }; return x }
        fun union(a: Int,b: Int) { parent[root(a)]=root(b) }
        fun coverage(x1: Float,y1: Float,x2: Float,y2: Float): Double {
            val steps=max(abs(x2-x1),abs(y2-y1)).toInt().coerceAtLeast(1); var hits=0
            for(i in 3 until steps-2) {
                val t=i.toFloat()/steps; val p=base.toImage(x1+(x2-x1)*t,y1+(y2-y1)*t)
                if((-1..1).any { a -> (-1..1).any { b -> image.dark((p.x*image.width).roundToInt()+a,(p.y*image.height).roundToInt()+b) } })hits++
            }
            return hits.toDouble()/(steps-5).coerceAtLeast(1)
        }
        var uncertain=false
        for(r in 0 until base.rows)for(c in 1 until base.columns) {
            val rate=coverage(base.xs[c].toFloat(),base.ys[r].toFloat(),base.xs[c].toFloat(),base.ys[r+1].toFloat())
            if(rate<.18)union(r*base.columns+c-1,r*base.columns+c) else if(rate<.90)uncertain=true
        }
        for(r in 1 until base.rows)for(c in 0 until base.columns) {
            val rate=coverage(base.xs[c].toFloat(),base.ys[r].toFloat(),base.xs[c+1].toFloat(),base.ys[r].toFloat())
            if(rate<.18)union((r-1)*base.columns+c,r*base.columns+c) else if(rate<.90)uncertain=true
        }
        val merged=(0 until count).groupBy { root(it) }.values.filter { it.size>1 }.mapNotNull { slots ->
            val t=slots.minOf { it/base.columns }; val b=slots.maxOf { it/base.columns }; val l=slots.minOf { it%base.columns }; val r=slots.maxOf { it%base.columns }
            if((b-t+1)*(r-l+1)==slots.size)GridCell(t,l,b-t+1,r-l+1) else { uncertain=true; null }
        }
        val reasons=base.reasons.toMutableList()
        if(uncertain)reasons+="存在局部断线或非矩形边界，请核对分割。"
        if(merged.isNotEmpty())reasons+="存在合并区域，请核对表头层级与数据区。"
        val depth=merged.filter { it.row==0 }.maxOfOrNull { max(it.rowSpan,if(it.colSpan>1)2 else 1) }?.coerceAtMost(base.rows-1) ?: 1
        return base.copy(merged=merged,headerEnd=depth,outcome=if(reasons.isEmpty())StructureOutcome.AUTO_ACCEPTED else StructureOutcome.STRUCTURE_REVIEW_REQUIRED,reasons=reasons)
    }
}
