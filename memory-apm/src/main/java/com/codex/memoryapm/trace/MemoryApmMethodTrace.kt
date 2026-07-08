package com.codex.memoryapm.trace

import android.util.Log

object MemoryApmMethodTrace {
    private const val TAG = "MemoryApmTrace"

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var logSlowMethod: Boolean = true

    @Volatile
    private var slowThresholdNs: Long = 16_000_000L

    @JvmStatic
    fun configure(
        enabled: Boolean,
        slowThresholdMs: Long = 16L,
        logSlowMethod: Boolean = true,
    ) {
        this.enabled = enabled
        this.slowThresholdNs = slowThresholdMs.coerceAtLeast(0L) * 1_000_000L
        this.logSlowMethod = logSlowMethod
    }

    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    @JvmStatic
    fun onMethodEnter(className: String, methodName: String): Long {
        if (!enabled) {
            return 0L
        }
        return System.nanoTime()
    }

    @JvmStatic
    fun onMethodExit(className: String, methodName: String, startNs: Long) {
        if (!enabled || startNs == 0L) {
            return
        }

        val costNs = System.nanoTime() - startNs
        if (logSlowMethod && costNs >= slowThresholdNs) {
            Log.d(TAG, "${className.replace('/', '.')}#$methodName cost=${costNs / 1_000_000.0}ms")
        }
    }
}
