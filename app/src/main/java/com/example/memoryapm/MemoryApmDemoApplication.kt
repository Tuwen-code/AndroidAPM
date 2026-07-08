package com.example.memoryapm

import android.app.Application
import com.codex.memoryapm.MemoryApm
import com.codex.memoryapm.MemoryApmConfig
import com.codex.memoryapm.koom.KoomAutoDumpConfig
import com.codex.memoryapm.koom.KoomAutoDumpUploader
import com.codex.memoryapm.koom.KoomDumpConfig
import com.codex.memoryapm.koom.KoomForkDumpManager
import com.codex.memoryapm.uploader.LogcatMemoryUploader

/**
 * Demo 宿主 Application。
 *
 * 真实业务接入时，这里通常会接远程配置：
 * - sampleRate 按灰度分桶控制。
 * - enableLeakDetect 按版本/机型动态开关。
 * - uploader 替换为公司的埋点/APM 上报实现。
 */
class MemoryApmDemoApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        KoomForkDumpManager.init(
            application = this,
            config = KoomDumpConfig(
                // Demo 为了前台点击按钮和自动触发学习流程，允许前台 dump；线上一定要改回 false 或只对灰度用户开启。
                allowForegroundDump = true,
                // Demo 允许多次手动尝试；线上建议每进程/每天强限频。
                maxDumpCountPerProcess = 10,
                minDumpIntervalMs = 0L,
            ),
        )

        MemoryApm.init(
            application = this,
            config = MemoryApmConfig(
                // Demo 为了便于观察设为 100% 采样；线上建议从 0.1% 或 1% 开始。
                sampleRate = 1.0f,
                // Demo 用 10 秒方便看到周期日志；线上一般 15-30 秒即可。
                collectIntervalMs = 10_000L,
                ringBufferSize = 60,
                enableLeakDetect = true,
                // Demo 阈值略低，方便手动分配内存时更快看到 warning。
                javaHeapWarningRatio = 0.75f,
            ),
            uploader = KoomAutoDumpUploader(
                delegate = LogcatMemoryUploader(),
                config = KoomAutoDumpConfig(
                    // 这里采用 KOOM 的“连续高水位 + 快速暴增”模式。
                    // Demo 把 delta 调低到 24MB，方便点击“暴增 +64 MB”验证；线上建议使用 KOOM 默认量级 350_000KB。
                    dumpOnlyWhenForeground = false,
                    logEvaluation = true,
                    forceDumpJavaHeapDeltaBytes = 24L * 1024L * 1024L,
                    maxOverThresholdCount = 3,
                    // Demo 允许反复点击学习流程；线上通常应恢复为每进程/每天 1 次或更严格。
                    maxAutoDumpCountPerProcess = 10,
                ),
                onDumpResult = KoomDumpEventCenter::notify,
            ),
        )
    }
}
