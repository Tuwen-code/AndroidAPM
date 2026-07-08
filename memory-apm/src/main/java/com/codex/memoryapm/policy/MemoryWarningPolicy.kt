package com.codex.memoryapm.policy

import android.content.ComponentCallbacks2
import com.codex.memoryapm.MemoryApmConfig
import com.codex.memoryapm.model.MemorySnapshot
import com.codex.memoryapm.model.MemoryWarning

/**
 * 单次采样的轻量告警策略。
 *
 * 这里的规则只负责给 report 打 warning 标签，不直接弹窗、不崩溃、不强制 dump HPROF。
 * 线上真正报警建议放在服务端完成，因为服务端可以结合版本基线、机型分布和持续时间减少误报。
 */
internal class MemoryWarningPolicy(
    private val config: MemoryApmConfig,
) {
    fun evaluate(snapshot: MemorySnapshot): List<MemoryWarning> {
        val warnings = mutableListOf<MemoryWarning>()

        // Java heap 使用率过高时，后续大对象分配更容易触发 Java OOM。
        if (snapshot.javaHeap.usageRatio >= config.javaHeapWarningRatio) {
            warnings += MemoryWarning(
                type = "JAVA_HEAP_HIGH",
                message = "Java heap usage is high",
                value = "%.2f".format(snapshot.javaHeap.usageRatio),
                threshold = "%.2f".format(config.javaHeapWarningRatio),
            )
        }

        // PSS 阈值适合按机型/版本配置；低端机和高端机使用同一个固定阈值通常不合理。
        if (config.totalPssWarningKb > 0 && snapshot.pss.totalPssKb >= config.totalPssWarningKb) {
            warnings += MemoryWarning(
                type = "TOTAL_PSS_HIGH",
                message = "Total PSS is above configured threshold",
                value = snapshot.pss.totalPssKb.toString(),
                threshold = config.totalPssWarningKb.toString(),
            )
        }

        // 系统 trim 回调代表系统侧内存压力，是比普通数值阈值更强的风险信号。
        if (snapshot.trimLevel in criticalTrimLevels) {
            warnings += MemoryWarning(
                type = "SYSTEM_MEMORY_PRESSURE",
                message = "System requested aggressive memory trimming",
                value = snapshot.trimLevel.toString(),
                threshold = criticalTrimLevels.joinToString(),
            )
        }

        return warnings
    }

    @Suppress("DEPRECATION")
    private companion object {
        // 这些 level 表示系统已经要求进程积极释放内存，服务端可以重点观察这些事件后的退出原因。
        private val criticalTrimLevels = setOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )
    }
}
