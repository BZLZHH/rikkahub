package me.rerere.rikkahub.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v28 -> v29: workflow 的两张新表。
 *
 * 为什么值得单独测: 自动迁移是**纯靠生成的代码与 schema JSON 对账**的,
 * 编译期不会告诉我们"迁移跑起来对不对"。而迁移一旦出错, 表现是**用户启动即崩**,
 * 且崩在数据库上 —— 本项目已经有过一次"单测全绿但真机崩"的教训。
 *
 * `runMigrationsAndValidate(..., validateDroppedTables = true)` 会把迁移后的实际表结构
 * 与 29.json 逐列比对, 所以它同时验证了"表建出来了"和"建得与实体一致"。
 */
@RunWith(AndroidJUnit4::class)
class Migration_28_29_Test {
    private val TEST_DB = "migration-28-29-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate28To29_createsWorkflowTablesWithExpectedColumns() {
        helper.createDatabase(TEST_DB, 28).apply { close() }

        val db = helper.runMigrationsAndValidate(TEST_DB, 29, true)

        val runs = db.query("SELECT * FROM workflow_runs LIMIT 0")
        val runColumns = runs.columnNames.toList()
        runs.close()
        listOf(
            "id", "workspaceId", "conversationId", "assistantId", "name", "fingerprint",
            "scriptPath", "status", "phase", "progressJson", "resultJson", "error",
            "agentsCalled", "agentsReused", "createdAt", "startedAt", "finishedAt", "runtimeMs",
        ).forEach { column ->
            assertTrue("workflow_runs 缺少列 " + column + ", 实际: " + runColumns, column in runColumns)
        }

        val results = db.query("SELECT * FROM workflow_agent_results LIMIT 0")
        val resultColumns = results.columnNames.toList()
        results.close()
        listOf("fingerprint", "callIndex", "prompt", "schemaJson", "label", "resultText", "createdAt")
            .forEach { column ->
                assertTrue(
                    "workflow_agent_results 缺少列 " + column + ", 实际: " + resultColumns,
                    column in resultColumns,
                )
            }

        db.close()
    }

    @Test
    fun migrate28To29_keepsExistingRows() {
        // 迁移只应"新增表", 绝不能碰既有数据 —— 用一张既有表插一行来证明
        helper.createDatabase(TEST_DB, 28).apply {
            val values = ContentValues().apply {
                put("id", "migration-probe-run")
                put("workspaceId", "ws-1")
                put("title", "probe")
                put("prompt", "probe")
                put("toolGroupsJson", "[]")
                put("argsJson", "{}")
                put("status", "SUCCEEDED")
                put("triggerSource", "AI")
                put("createdAt", 1L)
                put("notify", 0)
                put("autoWake", 0)
                put("stepsDone", 0)
                put("toolCalls", 0)
                put("tokensIn", 0)
                put("tokensOut", 0)
                put("maxRuntimeMs", 1000L)
            }
            insert("agent_runs", SQLiteDatabase.CONFLICT_NONE, values)
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 29, true)

        val cursor = db.query("SELECT id FROM agent_runs WHERE id = ?", arrayOf("migration-probe-run"))
        assertEquals("既有行必须原样保留", 1, cursor.count)
        cursor.close()

        // 新表在迁移后应当是可写的（不只是存在）
        val inserted = ContentValues().apply {
            put("id", "after-migration")
            put("workspaceId", "ws-1")
            put("name", "audit")
            put("fingerprint", "fp")
            put("status", "RUNNING")
            put("progressJson", "[]")
            put("agentsCalled", 0)
            put("agentsReused", 0)
            put("createdAt", 2L)
        }
        db.insert("workflow_runs", SQLiteDatabase.CONFLICT_NONE, inserted)
        val wfCursor = db.query("SELECT id FROM workflow_runs WHERE id = ?", arrayOf("after-migration"))
        assertEquals("迁移后的新表应当可写", 1, wfCursor.count)
        wfCursor.close()

        db.close()
    }
}
