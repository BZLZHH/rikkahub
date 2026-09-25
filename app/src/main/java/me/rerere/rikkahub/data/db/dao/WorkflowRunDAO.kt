package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.WorkflowAgentResultEntity
import me.rerere.rikkahub.data.db.entity.WorkflowRunEntity

@Dao
interface WorkflowRunDAO {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(run: WorkflowRunEntity)

    @Query("SELECT * FROM workflow_runs WHERE id = :id")
    suspend fun getById(id: String): WorkflowRunEntity?

    @Query("SELECT * FROM workflow_runs ORDER BY createdAt DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<WorkflowRunEntity>

    /** App 重启后仍标着未结束的运行: 协程已随进程消失, 需要收敛成中断。 */
    @Query("SELECT * FROM workflow_runs WHERE status IN (:unfinished)")
    suspend fun listByStatus(unfinished: List<String>): List<WorkflowRunEntity>

    @Query("DELETE FROM workflow_runs WHERE id = :id")
    suspend fun delete(id: String)

    // ---- 可恢复性的存档 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun rememberAgent(record: WorkflowAgentResultEntity)

    @Query("SELECT * FROM workflow_agent_results WHERE fingerprint = :fingerprint AND callIndex = :callIndex")
    suspend fun recallAgent(fingerprint: String, callIndex: Int): WorkflowAgentResultEntity?

    @Query("DELETE FROM workflow_agent_results WHERE fingerprint = :fingerprint")
    suspend fun forgetFingerprint(fingerprint: String)
}
