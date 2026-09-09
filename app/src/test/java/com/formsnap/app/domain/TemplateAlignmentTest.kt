package com.formsnap.app.domain

import com.formsnap.app.processing.*
import com.formsnap.app.domain.repository.SchemaMismatchException
import org.junit.Assert.*
import org.junit.Test

class TemplateAlignmentTest {
    private val reference=TableGrid(listOf(0,100,200,300,400),listOf(0,100,200,300,400),400,400,
        merged=listOf(GridCell(0,0,1,2)),headerEnd=2)
    private fun proposal(grid: TableGrid)=StructureProposal("source",grid,emptyList(),emptyList(),emptyList(),0)
    @Test fun `missing column is restored in the new perspective frame`() {
        val quad=TableQuad(TablePoint(.1f,.15f),TablePoint(.9f,.18f),TablePoint(.87f,.94f),TablePoint(.12f,.92f))
        val page=reference.copy(xs=listOf(0,200,600,800),ys=listOf(0,80,160,240,320),width=800,height=320,merged=emptyList(),headerEnd=1,quad=quad)
        val aligned=TemplateAlignment.grid(proposal(reference),proposal(page))
        assertEquals(listOf(0,200,400,600,800),aligned.xs);assertEquals(quad,aligned.quad)
        assertEquals(2,aligned.headerEnd);assertEquals(reference.merged,aligned.merged)
        val point=aligned.toImage(400f,160f);val restored=aligned.fromImage(point)
        assertEquals(400f,restored.x,.001f);assertEquals(160f,restored.y,.001f)
    }
    @Test fun `extra or displaced columns cannot reuse template`() {
        for(xs in listOf(listOf(0,80,160,240,320,400),listOf(0,100,240,300,400))) {
            assertThrows(SchemaMismatchException::class.java) { TemplateAlignment.grid(proposal(reference),proposal(reference.copy(xs=xs,merged=emptyList()))) }
        }
    }
    @Test fun `header normalization only tolerates whitespace and retains hierarchy`() {
        assertTrue(TemplateAlignment.sameHeaders(listOf(listOf("课程成绩","大学英语3")),listOf(listOf("课程 成绩","大学英语\n3"))))
        assertFalse(TemplateAlignment.sameHeaders(listOf(listOf("课程成绩","大学英语3")),listOf(listOf("课程成绩大学英语3"))))
        assertFalse(TemplateAlignment.sameHeaders(listOf(listOf("编号")),listOf(listOf("姓名"))))
    }
    @Test fun `restoring missing columns cannot silently remove body merge evidence`() {
        val page=reference.copy(xs=listOf(0,100,300,400),merged=listOf(GridCell(2,0,2,1)))
        assertThrows(SchemaMismatchException::class.java) { TemplateAlignment.grid(proposal(reference),proposal(page)) }
    }
}
