package com.codex.memoryapm.model

/**
 * App 当前前后台状态。
 *
 * 线上分析时同一个 PSS 数值在前台和后台意义不同：
 * 前台高内存可能影响流畅度，后台高内存更容易触发系统回收。
 */
enum class AppState {
    UNKNOWN,
    FOREGROUND,
    BACKGROUND,
}

/**
 * 内存采样的触发来源。
 *
 * 服务端聚合时应按 eventType 分组，否则周期采样、页面生命周期、系统低内存信号会混在一起，
 * 很难判断某次高内存到底是页面导致的，还是系统压力导致的。
 */
enum class MemoryEventType {
    APP_START,
    APP_FOREGROUND,
    APP_BACKGROUND,
    PERIODIC,
    MANUAL,
    ACTIVITY_CREATED,
    ACTIVITY_STARTED,
    ACTIVITY_RESUMED,
    ACTIVITY_PAUSED,
    ACTIVITY_STOPPED,
    ACTIVITY_DESTROYED,
    TRIM_MEMORY,
    LOW_MEMORY,
    LEAK_DETECTED,
}

/**
 * Java/Kotlin 对象所在的 managed heap 统计。
 *
 * 这些值来自 Runtime：
 * - usedBytes = totalMemory - freeMemory，表示当前已向 ART 申请并正在使用的 Java 堆。
 * - maxBytes 是当前进程 Java 堆上限，受设备、系统版本、largeHeap 和进程类型影响。
 *
 * 注意：Java heap 只覆盖 managed object，不包含 Bitmap native pixel buffer、线程栈、so、WebView 等 native 占用。
 */
data class JavaHeapStats(
    val usedBytes: Long,
    val freeBytes: Long,
    val totalBytes: Long,
    val maxBytes: Long,
) {
    val usageRatio: Float
        get() = if (maxBytes > 0L) usedBytes.toFloat() / maxBytes else 0.0f
}

/**
 * Native heap 统计。
 *
 * 这些值来自 Debug.getNativeHeap*，主要反映 native malloc 区域。
 * 它不等价于整个 native 内存，也不等价于 PSS；线上通常和 nativePss 一起看。
 */
data class NativeHeapStats(
    val allocatedBytes: Long,
    val freeBytes: Long,
    val sizeBytes: Long,
)

/**
 * PSS(Proportional Set Size) 统计，单位 KB。
 *
 * PSS 会把共享内存按比例分摊到进程，是 Android 线上判断进程真实内存压力时最常用的指标。
 * totalPssKb 常用于版本/机型/页面维度的 P95、P99 聚合。
 */
data class PssStats(
    val totalPssKb: Int,
    val dalvikPssKb: Int,
    val nativePssKb: Int,
    val graphicsPssKb: Int,
    val otherPssKb: Int,
)

/**
 * ART GC 统计。
 *
 * GC 次数和耗时可以辅助判断内存抖动：
 * 如果页面切换期间 GC 次数/耗时明显升高，通常意味着对象分配过多或缓存策略不合理。
 */
data class GcStats(
    val gcCount: Long,
    val gcTimeMs: Long,
    val blockingGcCount: Long,
    val blockingGcTimeMs: Long,
)

/**
 * 一次完整的内存快照。
 *
 * SDK 所有上报都围绕 snapshot 组织。服务端可以把它作为事实表，
 * 再按 appVersion、deviceModel、pageName、eventType 等维度建立聚合看板。
 */
data class MemorySnapshot(
    val timestampMs: Long,
    val eventType: MemoryEventType,
    val processName: String,
    val appState: AppState,
    val pageName: String?,
    val javaHeap: JavaHeapStats,
    val nativeHeap: NativeHeapStats,
    val pss: PssStats,
    val gc: GcStats?,
    val trimLevel: Int? = null,
    val note: String? = null,
)

/**
 * 单次采样中命中的风险提示。
 *
 * warning 是轻量规则结果，不代表一定已经发生泄漏或 OOM。
 * 真正报警时建议服务端再结合趋势、同版本基线和同机型分布做二次判断。
 */
data class MemoryWarning(
    val type: String,
    val message: String,
    val value: String,
    val threshold: String,
)

/**
 * 设备维度信息。
 *
 * 内存问题高度依赖机型和系统版本，所以这些字段应该随每条报告上报。
 */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val release: String,
    val supportedAbis: List<String>,
)

/**
 * App 版本信息。
 *
 * 线上排查通常先比较新旧版本的 OOM 率、PSS P95/P99 和页面增量。
 */
data class AppInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
)

/**
 * 一条报告的稳定上下文，避免服务端从埋点外层再拼接这些关键字段。
 */
data class RuntimeContext(
    val app: AppInfo,
    val device: DeviceInfo,
)

/**
 * 上次进程退出原因。
 *
 * Android 11+ 的 ApplicationExitInfo 能帮助识别低内存杀进程、native crash、ANR 等情况。
 * 低版本无法读取该信息时该字段为空。
 */
data class ProcessExitRecord(
    val timestampMs: Long,
    val reason: Int,
    val reasonName: String,
    val importance: Int,
    val status: Int,
    val pssKb: Long,
    val rssKb: Long,
    val description: String?,
)

/**
 * 普通内存上报载荷。
 *
 * recentSnapshots 是当前 snapshot 前后的局部轨迹，用于服务端判断“持续上涨”还是“短时峰值”。
 */
data class MemoryReport(
    val context: RuntimeContext,
    val snapshot: MemorySnapshot,
    val warnings: List<MemoryWarning>,
    val recentSnapshots: List<MemorySnapshot>,
    val lastProcessExit: ProcessExitRecord? = null,
    val extras: Map<String, String> = emptyMap(),
)

/**
 * 被弱引用观察后仍未释放的对象。
 *
 * 当前版本只上报类名和保留时长，不在线上解析引用链。引用链分析应放到灰度 HPROF 或线下复现中做。
 */
data class RetainedObject(
    val key: String,
    val className: String,
    val description: String,
    val retainedDurationMs: Long,
)

/**
 * 疑似泄漏上报载荷。
 *
 * 这是“retained”信号，不是严格的 leak 结论。服务端应看聚合趋势，
 * 例如某个 Activity 在新版本 retained 数量明显升高，再安排 HPROF 或线下复现。
 */
data class LeakReport(
    val context: RuntimeContext,
    val timestampMs: Long,
    val retainedCount: Int,
    val retainedObjects: List<RetainedObject>,
    val snapshot: MemorySnapshot,
    val recentSnapshots: List<MemorySnapshot>,
)
