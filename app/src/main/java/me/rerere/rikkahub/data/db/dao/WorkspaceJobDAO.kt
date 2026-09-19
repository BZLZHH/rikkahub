package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.WorkspaceJobDefEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceJobEntity

@Dao
interface WorkspaceJobDAO {

    // ---- 运行实例 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJob(job: WorkspaceJobEntity)

    @Query("SELECT * FROM workspace_jobs WHERE id = :id")
    suspend fun getJob(id: String): WorkspaceJobEntity?

    @Query("SELECT * FROM workspace_jobs WHERE workspace_id = :workspaceId ORDER BY created_at DESC LIMIT :limit")
    fun listJobsFlow(workspaceId: String, limit: Int): Flow<List<WorkspaceJobEntity>>

    @Query("SELECT * FROM workspace_jobs WHERE workspace_id = :workspaceId ORDER BY created_at DESC LIMIT :limit")
    suspend fun listJobs(workspaceId: String, limit: Int): List<WorkspaceJobEntity>

    @Query("SELECT * FROM workspace_jobs WHERE status = 'RUNNING'")
    suspend fun listRunningJobs(): List<WorkspaceJobEntity>

    @Query("SELECT * FROM workspace_jobs ORDER BY created_at DESC LIMIT :limit")
    fun listRecentJobsFlow(limit: Int): Flow<List<WorkspaceJobEntity>>

    @Query("SELECT * FROM workspace_jobs WHERE status = 'RUNNING' ORDER BY created_at DESC")
    fun listRunningJobsFlow(): Flow<List<WorkspaceJobEntity>>

    @Query("SELECT COUNT(*) FROM workspace_jobs WHERE status = 'RUNNING'")
    suspend fun countRunningJobs(): Int

    @Query("SELECT COUNT(*) FROM workspace_jobs WHERE status = 'RUNNING' AND workspace_id = :workspaceId")
    suspend fun countRunningJobsInWorkspace(workspaceId: String): Int

    @Query("SELECT * FROM workspace_jobs WHERE status = 'DEFERRED' ORDER BY created_at ASC")
    suspend fun listDeferredJobs(): List<WorkspaceJobEntity>

    @Query("UPDATE workspace_jobs SET status = :status, exit_code = :exitCode, error = :error, finished_at = :finishedAt WHERE status = 'RUNNING'")
    suspend fun markRunningAs(status: String, exitCode: Int?, error: String?, finishedAt: Long)

    @Query("UPDATE workspace_jobs SET wake_state = :state WHERE id = :id")
    suspend fun updateWakeState(id: String, state: String)

    @Query("DELETE FROM workspace_jobs WHERE id = :id")
    suspend fun deleteJob(id: String): Int

    @Query("DELETE FROM workspace_jobs WHERE workspace_id = :workspaceId AND status NOT IN ('RUNNING', 'PENDING')")
    suspend fun clearFinished(workspaceId: String): Int

    @Query("DELETE FROM workspace_jobs WHERE def_id = :defId AND status NOT IN ('RUNNING', 'PENDING')")
    suspend fun deleteFinishedByDef(defId: String)

    // ---- 任务定义 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDef(def: WorkspaceJobDefEntity)

    @Query("SELECT * FROM workspace_job_defs WHERE id = :id")
    suspend fun getDef(id: String): WorkspaceJobDefEntity?

    @Query("SELECT * FROM workspace_job_defs WHERE workspace_id = :workspaceId ORDER BY name ASC")
    fun listDefsFlow(workspaceId: String): Flow<List<WorkspaceJobDefEntity>>

    @Query("SELECT * FROM workspace_job_defs WHERE workspace_id = :workspaceId ORDER BY name ASC")
    suspend fun listDefs(workspaceId: String): List<WorkspaceJobDefEntity>

    @Query("SELECT * FROM workspace_job_defs WHERE workspace_id = :workspaceId AND name = :name LIMIT 1")
    suspend fun getDefByName(workspaceId: String, name: String): WorkspaceJobDefEntity?

    @Query("SELECT * FROM workspace_job_defs WHERE enabled = 1 AND trigger_json IS NOT NULL")
    suspend fun listScheduledDefs(): List<WorkspaceJobDefEntity>

    @Query("DELETE FROM workspace_job_defs WHERE id = :id")
    suspend fun deleteDef(id: String): Int

    @Query("DELETE FROM workspace_job_defs WHERE workspace_id = :workspaceId")
    suspend fun deleteDefsByWorkspace(workspaceId: String): Int
}
