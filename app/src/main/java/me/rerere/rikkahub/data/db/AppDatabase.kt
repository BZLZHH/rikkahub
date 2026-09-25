package me.rerere.rikkahub.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.AgentRunDAO
import me.rerere.rikkahub.data.db.dao.FolderDAO
import me.rerere.rikkahub.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.dao.WorkspaceJobDAO
import me.rerere.rikkahub.data.db.dao.WorkflowRunDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.db.entity.FolderEntity
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.entity.AgentRunEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceJobDefEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceJobEntity
import me.rerere.rikkahub.data.db.entity.WorkflowAgentEntity
import me.rerere.rikkahub.data.db.entity.WorkflowAgentResultEntity
import me.rerere.rikkahub.data.db.entity.WorkflowRunEntity
import me.rerere.rikkahub.data.db.migrations.Migration_16_17
import me.rerere.rikkahub.data.db.migrations.Migration_22_23
import me.rerere.rikkahub.data.db.migrations.Migration_8_9
import me.rerere.rikkahub.utils.JsonInstant

@Database(
    entities = [
        ConversationEntity::class,
        MemoryEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ManagedFileEntity::class,
        FavoriteEntity::class,
        WorkspaceEntity::class,
        FolderEntity::class,
        WorkspaceJobEntity::class,
        WorkspaceJobDefEntity::class,
        AgentRunEntity::class,
        // workflow: 运行账目 + 可恢复性存档（后者按「脚本指纹 + 调用序号」存每个 agent 的产出）
        WorkflowRunEntity::class,
        WorkflowAgentResultEntity::class,
        // 逐 agent 的行: 高可读性 UI 的右栏（标签 / 模型 / token / 耗时 / 子会话 id）
        WorkflowAgentEntity::class,
    ],
    // v29 这个版本号**被复用出过事故**: 早先那套被移除的实现也用过 v29,
    // 装过它的设备上 user_version 已经是 29, 于是 Room 不跑迁移、直接比对 identity hash 并崩溃。
    // v30 由 Migration_29_30 把两张表补上（见该文件的注释）。
    //
    // v32: workflow_runs 加 modelName（可空, 老行补 NULL 即可）。同样**只加不减**。
    //
    // v31: 加"逐 agent 的行"。**绝不复用版本号** —— 30 已经交出去过（用户手上那版 APK 就是 30）,
    // 复用它会原样重演上面那次事故。
    version = 34,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = Migration_8_9::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 16, to = 17, spec = Migration_16_17::class),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 21, to = 22),
        AutoMigration(from = 22, to = 23, spec = Migration_22_23::class),
        AutoMigration(from = 23, to = 24),
        AutoMigration(from = 24, to = 25),
        AutoMigration(from = 25, to = 26),
        AutoMigration(from = 26, to = 27),
        AutoMigration(from = 27, to = 28),
        // 只新增两张表, 没有改动既有列, 所以是纯粹的自动迁移
        AutoMigration(from = 28, to = 29),
        // v30 -> v31: 只新增一张表 + workflow_runs 加一列, 纯自动迁移。
        AutoMigration(from = 30, to = 31),
        // v31 -> v32: 只加一列可空列, 纯自动迁移
        AutoMigration(from = 31, to = 32),
        // v32 -> v33: workflow_agents 加 reasoning（可空列, 纯自动迁移）
        AutoMigration(from = 32, to = 33),
        // v33 -> v34: workflow_agents 加 schemaJson（可空列）, 为了能"照着原样重试"
        AutoMigration(from = 33, to = 34),
    ]
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun managedFileDao(): ManagedFileDAO

    abstract fun favoriteDao(): FavoriteDAO

    abstract fun workspaceDao(): WorkspaceDAO

    abstract fun workspaceJobDao(): WorkspaceJobDAO

    abstract fun folderDao(): FolderDAO

    abstract fun agentRunDao(): AgentRunDAO

    abstract fun workflowRunDao(): WorkflowRunDAO
}

object TokenUsageConverter {
    @TypeConverter
    fun fromTokenUsage(usage: TokenUsage?): String {
        return JsonInstant.encodeToString(usage)
    }

    @TypeConverter
    fun toTokenUsage(usage: String): TokenUsage? {
        return JsonInstant.decodeFromString(usage)
    }
}
