package com.codex.adapm

/**
 * 可替换时钟。
 *
 * 生产环境使用系统时间，单测中注入 FakeAdClock，避免依赖真实等待时间。
 */
fun interface AdClock {
    fun nowMs(): Long
}

/**
 * 默认系统时钟实现。
 */
object SystemAdClock : AdClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
