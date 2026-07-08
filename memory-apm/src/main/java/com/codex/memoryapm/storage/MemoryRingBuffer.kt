package com.codex.memoryapm.storage

import com.codex.memoryapm.model.MemorySnapshot

/**
 * 固定容量的内存快照环形缓存。
 *
 * SDK 只保留最近 N 条快照，避免监控数据本身无限增长造成内存问题。
 * 上报时把 recentSnapshots 一起带上，可以在服务端看到短时间内的内存轨迹。
 */
internal class MemoryRingBuffer(
    private val capacity: Int,
) {
    private val snapshots = ArrayDeque<MemorySnapshot>(capacity)

    // capture 运行在 worker 线程，但 Demo/UI 可能从主线程读取 lastSnapshots，因此这里做最小粒度同步。
    private val lock = Any()

    fun add(snapshot: MemorySnapshot) {
        synchronized(lock) {
            // 达到容量后丢弃最旧数据，只保留最近轨迹。
            if (snapshots.size == capacity) {
                snapshots.removeFirst()
            }
            snapshots.addLast(snapshot)
        }
    }

    fun snapshot(): List<MemorySnapshot> {
        return synchronized(lock) {
            // 返回拷贝，避免调用方修改内部队列或在遍历时和写入并发冲突。
            snapshots.toList()
        }
    }
}
