package com.codex.memoryapm

/**
 * 内存监控 SDK 的运行配置。
 *
 * 线上接入时建议所有字段都由远程配置下发，并支持按版本、机型、系统版本、用户分桶分别控制。
 * 这样一旦某个策略对性能有影响，可以不发版直接降采样或关闭对应能力。
 *
 * @property enabled SDK 总开关。false 时 init 会直接返回，不创建线程、不注册生命周期。
 * @property sampleRate 用户采样率，范围 0.0 到 1.0。线上建议先使用 0.001 到 0.01 灰度。
 * @property collectIntervalMs 周期采集间隔。前台监控一般 15-30 秒即可，过短会增加运行时开销。
 * @property ringBufferSize 内存快照环形缓存大小，用于上报时携带最近轨迹，帮助判断内存是否持续上涨。
 * @property enablePeriodicCollect 是否开启周期采集。关闭后仍保留生命周期、trim、手动采集。
 * @property enableActivityTracking 是否注册 Activity 生命周期，用于页面级内存归因和前后台判断。
 * @property enableLeakDetect 是否启用轻量 retained Activity 检测。它只判断对象是否迟迟未释放，不解析引用链。
 * @property leakWatchDelayMs Activity destroyed 后等待多久再检查。时间太短容易被正常 GC 延迟误判。
 * @property retainedActivityThreshold retained 对象数量达到多少才上报，用来降低线上噪声。
 * @property javaHeapWarningRatio Java 堆使用率告警阈值，used/max 达到该比例时生成 warning。
 * @property totalPssWarningKb 总 PSS 告警阈值，单位 KB。0 表示不启用固定 PSS 阈值。
 */
data class MemoryApmConfig(
    val enabled: Boolean = true,
    val sampleRate: Float = 1.0f,
    val collectIntervalMs: Long = 30_000L,
    val ringBufferSize: Int = 80,
    val enablePeriodicCollect: Boolean = true,
    val enableActivityTracking: Boolean = true,
    val enableLeakDetect: Boolean = true,
    val leakWatchDelayMs: Long = 10_000L,
    val retainedActivityThreshold: Int = 1,
    val javaHeapWarningRatio: Float = 0.80f,
    val totalPssWarningKb: Int = 0,
)
