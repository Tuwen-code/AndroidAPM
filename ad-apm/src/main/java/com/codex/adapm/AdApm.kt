package com.codex.adapm

import android.app.Activity
import android.content.Context

/**
 * 广告模块对业务侧暴露的统一入口。
 *
 * 业务页面只需要通过这里初始化、预加载和展示广告，不直接依赖 AdMob、穿山甲等具体 SDK。
 */
object AdApm {
    @Volatile
    private var manager: AdApmManager? = null

    /**
     * 初始化广告模块。
     *
     * @param context 建议传入 Application Context，避免持有页面引用。
     * @param adapters 已注册的平台适配器，例如 AdMob、穿山甲或 Mock Adapter。
     * @param networkConfigs 各广告平台的 appId、开关或扩展配置。
     * @param eventListener 广告加载、展示、点击、关闭等事件监听，可对接埋点系统。
     */
    @Synchronized
    fun init(
        context: Context,
        adapters: List<AdNetworkAdapter>,
        networkConfigs: List<AdNetworkConfig> = emptyList(),
        eventListener: AdEventListener = AdEventListener.NONE,
    ) {
        val newManager = AdApmManager(
            adapters = adapters,
            eventListener = eventListener,
        )
        newManager.initialize(context, networkConfigs)
        manager = newManager
    }

    /**
     * 预加载指定广告位。
     *
     * 激励视频、插屏等广告建议提前调用，用户触发展示时可直接消费缓存广告，减少等待。
     */
    fun preload(
        activity: Activity?,
        slot: AdSlot,
        request: AdRequest = AdRequest(slotId = slot.slotId),
        callback: AdLoadCallback = AdLoadCallback.NONE,
    ) {
        manager?.preload(activity, slot, request, callback)
            ?: callback.onAdLoadFailed(
                AdError(
                    code = AdErrorCode.NOT_INITIALIZED,
                    message = "AdApm is not initialized.",
                    slotId = slot.slotId,
                )
            )
    }

    /**
     * 展示指定广告位。
     *
     * 管理器会优先使用缓存广告；没有缓存时会尝试加载，并按广告位配置的 placements 顺序做失败降级。
     */
    fun show(
        activity: Activity?,
        slot: AdSlot,
        request: AdRequest = AdRequest(slotId = slot.slotId),
        callback: AdShowCallback = AdShowCallback.NONE,
    ) {
        manager?.show(activity, slot, request, callback)
            ?: callback.onAdShowFailed(
                AdError(
                    code = AdErrorCode.NOT_INITIALIZED,
                    message = "AdApm is not initialized.",
                    slotId = slot.slotId,
                )
            )
    }

    /**
     * 创建激励广告奖励订单。
     *
     * 业务侧可在展示激励视频前创建 rewardId，后续奖励回调会通过同一个 rewardId 做本地幂等保护。
     */
    fun createRewardOrder(
        rewardId: String,
        userId: String,
        slotId: String,
        rewardType: String,
        amount: Int,
        metadata: Map<String, String> = emptyMap(),
    ): RewardOrder? {
        return manager?.createRewardOrder(
            rewardId = rewardId,
            userId = userId,
            slotId = slotId,
            rewardType = rewardType,
            amount = amount,
            metadata = metadata,
        )
    }

    /**
     * 关闭广告模块并释放已注册 Adapter 与缓存。
     *
     * 主要用于测试、动态开关或应用退出前的清理场景。
     */
    @Synchronized
    fun shutdown() {
        manager?.destroy()
        manager = null
    }
}
