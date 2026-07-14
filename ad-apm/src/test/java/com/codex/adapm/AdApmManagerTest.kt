package com.codex.adapm

import com.codex.adapm.mock.MockAdNetworkAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdApmManagerTest {
    @Test
    fun preload_fallsBackToNextPlacementWhenPrimaryFails() {
        val failed = MockAdNetworkAdapter(network = "admob", loadSucceeds = false)
        val fallback = MockAdNetworkAdapter(network = "pangle")
        val manager = AdApmManager(adapters = listOf(failed, fallback))
        val callback = RecordingLoadCallback()

        manager.preload(
            activity = null,
            slot = rewardSlot(),
            callback = callback,
        )

        assertEquals(1, failed.loadCount)
        assertEquals(1, fallback.loadCount)
        assertEquals("pangle", callback.loadedAd?.network)
    }

    @Test
    fun show_usesCachedAdAndEmitsSingleRewardWhenSdkDuplicatesCallback() {
        val adapter = MockAdNetworkAdapter(rewardCallbackCount = 2)
        val manager = AdApmManager(adapters = listOf(adapter))
        val slot = AdSlot(
            slotId = "reward_video",
            format = AdFormat.REWARDED_VIDEO,
            placements = listOf(AdPlacement(adapter.network, "reward_mock")),
        )
        val showCallback = RecordingShowCallback()

        manager.preload(activity = null, slot = slot)
        manager.show(activity = null, slot = slot, callback = showCallback)

        assertEquals(1, adapter.loadCount)
        assertEquals(1, adapter.showCount)
        assertEquals(1, showCallback.rewardCount)
        assertTrue(showCallback.closed)
    }

    @Test
    fun show_blocksWhenFrequencyCooldownIsActive() {
        val clock = FakeAdClock()
        val adapter = MockAdNetworkAdapter(clock = clock)
        val manager = AdApmManager(
            adapters = listOf(adapter),
            clock = clock,
        )
        val slot = AdSlot(
            slotId = "interstitial_home",
            format = AdFormat.INTERSTITIAL,
            placements = listOf(AdPlacement(adapter.network, "interstitial_mock")),
            frequencyRule = AdFrequencyRule(minIntervalMs = 60_000L),
        )
        val firstCallback = RecordingShowCallback()
        val secondCallback = RecordingShowCallback()

        manager.show(activity = null, slot = slot, callback = firstCallback)
        manager.show(activity = null, slot = slot, callback = secondCallback)

        assertEquals(1, adapter.showCount)
        assertEquals(AdErrorCode.FREQUENCY_CAPPED, secondCallback.error?.code)
    }

    private fun rewardSlot(): AdSlot {
        return AdSlot(
            slotId = "reward_video",
            format = AdFormat.REWARDED_VIDEO,
            placements = listOf(
                AdPlacement("admob", "reward_admob"),
                AdPlacement("pangle", "reward_pangle"),
            ),
        )
    }

    private class RecordingLoadCallback : AdLoadCallback {
        var loadedAd: LoadedAd? = null
        var error: AdError? = null

        override fun onAdLoaded(ad: LoadedAd) {
            loadedAd = ad
        }

        override fun onAdLoadFailed(error: AdError) {
            this.error = error
        }
    }

    private class RecordingShowCallback : AdShowCallback {
        var rewardCount: Int = 0
        var closed: Boolean = false
        var error: AdError? = null

        override fun onRewarded(reward: RewardGrant) {
            rewardCount += 1
        }

        override fun onAdClosed(result: AdShowResult) {
            closed = true
        }

        override fun onAdShowFailed(error: AdError) {
            this.error = error
        }
    }
}
