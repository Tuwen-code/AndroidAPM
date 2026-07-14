package com.codex.adapm

/**
 * 广告形态枚举。
 *
 * 后续接真实 SDK 时，各平台 Adapter 只需要把这些统一形态映射到平台自己的广告类型。
 */
enum class AdFormat {
    SPLASH,
    REWARDED_VIDEO,
    INTERSTITIAL,
    BANNER,
    NATIVE_FEED,
}

/**
 * 业务广告位配置。
 *
 * 一个广告位可以配置多个平台 placement，管理器会按列表顺序加载，前一个失败后自动尝试下一个。
 */
data class AdSlot(
    val slotId: String,
    val format: AdFormat,
    val placements: List<AdPlacement>,
    val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    val frequencyRule: AdFrequencyRule = AdFrequencyRule.NONE,
) {
    init {
        require(slotId.isNotBlank()) { "slotId must not be blank." }
        require(placements.isNotEmpty()) { "placements must not be empty." }
        require(cacheTtlMs > 0) { "cacheTtlMs must be greater than 0." }
    }

    companion object {
        const val DEFAULT_CACHE_TTL_MS: Long = 30 * 60 * 1000L
    }
}

/**
 * 单个平台上的广告位信息。
 *
 * 例如同一个业务激励广告位，可以分别配置 AdMob placementId 和穿山甲 placementId。
 */
data class AdPlacement(
    val network: String,
    val placementId: String,
) {
    init {
        require(network.isNotBlank()) { "network must not be blank." }
        require(placementId.isNotBlank()) { "placementId must not be blank." }
    }
}

/**
 * 单次广告请求上下文。
 *
 * rewardId 用于激励视频发奖闭环，extras 可承载 SSV、自定义标签或平台扩展参数。
 */
data class AdRequest(
    val slotId: String,
    val userId: String? = null,
    val rewardId: String? = null,
    val extras: Map<String, String> = emptyMap(),
)

/**
 * 已加载的广告对象。
 *
 * payload 预留给真实 SDK Adapter 保存平台返回的广告实例，核心模块不直接解析它。
 */
data class LoadedAd(
    val adId: String,
    val slotId: String,
    val format: AdFormat,
    val network: String,
    val placementId: String,
    val loadedAtMs: Long,
    val expiresAtMs: Long,
    val payload: Any? = null,
) {
    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs
}

/**
 * 广告频控规则。
 *
 * 可用于插屏、开屏等强打断广告，避免短时间内反复展示影响用户体验。
 */
data class AdFrequencyRule(
    val minIntervalMs: Long = 0L,
    val maxShowsPerSession: Int = Int.MAX_VALUE,
    val startDelayMs: Long = 0L,
) {
    init {
        require(minIntervalMs >= 0) { "minIntervalMs must not be negative." }
        require(maxShowsPerSession > 0) { "maxShowsPerSession must be greater than 0." }
        require(startDelayMs >= 0) { "startDelayMs must not be negative." }
    }

    companion object {
        val NONE = AdFrequencyRule()
    }
}

/**
 * 频控判断结果。
 */
data class FrequencyDecision(
    val allowed: Boolean,
    val reason: String? = null,
) {
    companion object {
        fun allowed(): FrequencyDecision = FrequencyDecision(allowed = true)

        fun blocked(reason: String): FrequencyDecision {
            return FrequencyDecision(allowed = false, reason = reason)
        }
    }
}

/**
 * 广告事件类型，用于统一埋点和调试日志。
 */
enum class AdEventType {
    LOAD_STARTED,
    LOADED,
    LOAD_FAILED,
    SHOWN,
    CLICKED,
    REWARDED,
    CLOSED,
    SHOW_FAILED,
}

/**
 * 广告运行时事件。
 *
 * 事件里保留 slot、format、network 和 placement 信息，方便后续统计填充率、展示率、点击率等指标。
 */
data class AdEvent(
    val type: AdEventType,
    val slotId: String,
    val format: AdFormat,
    val network: String? = null,
    val placementId: String? = null,
    val timestampMs: Long,
    val message: String? = null,
    val extras: Map<String, String> = emptyMap(),
)

/**
 * 广告关闭结果。
 *
 * completed 可用于区分视频完整播放关闭和中途关闭。
 */
data class AdShowResult(
    val event: AdEvent,
    val completed: Boolean,
)

/**
 * 模块内部统一错误码。
 */
enum class AdErrorCode {
    NOT_INITIALIZED,
    ADAPTER_NOT_FOUND,
    LOAD_FAILED,
    SHOW_FAILED,
    NO_FILL,
    FREQUENCY_CAPPED,
}

/**
 * 广告加载或展示失败信息。
 */
data class AdError(
    val code: AdErrorCode,
    val message: String,
    val slotId: String? = null,
    val network: String? = null,
    val placementId: String? = null,
    val cause: Throwable? = null,
)

/**
 * 单个平台初始化配置。
 *
 * appId 和 extras 由具体平台 Adapter 自行解释，核心模块只负责透传。
 */
data class AdNetworkConfig(
    val network: String,
    val appId: String? = null,
    val extras: Map<String, String> = emptyMap(),
)
