package com.formsnap.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.formsnap.app.FormSnapApplication
import com.formsnap.app.MainActivity
import com.formsnap.app.data.TestExportProvider
import com.formsnap.app.data.TestImageProvider
import com.formsnap.app.domain.model.*
import com.formsnap.app.export.XlsxWriter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real app/navigation/Room/export provider flow with clearly synthetic recognition candidates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN")
class WorkflowFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun `configure review duplicates inspect final data and save XLSX across picker recreation`() {
        val app = compose.activity.application as FormSnapApplication
        TestImageProvider.register(app)
        val output = TestExportProvider.register(app)
        val task = runBlocking {
            val task = app.taskRepository.createTask("设备数据闭环测试")
            app.sourceRepository.addSources(task.id, listOf("content://${TestImageProvider.AUTHORITY}/page"))
            val source = app.sourceRepository.observeSources(task.id).first().single()
            app.structuredRepository.createSchema(task.id, listOf(FieldDefinition("id", "资产编号", FieldType.ID, FieldRules(duplicateKey = true)),
                FieldDefinition("number", "数值", FieldType.INTEGER, FieldRules(required = true, minimum = "0", maximum = "710"))), configurationConfirmed = false)
            app.structuredRepository.replacePage(task.id, CandidatePage(source.id, listOf("资产编号", "数值"), listOf("S12", "512").mapIndexed { index, number ->
                CandidateRow(index + 1, listOf(CandidateCell("001", SourceRegion(0.1f, 0.1f, 0.4f, 0.2f), RecognitionReliability.HIGH),
                    CandidateCell(number, SourceRegion(0.5f, 0.1f, 0.9f, 0.2f), RecognitionReliability.HIGH)))
            }))
            app.qualityRepository.revalidate(task.id)
            task
        }
        waitFor("设备数据闭环测试")
        compose.onNodeWithText("设备数据闭环测试").performClick()
        waitFor("字段设置")
        compose.onNodeWithText("字段设置").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("saveFields") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("saveFields").performScrollTo().performClick()
        waitFor("字段设置已保存，数据已重新检查。")
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("开始检查").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("reviewValue") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reviewValue").performScrollTo().performTextReplacement("512")
        compose.onNodeWithTag("confirmReview").performScrollTo().performClick()
        waitFor("疑似重复")
        compose.onNodeWithText("视为不同业务记录").performScrollTo().performClick()
        waitFor("当前没有待确认项目。")
        compose.onNodeWithText("查看最终数据").performClick()
        compose.onNodeWithTag("searchRecords").performTextInput("512")
        compose.onNodeWithText("2 条记录 · 0 项待确认").assertIsDisplayed()
        compose.onNodeWithTag("exportXlsx").assertIsEnabled().performClick()
        var requestCode: Int? = null
        var request: Intent? = null
        compose.waitUntil(10_000) {
            shadowOf(compose.activity).nextStartedActivityForResult?.let { requestCode = it.requestCode; request = it.intent }
            requestCode != null
        }
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request!!.action)
        assertEquals(XlsxWriter.MIME, request!!.type)
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { it.activityResultRegistry.dispatchResult(requestCode!!, Activity.RESULT_OK,
            Intent().setData(Uri.parse(TestExportProvider.URI)).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) }
        waitFor("XLSX 已保存，包含最终数据和复核记录。")
        assertTrue(output.output.length() > 0)
        val files = mutableMapOf<String, String>()
        ZipInputStream(output.output.inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertFalse(files.getValue("xl/worksheets/sheet1.xml").contains("S12"))
        assertTrue(files.getValue("xl/worksheets/sheet1.xml").contains("512"))
        assertTrue(files.getValue("xl/worksheets/sheet2.xml").contains("S12"))
        runBlocking {
            val restored = app.structuredRepository.getDataset(task.id)!!
            assertEquals("S12", restored.rows[0].cells[1].rawValue)
            assertEquals("512", restored.rows[0].cells[1].confirmedValue)
            assertEquals(TaskStatus.EXPORTED, app.taskRepository.observeTask(task.id).first()!!.status)
        }
    }

    private fun waitFor(value: String) { compose.waitUntil(15_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() } }
}
