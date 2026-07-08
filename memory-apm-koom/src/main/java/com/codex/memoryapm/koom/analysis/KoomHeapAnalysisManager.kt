package com.codex.memoryapm.koom.analysis

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import com.codex.memoryapm.koom.KoomForkDumpManager
import java.io.File

/**
 * Demo/SDK 侧启动 hprof 本地解析的入口。
 *
 * 它只负责任务编排：找到最新 hprof、准备 JSON 输出路径、启动独立解析进程。
 * 真正的 Shark 解析逻辑在 KoomHeapAnalysisService 中执行。
 */
object KoomHeapAnalysisManager {
    fun analyzeLatestHprof(
        context: Context,
        callback: (KoomHeapAnalysisResult) -> Unit,
    ) {
        val latestHprof = latestHprofFile()
        if (latestHprof == null) {
            callback(
                KoomHeapAnalysisResult(
                    success = false,
                    hprofPath = null,
                    reportPath = null,
                    durationMs = 0L,
                    reason = "No hprof file found. Please dump hprof first.",
                    summary = "",
                ),
            )
            return
        }
        analyzeHprof(context, latestHprof, callback)
    }

    fun analyzeHprof(
        context: Context,
        hprofFile: File,
        callback: (KoomHeapAnalysisResult) -> Unit,
    ) {
        val reportFile = createReportFile(context, hprofFile)
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: android.os.Bundle?) {
                callback(KoomHeapAnalysisResult.fromBundle(resultData))
            }
        }

        KoomHeapAnalysisService.start(
            context = context.applicationContext,
            hprofPath = hprofFile.absolutePath,
            reportPath = reportFile.absolutePath,
            receiver = receiver,
        )
    }

    fun latestHprofFile(): File? {
        return KoomForkDumpManager.dumpDirectory()
            ?.listFiles { file -> file.isFile && file.extension.equals("hprof", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
    }

    fun analysisDirectory(context: Context): File {
        val externalRoot = context.applicationContext.getExternalFilesDir(null)
        val root = externalRoot ?: context.applicationContext.filesDir
        return File(root, "memory-apm/koom-analysis").apply { mkdirs() }
    }

    private fun createReportFile(context: Context, hprofFile: File): File {
        val fileName = "${hprofFile.nameWithoutExtension}-analysis.json"
        return File(analysisDirectory(context), fileName).apply {
            parentFile?.mkdirs()
        }
    }
}
