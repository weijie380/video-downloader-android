package com.videodl.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.videodl.app.data.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VideoDlApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        TaskRepository.init(this)
        createNotificationChannels(this)
        appScope.launch {
            // 进程重启后，之前 DOWNLOADING/MERGING/SAVING 的任务不可能还在跑，
            // 必须显式失败，否则界面会一直停在虚假的进度上。QUEUED 的任务保留，稍后由队列继续。
            TaskRepository.failInterrupted("App 进程已退出，下载被中断，请点重试")
        }
    }

    companion object {
        lateinit var instance: VideoDlApp
            private set

        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        const val CHANNEL_PROGRESS = "download_progress"
        const val CHANNEL_RESULT = "download_result"

        fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val progress = NotificationChannel(
                CHANNEL_PROGRESS,
                "下载进度",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "显示正在进行的下载任务" }

            val result = NotificationChannel(
                CHANNEL_RESULT,
                "下载结果",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "下载完成或失败时提醒" }

            manager.createNotificationChannels(listOf(progress, result))
        }
    }
}