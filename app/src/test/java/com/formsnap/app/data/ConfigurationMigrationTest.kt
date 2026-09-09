package com.formsnap.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.formsnap.app.data.local.FormSnapDatabase
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConfigurationMigrationTest {
    @Test fun `v4 to v5 preserves reviews and requires field configuration before export`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "v4.db"
        context.deleteDatabase(name)
        val schema = javaClass.classLoader!!.getResourceAsStream("com.formsnap.app.data.local.FormSnapDatabase/4.json")!!
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
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO digitization_tasks VALUES ('task', '任务', 'EXPORTED', 1, 2)")
            old.execSQL("INSERT INTO table_schemas VALUES ('schema', 'task')")
            old.execSQL("INSERT INTO review_decisions VALUES ('decision', 'task', 'CELL', 'cell', 'EDIT', 'S12', 'S12', '512', 'INVALID_FORMAT', 2)")
            old.execSQL("INSERT INTO validation_issues VALUES ('issue', 'task', 'CELL', 'CELL', 'cell', 'INVALID_FORMAT', 'fp', '格式', '[]', 'RESOLVED', 0)")
            old.version = 4
        }
        val db = Room.databaseBuilder(context, FormSnapDatabase::class.java, name).addMigrations(*FormSnapDatabase.ALL_MIGRATIONS).build()
        try {
            assertFalse(db.structuredDao().schema("task")!!.configurationConfirmed)
            assertEquals("REVIEW_REQUIRED", db.taskDao().getTask("task")!!.status)
            assertEquals("512", db.qualityDao().decisions("task").single().finalValue)
            assertEquals("S12", db.qualityDao().decisions("task").single().rawValue)
            assertEquals("RESOLVED", db.qualityDao().issues("task").single().status)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
