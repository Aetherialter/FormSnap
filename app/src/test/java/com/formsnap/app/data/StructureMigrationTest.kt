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
@Config(sdk=[35],application=Application::class)
class StructureMigrationTest {
    @Test fun `frozen v5 migrates without changing original values quality decisions or readiness`() = runTest {
        val context=ApplicationProvider.getApplicationContext<Context>(); val name="v5-structure.db"
        context.deleteDatabase(name)
        val schema=javaClass.classLoader!!.getResourceAsStream("com.formsnap.app.data.local.FormSnapDatabase/5.json")!!.bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use { old ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i)
                fun sql(value: String)=value.replace('$'+"{TABLE_NAME}",entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                val indexes=entity.optJSONArray("indices")
                if(indexes!=null)for(j in 0 until indexes.length())old.execSQL(sql(indexes.getJSONObject(j).getString("createSql")))
            }
            val setup=schema.getJSONArray("setupQueries"); for(i in 0 until setup.length())old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO digitization_tasks VALUES ('task','原任务','EXPORTED',1,2)")
            old.execSQL("INSERT INTO table_schemas VALUES ('schema','task',1)")
            old.execSQL("INSERT INTO field_definitions VALUES ('field','schema','task',0,'已改名称','TEXT',0,NULL,NULL,NULL,0,0,0,'原表头')")
            old.execSQL("INSERT INTO review_decisions VALUES ('d','task','CELL','c','EDIT','S12','S12','512','INVALID_FORMAT',2)")
            old.version=5
        }
        val db=Room.databaseBuilder(context,FormSnapDatabase::class.java,name).addMigrations(*FormSnapDatabase.ALL_MIGRATIONS).build()
        try {
            val data=RoomStructuredRepository(db).getDataset("task")!!
            assertEquals(listOf("原表头"),data.schema.fields.single().headerPath)
            assertEquals("已改名称",data.schema.fields.single().name)
            assertEquals("EXPORTED",db.taskDao().getTask("task")!!.status)
            assertEquals("S12",db.qualityDao().decisions("task").single().rawValue)
            assertEquals("512",db.qualityDao().decisions("task").single().finalValue)
            assertNull(db.structureDao().template("task"))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
