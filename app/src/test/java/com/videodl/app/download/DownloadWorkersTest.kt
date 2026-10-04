package com.videodl.app.download

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

class DownloadWorkersTest {
    @Test fun twoSlotsOverlapAndEveryTaskRunsOnce() = runBlocking {
        val queue = ArrayDeque((1..6).toList())
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val seen = mutableListOf<Int>()
        withTimeout(5000) {
            DownloadWorkers.run(
                claim = { synchronized(queue) { queue.pollFirst()?.also { active.incrementAndGet() } } },
                hasWork = { active.get() > 0 || synchronized(queue) { queue.isNotEmpty() } },
            ) { task ->
                peak.updateAndGet { maxOf(it, active.get()) }
                seen.add(task)
                delay(30)
                active.decrementAndGet()
            }
        }
        assertEquals(2, peak.get())
        assertEquals((1..6).toList(), seen.sorted())
    }

    @Test fun idleSlotTakesNewTaskWhileFirstStillRunning() = runBlocking {
        val queue = ArrayDeque(listOf(1))
        val active = AtomicInteger()
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val worker = launch {
            DownloadWorkers.run(
                claim = { synchronized(queue) { queue.pollFirst()?.also { active.incrementAndGet() } } },
                hasWork = { active.get() > 0 || synchronized(queue) { queue.isNotEmpty() } },
            ) { task ->
                if (task == 1) { firstStarted.complete(Unit); releaseFirst.await() }
                else secondStarted.complete(Unit)
                active.decrementAndGet()
            }
        }
        withTimeout(5000) {
            firstStarted.await()
            delay(250) // 空闲位置已经经历过空队列。
            synchronized(queue) { queue.add(2) }
            secondStarted.await()
            assertFalse(releaseFirst.isCompleted)
            releaseFirst.complete(Unit)
            worker.join()
        }
    }
}
