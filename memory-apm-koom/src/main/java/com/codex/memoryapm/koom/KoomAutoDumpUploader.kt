package com.codex.memoryapm.koom

import android.util.Log
import com.codex.memoryapm.MemoryUploader
import com.codex.memoryapm.model.AppState
import com.codex.memoryapm.model.LeakReport
import com.codex.memoryapm.model.MemoryReport
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把 memory-apm 的轻量采样结果转换成 KOOM 风格的自动 fork dump 触发器。
 *
 * 它不会替代原有 uploader，而是先把 report 交给 delegate，再判断是否触发 hprof。
 * 真实线上接入时，可以把 delegate 换成公司的埋点/APM 上报实现。
 */
class KoomAutoDumpUploader(
    private val delegate: MemoryUploader,
    private val config: KoomAutoDumpConfig = KoomAutoDumpConfig(),
    private val onDumpResult: (KoomDumpResult) -> Unit = {},
) : MemoryUploader {
    private val dumping = AtomicBoolean(false)
    private val heapThreshold = config.heapThreshold ?: defaultHeapThreshold()

    private var lastHeapRatio = 0.0f
    private var lastJavaHeapUsedBytes = 0L
    private var overThresholdCount = 0
    private var autoDumpCount = 0

    override fun upload(report: MemoryReport) {
        delegate.upload(report)
        evaluate(report)
    }

    override fun uploadLeak(report: LeakReport) {
        delegate.uploadLeak(report)
    }

    @Synchronized
    private fun evaluate(report: MemoryReport) {
        if (!config.enabled || autoDumpCount >= config.maxAutoDumpCountPerProcess) {
            if (config.logEvaluation) {
                Log.i(TAG, "skip evaluate enabled=${config.enabled} autoDumpCount=$autoDumpCount")
            }
            updateLastValues(report)
            return
        }

        val snapshot = report.snapshot
        if (config.dumpOnlyWhenForeground && snapshot.appState != AppState.FOREGROUND) {
            if (config.logEvaluation) {
                Log.i(TAG, "skip evaluate because appState=${snapshot.appState}")
            }
            updateLastValues(report)
            return
        }

        val javaHeap = snapshot.javaHeap
        val heapRatio = javaHeap.usageRatio
        val heapDeltaBytes = if (lastJavaHeapUsedBytes > 0L) {
            javaHeap.usedBytes - lastJavaHeapUsedBytes
        } else {
            0L
        }

        if (config.logEvaluation) {
            Log.i(
                TAG,
                "evaluate event=${snapshot.eventType} state=${snapshot.appState} " +
                    "heap=${heapRatio.percent()} used=${javaHeap.usedBytes.toMb()}MB " +
                    "last=${lastJavaHeapUsedBytes.toMb()}MB delta=${heapDeltaBytes.toMb()}MB " +
                    "deltaThreshold=${config.forceDumpJavaHeapDeltaBytes.toMb()}MB " +
                    "autoDumpCount=$autoDumpCount",
            )
        }

        val dumpReason = when {
            heapRatio > config.forceDumpJavaHeapMaxThreshold -> {
                "fast-high-watermark-${heapRatio.percent()}"
            }

            heapDeltaBytes > config.forceDumpJavaHeapDeltaBytes -> {
                "fast-delta-${heapDeltaBytes.toMb()}MB"
            }

            isHeapContinuouslyHigh(heapRatio) -> {
                "heap-over-threshold-${heapRatio.percent()}-count-$overThresholdCount"
            }

            else -> null
        }

        updateLastValues(report)

        if (dumpReason != null) {
            triggerDump(report, dumpReason)
        }
    }

    private fun isHeapContinuouslyHigh(heapRatio: Float): Boolean {
        return if (
            heapRatio > heapThreshold &&
            heapRatio >= lastHeapRatio - config.heapRatioThresholdGap
        ) {
            overThresholdCount += 1
            Log.i(
                TAG,
                "meet heap condition count=$overThresholdCount " +
                    "heapRatio=${heapRatio.percent()} threshold=${heapThreshold.percent()}",
            )
            overThresholdCount >= config.maxOverThresholdCount
        } else {
            overThresholdCount = 0
            false
        }
    }

    private fun updateLastValues(report: MemoryReport) {
        lastHeapRatio = report.snapshot.javaHeap.usageRatio
        lastJavaHeapUsedBytes = report.snapshot.javaHeap.usedBytes
    }

    private fun triggerDump(report: MemoryReport, reason: String) {
        if (!dumping.compareAndSet(false, true)) {
            Log.i(TAG, "skip auto dump because another dump is running")
            return
        }

        autoDumpCount += 1
        val snapshot = report.snapshot
        val dumpReason = listOfNotNull(
            config.dumpReasonPrefix,
            reason,
            snapshot.eventType.name.lowercase(Locale.US),
            snapshot.pageName?.replace(Regex("[^A-Za-z0-9._-]"), "_"),
        ).joinToString("-")

        Log.w(
            TAG,
            "trigger KOOM fork dump reason=$dumpReason " +
                "heap=${snapshot.javaHeap.usageRatio.percent()} " +
                "used=${snapshot.javaHeap.usedBytes.toMb()}MB",
        )

        KoomForkDumpManager.dumpAsync(reason = dumpReason) { result ->
            dumping.set(false)
            if (!result.success) {
                synchronized(this@KoomAutoDumpUploader) {
                    autoDumpCount = (autoDumpCount - 1).coerceAtLeast(0)
                }
            }
            Log.i(TAG, "auto dump result=$result")
            runCatching {
                onDumpResult(result)
            }.onFailure { throwable ->
                Log.e(TAG, "auto dump callback failed", throwable)
            }
        }
    }

    private fun defaultHeapThreshold(): Float {
        val maxMemMb = Runtime.getRuntime().maxMemory() / 1024f / 1024f
        return when {
            maxMemMb >= 512f - 10f -> 0.80f
            maxMemMb >= 256f - 10f -> 0.85f
            else -> 0.90f
        }
    }

    private fun Float.percent(): String {
        return String.format(Locale.US, "%.1f%%", this * 100f)
    }

    private fun Long.toMb(): Long = this / 1024L / 1024L

    private companion object {
        private const val TAG = "MemoryApm-KOOM"
    }
}
