package com.formsnap.app.domain

import com.formsnap.app.processing.*
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

/**
 * Deterministic PC-only structure benchmark. The real user image is deliberately absent: it is
 * a TRAIN/regression observation, while these generated cases provide disjoint validation and
 * holdout evidence. Ground truth is only read by the scorer after detection.
 */
class StructureDatasetHarnessTest {
    private data class Truth(val split:String,val id:String,val xs:List<Int>,val ys:List<Int>,val image:GrayImage,
        val expectedReview:Boolean,val falseLineCount:Int)

    @Test fun `disjoint train validation and holdout benchmark preserves ground truth boundary metrics`() {
        val all=(0 until 48).map { make(it) }
        assertEquals(listOf("TRAIN","VALIDATION","HOLDOUT"),all.map { it.split }.distinct())
        assertEquals(48,all.map { it.id }.toSet().size)
        val policies=listOf(StructureDetectorPolicy(24,25,205),StructureDetectorPolicy(32,35,205),StructureDetectorPolicy(48,45,205))
        fun score(policy:StructureDetectorPolicy,cases:List<Truth>):Int = cases.sumOf { truth ->
            val result=GridDetector(policy).recover(truth.image);val grid=result.grid
            if(grid==null)100000 else abs(grid.rows-(truth.ys.size-1))*1000+abs(grid.columns-(truth.xs.size-1))*1000+
                if(truth.expectedReview && result.outcome==StructureOutcome.AUTO_ACCEPTED)500 else 0
        }
        // TRAIN chooses the policy; VALIDATION chooses among TRAIN winners. HOLDOUT is never
        // read until the policy is fixed and is reported only as an unseen final check.
        val train=all.filter { it.split=="TRAIN" };val validation=all.filter { it.split=="VALIDATION" }
        val trainBest=policies.minBy { score(it,train) }
        val selected=policies.filter { score(it,train)==score(trainBest,train) }.minBy { score(it,validation) }
        val reports=all.map { truth ->
            val detected=GridDetector(selected).recover(truth.image)
            val grid=detected.grid
            val rows=grid?.rows ?: 0;val columns=grid?.columns ?: 0
            val rowRecall=boundaryRecall(truth.ys,grid?.ys.orEmpty())
            val columnRecall=boundaryRecall(truth.xs,grid?.xs.orEmpty())
            val catastrophic=grid==null || abs(rows-(truth.ys.size-1))>1 || abs(columns-(truth.xs.size-1))>1
            if(truth.expectedReview) assertNotEquals("Unsafe auto acceptance for ${truth.id}",StructureOutcome.AUTO_ACCEPTED,detected.outcome)
            if(truth.split=="HOLDOUT") assertFalse("Holdout case ${truth.id} catastrophically failed (rows=$rows cols=$columns)",catastrophic)
            "${truth.split}\t${truth.id}\t${grid!=null}\t${truth.ys.size-1}\t$rows\t${truth.xs.size-1}\t$columns\t$columnRecall\t$rowRecall\t${if(truth.expectedReview)"STRUCTURE_REVIEW_REQUIRED" else "AUTO_ACCEPTED"}\t${detected.outcome}\t${truth.falseLineCount}"
        }
        val report=buildString {
            appendLine("split\tid\ttableDetected\texpectedRows\tdetectedRows\texpectedColumns\tdetectedColumns\tcolumnBoundaryRecall\trowBoundaryRecall\texpectedOutcome\tdetectedOutcome\tknownFalseLines")
            reports.forEach(::appendLine)
        }
        File("build/reports/structure").mkdirs()
        File("build/reports/structure/dataset-splits.tsv").writeText(report)
        File("build/reports/structure/dataset-manifest.tsv").writeText(all.joinToString("\n") { "${it.split}\t${it.id}\tseed=${it.id.substringAfter('-')}" }+"\n")
        val holdout=reports.count { it.startsWith("HOLDOUT") }
        assertEquals(12,holdout)
    }

    private fun make(index:Int): Truth {
        val split=when { index<24->"TRAIN"; index<36->"VALIDATION"; else->"HOLDOUT" }
        val cols=4+(index*7%34);val rows=5+(index*11%30)
        val xs=(0..cols).map { 70 + (it*104 + (it*it*3%19)) }
        val ys=(0..rows).map { 90 + it*40 + (it*index%5) }
        val review=index%2==0
        val falseLines=if(index%4==0)2 else if(index%4==1)1 else 0
        val width=xs.last()+80;val height=ys.last()+80
        val pixels=ByteArray(width*height) { n ->
            val x=n%width;val y=n/width
            val texture=if(index%5==0) ((x*13+y*7)%17) else 0
            val gridLine=xs.any { abs(x-it)<=1 } && y in ys.first()..ys.last() ||
                ys.any { abs(y-it)<=1 } && x in xs.first()..xs.last()
            val gap=review && x in xs[2]..(xs[2]+(xs[3]-xs[2])/3) && y in ys[2]..ys[minOf(10,ys.lastIndex)]
            val ui=(falseLines>0 && (y==16 || y==height-18) && x in 0 until width)
            when { gridLine && !gap -> 90; ui -> 145; else -> (235-texture).coerceIn(0,255) }.toByte()
        }
        return Truth(split,"case-$index",xs,ys,GrayImage(width,height,pixels),review,falseLines)
    }

    private fun boundaryRecall(expected:List<Int>,actual:List<Int>):String {
        if(actual.isEmpty())return "0.000"
        val matched=expected.count { value -> actual.any { abs(it-value)<=4 } }
        return "%.3f".format(java.util.Locale.ROOT,matched.toDouble()/expected.size)
    }
}
