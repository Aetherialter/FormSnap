package com.formsnap.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.formsnap.app.data.local.FormSnapDatabase
import com.formsnap.app.domain.model.CellReviewStatus
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class QualityMigrationTest {
    @Test fun `frozen v3 migration preserves structured values and provenance before quality evaluation`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "v3.db"
        context.deleteDatabase(name)
        val schema = javaClass.classLoader!!.getResourceAsStream("com.formsnap.app.data.local.FormSnapDatabase/3.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                fun sql(value: String) = value.replace('$' + "{TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
            val queries = schema.getJSONArray("setupQueries")
            for (i in 0 until queries.length()) old.execSQL(queries.getString(i))
            old.execSQL("INSERT INTO digitization_tasks VALUES ('old', '旧任务', 'PROCESSING', 123, 456)")
            old.execSQL("INSERT INTO source_documents VALUES ('page', 'old', 'content://test/old', 0, 'AVAILABLE', 200, '旧原图.png')")
            old.execSQL("INSERT INTO table_schemas VALUES ('schema', 'old')")
            old.execSQL("INSERT INTO field_definitions VALUES ('field', 'schema', 'old', 0, '值', 'TEXT', 0, NULL, NULL, NULL, 0, 0, 0, '值')")
            old.execSQL("INSERT INTO table_rows VALUES ('row', 'old', 'schema', 'page', 5, 0)")
            old.execSQL("INSERT INTO cells VALUES ('cell', 'row', 'field', 'schema', 'old', 'page', 0, ' S12 ', 'S12', '512', 'MANUAL_CONFIRMED', 'LOW', 0.1, 0.2, 0.3, 0.4)")
            old.version = 3
        }
        val db = Room.databaseBuilder(context, FormSnapDatabase::class.java, name).addMigrations(*FormSnapDatabase.ALL_MIGRATIONS).build()
        try {
            val data = RoomStructuredRepository(db).getDataset("old")!!
            val cell = data.rows.single().cells.single()
            assertEquals(" S12 ", cell.rawValue)
            assertEquals("S12", cell.normalizedValue)
            assertEquals("512", cell.confirmedValue)
            assertEquals(CellReviewStatus.MANUAL_CONFIRMED, cell.reviewStatus)
            assertEquals("page", cell.source!!.documentId)
            assertEquals(5, cell.source!!.originalRowIndex)
            assertEquals(0.1f, cell.source!!.region!!.left)
            assertEquals("SUCCEEDED", db.qualityDao().pages("old").single().state)
            assertEquals("REVIEW_REQUIRED", db.taskDao().getTask("old")!!.status)
            assertFalse(data.schema.configurationConfirmed)
            assertTrue(db.qualityDao().issues("old").isEmpty())
            assertTrue(db.qualityDao().decisions("old").isEmpty())
            assertEquals(6, db.openHelper.readableDatabase.version)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
