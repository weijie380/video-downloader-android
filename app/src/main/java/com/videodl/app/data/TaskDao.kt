package com.videodl.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("SELECT * FROM download_tasks ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks ORDER BY createdAt ASC, id ASC")
    suspend fun all(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun byId(id: Long): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE status = :status ORDER BY createdAt ASC, id ASC LIMIT 1")
    suspend fun firstWithStatus(status: String): DownloadTaskEntity?

    @Query("SELECT COUNT(*) FROM download_tasks WHERE status IN (:statuses)")
    suspend fun countWithStatuses(statuses: List<String>): Int

    @Insert
    suspend fun insert(task: DownloadTaskEntity): Long

    @Update
    suspend fun update(task: DownloadTaskEntity)

    /** 保存结束与取消互斥，避免取消后又显示下载成功。 */
    @Transaction
    suspend fun completeIfSaving(task: DownloadTaskEntity): Boolean {
        if (byId(task.id)?.status != TaskStatus.SAVING.name) return false
        update(task)
        return true
    }

    @Query("UPDATE download_tasks SET status='QUEUED', updatedAt=:now WHERE id=:id AND status='READY'")
    suspend fun enqueueIfReady(id: Long, now: Long): Int

    @Query("""UPDATE download_tasks SET status='DOWNLOADING', progress=0,
        downloadedBytes=0, totalBytes=0, speedText=NULL, etaSeconds=0, errorMessage=NULL,
        updatedAt=:now WHERE id=:id AND status='QUEUED'""")
    suspend fun claim(id: Long, now: Long): Int

    /** 两个 worker 领取时必须在同一事务内读取并转换状态。 */
    @Transaction
    suspend fun claimNextQueued(): DownloadTaskEntity? {
        val task = firstWithStatus(TaskStatus.QUEUED.name) ?: return null
        if (claim(task.id, System.currentTimeMillis()) == 0) return null
        return task.copy(status = TaskStatus.DOWNLOADING.name)
    }

    @Query("""UPDATE download_tasks SET status='FAILED', speedText=NULL, etaSeconds=0,
        errorMessage=:reason, updatedAt=:now WHERE id=:id AND status IN ('DOWNLOADING','MERGING','SAVING')""")
    suspend fun failIfActive(id: Long, reason: String, now: Long): Int

    @Query("""UPDATE download_tasks SET status='SAVING', updatedAt=:now
        WHERE id=:id AND status IN ('DOWNLOADING','MERGING')""")
    suspend fun beginSaving(id: Long, now: Long): Int

    @Query("""UPDATE download_tasks SET status='CANCELED', speedText=NULL,
        etaSeconds=0, errorMessage=NULL, updatedAt=:now
        WHERE id=:id AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELED')""")
    suspend fun cancelIfActive(id: Long, now: Long): Int

    @Query("""UPDATE download_tasks SET status='CANCELED', speedText=NULL,
        etaSeconds=0, errorMessage=NULL, updatedAt=:now
        WHERE id IN (:ids) AND status NOT IN ('COMPLETED','FAILED','CANCELED')""")
    suspend fun cancelBatchIfActive(ids: List<Long>, now: Long): Int

    /** 进度回调可能晚到，不能覆盖取消、保存或完成状态。 */
    @Query("""
        UPDATE download_tasks SET status=:status, progress=:progress,
        downloadedBytes=:downloaded, totalBytes=:total, speedText=:speed,
        etaSeconds=:eta, updatedAt=:now
        WHERE id=:id AND status IN ('DOWNLOADING', 'MERGING')
    """)
    suspend fun updateProgress(id: Long, status: String, progress: Float, downloaded: Long,
        total: Long, speed: String?, eta: Long, now: Long): Int

    @Query("UPDATE download_tasks SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, now: Long = System.currentTimeMillis())

    @Query(
        """
        UPDATE download_tasks
           SET status = :status,
               errorMessage = :reason,
               progress = 0,
               downloadedBytes = 0,
               totalBytes = 0,
               speedText = NULL,
               etaSeconds = 0,
               updatedAt = :now
         WHERE id = :id
        """
    )
    suspend fun resetTo(
        id: Long,
        status: String,
        reason: String?,
        now: Long = System.currentTimeMillis(),
    )

    @Query(
        """
        UPDATE download_tasks
           SET status = 'FAILED',
               errorMessage = :reason,
               speedText = NULL,
               updatedAt = :now
         WHERE status IN ('DOWNLOADING', 'MERGING', 'SAVING', 'QUEUED', 'PARSING')
        """
    )
    suspend fun failInterrupted(reason: String, now: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM download_tasks WHERE status IN ('COMPLETED', 'FAILED', 'CANCELED')")
    suspend fun deleteFinished(): Int

    @Query("DELETE FROM download_tasks")
    suspend fun deleteAll()
}
