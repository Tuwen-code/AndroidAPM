package com.codex.adapm.mock

import android.app.Activity
import com.codex.adapm.AdClock
import com.codex.adapm.AdError
import com.codex.adapm.AdErrorCode
import com.codex.adapm.AdEvent
import com.codex.adapm.AdEventType
import com.codex.adapm.AdFormat
import com.codex.adapm.AdLoadCallback
import com.codex.adapm.AdNetworkAdapter
import com.codex.adapm.AdPlacement
import com.codex.adapm.AdRequest
import com.codex.adapm.AdShowCallback
import com.codex.adapm.AdShowResult
import com.codex.adapm.AdSlot
import com.codex.adapm.LoadedAd
import com.codex.adapm.RewardGrant
import com.codex.adapm.SystemAdClock

/**
 * 用于测试和本地联调的模拟广告平台。
 *
 * 它不依赖任何真实广告 SDK，可通过开关模拟加载失败、展示失败和重复奖励回调。
 */
class MockAdNetworkAdapter(
    override val network: String = NETWORK,
    private val clock: AdClock = SystemAdClock,
    var loadSucceeds: Boolean = true,
    var showSucceeds: Boolean = true,
    var rewardCallbackCount: Int = 1,
) : AdNetworkAdapter {
    var loadCount: Int = 0
        private set

    var showCount: Int = 0
        private set

    override fun load(
        activity: Activity?,
        slot: AdSlot,
        placement: AdPlacement,
        request: AdRequest,
        callback: AdLoadCallback,
    ) {
        loadCount += 1
        if (!loadSucceeds) {
            // 模拟平台无填充、网络失败或 SDK 加载失败。
            callback.onAdLoadFailed(
                AdError(
                    code = AdErrorCode.LOAD_FAILED,
                    message = "Mock load failed.",
                    slotId = slot.slotId,
                    network = network,
                    placementId = placement.placementId,
                )
            )
            return
        }

        val nowMs = clock.nowMs()
        callback.onAdLoaded(
            LoadedAd(
                adId = "${network}_${slot.slotId}_$loadCount",
                slotId = slot.slotId,
                format = slot.format,
                network = network,
                placementId = placement.placementId,
                loadedAtMs = nowMs,
                // 使用广告位配置的缓存时长，便于测试过期逻辑。
                expiresAtMs = nowMs + slot.cacheTtlMs,
                payload = request,
            )
        )
    }

    override fun show(
        activity: Activity?,
        ad: LoadedAd,
        callback: AdShowCallback,
    ) {
        showCount += 1
        if (!showSucceeds) {
            // 模拟广告对象失效、Activity 状态不合法或平台 show 接口失败。
            callback.onAdShowFailed(
                AdError(
                    code = AdErrorCode.SHOW_FAILED,
                    message = "Mock show failed.",
                    slotId = ad.slotId,
                    network = network,
                    placementId = ad.placementId,
                )
            )
            return
        }

        callback.onAdShown(
            AdEvent(
                type = AdEventType.SHOWN,
                slotId = ad.slotId,
                format = ad.format,
                network = ad.network,
                placementId = ad.placementId,
                timestampMs = clock.nowMs(),
            )
        )

        if (ad.format == AdFormat.REWARDED_VIDEO) {
            // 部分 SDK 在异常生命周期下可能重复触发奖励回调，这里用于验证幂等保护。
            repeat(rewardCallbackCount.coerceAtLeast(0)) {
                callback.onRewarded(createReward(ad))
            }
        }

        callback.onAdClosed(
            AdShowResult(
                event = AdEvent(
                    type = AdEventType.CLOSED,
                    slotId = ad.slotId,
                    format = ad.format,
                    network = ad.network,
                    placementId = ad.placementId,
                    timestampMs = clock.nowMs(),
                ),
                completed = true,
            )
        )
    }

    private fun createReward(ad: LoadedAd): RewardGrant {
        val request = ad.payload as? AdRequest
        return RewardGrant(
            rewardId = request?.rewardId ?: "reward_${ad.adId}",
            userId = request?.userId ?: "mock_user",
            slotId = ad.slotId,
            rewardType = "coin",
            amount = 1,
            network = ad.network,
            placementId = ad.placementId,
        )
    }

    companion object {
        const val NETWORK = "mock"
    }
}
