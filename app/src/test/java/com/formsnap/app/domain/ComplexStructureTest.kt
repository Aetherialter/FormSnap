package com.formsnap.app.domain

import com.formsnap.app.domain.model.SourceRegion
import com.formsnap.app.processing.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Geometry fixtures deliberately separate ground-truth topology, pixels and OCR evidence. */
class ComplexStructureTest {
    data class Case(val name: String,val xs: List<Int>,val ys: List<Int>,val depth: Int=1,
        val merged: List<GridCell> = emptyList(),val gaps: Boolean=false,val metadata: Boolean=false,val perspective: Boolean=false)
    private val width=1000; private val height=800
    private val cases=listOf(
        Case("A_regular",listOf(60,240,450,640,930),listOf(120,200,280,360,440,520)),
        Case("B_header_2",listOf(60,240,450,640,930),listOf(120,200,280,360,440,520),2),
        Case("C_header_3",listOf(60,240,450,640,930),listOf(120,200,280,360,440,520),3),
        Case("D_merged_header",listOf(60,240,450,640,930),listOf(120,200,280,360,440,520),2,listOf(GridCell(0,0,1,2))),
        Case("E_vertical_merge",listOf(60,240,450,640,930),listOf(120,200,280,360,440,520),1,listOf(GridCell(2,0,2,1))),
        Case("F_variable_width",listOf(60,110,350,425,930),listOf(120,200,280,360,440,520)),
        Case("G_many_empty",listOf(60,240,450,640,930),listOf(120,200,280,360,440,520)),
        Case("H_edge_gaps",listOf(60,240,450,640,930),listOf(120,200,280,360,440,520),gaps=true),
        Case("I_metadata",listOf(60,240,450,640,930),listOf(300,380,460,540,620,700),metadata=true),
        Case("J_36_columns",(0..36).map { 50+it*25 },listOf(120,200,280,360,440,520)),
        Case("K_perspective",listOf(60,240,450,640,930),listOf(120,200,280,360,440,520),perspective=true),
    )
    private val quad=TableQuad(TablePoint(.05f,.05f),TablePoint(.96f,.09f),TablePoint(.91f,.96f),TablePoint(.08f,.91f))
    private fun shape(test: Case)=TableGrid(test.xs,test.ys,width,height,test.merged,headerEnd=test.depth,quad=if(test.perspective)quad else null)
    private fun raster(test: Case): GrayImage {
        val grid=shape(test)
        val bytes=ByteArray(width*height) { n ->
            val p=grid.fromImage(TablePoint(n%width/width.toFloat(),n/width/height.toFloat()))
            val x=p.x; val y=p.y
            val vertical=test.xs.any { kotlin.math.abs(x-it)<1.0 } && y>=test.ys.first() && y<=test.ys.last()
            val horizontal=test.ys.any { kotlin.math.abs(y-it)<1.0 } && x>=test.xs.first() && x<=test.xs.last()
            val insideMerge=test.merged.any { m ->
                x>test.xs[m.column]+2 && x<test.xs[m.column+m.colSpan]-2 && y>test.ys[m.row]+2 && y<test.ys[m.row+m.rowSpan]-2
            }
            val gap=test.gaps && kotlin.math.abs(x-test.xs[2])<3 && y in 310f..331f
            if((vertical || horizontal) && !insideMerge && !gap)180.toByte() else 255.toByte()
        }
        // Separate short metadata rules should not expand the table above its actual top edge.
        if(test.metadata)for(y in listOf(40,70,100))for(x in 80..170)bytes[y*width+x]=80
        return GrayImage(width,height,bytes)
    }
    private fun evidence(test: Case): List<TextEvidence> {
        val grid=shape(test)
        return grid.cells.mapNotNull { cell ->
            if(test.name=="G_many_empty" && cell.row>=test.depth && cell.column!=0)return@mapNotNull null
            val value=if(cell.row<test.depth) listOf("分类","分组","名称")[cell.row%3]+('甲'+cell.column).toString() else "${cell.row*100+cell.column}"
            val center=grid.toImage((test.xs[cell.column]+test.xs[cell.column+cell.colSpan])/2f,(test.ys[cell.row]+test.ys[cell.row+cell.rowSpan])/2f)
            TextEvidence(value,SourceRegion(center.x-.006f,center.y-.01f,center.x+.006f,center.y+.01f),.99f)
        } + if(test.metadata)listOf(TextEvidence("学院 专业 班级",SourceRegion(.1f,.03f,.4f,.07f),.99f)) else emptyList()
    }

    @Test fun `A through K recover geometry spans header paths and logical text ownership`() {
        val report=mutableListOf<String>()
        for(test in cases) {
            val image=raster(test)
            val detection=GridDetector().recover(image)
            assertNotNull("${test.name}: ${detection.reasons}",detection.grid)
            val grid=detection.grid!!
            assertEquals("${test.name} columns",test.xs.size-1,grid.columns)
            assertEquals("${test.name} rows",test.ys.size-1,grid.rows)
            assertEquals("${test.name} merged",test.merged.toSet(),grid.merged.toSet())
            val proposal=TableCandidateAssembler().propose("source",image,grid,evidence(test))
            assertEquals("${test.name} header",test.depth,proposal.grid.headerEnd)
            assertEquals("${test.name} assignment",0,proposal.assignmentErrors)
            assertEquals(test.depth,proposal.headerPaths.last().size)
            val candidates=TableCandidateAssembler().candidates(proposal)
            assertEquals(test.xs.size-1,candidates.headers.size)
            val recognized=proposal.logicalCells.mapNotNull { it.candidate.rawValue?.takeIf(String::isNotEmpty) }
            assertEquals("One OCR element belongs to one logical cell",evidence(test).count { !it.text.startsWith("学院") },recognized.size)
            assertEquals(recognized.size,recognized.distinct().size)
            report+="${test.name}\ttrue\t${test.ys.size-1}\t${grid.rows}\t${test.xs.size-1}\t${grid.columns}\t${if(test.merged.isEmpty())"n/a" else "1.0"}\ttrue\t${proposal.assignmentErrors}\t${proposal.grid.outcome}"
        }
        File("build/reports/structure").mkdirs()
        File("build/reports/structure/metrics.tsv").writeText("case\ttableDetected\texpectedRows\tdetectedRows\texpectedColumns\tdetectedColumns\tmergedRegionRecall\theaderDepthCorrect\tcellAssignmentErrors\toutcome\n"+report.joinToString("\n"))
    }
}
