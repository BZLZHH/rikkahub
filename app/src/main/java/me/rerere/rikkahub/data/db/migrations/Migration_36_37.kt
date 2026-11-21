package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val Migration_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Upstream v34 already has this column. Preserve its mount list when the v36 bridge runs first.
        db.addColumnIfMissing("workspaces", "storage_mounts", "TEXT NOT NULL DEFAULT '[]'")
    }
}
