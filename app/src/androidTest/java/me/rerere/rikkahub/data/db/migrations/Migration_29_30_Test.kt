package me.rerere.rikkahub.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabase_AutoMigration_28_29_Impl
import me.rerere.rikkahub.data.db.AppDatabase_AutoMigration_30_31_Impl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v29 -> v30。
 *
 * 这个测试存在的唯一理由: **真机上已经因为它们崩过一次**。
 *
 * 背景是版本号撞车 —— 早先那套被整体移除的实现也用过 v29（表 workspace_workflows /
 * workspace_workflow_runs）。装过那一版的设备 user_version 已经是 29, 于是 Room 看到
 * "版本相同"就不跑迁移, 直接比对 identity hash 并抛
 * `Room cannot verify the data integrity ... Expected identity hash: 5597..., found: 6351...`。
 *
 * 所以这里不能只测"我的 v29 -> 30"。**必须把设备上那个真实状态复现出来**:
 * 把我的两张表删掉、换成那两张遗留表, 再跑迁移 —— 这才等价于用户的机器。
 */
@RunWith(AndroidJUnit4::class)
class Migration_29_30_Test {
    private val TEST_DB = "migration-29-30-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    /** 把库改成"被移除那套实现留下的 v29"的样子: 没有我的表, 只有它那两张。 */
    private fun SupportSQLiteDatabase.makeItLookLikeTheLegacyV29() {
        execSQL("DROP TABLE IF EXISTS `workflow_runs`")
        execSQL("DROP TABLE IF EXISTS `workflow_agent_results`")
        // 下面两段是从 git 历史里那个已删除的 29.json 逐字抄下来的
        execSQL(
            "CREATE TABLE IF NOT EXISTS `workspace_workflows` (`id` TEXT NOT NULL, " +
                "`workspace_id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT, " +
                "`params_json` TEXT NOT NULL, `steps_json` TEXT NOT NULL, `env_json` TEXT NOT NULL, " +
                "`notify` INTEGER NOT NULL, `auto_wake` INTEGER NOT NULL, " +
                "`max_runtime_ms` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, `last_run_at` INTEGER, `run_count` INTEGER NOT NULL, " +
                "`last_status` TEXT, PRIMARY KEY(`id`))"
        )
        execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_workflows_workspace_id_name` " +
                "ON `workspace_workflows` (`workspace_id`, `name`)"
        )
        execSQL(
            "CREATE TABLE IF NOT EXISTS `workspace_workflow_runs` (`id` TEXT NOT NULL, " +
                "`workflow_id` TEXT NOT NULL, `workspace_id` TEXT NOT NULL, `conversation_id` TEXT, " +
                "`assistant_id` TEXT, `title` TEXT NOT NULL, `args_json` TEXT NOT NULL, " +
                "`resolved_steps_json` TEXT NOT NULL, `step_states_json` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, `trigger_source` TEXT NOT NULL, `current_step` TEXT, " +
                "`created_at` INTEGER NOT NULL, `started_at` INTEGER, `finished_at` INTEGER, " +
                "`runtime_ms` INTEGER, `error` TEXT, `notify` INTEGER NOT NULL, " +
                "`auto_wake` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        execSQL(
            "CREATE INDEX IF NOT EXISTS `index_workspace_workflow_runs_workflow_id_created_at` " +
                "ON `workspace_workflow_runs` (`workflow_id`, `created_at`)"
        )
        execSQL(
            "CREATE INDEX IF NOT EXISTS `index_workspace_workflow_runs_status` " +
                "ON `workspace_workflow_runs` (`status`)"
        )
        execSQL(
            "CREATE INDEX IF NOT EXISTS `index_workspace_workflow_runs_workspace_id_created_at` " +
                "ON `workspace_workflow_runs` (`workspace_id`, `created_at`)"
        )
    }

    /** 这一条复现的就是用户机器上的状态 —— 它挂了, 说明修复没生效。 */
    @Test
    fun migrateLegacyV29ToV30_createsTheRealWorkflowTables() {
        helper.createDatabase(TEST_DB, 29).apply {
            makeItLookLikeTheLegacyV29()
            close()
        }

        // validateDroppedTables = true: 会把迁移后的结构与 30.json 逐列比对
        val db = helper.runMigrationsAndValidate(TEST_DB, 30, true, Migration_29_30)

        val runs = db.query("SELECT * FROM workflow_runs LIMIT 0")
        assertTrue("workflow_runs 应当被补上", runs.columnNames.contains("fingerprint"))
        runs.close()

        val results = db.query("SELECT * FROM workflow_agent_results LIMIT 0")
        assertTrue("workflow_agent_results 应当被补上", results.columnNames.contains("resultText"))
        results.close()

        // 刻意**不删**遗留表（删是不可逆的, 而校验不会因为多两张表失败）——
        // 这里把"刻意留着"也锁进测试, 免得以后有人顺手改成 DROP 而没人注意到。
        val legacy = db.query("SELECT name FROM sqlite_master WHERE type = ? AND name = ?",
            arrayOf("table", "workspace_workflows"))
        assertEquals("遗留表应当被保留（不破坏用户数据）", 1, legacy.count)
        legacy.close()

        db.close()
    }

    /**
     * v28 -> v30 的完整链条。
     *
     * 注意这里**必须显式把自动迁移传进来**: Room 2.8.5 的 MigrationTestHelper 只认显式的
     * Migration / AutoMigrationSpec, **不会**自己去发现 @AutoMigration（javap 过它的字节码,
     * 里面只有 AutoMigrationSpec, 没有 getAutoMigrations）。
     * 这正是先前那条 Migration_28_29_Test 写错的地方 —— 它传了个空列表, 在设备上只会报
     * "A migration from 28 to 29 was required but not found", 属于测试自己搭错了台子。
     *
     * 生成的 impl 是 internal 的, 但 androidTest 与 main 是 Kotlin 的 friend 模块, 可以直接用。
     */
    @Test
    fun migrateV28ToV30_wholeChain() {
        helper.createDatabase(TEST_DB, 28).apply { close() }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 30, true,
            AppDatabase_AutoMigration_28_29_Impl(),
            Migration_29_30,
        )

        val runs = db.query("SELECT * FROM workflow_runs LIMIT 0")
        assertTrue("v28 一路升上来也该有 workflow_runs", runs.columnNames.contains("fingerprint"))
        runs.close()
        db.close()
    }

    /**
     * v30 -> v31: 加"逐 agent 的行" + workflow_runs 加 phasesJson 列。
     *
     * v30 是**已经交出去过**的那版 APK, 所以这一步必须真的能从 30 升上来 ——
     * 不能再犯"复用版本号"的错（那次的后果是启动即崩）。
     */
    @Test
    fun migrateV30ToV31_addsAgentRowsTable() {
        helper.createDatabase(TEST_DB, 30).apply { close() }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 31, true,
            AppDatabase_AutoMigration_30_31_Impl(),
        )

        val agents = db.query("SELECT * FROM workflow_agents LIMIT 0")
        val columns = agents.columnNames.toList()
        agents.close()
        listOf("runId", "callIndex", "label", "phase", "sessionId", "tokensIn").forEach { column ->
            assertTrue("workflow_agents 缺少列 " + column + ", 实际: " + columns, column in columns)
        }

        val runs = db.query("SELECT * FROM workflow_runs LIMIT 0")
        assertTrue("workflow_runs 应当有 phasesJson", runs.columnNames.contains("phasesJson"))
        runs.close()
        db.close()
    }

    @Test
    fun migrateNewV29ToV30_isANoOp() {
        // 已经是"新 v29"的设备: 两张表都在, 迁移必须是空操作（IF NOT EXISTS）
        helper.createDatabase(TEST_DB, 29).apply { close() }

        val db = helper.runMigrationsAndValidate(TEST_DB, 30, true, Migration_29_30)

        val runs = db.query("SELECT * FROM workflow_runs LIMIT 0")
        assertTrue(runs.columnNames.contains("fingerprint"))
        runs.close()
        db.close()
    }

    @Test
    fun migrateLegacyV29ToV30_keepsExistingData() {
        helper.createDatabase(TEST_DB, 29).apply {
            val values = ContentValues().apply {
                put("id", "keep-me")
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
            makeItLookLikeTheLegacyV29()
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 30, true, Migration_29_30)

        val cursor = db.query("SELECT id FROM agent_runs WHERE id = ?", arrayOf("keep-me"))
        assertEquals("既有数据必须原样保留", 1, cursor.count)
        cursor.close()
        db.close()
    }
}
