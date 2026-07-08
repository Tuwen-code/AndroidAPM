package com.codex.memoryapm.uploader

import android.util.Log
import com.codex.memoryapm.MemoryUploader
import com.codex.memoryapm.model.LeakReport
import com.codex.memoryapm.model.MemoryReport
import java.util.Locale

/**
 * Demo/本地调试用的 Logcat 上报实现。
 *
 * 线上接入时不要直接使用它作为最终方案，应实现 MemoryUploader，把数据写入统一埋点或 APM 通道。
 */
class LogcatMemoryUploader(
    private val tag: String = "MemoryApm",
) : MemoryUploader {
    override fun upload(report: MemoryReport) {
        val snapshot = report.snapshot
        val warnings = if (report.warnings.isEmpty()) {
            "none"
        } else {
            report.warnings.joinToString { it.type }
        }
        Log.i(
            tag,
            // 日志保持单行，方便 Logcat 过滤和复制；更完整的数据结构在 MemoryReport 中。
            "event=${snapshot.eventType} page=${snapshot.pageName.orEmpty()} " +
                "state=${snapshot.appState} java=${snapshot.javaHeap.usedBytes.toMb()}MB/" +
                "${snapshot.javaHeap.maxBytes.toMb()}MB pss=${snapshot.pss.totalPssKb.toMbFromKb()}MB " +
                "native=${snapshot.nativeHeap.allocatedBytes.toMb()}MB warnings=$warnings",
        )

        report.lastProcessExit?.let {
            // 只在启动采样携带 lastProcessExit 时打印，便于验证 ApplicationExitInfo 是否可用。
            Log.i(tag, "lastExit reason=${it.reasonName} pss=${it.pssKb.toMbFromKb()}MB desc=${it.description.orEmpty()}")
        }
    }

    override fun uploadLeak(report: LeakReport) {
        Log.w(
            tag,
            "retained=${report.retainedCount} " +
                report.retainedObjects.joinToString { "${it.className}:${it.retainedDurationMs}ms" },
        )
    }

    private fun Long.toMb(): String {
        return String.format(Locale.US, "%.1f", this / 1024f / 1024f)
    }

    private fun Int.toMbFromKb(): String {
        return String.format(Locale.US, "%.1f", this / 1024f)
    }

    private fun Long.toMbFromKb(): String {
        return String.format(Locale.US, "%.1f", this / 1024f)
    }
}
