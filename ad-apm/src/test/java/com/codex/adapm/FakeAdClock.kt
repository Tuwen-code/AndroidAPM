package com.codex.adapm

/**
 * 单测专用时钟。
 *
 * 通过手动推进时间验证缓存过期和频控冷却，无需让测试真实 sleep。
 */
class FakeAdClock(
    private var nowMs: Long = 0L,
) : AdClock {
    override fun nowMs(): Long = nowMs

    fun advanceBy(deltaMs: Long) {
        nowMs += deltaMs
    }
}
