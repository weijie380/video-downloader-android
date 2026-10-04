package com.videodl.app.download

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** 固定两个下载位置；完成一条便继续领取下一条。 */
object DownloadWorkers {
    const val LIMIT = 2

    suspend fun <T> run(claim: suspend () -> T?, hasWork: suspend () -> Boolean,
                        execute: suspend (T) -> Unit) = coroutineScope {
        repeat(LIMIT) {
            launch {
                while (true) {
                    val task = claim()
                    if (task == null) {
                        // 另一条仍在运行时保持空闲位置，用户新加任务可以立即开始。
                        if (!hasWork()) break
                        delay(200)
                        continue
                    }
                    execute(task)
                }
            }
        }
    }
}
