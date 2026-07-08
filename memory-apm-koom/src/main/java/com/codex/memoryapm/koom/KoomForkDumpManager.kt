package com.codex.memoryapm.koom

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.kwai.koom.base.DefaultInitTask
import com.kwai.koom.fastdump.ForkJvmHeapDumper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * KOOM fast dump 的适配层。
 *
 * 这个类只封装“手动触发 fork hprof dump”能力，不接管 KOOM Java leak monitor 的轮询逻辑。
 * 这样我们的 memory-apm 仍然负责轻量监控，KOOM 只作为按需触发的重型诊断工具。
 */
object KoomForkDumpManager {
    private val initialized = AtomicBoolean(false)
    private val dumping = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var application: Application
    private lateinit var config: KoomDumpConfig
    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler

    @Volatile
    private var foregroundActivityCount = 0

    @Volatile
    private var lastDumpUptimeMs = 0L

    @Volatile
    private var dumpCount = 0

    /**
     * 初始化 KOOM base 和本适配层。
     *
     * 应在 Application.onCreate 中调用一次。重复调用会被忽略，避免 KOOM base 重复注册生命周期。
     */
    fun init(
        application: Application,
        config: KoomDumpConfig = KoomDumpConfig(),
    ) {
        if (!initialized.compareAndSet(false, true)) {
            return
        }

        this.application = application
        this.config = config
        workerThread = HandlerThread("memory-apm-koom")
        workerThread.start()
        workerHandler = Handler(workerThread.looper)

        application.registerActivityLifecycleCallbacks(foregroundTracker)

        // KOOM fast dump 依赖 KOOM base 的 CommonConfig，例如 sdkVersionMatch 和 so 加载能力。
        DefaultInitTask.init(application)
    }

    /**
     * 异步触发一次 fork hprof dump。
     *
     * callback 会回到主线程，方便 Demo 页面刷新；真实线上可以在 callback 中加密、压缩、入队上传。
     */
    fun dumpAsync(
        reason: String,
        callback: (KoomDumpResult) -> Unit,
    ) {
        if (!initialized.get()) {
            callback.onMain(KoomDumpResult(false, null, 0L, 0L, "KOOM adapter not initialized"))
            return
        }

        val rejection = checkCanDump()
        if (rejection != null) {
            callback.onMain(KoomDumpResult(false, null, 0L, 0L, rejection))
            return
        }

        if (!dumping.compareAndSet(false, true)) {
            callback.onMain(KoomDumpResult(false, null, 0L, 0L, "another dump is running"))
            return
        }

        workerHandler.post {
            val start = SystemClock.elapsedRealtime()
            val outputFile = createDumpFile(reason)
            val result = runCatching {
                outputFile.parentFile?.mkdirs()
                val success = ForkJvmHeapDumper.getInstance().dump(outputFile.absolutePath)
                val duration = SystemClock.elapsedRealtime() - start
                if (success && outputFile.exists()) {
                    lastDumpUptimeMs = SystemClock.uptimeMillis()
                    dumpCount += 1
                    KoomDumpResult(
                        success = true,
                        path = outputFile.absolutePath,
                        fileSizeBytes = outputFile.length(),
                        durationMs = duration,
                        reason = "dump success: $reason",
                    )
                } else {
                    KoomDumpResult(
                        success = false,
                        path = outputFile.absolutePath,
                        fileSizeBytes = if (outputFile.exists()) outputFile.length() else 0L,
                        durationMs = duration,
                        reason = "KOOM dump returned false: $reason",
                    )
                }
            }.getOrElse { throwable ->
                KoomDumpResult(
                    success = false,
                    path = outputFile.absolutePath,
                    fileSizeBytes = if (outputFile.exists()) outputFile.length() else 0L,
                    durationMs = SystemClock.elapsedRealtime() - start,
                    reason = throwable.message ?: throwable.javaClass.name,
                )
            }

            dumping.set(false)
            callback.onMain(result)
        }
    }

    fun isDumping(): Boolean = dumping.get()

    fun dumpDirectory(): File? {
        return if (initialized.get()) {
            resolveDumpDirectory()
        } else {
            null
        }
    }

    private fun checkCanDump(): String? {
        if (!config.enabled) {
            return "KOOM dump disabled"
        }
        if (!config.allowForegroundDump && foregroundActivityCount > 0) {
            return "foreground dump is not allowed"
        }
        if (dumpCount >= config.maxDumpCountPerProcess) {
            return "dump count limit reached: $dumpCount"
        }
        val now = SystemClock.uptimeMillis()
        if (lastDumpUptimeMs > 0L && now - lastDumpUptimeMs < config.minDumpIntervalMs) {
            return "dump interval limit: ${now - lastDumpUptimeMs}ms"
        }
        val directory = resolveDumpDirectory()
        directory.mkdirs()
        if (directory.usableSpace < config.minFreeSpaceBytes) {
            return "not enough free space: ${directory.usableSpace} bytes"
        }
        return null
    }

    private fun createDumpFile(reason: String): File {
        val safeReason = reason
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(32)
            .ifBlank { "manual" }
        val time = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(
            resolveDumpDirectory(),
            "${config.fileNamePrefix}-${safeReason}-$time.hprof",
        )
    }

    private fun resolveDumpDirectory(): File {
        if (config.useExternalFilesDir) {
            val externalRoot = application.getExternalFilesDir(null)
            if (externalRoot != null) {
                return File(externalRoot, config.dumpDirectoryName)
            }
        }
        return File(application.filesDir, config.dumpDirectoryName)
    }

    private fun ((KoomDumpResult) -> Unit).onMain(result: KoomDumpResult) {
        mainHandler.post { invoke(result) }
    }

    private val foregroundTracker = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) {
            foregroundActivityCount += 1
        }

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) {
            foregroundActivityCount = (foregroundActivityCount - 1).coerceAtLeast(0)
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
