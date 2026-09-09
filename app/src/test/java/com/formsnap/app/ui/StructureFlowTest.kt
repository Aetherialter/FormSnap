package com.formsnap.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.formsnap.app.FormSnapApplication
import com.formsnap.app.MainActivity
import com.formsnap.app.data.TestImageProvider
import com.formsnap.app.processing.*
import com.formsnap.app.domain.model.SourceRegion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="zh-rCN")
class StructureFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun `edit header and separators save across recreation then confirm actual app structure`() {
        val app=compose.activity.application as FormSnapApplication
        TestImageProvider.register(app)
        val task=runBlocking {
            val task=app.taskRepository.createTask("结构确认界面测试")
            app.sourceRepository.addSources(task.id,listOf("content://${TestImageProvider.AUTHORITY}/structure"))
            val source=app.sourceRepository.observeSources(task.id).first().single()
            val grid=TableGrid(listOf(50,500,950),listOf(50,150,250,350,450),1000,500,headerEnd=2,
                outcome=StructureOutcome.STRUCTURE_REVIEW_REQUIRED,reasons=listOf("请核对两层表头"))
            val words=(0 until 4).flatMap { row -> (0 until 2).map { col ->
                val x=if(col==0).25f else .75f;val y=.2f+row*.2f
                TextEvidence(if(row<2) listOf("类别","字段")[row]+if(col==0)"甲" else "乙" else "${row*100+col}",SourceRegion(x-.03f,y-.03f,x+.03f,y+.03f),.99f)
            } }
            val proposal=TableCandidateAssembler().propose(source.id,GrayImage(1000,500,ByteArray(500000){255.toByte()}),grid,words)
            app.structureRepository.record(task.id,proposal,false)
            app.qualityRepository.revalidate(task.id)
            task
        }
        waitFor("结构确认界面测试")
        compose.onNodeWithText("结构确认界面测试").performClick()
        compose.onNodeWithTag("openStructure").performScrollTo().performClick()
        waitFor("2列 · 4行（含表头） · 0个合并区域")
        compose.onNodeWithTag("headerEnd").performScrollTo().performTextReplacement("2")
        // Add and remove a separator through the real editor; retain the expected final two columns.
        compose.onNodeWithTag("columnCuts").performScrollTo().performTextReplacement("0, 25, 50, 100")
        waitFor("3列 · 4行（含表头） · 0个合并区域")
        compose.onNodeWithTag("columnCuts").performTextReplacement("0, 50, 100")
        compose.onNodeWithTag("saveStructureDraft").performScrollTo().performClick()
        waitFor("结构草稿已保存，覆盖图和字段路径已更新。")
        compose.activityRule.scenario.recreate()
        waitFor("2列 · 4行（含表头） · 0个合并区域")
        compose.onNodeWithTag("headerEnd").assertTextContains("2")
        compose.waitUntil(10000){compose.onAllNodes(hasTestTag("confirmStructure") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("confirmStructure").performScrollTo().performClick()
        waitFor("结构已确认，已安排后续页面继续整理。")
        runBlocking {
            val stored=app.structureRepository.observe(task.id).first().single()
            assertTrue(stored.confirmed)
            val data=app.structuredRepository.getDataset(task.id)!!
            assertEquals(2,data.schema.fields.size)
            assertEquals(listOf("类别甲","字段甲"),data.schema.fields[0].headerPath)
            assertEquals("200",data.rows[0].cells[0].rawValue)
        }
    }
    private fun waitFor(text: String) {
        try { compose.waitUntil(15000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()} }
        catch(failure: Throwable) {
            println("Waiting for: $text")
            println(compose.onRoot().printToString())
            throw failure
        }
    }
}
