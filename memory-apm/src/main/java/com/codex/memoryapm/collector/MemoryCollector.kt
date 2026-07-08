package com.codex.memoryapm.collector

import android.app.Application
import android.os.Debug
import android.os.SystemClock
import com.codex.memoryapm.model.AppState
import com.codex.memoryapm.model.GcStats
import com.codex.memoryapm.model.JavaHeapStats
import com.codex.memoryapm.model.MemoryEventType
import com.codex.memoryapm.model.MemorySnapshot
import com.codex.memoryapm.model.NativeHeapStats
import com.codex.memoryapm.model.PssStats

/**
 * 负责从 Android Runtime/Debug API 读取一次内存快照。
 *
 * 这个类只做“读数”和“组装模型”，不做线程切换、不做阈值判断、不做上报。
 * 这样可以保证采集逻辑足够稳定，后续替换上报或调整策略时不会影响底层指标定义。
 */
internal class MemoryCollector(
    // 预留 Application 引用，后续可扩展 ActivityManager.MemoryInfo、进程重要性、系统可用内存等指标。
    private val application: Application,
    private val processNameProvider: () -> String,
    private val appStateProvider: () -> AppState,
    private val pageNameProvider: () -> String?,
) {
    fun collect(
        eventType: MemoryEventType,
        pageNameOverride: String? = null,
        trimLevel: Int? = null,
        note: String? = null,
    ): MemorySnapshot {
        // Runtime 只能看到 Java/Kotlin managed heap，适合判断是否接近 Java OOM。
        val runtime = Runtime.getRuntime()
        val totalBytes = runtime.totalMemory()
        val freeBytes = runtime.freeMemory()
        val maxBytes = runtime.maxMemory()
        val javaHeap = JavaHeapStats(
            usedBytes = totalBytes - freeBytes,
            freeBytes = freeBytes,
            totalBytes = totalBytes,
            maxBytes = maxBytes,
        )

        // Native heap 反映 native malloc 区域，常见来源包括 JNI、部分图片/音视频库、WebView 相关 native 分配等。
        val nativeHeap = NativeHeapStats(
            allocatedBytes = Debug.getNativeHeapAllocatedSize(),
            freeBytes = Debug.getNativeHeapFreeSize(),
            sizeBytes = Debug.getNativeHeapSize(),
        )

        // PSS 是 Android 线上内存监控最关键的指标之一，比单看 Java heap 更接近系统回收进程时的视角。
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        val graphicsPssKb = memoryInfo.getMemoryStat("summary.graphics").toIntOrZero()
        val totalPssKb = memoryInfo.totalPss
        val pss = PssStats(
            totalPssKb = totalPssKb,
            dalvikPssKb = memoryInfo.dalvikPss,
            nativePssKb = memoryInfo.nativePss,
            graphicsPssKb = graphicsPssKb,
            otherPssKb = (totalPssKb - memoryInfo.dalvikPss - memoryInfo.nativePss - graphicsPssKb)
                .coerceAtLeast(0),
        )

        return MemorySnapshot(
            timestampMs = System.currentTimeMillis(),
            eventType = eventType,
            processName = processNameProvider(),
            appState = appStateProvider(),
            pageName = pageNameOverride ?: pageNameProvider(),
            javaHeap = javaHeap,
            nativeHeap = nativeHeap,
            pss = pss,
            gc = readGcStats(),
            trimLevel = trimLevel,
            note = note ?: "uptime=${SystemClock.uptimeMillis()}",
        )
    }

    /**
     * Debug.getRuntimeStats() 返回 ART 运行时统计。
     *
     * 字段名由 Android Runtime 定义，缺失时兜底为 0，避免不同系统版本上的兼容性问题影响采样。
     */
    private fun readGcStats(): GcStats {
        val stats = Debug.getRuntimeStats()
        return GcStats(
            gcCount = stats["art.gc.gc-count"].toLongOrZero(),
            gcTimeMs = stats["art.gc.gc-time"].toLongOrZero(),
            blockingGcCount = stats["art.gc.blocking-gc-count"].toLongOrZero(),
            blockingGcTimeMs = stats["art.gc.blocking-gc-time"].toLongOrZero(),
        )
    }

    private fun String?.toIntOrZero(): Int = this?.toIntOrNull() ?: 0

    private fun String?.toLongOrZero(): Long = this?.toLongOrNull() ?: 0L
}
