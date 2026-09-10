package com.formsnap.app.domain

import com.formsnap.app.processing.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Invented pixels only: gray display, luminance variation, moire and unrelated software chrome. */
class ScreenPhotoStructureTest {
    private val width=1200;private val height=900
    private val xs=(0..27).map { 70+it*40 }
    private val ys=listOf(160)+(0..28).map { 250+it*20 }
    private fun image(): GrayImage {
        val bytes=ByteArray(width*height) { n ->
            val x=n%width;val y=n/width
            val background=175+25*x/width+8*sin(x*.7+y*.15)+6*cos(x*.14-y*.9)
            val table=(x in xs.first()..xs.last() && ys.any { abs(y-it)<=1 }) ||
                (y in ys.first()..ys.last() && xs.any { abs(x-it)<=1 })
            val menu=y in listOf(20,60,100,850) && x in 10..1180 || x==10 && y in 20..850
            (if(table || menu)95 else background.roundToInt()).coerceIn(0,255).toByte()
        }
        return GrayImage(width,height,bytes)
    }
    @Test fun `gray screen texture does not become hundreds of fictitious rulings`() {
        val result=GridDetector().recover(image())
        val grid=checkNotNull(result.grid) { result.reasons.joinToString() }
        assertEquals(27,grid.columns)
        assertEquals(29,grid.rows)
        assertEquals(emptyList<GridCell>(),grid.merged)
        val corners=grid.corners(GridCell(0,0,grid.rows,grid.columns))
        assertEquals(xs.first()/width.toFloat(),corners[0].x,.006f)
        assertEquals(ys.first()/height.toFloat(),corners[0].y,.006f)
        assertEquals(xs.last()/width.toFloat(),corners[2].x,.006f)
        assertEquals(ys.last()/height.toFloat(),corners[2].y,.006f)
    }
    @Test fun `geometry computation observes cancellation during voting`() {
        var polls=0
        assertThrows(CancellationException::class.java) {
            GridDetector { if(++polls==5)throw CancellationException("test deadline") }.recover(image())
        }
        assertEquals(5,polls)
    }
}
