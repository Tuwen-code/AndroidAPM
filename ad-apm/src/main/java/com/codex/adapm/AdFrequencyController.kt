package com.codex.adapm

/**
 * 广告展示频控器。
 *
 * 目前按广告位维度记录本次进程会话内的展示次数和最近展示时间，
 * 可避免插屏、开屏等广告过于频繁打断用户。
 */
class AdFrequencyController(
    private val clock: AdClock = SystemAdClock,
) {
    private val lock = Any()
    private val sessionStartedAtMs = clock.nowMs()
    private val states = mutableMapOf<String, FrequencyState>()

    /**
     * 判断指定广告位当前是否允许展示。
     */
    fun canShow(slotId: String, rule: AdFrequencyRule): FrequencyDecision {
        synchronized(lock) {
            val nowMs = clock.nowMs()
            val state = states.getOrPut(slotId) { FrequencyState() }

            if (rule.startDelayMs > 0 && nowMs - sessionStartedAtMs < rule.startDelayMs) {
                return FrequencyDecision.blocked("Ad start delay is active.")
            }

            if (state.showCount >= rule.maxShowsPerSession) {
                return FrequencyDecision.blocked("Ad session show limit is reached.")
            }

            val lastShownAtMs = state.lastShownAtMs
            if (lastShownAtMs != null && nowMs - lastShownAtMs < rule.minIntervalMs) {
                return FrequencyDecision.blocked("Ad cooldown is active.")
            }

            return FrequencyDecision.allowed()
        }
    }

    /**
     * 记录一次成功展示，用于后续冷却时间和会话次数判断。
     */
    fun recordShow(slotId: String) {
        synchronized(lock) {
            val state = states.getOrPut(slotId) { FrequencyState() }
            state.lastShownAtMs = clock.nowMs()
            state.showCount += 1
        }
    }

    /**
     * 重置频控状态。
     */
    fun reset(slotId: String? = null) {
        synchronized(lock) {
            if (slotId == null) {
                states.clear()
            } else {
                states.remove(slotId)
            }
        }
    }

    private data class FrequencyState(
        var lastShownAtMs: Long? = null,
        var showCount: Int = 0,
    )
}
