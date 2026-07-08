package com.codex.memoryapm

import com.codex.memoryapm.model.LeakReport
import com.codex.memoryapm.model.MemoryReport

/**
 * 宿主 App 需要实现的上报接口。
 *
 * SDK 不直接绑定任何网络库或埋点平台，原因有三点：
 * 1. 避免 SDK 引入额外依赖，降低接入成本。
 * 2. 线上上报通常已有统一通道，内存数据应该复用同一套鉴权、压缩、重试和限流策略。
 * 3. 内存异常时进程可能不稳定，上报逻辑应尽量交给宿主已有的可靠队列。
 */
interface MemoryUploader {
    /**
     * 上报一次普通内存快照。
     *
     * report 中包含当前 snapshot、最近 ring buffer 快照、warning 和运行上下文。
     * 线上建议批量/异步落盘后上报，不要在这里执行同步网络请求。
     */
    fun upload(report: MemoryReport)

    /**
     * 上报疑似泄漏结果。
     *
     * 默认空实现，方便只关心基础内存指标的宿主先低成本接入。
     */
    fun uploadLeak(report: LeakReport) = Unit
}
