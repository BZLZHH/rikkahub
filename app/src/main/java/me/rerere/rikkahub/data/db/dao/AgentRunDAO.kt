package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.AgentRunEntity

@Dao
interface AgentRunDAO {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(run: AgentRunEntity)

    @Query("SELECT * FROM agent_runs WHERE id = :id")
    suspend fun getById(id: String): AgentRunEntity?

    @Query("SELECT * FROM agent_runs WHERE workspace_id = :workspaceId ORDER BY created_at DESC LIMIT :limit")
    fun listFlow(workspaceId: String, limit: Int = 50): Flow<List<AgentRunEntity>>

    @Query("SELECT * FROM agent_runs ORDER BY created_at DESC LIMIT :limit")
    fun listRecentFlow(limit: Int = 200): Flow<List<AgentRunEntity>>

    @Query("SELECT * FROM agent_runs ORDER BY created_at DESC LIMIT :limit")
    suspend fun listRecent(limit: Int = 200): List<AgentRunEntity>

    @Query("SELECT * FROM agent_runs WHERE status = 'RUNNING'")
    suspend fun listRunning(): List<AgentRunEntity>

    @Query("SELECT * FROM agent_runs WHERE status = 'RUNNING'")
    fun listRunningFlow(): Flow<List<AgentRunEntity>>

    @Query("UPDATE agent_runs SET status = :status, finished_at = :finishedAt, error = :error WHERE status = 'RUNNING'")
    suspend fun markRunningAs(status: String, finishedAt: Long, error: String?)

    @Query("DELETE FROM agent_runs WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM agent_runs WHERE workspace_id = :workspaceId AND status NOT IN ('RUNNING', 'PENDING')")
    suspend fun clearFinished(workspaceId: String): Int

    @Query("DELETE FROM agent_runs WHERE workspace_id = :workspaceId")
    suspend fun deleteByWorkspace(workspaceId: String): Int
}
