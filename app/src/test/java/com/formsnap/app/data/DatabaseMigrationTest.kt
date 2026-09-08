package com.formsnap.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.formsnap.app.data.local.FormSnapDatabase
import com.formsnap.app.domain.model.DigitizationTask
import com.formsnap.app.domain.model.TaskStatus
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DatabaseMigrationTest {
    @Test fun `frozen Phase zero schema migrates preserving tasks and accepting sources`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v1-v2.db"
        context.deleteDatabase(name)
        val old = DigitizationTask("phase-zero-task", "迁移前任务", TaskStatus.DRAFT, Instant.ofEpochMilli(123), Instant.ofEpochMilli(456))
        val schema = javaClass.classLoader!!.getResourceAsStream("com.formsnap.app.data.local.FormSnapDatabase/1.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { legacy ->
            val entity = schema.getJSONArray("entities").getJSONObject(0)
            legacy.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", entity.getString("tableName")))
            val queries = schema.getJSONArray("setupQueries")
            for (i in 0 until queries.length()) legacy.execSQL(queries.getString(i))
            legacy.execSQL("INSERT INTO digitization_tasks VALUES (?, ?, ?, ?, ?)", arrayOf<Any>(old.id, old.name, old.status.name, 123L, 456L))
            legacy.version = 1
        }
        val upgraded = Room.databaseBuilder(context, FormSnapDatabase::class.java, name)
            .addMigrations(FormSnapDatabase.MIGRATION_1_2).build()
        try {
            val tasks = RoomTaskRepository(upgraded.taskDao())
            assertEquals(old, tasks.observeTask(old.id).first())
            val sources = RoomSourceRepository(upgraded, FakeSourceAccess())
            assertTrue(sources.observeSources(old.id).first().isEmpty())
            assertEquals(1, sources.addSources(old.id, listOf(RoomSourceRepositoryTest.A)).added)
            assertEquals(old.id, sources.observeSources(old.id).first().single().taskId)
            assertEquals(2, upgraded.openHelper.readableDatabase.version)
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }
}
