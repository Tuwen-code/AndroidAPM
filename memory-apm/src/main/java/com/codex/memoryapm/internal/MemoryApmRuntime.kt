package com.codex.memoryapm.internal

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.codex.memoryapm.MemoryApmConfig
import com.codex.memoryapm.MemoryUploader
import com.codex.memoryapm.collector.MemoryCollector
import com.codex.memoryapm.exit.ProcessExitReader
import com.codex.memoryapm.leak.ObjectWatcher
import com.codex.memoryapm.lifecycle.ActivityTracker
import com.codex.memoryapm.model.AppState
import com.codex.memoryapm.model.LeakReport
import com.codex.memoryapm.model.MemoryEventType
import com.codex.memoryapm.model.MemoryReport
import com.codex.memoryapm.model.MemorySnapshot
import com.codex.memoryapm.model.RuntimeContext
import com.codex.memoryapm.policy.MemoryWarningPolicy
import com.codex.memoryapm.storage.MemoryRingBuffer
import com.codex.memoryapm.util.RuntimeContextProvider
import com.codex.memoryapm.util.ProcessNameProvider

/**
 * SDK 的运行时协调器。
 *
 * 职责边界：
 * - 注册/注销 Android 生命周期回调。
 * - 维护当前页面、前后台状态和最近快照 ring buffer。
 * - 把采集结果包装成 MemoryReport/LeakReport 后交给 uploader。
 *
 * 线程模型：
 * - 生命周期注册必须发生在主线程。
 * - 内存采集、告警判断、弱引用检查和上报回调都放在 HandlerThread 中，减少对 UI 线程的影响。
 */
internal class MemoryApmRuntime(
    private val application: Application,
    private val config: MemoryApmConfig,
    private val uploader: MemoryUploader,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("memory-apm")
    private val ringBuffer = MemoryRingBuffer(config.ringBufferSize)
    private val warningPolicy = MemoryWarningPolicy(config)

    // Activity 生命周期回调来自主线程，上报采集运行在 worker 线程，因此这里需要 volatile 保证可见性。
    @Volatile
    private var currentPageName: String? = null

    // 前后台状态用于区分前台体验问题和后台保活/系统回收风险。
    @Volatile
    private var appState: AppState = AppState.UNKNOWN

    private lateinit var workerHandler: Handler
    private lateinit var runtimeContext: RuntimeContext
    private lateinit var collector: MemoryCollector
    private lateinit var objectWatcher: ObjectWatcher

    private val processName: String by lazy { ProcessNameProvider.get(application) }

    /**
     * ActivityTracker 只负责把系统生命周期转成 SDK 事件。
     * Runtime 在这里做页面状态维护、泄漏观察和采样触发。
     */
    private val activityTracker = ActivityTracker(
        listener = object : ActivityTracker.Listener {
            override fun onAppStateChanged(newState: AppState) {
                appState = newState
                capture(
                    eventType = if (newState == AppState.FOREGROUND) {
                        MemoryEventType.APP_FOREGROUND
                    } else {
                        MemoryEventType.APP_BACKGROUND
                    },
                    pageNameOverride = currentPageName,
                )
            }

            override fun onActivityEvent(
                activity: Activity,
                eventType: MemoryEventType,
                pageName: String,
            ) {
                when (eventType) {
                    MemoryEventType.ACTIVITY_CREATED,
                    MemoryEventType.ACTIVITY_STARTED,
                    MemoryEventType.ACTIVITY_RESUMED -> currentPageName = pageName
                    MemoryEventType.ACTIVITY_DESTROYED -> {
                        // 当前页面销毁后清空 pageName，避免后续后台周期采样误归因到已销毁页面。
                        if (currentPageName == pageName) {
                            currentPageName = null
                        }
                        if (config.enableLeakDetect) {
                            // destroyed 后只保留弱引用观察，不持有 Activity 强引用，避免监控 SDK 自己制造泄漏。
                            workerHandler.post {
                                objectWatcher.watch(
                                    watchedObject = activity,
                                    description = "Destroyed Activity: $pageName",
                                )
                            }
                        }
                    }
                    else -> Unit
                }

                capture(eventType = eventType, pageNameOverride = pageName)
            }
        },
    )

    /**
     * 系统内存压力回调。
     *
     * onTrimMemory/onLowMemory 比单纯阈值更有价值，因为它们代表系统已经感知到内存压力。
     * 服务端可以重点观察这些事件前后的 PSS 曲线。
     */
    private val componentCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        override fun onLowMemory() {
            capture(eventType = MemoryEventType.LOW_MEMORY)
        }

        override fun onTrimMemory(level: Int) {
            capture(
                eventType = MemoryEventType.TRIM_MEMORY,
                trimLevel = level,
                note = trimLevelName(level),
                extras = mapOf("trimLevel" to level.toString()),
            )
        }
    }

    /**
     * 周期采样用于形成连续曲线。
     *
     * 生命周期采样只能看到离散事件，周期采样能发现“用户停留在某页面时内存持续上涨”这类问题。
     */
    private val periodicCollect = object : Runnable {
        override fun run() {
            capture(eventType = MemoryEventType.PERIODIC)
            workerHandler.postDelayed(this, config.collectIntervalMs)
        }
    }

    fun start() {
        // 先启动 worker，再注册回调，确保生命周期回调到来时可以立即投递采集任务。
        workerThread.start()
        workerHandler = Handler(workerThread.looper)
        runtimeContext = RuntimeContextProvider.create(application)
        collector = MemoryCollector(
            application = application,
            processNameProvider = { processName },
            appStateProvider = { appState },
            pageNameProvider = { currentPageName },
        )
        objectWatcher = ObjectWatcher(
            handler = workerHandler,
            watchDelayMs = config.leakWatchDelayMs,
            retainedThreshold = config.retainedActivityThreshold,
            onRetained = ::reportRetainedObjects,
        )

        runOnMain {
            application.registerComponentCallbacks(componentCallbacks)
            if (config.enableActivityTracking) {
                application.registerActivityLifecycleCallbacks(activityTracker)
            }
        }

        // 启动时立即采样，并附带上次退出原因。OOM/低内存杀进程通常只能在下次启动时回看。
        capture(
            eventType = MemoryEventType.APP_START,
            extras = mapOf("processName" to processName),
            lastProcessExit = ProcessExitReader.readLastExit(application),
        )

        if (config.enablePeriodicCollect) {
            workerHandler.postDelayed(periodicCollect, config.collectIntervalMs)
        }
    }

    fun stop() {
        // 注销必须走主线程；worker 清空消息后安全退出，避免 shutdown 后仍继续周期采样。
        runOnMain {
            application.unregisterComponentCallbacks(componentCallbacks)
            if (config.enableActivityTracking) {
                application.unregisterActivityLifecycleCallbacks(activityTracker)
            }
        }
        workerHandler.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
    }

    fun capture(
        eventType: MemoryEventType,
        pageNameOverride: String? = null,
        trimLevel: Int? = null,
        note: String? = null,
        extras: Map<String, String> = emptyMap(),
        lastProcessExit: com.codex.memoryapm.model.ProcessExitRecord? = null,
    ) {
        workerHandler.post {
            // 所有采集统一串行化到 worker 线程，避免并发采样导致 report 顺序错乱。
            val snapshot = collector.collect(
                eventType = eventType,
                pageNameOverride = pageNameOverride,
                trimLevel = trimLevel,
                note = note,
            )
            ringBuffer.add(snapshot)
            uploader.upload(
                MemoryReport(
                    context = runtimeContext,
                    snapshot = snapshot,
                    warnings = warningPolicy.evaluate(snapshot),
                    recentSnapshots = ringBuffer.snapshot(),
                    lastProcessExit = lastProcessExit,
                    extras = extras,
                ),
            )
        }
    }

    fun snapshots(): List<MemorySnapshot> = ringBuffer.snapshot()

    private fun reportRetainedObjects(retainedObjects: List<com.codex.memoryapm.model.RetainedObject>) {
        // retained 发生时补一次内存快照，方便服务端把泄漏信号和当时的 Java/PSS 状态关联起来。
        val snapshot = collector.collect(
            eventType = MemoryEventType.LEAK_DETECTED,
            note = "retained=${retainedObjects.size}",
        )
        ringBuffer.add(snapshot)
        uploader.uploadLeak(
            LeakReport(
                context = runtimeContext,
                timestampMs = System.currentTimeMillis(),
                retainedCount = retainedObjects.size,
                retainedObjects = retainedObjects,
                snapshot = snapshot,
                recentSnapshots = ringBuffer.snapshot(),
            ),
        )
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    @Suppress("DEPRECATION")
    private fun trimLevelName(level: Int): String {
        // 旧的 TRIM_MEMORY_* 常量虽然被标记 deprecated，但仍然是线上兼容老系统和日志可读性的关键语义。
        return when (level) {
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "TRIM_MEMORY_COMPLETE"
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "TRIM_MEMORY_MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "TRIM_MEMORY_BACKGROUND"
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "TRIM_MEMORY_UI_HIDDEN"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "TRIM_MEMORY_RUNNING_CRITICAL"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "TRIM_MEMORY_RUNNING_LOW"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "TRIM_MEMORY_RUNNING_MODERATE"
            else -> "TRIM_MEMORY_$level"
        }
    }
}
