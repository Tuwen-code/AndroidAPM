package com.codex.adapm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdFrequencyControllerTest {
    @Test
    fun canShow_returnsAllowedAfterCooldownPasses() {
        val clock = FakeAdClock()
        val controller = AdFrequencyController(clock)
        val rule = AdFrequencyRule(minIntervalMs = 1_000L)

        assertTrue(controller.canShow("splash", rule).allowed)
        controller.recordShow("splash")
        assertFalse(controller.canShow("splash", rule).allowed)

        clock.advanceBy(1_000L)

        assertTrue(controller.canShow("splash", rule).allowed)
    }
}
