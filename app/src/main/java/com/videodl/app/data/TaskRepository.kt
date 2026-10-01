package com.videodl.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * 任务队列的唯一入口。所有读写都经过这里，保证串行 Worker 与界面看到同一份状态。
 */
object TaskRepository {

    @Volatile
    private var dao: TaskDao? = null

    fun init(context: Context) {
        if (dao == null) {
            synchronized(this) {
                if (dao == null) dao = AppDatabase.get(context).taskDao()
            }
        }
    }

    private val taskDao: TaskDao
        get() = dao ?: error("TaskRepository 未初始化，请在 Application 中调用 init()")

    val tasks: Flow<List<DownloadTaskEntity>> get() = taskDao.observeAll()

    suspend fun all(): List<DownloadTaskEntity> = taskDao.all()

    suspend fun byId(id: Long): DownloadTaskEntity? = taskDao.byId(id)

    suspend fun insert(task: DownloadTaskEntity): Long = taskDao.insert(task)

    suspend fun update(task: DownloadTaskEntity) =
        taskDao.update(task.copy(updatedAt = System.currentTimeMillis()))

    suspend fun save(task: DownloadTaskEntity) {
        taskDao.update(task.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun completeGallery(task: DownloadTaskEntity): Boolean =
        taskDao.completeGalleryIfSaving(task.copy(updatedAt = System.currentTimeMillis()))

    suspend fun cancelIfActive(id: Long) = taskDao.cancelIfActive(id, System.currentTimeMillis())

    suspend fun updateProgress(id: Long, status: TaskStatus, progress: Float, downloaded: Long,
        total: Long, speed: String?, eta: Long): Boolean =
        taskDao.updateProgress(id, status.name, progress, downloaded, total, speed, eta,
            System.currentTimeMillis()) > 0

    suspend fun setStatus(id: Long, status: TaskStatus) = taskDao.setStatus(id, status.name)

    suspend fun resetTo(id: Long, status: TaskStatus, reason: String? = null) =
        taskDao.resetTo(id, status.name, reason)

    suspend fun nextQueued(): DownloadTaskEntity? =
        taskDao.firstWithStatus(TaskStatus.QUEUED.name)

    suspend fun busyCount(): Int = taskDao.countWithStatuses(
        listOf(
            TaskStatus.PARSING.name,
            TaskStatus.QUEUED.name,
            TaskStatus.DOWNLOADING.name,
            TaskStatus.MERGING.name,
            TaskStatus.SAVING.name,
        )
    )

    suspend fun hasQueuedOrRunning(): Boolean = taskDao.countWithStatuses(
        listOf(
            TaskStatus.QUEUED.name,
            TaskStatus.DOWNLOADING.name,
            TaskStatus.MERGING.name,
            TaskStatus.SAVING.name,
        )
    ) > 0

    suspend fun delete(id: Long) = taskDao.deleteById(id)

    suspend fun deleteFinished() = taskDao.deleteFinished()

    suspend fun deleteAll() = taskDao.deleteAll()

    /**
     * App 进程被系统回收后，之前处于进行中的任务不可能再继续，
     * 统一标记为失败并给出可读原因，避免界面停留在假的进度上。
     */
    suspend fun failInterrupted(reason: String): Int = taskDao.failInterrupted(reason)
}
