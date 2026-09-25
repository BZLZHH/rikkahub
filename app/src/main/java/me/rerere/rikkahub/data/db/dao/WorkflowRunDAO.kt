package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.WorkflowAgentEntity
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

    /** UI 用: 运行列表要**跟着状态自己动**, 不能让用户手动刷新。 */
    @Query("SELECT * FROM workflow_runs ORDER BY createdAt DESC LIMIT :limit")
    fun listRecentFlow(limit: Int): Flow<List<WorkflowRunEntity>>

    @Query("SELECT * FROM workflow_runs WHERE id = :id")
    fun getByIdFlow(id: String): Flow<WorkflowRunEntity?>

    /** 入口角标用: 现在有几个 workflow 还没结束。 */
    @Query("SELECT COUNT(*) FROM workflow_runs WHERE status IN ('PENDING', 'RUNNING')")
    fun runningCountFlow(): Flow<Int>

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

    // ---- 逐 agent 的行（UI 右栏）----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAgent(agent: WorkflowAgentEntity)

    @Update
    suspend fun updateAgent(agent: WorkflowAgentEntity)

    @Query("SELECT * FROM workflow_agents WHERE runId = :runId ORDER BY callIndex ASC")
    suspend fun listAgents(runId: String): List<WorkflowAgentEntity>

    /** UI 用: agent 的状态与 token 要边跑边长。 */
    @Query("SELECT * FROM workflow_agents WHERE runId = :runId ORDER BY callIndex ASC")
    fun listAgentsFlow(runId: String): Flow<List<WorkflowAgentEntity>>

    @Query("SELECT * FROM workflow_agents WHERE runId = :runId AND callIndex = :callIndex")
    suspend fun getAgent(runId: String, callIndex: Int): WorkflowAgentEntity?

    /** 子会话页面拿 sessionId 反查它属于哪个 agent（拿标题与统计）。 */
    @Query("SELECT * FROM workflow_agents WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getAgentBySession(sessionId: String): WorkflowAgentEntity?
}
