package com.codex.memoryapm.koom

/**
 * 一次 fork hprof dump 的结果。
 *
 * success=false 时 path 可能为空或文件不可用，reason 会说明失败原因。
 */
data class KoomDumpResult(
    val success: Boolean,
    val path: String?,
    val fileSizeBytes: Long,
    val durationMs: Long,
    val reason: String,
)
