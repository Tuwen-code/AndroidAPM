package com.example.memoryapm

import com.codex.memoryapm.koom.KoomDumpResult
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Demo 页面和 Application 不在同一个创建时机里，自动 dump 结果需要一个轻量的进程内分发点。
 *
 * 真实线上 SDK 不建议把诊断结果直接绑定 UI；这里这么做只是为了学习 KOOM fork dump
 * 的完整闭环：触发 -> fork dump -> 生成 hprof -> 页面展示输出路径。
 */
object KoomDumpEventCenter {
    private val listeners = CopyOnWriteArraySet<(KoomDumpResult) -> Unit>()

    @Volatile
    private var latestResult: KoomDumpResult? = null

    fun latest(): KoomDumpResult? = latestResult

    fun notify(result: KoomDumpResult) {
        latestResult = result
        listeners.forEach { listener ->
            listener(result)
        }
    }

    fun addListener(listener: (KoomDumpResult) -> Unit): () -> Unit {
        listeners += listener
        latestResult?.let(listener)
        return {
            listeners -= listener
        }
    }
}
