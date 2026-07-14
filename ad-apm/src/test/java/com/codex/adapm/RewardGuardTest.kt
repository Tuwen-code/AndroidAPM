package com.codex.adapm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardGuardTest {
    @Test
    fun markRewarded_allowsOnlyFirstRewardForSameRewardId() {
        val guard = RewardGuard(FakeAdClock())
        val reward = RewardGrant(
            rewardId = "reward-001",
            userId = "user-001",
            slotId = "reward_video",
            rewardType = "coin",
            amount = 10,
            network = "admob",
            placementId = "reward_admob",
        )

        assertTrue(guard.markRewarded(reward).allowed)
        assertFalse(guard.markRewarded(reward).allowed)
    }
}
