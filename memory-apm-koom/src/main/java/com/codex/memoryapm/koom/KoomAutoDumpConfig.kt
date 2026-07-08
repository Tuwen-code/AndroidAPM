package com.codex.memoryapm.koom

/**
 * KOOM 风格的自动 dump 触发策略。
 *
 * 这里复刻的是 KOOM Java leak monitor 的两类核心判断：
 * 1. HeapOOMTracker：Java heap 持续高水位，连续多次命中后 dump。
 * 2. FastHugeMemoryOOMTracker：Java heap 短时间暴增或达到极高水位，立即 dump。
 */
data class KoomAutoDumpConfig(
    val enabled: Boolean = true,
    val dumpOnlyWhenForeground: Boolean = true,
    val logEvaluation: Boolean = false,
    val heapThreshold: Float? = null,
    val heapRatioThresholdGap: Float = 0.05f,
    val maxOverThresholdCount: Int = 3,
    val forceDumpJavaHeapMaxThreshold: Float = 0.90f,
    val forceDumpJavaHeapDeltaBytes: Long = 350_000L * 1024L,
    val maxAutoDumpCountPerProcess: Int = 1,
    val dumpReasonPrefix: String = "auto",
)
