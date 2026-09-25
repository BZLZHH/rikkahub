package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v29 -> v30: 建上真正的 workflow 两张表。
 *
 * ## 为什么会有这一步: 一次**版本号撞车**
 *
 * 早先那套被整体移除的实现（"多步骤 shell 流程"）曾经把 Room 推到 **v29**
 * （表 `workspace_workflows` / `workspace_workflow_runs`）。移除时把版本退回了 28 ——
 * 但**代码可以退回过去, 设备不能**: 装过那一版的机器上已经留下了 user_version = 29 的库。
 * 后来真正的 workflow 又把版本推到 29（表变成了 `workflow_runs` / `workflow_agent_results`）,
 * 版本号相同、schema 不同, 于是 Room:
 *
 *   看到 user_version(29) == 目标版本(29) -> **不跑迁移** -> 直接比对 identity hash -> 对不上
 *   -> `Room cannot verify the data integrity ... Expected identity hash: 5597..., found: 6351...`
 *   -> **启动即崩**。
 *
 * 那两个 hash 正是"新 v29"与"老 v29"两份 schema 各自的 identity hash（见 29.json 里的 identityHash）。
 *
 * **教训: 版本号一旦发出去就不能复用。** 回退版本号只能让代码回到过去, 不能让设备回到过去。
 * 以后移除一个已发布的 schema 改动时, 要么**继续往上加版本**, 要么写一个显式迁移把它清干净,
 * 绝不能只是把版本号改小。
 *
 * ## 这个迁移做什么
 *
 * 只做一件事: 用 IF NOT EXISTS 建上两张新表。于是三种设备都对:
 * - v28 的设备: AutoMigration(28,29) 建表 -> 本迁移空操作 -> 校验通过;
 * - 已经是"新 v29"的设备: 本迁移空操作;
 * - **老 v29 的设备（就是崩的那种）**: 本迁移把表补上 -> 校验通过 -> identity hash 被更新。
 *
 * ## 刻意**不**删那两张遗留表
 *
 * `workspace_workflows` / `workspace_workflow_runs` 已经没有任何代码会读, 但删除是**不可逆**的,
 * 而它们里面可能还留着用户当时建的东西。Room 的 schema 校验只检查"该有的表在不在",
 * 不会因为多两张表而报错, 所以留着不影响正确性。
 * 真要清理, 应当单独做一次、并且在那之前明确告诉用户会丢什么。
 */
object Migration_29_30 : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // SQL 必须与 Room 按实体生成的完全一致, 否则迁移后的 schema 校验会失败。
        // 下面两条是从生成的 30.json 里逐字抄下来的。
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `workflow_runs` (" +
                "`id` TEXT NOT NULL, `workspaceId` TEXT NOT NULL, `conversationId` TEXT, " +
                "`assistantId` TEXT, `name` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, " +
                "`scriptPath` TEXT, `status` TEXT NOT NULL, `phase` TEXT, " +
                "`progressJson` TEXT NOT NULL, `resultJson` TEXT, `error` TEXT, " +
                "`agentsCalled` INTEGER NOT NULL, `agentsReused` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `startedAt` INTEGER, `finishedAt` INTEGER, " +
                "`runtimeMs` INTEGER, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `workflow_agent_results` (" +
                "`fingerprint` TEXT NOT NULL, `callIndex` INTEGER NOT NULL, `prompt` TEXT NOT NULL, " +
                "`schemaJson` TEXT, `label` TEXT, `resultText` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`fingerprint`, `callIndex`))"
        )
    }
}
