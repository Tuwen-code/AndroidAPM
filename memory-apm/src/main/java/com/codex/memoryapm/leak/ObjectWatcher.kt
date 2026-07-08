package com.codex.memoryapm.leak

import android.os.Handler
import android.os.SystemClock
import com.codex.memoryapm.model.RetainedObject
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/**
 * 轻量 retained object 观察器。
 *
 * 它的目标不是在线上完整定位泄漏引用链，而是低成本发现“对象销毁后迟迟没有被 GC”的信号。
 * 真正的引用链分析通常需要 HPROF + Shark/MAT，这类重型能力应通过灰度、后台、低采样触发。
 */
internal class ObjectWatcher(
    private val handler: Handler,
    private val watchDelayMs: Long,
    private val retainedThreshold: Int,
    private val onRetained: (List<RetainedObject>) -> Unit,
) {
    private val queue = ReferenceQueue<Any>()
    private val watched = mutableListOf<WatchedReference>()
    private var nextKey = 0L

    /**
     * 开始观察一个理论上应被释放的对象。
     *
     * 传入对象后这里只创建 WeakReference；如果 SDK 还持有强引用，就会污染检测结果。
     */
    fun watch(watchedObject: Any, description: String) {
        // 每次加入新对象前先清理已经进入 ReferenceQueue 的弱引用，控制列表大小。
        removeWeaklyReachableObjects()

        val key = (++nextKey).toString()
        watched += WatchedReference(
            referent = watchedObject,
            queue = queue,
            key = key,
            className = watchedObject.javaClass.name,
            description = description,
            watchUptimeMs = SystemClock.uptimeMillis(),
        )

        handler.postDelayed(::checkRetainedObjects, watchDelayMs)
    }

    private fun checkRetainedObjects() {
        removeWeaklyReachableObjects()
        // 主动触发一次 GC 只用于降低误判概率。GC 不保证立刻回收，因此结果仍应视为“疑似 retained”。
        Runtime.getRuntime().gc()
        System.runFinalization()
        removeWeaklyReachableObjects()

        val now = SystemClock.uptimeMillis()
        val retained = watched
            .filter { it.get() != null && now - it.watchUptimeMs >= watchDelayMs }
            .map {
                RetainedObject(
                    key = it.key,
                    className = it.className,
                    description = it.description,
                    retainedDurationMs = now - it.watchUptimeMs,
                )
            }

        if (retained.size >= retainedThreshold) {
            val retainedKeys = retained.mapTo(mutableSetOf()) { it.key }
            // 上报后移除，避免同一个对象在后续检查中重复刷屏。服务端更适合做聚合趋势分析。
            watched.removeAll { it.key in retainedKeys }
            onRetained(retained)
        }
    }

    private fun removeWeaklyReachableObjects() {
        // ReferenceQueue 中出现的对象说明弱引用 referent 已经被 GC，可以从观察列表移除。
        while (true) {
            val reference = queue.poll() as? WatchedReference ?: break
            watched.removeAll { it.key == reference.key }
        }
    }

    private class WatchedReference(
        referent: Any,
        queue: ReferenceQueue<Any>,
        val key: String,
        val className: String,
        val description: String,
        val watchUptimeMs: Long,
    ) : WeakReference<Any>(referent, queue)
}
