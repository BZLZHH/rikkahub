package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.model.MemoryFile

/**
 * v25 is the last shared schema. Enhanced shipped v26..36, while upstream independently
 * shipped incompatible v26..34. In particular, Enhanced v29 also has a retired workflow
 * variant. A version number alone therefore cannot identify the schema being upgraded.
 *
 * Go straight to a NEW version from every forked version; never run the other branch's
 * auto migrations. Inspect actual tables/columns, add only missing structures, and run
 * upstream data conversions only when their destination table did not already exist.
 * The bridge leaves any upstream storage_mounts column in place for Migration_36_37.
 * Room wraps the upgrade in a transaction and validates the final schema.
 * Legacy MemoryEntity and retired workflow tables are deliberately retained, with data.
 */
internal val MigrationsTo36: Array<Migration> = (25..35).map { from ->
    object : Migration(from, 36) {
        override fun migrate(db: SupportSQLiteDatabase) = migrateTo36(db)
    }
}.toTypedArray()

private fun migrateTo36(db: SupportSQLiteDatabase) {
    val needsMemoryImport = !db.hasTable("memory_file") && db.hasTable("MemoryEntity")
    val needsConsolidation = !db.hasTable("memory_consolidation")
    val needsUsageBackfill = !db.hasTable("usage_record")

    createUpstreamTables(db)
    db.addColumnIfMissing("ConversationEntity", "config", "TEXT NOT NULL DEFAULT ''")
    db.addColumnIfMissing("workspaces", "type", "TEXT NOT NULL DEFAULT 'LOCAL'")
    db.addColumnIfMissing("workspaces", "ssh_config", "TEXT")

    createEnhancedTables(db)
    db.addColumnIfMissing("workspace_jobs", "reason", "TEXT")
    db.addColumnIfMissing("workspace_job_defs", "description", "TEXT")
    db.addColumnIfMissing("workflow_runs", "phasesJson", "TEXT NOT NULL DEFAULT '[]'")
    db.addColumnIfMissing("workflow_runs", "modelName", "TEXT")
    db.addColumnIfMissing("workflow_runs", "notify", "INTEGER NOT NULL DEFAULT 1")
    db.addColumnIfMissing("workflow_runs", "autoWake", "INTEGER NOT NULL DEFAULT 0")
    db.addColumnIfMissing("workflow_agents", "reasoning", "TEXT")
    db.addColumnIfMissing("workflow_agents", "schemaJson", "TEXT")

    if (needsMemoryImport) importLegacyMemories(db)
    if (needsConsolidation) Migration_28_29().onPostMigrate(db)
    if (needsUsageBackfill) Migration_30_31.migrate(db)
    Migration_32_33.migrate(db)
}

private fun SupportSQLiteDatabase.hasTable(table: String): Boolean = query(
    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
    arrayOf(table),
).use { it.moveToFirst() }

internal fun SupportSQLiteDatabase.addColumnIfMissing(table: String, column: String, definition: String) {
    val exists = query("PRAGMA table_info(`$table`)").use { cursor ->
        val name = cursor.getColumnIndexOrThrow("name")
        var found = false
        while (cursor.moveToNext()) if (cursor.getString(name) == column) found = true
        found
    }
    if (!exists) execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
}

private fun importLegacyMemories(db: SupportSQLiteDatabase) {
    val memories = linkedMapOf<String, MutableList<String>>()
    db.query("SELECT assistant_id, content FROM MemoryEntity ORDER BY id").use { cursor ->
        while (cursor.moveToNext()) {
            memories.getOrPut(cursor.getString(0)) { mutableListOf() }.add(cursor.getString(1))
        }
    }
    val now = System.currentTimeMillis()
    memories.forEach { (assistantId, contents) ->
        val profile = legacyMemoriesToProfile(contents) ?: return@forEach
        db.execSQL(
            "INSERT INTO memory_file (assistant_id, path, content, updated_at) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(assistantId, MemoryFile.PROFILE_PATH, profile, now),
        )
    }
}

// SQL copied from the shipped upstream v33 and Enhanced v35 exports. Existing tables
// are never rebuilt: CREATE IF NOT EXISTS + explicit additive columns preserves rows.
private fun createUpstreamTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS `memory_file` (`assistant_id` TEXT NOT NULL,
            `path` TEXT NOT NULL,
            `content` TEXT NOT NULL,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`assistant_id`,
            `path`))""".trimIndent()
    )
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS `memory_consolidation` (`conversation_id` TEXT NOT NULL,
            `consolidated_at` INTEGER NOT NULL,
            PRIMARY KEY(`conversation_id`),
            FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""".trimIndent()
    )
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS `media_creation_session` (`id` TEXT NOT NULL,
            `title` TEXT NOT NULL,
            `draft` TEXT NOT NULL,
            `create_at` INTEGER NOT NULL,
            `update_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`))""".trimIndent()
    )
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS `media_creation_node` (`id` TEXT NOT NULL,
            `session_id` TEXT NOT NULL,
            `selected_record_id` TEXT NOT NULL,
            `create_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`session_id`) REFERENCES `media_creation_session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""".trimIndent()
    )
    db.execSQL(
        """CREATE INDEX IF NOT EXISTS `index_media_creation_node_session_id` ON `media_creation_node` (`session_id`)""".trimIndent()
    )
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS `media_creation_record` (`id` TEXT NOT NULL,
            `session_id` TEXT NOT NULL,
            `node_id` TEXT NOT NULL,
            `provider_id` TEXT NOT NULL,
            `provider_name` TEXT NOT NULL,
            `model_id` TEXT NOT NULL,
            `kind` TEXT NOT NULL,
            `prompt` TEXT NOT NULL,
            `params` TEXT NOT NULL,
            `inputs` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `task_id` TEXT,
            `error` TEXT,
            `outputs` TEXT NOT NULL,
            `create_at` INTEGER NOT NULL,
            `update_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`session_id`) REFERENCES `media_creation_session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE ,
            FOREIGN KEY(`node_id`) REFERENCES `media_creation_node`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""".trimIndent()
    )
    db.execSQL(
        """CREATE INDEX IF NOT EXISTS `index_media_creation_record_session_id` ON `media_creation_record` (`session_id`)""".trimIndent()
    )
    db.execSQL(
        """CREATE INDEX IF NOT EXISTS `index_media_creation_record_node_id` ON `media_creation_record` (`node_id`)""".trimIndent()
    )
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS `translation_history` (`id` TEXT NOT NULL,
            `source_text` TEXT NOT NULL,
            `translated_text` TEXT NOT NULL,
            `target_language` TEXT NOT NULL,
            `model_id` TEXT NOT NULL,
            `create_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`))""".trimIndent()
    )
    db.execSQL(
        """CREATE INDEX IF NOT EXISTS `index_translation_history_create_at` ON `translation_history` (`create_at`)""".trimIndent()
    )
}

private fun createEnhancedTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS `workspace_jobs` (`id` TEXT NOT NULL,
            `workspace_id` TEXT NOT NULL,
            `def_id` TEXT,
            `assistant_id` TEXT,
            `conversation_id` TEXT,
            `name` TEXT NOT NULL,
            `reason` TEXT,
            `command` TEXT NOT NULL,
            `cwd` TEXT NOT NULL,
            `args_json` TEXT NOT NULL,
            `mode` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `exit_code` INTEGER,
            `pid` INTEGER,
            `trigger_source` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            `started_at` INTEGER,
            `finished_at` INTEGER,
            `runtime_ms` INTEGER,
            `log_bytes_out` INTEGER NOT NULL,
            `log_bytes_err` INTEGER NOT NULL,
            `log_truncated` INTEGER NOT NULL,
            `error` TEXT,
            `deferred_reason` TEXT,
            `wake_state` TEXT NOT NULL,
            `notify` INTEGER NOT NULL,
            `auto_wake` INTEGER NOT NULL,
            `max_runtime_ms` INTEGER NOT NULL,
            PRIMARY KEY(`id`))""".trimIndent()
    )
    db.execSQL(
        """CREATE INDEX IF NOT EXISTS `index_workspace_jobs_workspace_id_created_at` ON `workspace_jobs` (`workspace_id`,
            `created_at`)""".trimIndent()
    )
    db.execSQL(
        """CREATE INDEX IF NOT EXISTS `index_workspace_jobs_status` ON `workspace_jobs` (`status`)""".trimIndent()
    )
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS `workspace_job_defs` (`id` TEXT NOT NULL,
            `workspace_id` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `command` TEXT NOT NULL,
            `description` TEXT,
            `cwd` TEXT NOT NULL,
            `mode` TEXT NOT NULL,
            `params_json` TEXT NOT NULL,
            `env_json` TEXT NOT NULL,
            `trigger_json` TEXT,
            `enabled` INTEGER NOT NULL,
            `notify` INTEGER NOT NULL,
            `auto_wake` INTEGER NOT NULL,
            `max_runtime_ms` INTEGER NOT NULL,
            `require_keep_alive` INTEGER NOT NULL,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            `last_run_at` INTEGER,
            `next_run_at` INTEGER,
            `run_count` INTEGER NOT NULL,
            `last_status` TEXT,
            PRIMARY KEY(`id`))""".trimIndent()
    )
    db.execSQL(
        """CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_job_defs_workspace_id_name` ON `workspace_job_defs` (`workspace_id`,
            `name`)""".trimIndent()
    )
    db.execSQL(
        """CREATE INDEX IF NOT EXISTS `index_workspace_job_defs_enabled_next_run_at` ON `workspace_job_defs` (`enabled`,
            `next_run_at`)""".trimIndent()
    )
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS `agent_runs` (`id` TEXT NOT NULL,
            `workspace_id` TEXT NOT NULL,
            `conversation_id` TEXT,
            `assistant_id` TEXT,
            `title` TEXT NOT NULL,
            `prompt` TEXT NOT NULL,
            `model_id` TEXT,
            `tool_groups_json` TEXT NOT NULL,
            `args_json` TEXT NOT NULL,
            `result_json` TEXT,
            `transcript` TEXT,
            `status` TEXT NOT NULL,
            `steps_done` INTEGER NOT NULL,
            `tool_calls` INTEGER NOT NULL,
            `tokens_in` INTEGER NOT NULL,
            `tokens_out` INTEGER NOT NULL,
            `trigger_source` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            `started_at` INTEGER,
            `finished_at` INTEGER,
            `runtime_ms` INTEGER,
            `error` TEXT,
            `notify` INTEGER NOT NULL,
            `auto_wake` INTEGER NOT NULL,
            `max_runtime_ms` INTEGER NOT NULL,
            PRIMARY KEY(`id`))""".trimIndent()
    )
    db.execSQL(
        """CREATE INDEX IF NOT EXISTS `index_agent_runs_workspace_id_created_at` ON `agent_runs` (`workspace_id`,
            `created_at`)""".trimIndent()
    )
    db.execSQL(
        """CREATE INDEX IF NOT EXISTS `index_agent_runs_status` ON `agent_runs` (`status`)""".trimIndent()
    )
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS `workflow_runs` (`id` TEXT NOT NULL,
            `workspaceId` TEXT NOT NULL,
            `conversationId` TEXT,
            `assistantId` TEXT,
            `name` TEXT NOT NULL,
            `fingerprint` TEXT NOT NULL,
            `modelName` TEXT,
            `scriptPath` TEXT,
            `status` TEXT NOT NULL,
            `phase` TEXT,
            `progressJson` TEXT NOT NULL,
            `phasesJson` TEXT NOT NULL DEFAULT '[]',
            `resultJson` TEXT,
            `error` TEXT,
            `agentsCalled` INTEGER NOT NULL,
            `agentsReused` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `startedAt` INTEGER,
            `finishedAt` INTEGER,
            `runtimeMs` INTEGER,
            `notify` INTEGER NOT NULL DEFAULT 1,
            `autoWake` INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(`id`))""".trimIndent()
    )
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS `workflow_agent_results` (`fingerprint` TEXT NOT NULL,
            `callIndex` INTEGER NOT NULL,
            `prompt` TEXT NOT NULL,
            `schemaJson` TEXT,
            `label` TEXT,
            `resultText` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            PRIMARY KEY(`fingerprint`,
            `callIndex`))""".trimIndent()
    )
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS `workflow_agents` (`runId` TEXT NOT NULL,
            `callIndex` INTEGER NOT NULL,
            `label` TEXT,
            `prompt` TEXT NOT NULL,
            `schemaJson` TEXT,
            `phase` TEXT,
            `status` TEXT NOT NULL,
            `reused` INTEGER NOT NULL,
            `modelId` TEXT,
            `modelName` TEXT,
            `steps` INTEGER NOT NULL,
            `toolCalls` INTEGER NOT NULL,
            `tokensIn` INTEGER NOT NULL,
            `tokensOut` INTEGER NOT NULL,
            `reasoning` TEXT,
            `sessionId` TEXT,
            `resultText` TEXT,
            `error` TEXT,
            `startedAt` INTEGER NOT NULL,
            `finishedAt` INTEGER,
            `runtimeMs` INTEGER,
            PRIMARY KEY(`runId`,
            `callIndex`))""".trimIndent()
    )
}
