package com.formsnap.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.formsnap.app.data.local.FormSnapDatabase
import com.formsnap.app.domain.model.*
import com.formsnap.app.export.*
import com.formsnap.app.processing.*
import com.formsnap.app.review.*
import com.formsnap.app.validation.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic candidate benchmark with explicitly planted labels and independent expected answers.
 * The recognizer is a fixture adapter, NOT a measurement of ML Kit or photographed handwriting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class V1QualityBenchmarkTest {
    private lateinit var context: Context
    private lateinit var db: FormSnapDatabase
    private lateinit var structured: RoomStructuredRepository
    private lateinit var quality: RoomQualityRepository
    private lateinit var sources: RoomSourceRepository
    private val access = FakeSourceAccess()
    private val fields = listOf(
        FieldDefinition("name", "姓名", FieldType.TEXT, FieldRules(required = true)),
        FieldDefinition("id", "编号", FieldType.ID, FieldRules(required = true, pattern = "[0-9]{8}", duplicateKey = true)),
        FieldDefinition("group", "班组", FieldType.TEXT, FieldRules(detectColumnOutliers = true)),
        FieldDefinition("level", "等级", FieldType.INTEGER),
        FieldDefinition("score", "分值", FieldType.INTEGER, FieldRules(required = true, minimum = "0", maximum = "710")),
        FieldDefinition("course", "项目", FieldType.TEXT, FieldRules(duplicateKey = true)),
        FieldDefinition("original", "原数值", FieldType.DECIMAL),
        FieldDefinition("note", "备注", FieldType.TEXT),
    )
    // Correct answers are defined before corrupting candidates; no production normalizer generates them.
    private val truth = (0 until 100).map { index -> mutableListOf("测试人员${index + 1}", "${20260000 + index}",
        "G0${index % 4 + 1}", if (index % 2 == 0) "4" else "6", "${400 + index}", "项目${index % 3 + 1}", "75.5", "") }.toMutableList().also {
        it[71] = it[70].toMutableList() // Exact duplicate, intentionally retained as a separate record after review.
        it[81][1] = it[80][1]; it[81][5] = it[80][5] // Composite key only, different other values.
    }
    private val corruptions = linkedMapOf(
        (0 to 1) to "20X!0000", (1 to 1) to "20X!0001", (2 to 1) to "20X!0002",
        (3 to 4) to "4O3", (4 to 4) to "4O4", (5 to 4) to "4O5",
        (6 to 4) to "812", (7 to 4) to "-10",
        (8 to 0) to "", (9 to 1) to "", (10 to 4) to "",
        (20 to 0) to "错读甲", (21 to 0) to "错读乙", (22 to 0) to "",
        (30 to 2) to "G001", (31 to 2) to "GROUP2",
    )
    private fun candidateValue(row: Int, column: Int) = corruptions[row to column] ?: truth[row][column]

    @Before fun open() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("v1-benchmark.db")
        reopen()
    }
    private fun reopen() {
        db = Room.databaseBuilder(context, FormSnapDatabase::class.java, "v1-benchmark.db").build()
        structured = RoomStructuredRepository(db); quality = RoomQualityRepository(db); sources = RoomSourceRepository(db, access)
    }
    @After fun close() { db.close(); context.deleteDatabase("v1-benchmark.db") }

    @Test fun `800 cells detect planted targets preserve recovery and export independently checked answers`() = runTest {
        val task = RoomTaskRepository(db.taskDao()).createTask("合成质量基准")
        sources.addSources(task.id, (0..4).map { "content://benchmark/page-$it" })
        val pages = sources.observeSources(task.id).first()
        structured.createSchema(task.id, fields)
        val runner = ProcessingRunner(db, PageRecognizer { source ->
            if (source.pageIndex == 4) CandidatePage(source.id, listOf("异种", "格式"), listOf(CandidateRow(1,
                listOf(CandidateCell("A", SourceRegion(0f, 0f, .5f, 1f), RecognitionReliability.HIGH),
                    CandidateCell("B", SourceRegion(.5f, 0f, 1f, 1f), RecognitionReliability.HIGH)))))
            else CandidatePage(source.id, fields.map { it.name }, (0 until 25).map { localRow ->
                val row = source.pageIndex * 25 + localRow
                CandidateRow(localRow + 1, fields.indices.map { column ->
                    CandidateCell(candidateValue(row, column), SourceRegion(column / 8f, (localRow + 1) / 27f,
                        (column + 1) / 8f, (localRow + 2) / 27f), when {
                        row == 22 && column == 0 -> RecognitionReliability.UNREADABLE
                        row in 20..21 && column == 0 -> RecognitionReliability.LOW
                        else -> RecognitionReliability.HIGH
                    })
                })
            })
        })
        assertEquals(ProcessingSummary(4, 1), runner.run(ProcessingRequest(task.id)))
        val initial = quality.revalidate(task.id)
        val dataset = initial.dataset!!
        assertEquals(100, dataset.rows.size)
        assertEquals(800, dataset.rows.sumOf { it.cells.size })
        assertEquals(TaskStatus.REVIEW_REQUIRED.name, db.taskDao().getTask(task.id)!!.status)
        val coordinates = dataset.rows.flatMapIndexed { row, record -> record.cells.mapIndexed { col, cell -> cell.id to (row to col) } }.toMap()
        fun key(item: ReviewItem): String = when (item.target) {
            IssueTarget.CELL -> coordinates.getValue(item.targetId).let { "cell:${it.first}:${it.second}" }
            IssueTarget.SOURCE -> "source:${pages.indexOfFirst { it.id == item.targetId }}"
            IssueTarget.ROW_GROUP -> "rows:" + item.issues.flatMap { it.relatedRowIds }.distinct().map { id -> dataset.rows.indexOfFirst { it.id == id } }.sorted().joinToString(",")
            else -> "unexpected-schema"
        }
        val expected = corruptions.keys.map { "cell:${it.first}:${it.second}" }.toSet() + setOf("rows:70,71", "rows:80,81", "source:4")
        val items = ReviewQueue.items(initial.issues)
        val detected = items.map(::key).toSet()
        val missed = expected - detected
        val falsePositives = detected - expected
        assertEquals("Missed planted targets", emptySet<String>(), missed)
        assertEquals("Unexpected review targets", emptySet<String>(), falsePositives)
        assertEquals(19, items.size)
        assertTrue(initial.issues.any { it.code == IssueCode.SCHEMA_MISMATCH })
        assertTrue(items.any { it.issues.size > 1 })
        try { quality.exportableDataset(task.id); fail("Initial issues must block standard export") } catch (_: IllegalStateException) { }

        val cells = items.filter { it.target == IssueTarget.CELL }
        for ((index, item) in cells.withIndex()) {
            val (row, col) = coordinates.getValue(item.targetId)
            val cell = dataset.rows[row].cells[col]
            assertEquals(pages[row / 25].id, cell.source!!.documentId)
            assertEquals(row % 25 + 1, cell.source.originalRowIndex)
            assertNotNull(cell.source.region)
            // Simulate a reviewer entering the independently specified answer from the original material.
            quality.decide(task.id, ReviewDecision(IssueTarget.CELL, cell.id, ReviewAction.EDIT, truth[row][col]))
            if (index == 7) {
                val saved = structured.getDataset(task.id)
                db.close(); reopen()
                assertEquals(saved, structured.getDataset(task.id))
                assertEquals(TaskStatus.REVIEW_REQUIRED.name, db.taskDao().getTask(task.id)!!.status)
            }
        }
        ReviewQueue.items(quality.revalidate(task.id).issues).filter { it.target == IssueTarget.ROW_GROUP }.forEach {
            quality.decide(task.id, ReviewDecision(it.target, it.targetId, ReviewAction.DISTINCT_RECORDS, expectedRevision = it.revision))
        }
        // Explicit source removal is the simulated operator response to the mismatched fifth page.
        sources.removeSource(task.id, pages[4].id)
        val final = quality.exportableDataset(task.id)
        val finalErrors = final.rows.flatMapIndexed { row, record -> record.cells.mapIndexed { col, cell -> cell.confirmedValue != truth[row][col] } }.count { it }
        assertEquals(0, finalErrors)
        assertEquals(0, ReviewQueue.items(quality.revalidate(task.id).issues).size)
        assertEquals(100, final.rows.size)
        corruptions.forEach { (coordinate, raw) -> assertEquals(raw, final.rows[coordinate.first].cells[coordinate.second].rawValue) }

        val bytes = ByteArrayOutputStream().use { output -> XlsxWriter().write(final, emptyList(), output); output.toByteArray() }
        val xml = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var sheet: ByteArray? = null
            while (true) { val entry = zip.nextEntry ?: break; if (entry.name == "xl/worksheets/sheet1.xml") sheet = zip.readBytes() }
            requireNotNull(sheet)
        }
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true; setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val xmlRows = document.getElementsByTagName("row")
        assertEquals(101, xmlRows.length)
        for (row in 0..100) {
            val xmlCells = (xmlRows.item(row) as org.w3c.dom.Element).getElementsByTagName("c")
            assertEquals(8, xmlCells.length)
            for (col in 0 until 8) assertEquals(if (row == 0) fields[col].name else truth[row - 1][col], xmlCells.item(col).textContent)
        }
        val folder = File("build/reports/quality").apply { mkdirs() }
        File(folder, "v1-benchmark.xlsx").writeBytes(bytes)
        File(folder, "expected.tsv").writeText((listOf(fields.map { it.name }) + truth).joinToString("\n") { it.joinToString("\t") })
        File(folder, "candidates.tsv").writeText((listOf(fields.map { it.name }) + truth.indices.map { row -> fields.indices.map { candidateValue(row, it) } }).joinToString("\n") { it.joinToString("\t") })
        File(folder, "v1-quality.json").writeText("""
            {
              "basis": "synthetic candidates, predeclared target labels and simulated review; not OCR accuracy",
              "totalCells": 800, "realIssues": ${expected.size}, "detectedIssues": ${detected.size},
              "detectedRealIssues": ${detected.intersect(expected).size}, "missedIssues": ${missed.size},
              "falsePositives": ${falsePositives.size}, "reviewItems": ${items.size},
              "reviewRate": ${items.size / 800.0}, "issueRecall": ${detected.intersect(expected).size.toDouble() / expected.size},
              "underlyingIssueCount": ${initial.issues.count { it.active }}, "finalErrors": $finalErrors
            }
        """.trimIndent())
    }
}
