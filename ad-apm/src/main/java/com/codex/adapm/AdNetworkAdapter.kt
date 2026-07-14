package com.codex.adapm

import android.app.Activity
import android.content.Context

/**
 * 第三方广告平台适配器接口。
 *
 * AdMob、穿山甲等真实 SDK 不直接暴露给业务层，而是各自实现这个接口接入统一调度。
 */
interface AdNetworkAdapter {
    val network: String

    /**
     * 初始化广告平台 SDK。
     *
     * 默认空实现，便于 Mock Adapter 或无需初始化的平台直接复用。
     */
    fun initialize(
        context: Context,
        config: AdNetworkConfig,
    ) = Unit

    /**
     * 加载广告。
     *
     * Adapter 负责调用具体 SDK，并把平台结果转换成统一的 LoadedAd 或 AdError。
     */
    fun load(
        activity: Activity?,
        slot: AdSlot,
        placement: AdPlacement,
        request: AdRequest,
        callback: AdLoadCallback,
    )

    /**
     * 展示已加载广告。
     *
     * 对于真实 SDK，LoadedAd.payload 通常保存平台广告实例，Adapter 在这里取出并展示。
     */
    fun show(
        activity: Activity?,
        ad: LoadedAd,
        callback: AdShowCallback,
    )

    /**
     * 释放平台资源。
     */
    fun destroy() = Unit
}

/**
 * 广告加载回调。
 */
interface AdLoadCallback {
    fun onAdLoaded(ad: LoadedAd)

    fun onAdLoadFailed(error: AdError)

    companion object {
        val NONE = object : AdLoadCallback {
            override fun onAdLoaded(ad: LoadedAd) = Unit

            override fun onAdLoadFailed(error: AdError) = Unit
        }
    }
}

/**
 * 广告展示回调。
 *
 * 激励广告可能出现重复 reward 回调，管理器会先经过 RewardGuard 幂等判断再回调业务侧。
 */
interface AdShowCallback {
    fun onAdShown(event: AdEvent) = Unit

    fun onAdClicked(event: AdEvent) = Unit

    fun onRewarded(reward: RewardGrant) = Unit

    fun onAdClosed(result: AdShowResult) = Unit

    fun onAdShowFailed(error: AdError) = Unit

    companion object {
        val NONE = object : AdShowCallback {}
    }
}

/**
 * 广告事件监听器。
 *
 * 适合统一接入日志、埋点或调试面板。
 */
interface AdEventListener {
    fun onAdEvent(event: AdEvent)

    companion object {
        val NONE = object : AdEventListener {
            override fun onAdEvent(event: AdEvent) = Unit
        }
    }
}
