package com.formsnap.app.ui

import com.formsnap.app.processing.*
import org.junit.Assert.*
import org.junit.Test

class StructureEditsTest {
    private val draft=StructureEdits("0, 25, 100","0, 20, 40, 70, 100","10,10;90,12;88,90;12,88","1,1,1,2","1","2","")
    @Test fun `editor supports region header separator and rectangular merge corrections`() {
        val grid=draft.grid()
        assertEquals(2,grid.columns);assertEquals(4,grid.rows);assertEquals(2,grid.headerEnd)
        assertEquals(GridCell(0,0,1,2),grid.merged.single())
        assertEquals(.1f,grid.quad!!.topLeft.x)
        val more=draft.copy(columns="0,25,50,100",merges="1,1,1,3").grid()
        assertEquals(3,more.columns);assertEquals(3,more.merged.single().colSpan)
        assertEquals(1,draft.copy(columns="0,100",merges="").grid().columns)
        assertEquals(1,draft.copy(end="1").grid().headerEnd)
    }
    @Test fun `crossed corners unordered lines and overlapping merges are rejected`() {
        assertThrows(IllegalArgumentException::class.java){draft.copy(corners="10,10;88,90;90,12;12,88").grid()}
        assertThrows(IllegalArgumentException::class.java){draft.copy(columns="0,50,25,100").grid()}
        assertThrows(IllegalArgumentException::class.java){draft.copy(merges="1,1,2,2;2,2,1,1").grid()}
    }
}
