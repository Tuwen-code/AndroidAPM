package com.codex.memoryapm

import android.app.Application
import com.codex.memoryapm.internal.MemoryApmRuntime
import com.codex.memoryapm.model.MemoryEventType
import com.codex.memoryapm.model.MemorySnapshot
import com.codex.memoryapm.uploader.LogcatMemoryUploader
import kotlin.random.Random

/**
 * MemoryApm 是宿主 App 接入内存监控 SDK 的唯一入口。
 *
 * 设计目标：
 * 1. 接入简单：Application.onCreate 中调用 init 即可。
 * 2. 默认安全：支持采样、开关、后台线程采集，避免监控 SDK 本身放大线上内存问题。
 * 3. 能归因：每次采样都会带上页面、前后台、进程、设备和最近快照，用于服务端聚合分析。
 *
 * 注意：这个对象只负责生命周期编排，真正的采集、泄漏观察、上报组装都在 internal 包中完成。
 */
object MemoryApm {
    /**
     * runtime 使用 volatile + synchronized 保证多线程初始化时的可见性和幂等性。
     * Android App 中可能存在多个 ContentProvider/Application 初始化路径，所以 SDK 入口必须防重入。
     */
    @Volatile
    private var runtime: MemoryApmRuntime? = null

    /**
     * 初始化内存监控 SDK。
     *
     * @param application 宿主 Application，用于注册生命周期、读取包信息和系统服务。
     * @param config 线上监控策略，包括采样率、周期采集、泄漏检测和告警阈值。
     * @param uploader 上报通道。Demo 默认写 Logcat，线上应替换为公司埋点/APM/日志平台。
     */
    @Synchronized
    fun init(
        application: Application,
        config: MemoryApmConfig = MemoryApmConfig(),
        uploader: MemoryUploader = LogcatMemoryUploader(),
    ) {
        // 已初始化、总开关关闭或采样未命中时直接返回，避免创建线程和注册回调。
        if (runtime != null || !config.enabled || !shouldSample(config.sampleRate)) {
            return
        }

        runtime = MemoryApmRuntime(
            application = application,
            config = config.normalized(),
            uploader = uploader,
        ).also { it.start() }
    }

    /**
     * 手动触发一次内存快照。
     *
     * 典型使用场景：
     * - 图片详情页加载大图后主动打点。
     * - WebView 创建/销毁后主动打点。
     * - 某个业务流程结束时记录页面内存增量。
     */
    fun capture(
        eventType: MemoryEventType = MemoryEventType.MANUAL,
        pageName: String? = null,
        note: String? = null,
        extras: Map<String, String> = emptyMap(),
    ) {
        runtime?.capture(
            eventType = eventType,
            pageNameOverride = pageName,
            note = note,
            extras = extras,
        )
    }

    /**
     * 返回当前进程内最近的内存快照。
     *
     * 这个方法主要用于 Demo、Debug 面板或本地诊断。线上完整数据应通过 MemoryUploader 上报，
     * 因为进程被系统杀死后，内存中的 ring buffer 会丢失。
     */
    fun lastSnapshots(): List<MemorySnapshot> {
        return runtime?.snapshots().orEmpty()
    }

    /**
     * SDK 是否已成功启动。采样未命中、enabled=false 或尚未 init 时都会返回 false。
     */
    fun isRunning(): Boolean = runtime != null

    /**
     * 停止 SDK 并注销回调。
     *
     * 普通 App 不需要主动调用；这个方法主要服务于测试、动态开关或多进程实验。
     */
    @Synchronized
    fun shutdown() {
        runtime?.stop()
        runtime = null
    }

    /**
     * 轻量采样判断。
     *
     * 线上建议通过远程配置控制 sampleRate，先从极低采样开始灰度，
     * 避免 SDK 上线第一天就产生大量性能和日志成本。
     */
    private fun shouldSample(sampleRate: Float): Boolean {
        return sampleRate >= 1.0f || Random.nextFloat() < sampleRate.coerceIn(0.0f, 1.0f)
    }

    /**
     * 对配置做保护性修正。
     *
     * 这些下限不是业务阈值，而是 SDK 自身安全阈值：例如周期采集最少 5 秒一次，
     * 防止误配置成 100ms 这类会明显增加 CPU/电量/锁竞争的频率。
     */
    private fun MemoryApmConfig.normalized(): MemoryApmConfig {
        return copy(
            sampleRate = sampleRate.coerceIn(0.0f, 1.0f),
            collectIntervalMs = collectIntervalMs.coerceAtLeast(5_000L),
            ringBufferSize = ringBufferSize.coerceAtLeast(8),
            leakWatchDelayMs = leakWatchDelayMs.coerceAtLeast(3_000L),
            retainedActivityThreshold = retainedActivityThreshold.coerceAtLeast(1),
            javaHeapWarningRatio = javaHeapWarningRatio.coerceIn(0.01f, 0.99f),
        )
    }
}
