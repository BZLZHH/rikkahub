package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.WorkflowEntity
import me.rerere.rikkahub.data.db.entity.WorkflowRunEntity

@Dao
interface WorkflowDAO {

    // ---- 定义 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkflow(workflow: WorkflowEntity)

    @Query("SELECT * FROM workspace_workflows WHERE id = :id")
    suspend fun getWorkflow(id: String): WorkflowEntity?

    @Query("SELECT * FROM workspace_workflows WHERE workspace_id = :workspaceId AND name = :name")
    suspend fun getWorkflowByName(workspaceId: String, name: String): WorkflowEntity?

    @Query("SELECT * FROM workspace_workflows WHERE workspace_id = :workspaceId ORDER BY updated_at DESC")
    fun listWorkflowsFlow(workspaceId: String): Flow<List<WorkflowEntity>>

    @Query("SELECT * FROM workspace_workflows ORDER BY updated_at DESC LIMIT :limit")
    fun listRecentWorkflowsFlow(limit: Int = 200): Flow<List<WorkflowEntity>>

    @Query("SELECT * FROM workspace_workflows WHERE workspace_id = :workspaceId ORDER BY updated_at DESC")
    suspend fun listWorkflows(workspaceId: String): List<WorkflowEntity>

    @Query("DELETE FROM workspace_workflows WHERE id = :id")
    suspend fun deleteWorkflow(id: String): Int

    // ---- 运行实例 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(run: WorkflowRunEntity)

    @Query("SELECT * FROM workspace_workflow_runs WHERE id = :id")
    suspend fun getRun(id: String): WorkflowRunEntity?

    @Query("SELECT * FROM workspace_workflow_runs WHERE workflow_id = :workflowId ORDER BY created_at DESC LIMIT :limit")
    suspend fun listRuns(workflowId: String, limit: Int = 20): List<WorkflowRunEntity>

    @Query("SELECT * FROM workspace_workflow_runs WHERE workspace_id = :workspaceId ORDER BY created_at DESC LIMIT :limit")
    fun listRunsFlow(workspaceId: String, limit: Int = 50): Flow<List<WorkflowRunEntity>>

    @Query("SELECT * FROM workspace_workflow_runs ORDER BY created_at DESC LIMIT :limit")
    fun listRecentRunsFlow(limit: Int = 200): Flow<List<WorkflowRunEntity>>

    @Query("SELECT * FROM workspace_workflow_runs WHERE status IN ('PENDING', 'RUNNING')")
    suspend fun listUnfinishedRuns(): List<WorkflowRunEntity>

    @Query("UPDATE workspace_workflow_runs SET status = :status, finished_at = :finishedAt, error = :error WHERE status IN ('PENDING', 'RUNNING')")
    suspend fun markUnfinishedAs(status: String, finishedAt: Long, error: String?)

    @Query("DELETE FROM workspace_workflow_runs WHERE id = :id")
    suspend fun deleteRun(id: String): Int

    @Query("DELETE FROM workspace_workflow_runs WHERE workspace_id = :workspaceId AND status NOT IN ('PENDING', 'RUNNING')")
    suspend fun clearFinishedRuns(workspaceId: String): Int
}
