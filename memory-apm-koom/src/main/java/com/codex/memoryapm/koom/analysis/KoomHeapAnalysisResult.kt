package com.codex.memoryapm.koom.analysis

import android.os.Bundle

/**
 * 一次端上 hprof 解析的结果。
 *
 * success=true 表示 JSON 报告已经写入 reportPath；success=false 时 reason 会说明失败原因。
 */
data class KoomHeapAnalysisResult(
    val success: Boolean,
    val hprofPath: String?,
    val reportPath: String?,
    val durationMs: Long,
    val reason: String,
    val summary: String,
) {
    fun toDisplayText(): String {
        return if (success) {
            "解析成功：耗时 ${durationMs}ms\n$summary\n报告：$reportPath"
        } else {
            "解析失败：$reason\n耗时 ${durationMs}ms\nHPROF：${hprofPath.orEmpty()}"
        }
    }

    internal fun toBundle(): Bundle {
        return Bundle().apply {
            putBoolean(KEY_SUCCESS, success)
            putString(KEY_HPROF_PATH, hprofPath)
            putString(KEY_REPORT_PATH, reportPath)
            putLong(KEY_DURATION_MS, durationMs)
            putString(KEY_REASON, reason)
            putString(KEY_SUMMARY, summary)
        }
    }

    internal companion object {
        private const val KEY_SUCCESS = "success"
        private const val KEY_HPROF_PATH = "hprofPath"
        private const val KEY_REPORT_PATH = "reportPath"
        private const val KEY_DURATION_MS = "durationMs"
        private const val KEY_REASON = "reason"
        private const val KEY_SUMMARY = "summary"

        fun fromBundle(bundle: Bundle?): KoomHeapAnalysisResult {
            return KoomHeapAnalysisResult(
                success = bundle?.getBoolean(KEY_SUCCESS) ?: false,
                hprofPath = bundle?.getString(KEY_HPROF_PATH),
                reportPath = bundle?.getString(KEY_REPORT_PATH),
                durationMs = bundle?.getLong(KEY_DURATION_MS) ?: 0L,
                reason = bundle?.getString(KEY_REASON).orEmpty(),
                summary = bundle?.getString(KEY_SUMMARY).orEmpty(),
            )
        }
    }
}
