package com.videodl.app.data

import com.videodl.app.ytdlp.GallerySelection
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 任务状态。字符串形式写入数据库，避免 Room TypeConverter 的额外复杂度。 */
enum class TaskStatus {
    /** 刚加入队列，尚未解析 */
    PENDING,

    /** 正在解析链接 */
    PARSING,

    /** 解析成功，等待用户确认画质 */
    READY,

    /** 已入队，等待下载 */
    QUEUED,

    /** 正在下载媒体流 */
    DOWNLOADING,

    /** 正在用 FFmpeg 合并音视频 */
    MERGING,

    /** 正在写入系统下载目录 */
    SAVING,

    /** 完成 */
    COMPLETED,

    /** 失败 */
    FAILED,

    /** 已取消 */
    CANCELED;

    /** 占用下载线程的状态 */
    val isRunning: Boolean
        get() = this == PARSING || this == QUEUED ||
            this == DOWNLOADING || this == MERGING || this == SAVING

    /** 终态，不再变化 */
    val isFinished: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELED
}

@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    /** 原始链接 */
    val url: String,

    /** 解析时成功的规范地址与接口，下载继续使用同一路径。 */
    val resolvedUrl: String? = null,
    val resolver: String? = null,
    /** 官方分享页媒体元数据；不保存 Cookie。 */
    val downloadInfoJson: String? = null,
    /** 图片总数及用户勾选的原帖序号；null 表示尚未选择。 */
    val gallerySelection: String? = null,

    /** 平台标识：douyin / bilibili / x / other */
    val platform: String,

    /** B 站分 P 序号（1 起）；其他平台为 null */
    val pageIndex: Int? = null,

    /** 解析出的标题 */
    val title: String? = null,

    /** 解析出的作者 */
    val uploader: String? = null,

    /** 时长（秒） */
    val durationSeconds: Int = 0,

    /** 缩略图地址（仅展示用途） */
    val thumbnailUrl: String? = null,

    /** 用户选定的画质：传给 yt-dlp 的 -f 表达式 */
    val formatSelector: String? = null,

    /** 用户选定的画质：界面展示文案 */
    val formatLabel: String? = null,

    /** 解析得到的可选画质列表（JSON 缓存，便于重开 App 后继续选择） */
    val formatOptionsJson: String? = null,

    /** [TaskStatus] 的名称 */
    val status: String = TaskStatus.PENDING.name,

    /** 0f..1f */
    val progress: Float = 0f,

    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,

    /** 形如 "1.2 MB/s" */
    val speedText: String? = null,
    val etaSeconds: Long = 0L,

    /** 失败时展示给用户的具体原因 */
    val errorMessage: String? = null,

    /** 落地到系统下载目录之后的文件名 */
    val outputFileName: String? = null,

    /** 落地后的 content:// URI */
    val outputUri: String? = null,
    val outputBytes: Long = 0L,
    /** 图文保存的全部图片及文案文件。旧视频任务为 null。 */
    val outputFilesJson: String? = null,

    /** 最终文件是否为带声音的 MP4 */
    val outputMp4: Boolean = false,

    /** 额外说明，例如「源格式无法无损封装为 MP4，已保留 WebM」 */
    val outputNote: String? = null,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val isGallery: Boolean get() = resolver == "douyin_gallery"
    val canDownload: Boolean get() = !isGallery ||
        GallerySelection.decode(gallerySelection)?.indices?.isNotEmpty() == true
    val statusEnum: TaskStatus
        get() = runCatching { TaskStatus.valueOf(status) }.getOrDefault(TaskStatus.PENDING)
}
