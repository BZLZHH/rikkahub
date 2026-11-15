package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.model.MemoryFile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises both meanings of v26..33, including the real, retired Enhanced v29. */
@RunWith(AndroidJUnit4::class)
class MigrationTo36Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val databaseName = AppDatabase::class.java.name

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation,
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun everyPublishedSchemaConvergesWithoutLosingAnyOriginalRow() {
        val fixtures = listOf("" to 25) +
            (26..33).map { "" to it } +
            (26..35).map { "enhanced/" to it } +
            listOf("enhanced-legacy29/" to 29)
        fixtures.forEach { (family, version) ->
            val name = "merge-36-${family.replace('/', '-')}$version"
            val snapshot = createHistoricalDatabase(name, family, version)
            try {
                // Old MemoryEntity and retired workflow tables deliberately survive. Room's
                // normal schema validation permits extra tables; the snapshots check their data.
                helper.runMigrationsAndValidate(name, 36, false, *MigrationsTo36).use { db ->
                    assertOriginalRows(db, snapshot)
                    assertTrue(db.hasTable("workspace_jobs"))
                    assertTrue(db.hasTable("agent_runs"))
                    assertTrue(db.hasTable("workflow_runs"))
                    assertTrue(db.hasTable("workflow_agents"))
                    assertTrue(db.hasTable("media_creation_record"))
                    assertTrue(db.hasTable("translation_history"))
                    if ("MemoryEntity" in snapshot) {
                        assertEquals(
                            "- [stated] keep",
                            db.query("SELECT content FROM memory_file WHERE path = ?",
                                arrayOf(MemoryFile.PROFILE_PATH)).use { cursor ->
                                assertTrue(cursor.moveToFirst())
                                cursor.getString(0).trim().lineSequence().last()
                            },
                        )
                    }
                    // Upstream v31+ already has a ledger: never backfill it a second time.
                    assertEquals(1L, db.scalar("SELECT COUNT(*) FROM usage_record"))
                    if ("usage_record" !in snapshot) {
                        assertEquals(7L, db.scalar("SELECT prompt_tokens FROM usage_record"))
                        assertEquals(3L, db.scalar("SELECT completion_tokens FROM usage_record"))
                        assertEquals(2L, db.scalar("SELECT cached_tokens FROM usage_record"))
                    }
                    if ("memory_consolidation" !in snapshot) {
                        assertEquals(1L, db.scalar("SELECT consolidated_at FROM memory_consolidation"))
                    }
                    // A repeat cannot overwrite profiles, reset consolidation, or double-count usage.
                    MigrationsTo36.first { it.startVersion == version }.migrate(db)
                    assertOriginalRows(db, snapshot)
                    assertEquals(1L, db.scalar("SELECT COUNT(*) FROM usage_record"))
                    assertEquals(1L, db.scalar("SELECT COUNT(*) FROM memory_file"))
                    assertEquals(0L, db.query("PRAGMA foreign_key_check").use { it.count.toLong() })
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }

    @Test
    fun productionFactoryOpensBothHeadsAndSharedVersionUsingRegisteredMigrations() {
        listOf("enhanced/" to 35, "" to 33, "" to 25, "enhanced-legacy29/" to 29).forEach { (family, version) ->
            val name = "factory-36-${family.replace('/', '-')}$version"
            val snapshot = createHistoricalDatabase(name, family, version)
            try {
                val database = AppDatabaseFactory.create(context, name)
                try {
                    val db = database.openHelper.writableDatabase
                    assertEquals(36, db.version)
                    assertOriginalRows(db, snapshot)
                } finally {
                    database.close()
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }

    private data class TableSnapshot(val columns: List<String>, val values: List<String?>)

    /** Build from the original export, including its original identity hash, not the merged entities. */
    private fun createHistoricalDatabase(name: String, family: String, version: Int): Map<String, TableSnapshot> {
        context.deleteDatabase(name)
        val schema = instrumentation.context.assets.open("$family$databaseName/$version.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val entities = schema.getJSONArray("entities")
        val factory = RequerySQLiteOpenHelperFactory()
        val openHelper = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = entity.optJSONArray("indices")
                            if (indices != null) for (j in 0 until indices.length()) {
                                db.execSQL(indices.getJSONObject(j).getString("createSql")
                                    .replace("\${TABLE_NAME}", table))
                            }
                        }
                        val setup = schema.getJSONArray("setupQueries")
                        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        error("Fixture helper must never upgrade a database")
                    }
                }).build()
        )
        return openHelper.use {
            val db = it.writableDatabase
            buildMap {
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    val fields = entity.getJSONArray("fields")
                    val columns = (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
                    val values: List<Any> = (0 until fields.length()).map { index ->
                        val field = fields.getJSONObject(index)
                        when {
                            table == "message_node" && field.getString("columnName") == "messages" ->
                                """[{"role":"assistant","createdAt":"2026-08-12T09:00:00","usage":{"promptTokens":7,"completionTokens":3,"cachedTokens":2}}]"""
                            field.getString("affinity") == "INTEGER" -> 1L
                            field.getString("affinity") == "REAL" -> 1.0
                            else -> "keep"
                        }
                    }
                    db.execSQL(
                        "INSERT INTO `$table` (${columns.joinToString { column -> "`$column`" }}) " +
                            "VALUES (${columns.joinToString { "?" }})",
                        values.toTypedArray(),
                    )
                    put(table, TableSnapshot(columns, db.row(table, columns)))
                }
            }
        }
    }

    private fun assertOriginalRows(db: SupportSQLiteDatabase, snapshot: Map<String, TableSnapshot>) {
        snapshot.forEach { (table, before) ->
            assertEquals("Preserve row in $table", before.values, db.row(table, before.columns))
            assertEquals("Preserve row count in $table", 1L, db.scalar("SELECT COUNT(*) FROM `$table`"))
        }
    }

    private fun SupportSQLiteDatabase.row(table: String, columns: List<String>): List<String?> =
        query("SELECT ${columns.joinToString { "`$it`" }} FROM `$table`").use { cursor ->
            assertTrue("Expected row in $table", cursor.moveToFirst())
            columns.indices.map { if (cursor.isNull(it)) null else cursor.getString(it) }
        }

    private fun SupportSQLiteDatabase.scalar(sql: String): Long = query(sql).use {
        assertTrue(it.moveToFirst())
        it.getLong(0)
    }

    private fun SupportSQLiteDatabase.hasTable(table: String): Boolean = query(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table),
    ).use { it.moveToFirst() }
}
