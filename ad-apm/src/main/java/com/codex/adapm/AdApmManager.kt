package com.codex.adapm

import android.app.Activity
import android.content.Context

/**
 * 广告模块的核心调度器。
 *
 * 它负责把业务广告位、多个平台 Adapter、缓存、频控和奖励幂等串起来，
 * 业务侧不需要关心当前广告来自 AdMob、穿山甲还是其他平台。
 */
class AdApmManager(
    adapters: List<AdNetworkAdapter>,
    private val clock: AdClock = SystemAdClock,
    private val cacheStore: AdCacheStore = AdCacheStore(clock),
    private val frequencyController: AdFrequencyController = AdFrequencyController(clock),
    private val rewardGuard: RewardGuard = RewardGuard(),
    private val eventListener: AdEventListener = AdEventListener.NONE,
) {
    private val adaptersByNetwork = adapters.associateBy { it.network }

    /**
     * 初始化所有已注册广告平台。
     *
     * configs 可只传需要特殊配置的平台，未传配置的平台会收到默认空配置。
     */
    fun initialize(
        context: Context,
        configs: List<AdNetworkConfig> = emptyList(),
    ) {
        val configsByNetwork = configs.associateBy { it.network }
        adaptersByNetwork.values.forEach { adapter ->
            adapter.initialize(
                context = context,
                config = configsByNetwork[adapter.network] ?: AdNetworkConfig(adapter.network),
            )
        }
    }

    /**
     * 预加载广告并写入缓存。
     *
     * 如果首选平台加载失败，会按 AdSlot.placements 顺序继续尝试后续平台。
     */
    fun preload(
        activity: Activity?,
        slot: AdSlot,
        request: AdRequest = AdRequest(slotId = slot.slotId),
        callback: AdLoadCallback = AdLoadCallback.NONE,
    ) {
        loadFromPlacement(activity, slot, request.normalized(slot), 0, callback)
    }

    /**
     * 展示广告。
     *
     * 展示前先做频控判断，再优先消费缓存；没有可用缓存时会临时加载并在成功后立即展示。
     */
    fun show(
        activity: Activity?,
        slot: AdSlot,
        request: AdRequest = AdRequest(slotId = slot.slotId),
        callback: AdShowCallback = AdShowCallback.NONE,
    ) {
        val normalizedRequest = request.normalized(slot)
        val frequencyDecision = frequencyController.canShow(slot.slotId, slot.frequencyRule)
        if (!frequencyDecision.allowed) {
            callback.onAdShowFailed(
                AdError(
                    code = AdErrorCode.FREQUENCY_CAPPED,
                    message = frequencyDecision.reason ?: "Ad frequency rule blocked the request.",
                    slotId = slot.slotId,
                )
            )
            return
        }

        val cachedAd = cacheStore.take(slot.slotId, slot.format)
        if (cachedAd != null) {
            // 有缓存时直接展示，避免用户点击后再等待广告 SDK 网络请求。
            showLoadedAd(activity, slot, cachedAd, callback)
            return
        }

        preload(
            activity = activity,
            slot = slot,
            request = normalizedRequest,
            callback = object : AdLoadCallback {
                override fun onAdLoaded(ad: LoadedAd) {
                    showLoadedAd(activity, slot, ad, callback)
                }

                override fun onAdLoadFailed(error: AdError) {
                    callback.onAdShowFailed(error)
                }
            }
        )
    }

    /**
     * 创建激励广告奖励订单。
     *
     * 这个订单只做进程内幂等保护；线上如果要防止崩溃或重启后的重复发奖，需要结合服务端和本地持久化。
     */
    fun createRewardOrder(
        rewardId: String,
        userId: String,
        slotId: String,
        rewardType: String,
        amount: Int,
        metadata: Map<String, String> = emptyMap(),
    ): RewardOrder {
        return rewardGuard.createOrder(
            RewardOrder(
                rewardId = rewardId,
                userId = userId,
                slotId = slotId,
                rewardType = rewardType,
                amount = amount,
                state = RewardOrderState.PENDING,
                createdAtMs = clock.nowMs(),
                updatedAtMs = clock.nowMs(),
                metadata = metadata,
            )
        )
    }

    /**
     * 返回当前进程内的奖励订单快照，主要用于调试和单测。
     */
    fun rewardSnapshot(): List<RewardOrder> = rewardGuard.snapshot()

    /**
     * 清理广告缓存。
     *
     * slotId 为空时清理全部广告位缓存。
     */
    fun clearCache(slotId: String? = null) {
        cacheStore.clear(slotId)
    }

    /**
     * 重置频控状态。
     *
     * 常用于用户登录态切换、配置刷新或测试场景。
     */
    fun resetFrequency(slotId: String? = null) {
        frequencyController.reset(slotId)
    }

    /**
     * 销毁模块内部资源。
     */
    fun destroy() {
        cacheStore.clear()
        adaptersByNetwork.values.forEach { it.destroy() }
    }

    private fun loadFromPlacement(
        activity: Activity?,
        slot: AdSlot,
        request: AdRequest,
        placementIndex: Int,
        callback: AdLoadCallback,
    ) {
        val placement = slot.placements.getOrNull(placementIndex)
        if (placement == null) {
            callback.onAdLoadFailed(
                AdError(
                    code = AdErrorCode.NO_FILL,
                    message = "No ad placement loaded successfully.",
                    slotId = slot.slotId,
                )
            )
            return
        }

        // 当前平台没有注册 Adapter 时，直接跳过，继续尝试下一个平台。
        val adapter = adaptersByNetwork[placement.network]
        if (adapter == null) {
            loadFromPlacement(activity, slot, request, placementIndex + 1, callback)
            return
        }

        eventListener.onAdEvent(
            AdEvent(
                type = AdEventType.LOAD_STARTED,
                slotId = slot.slotId,
                format = slot.format,
                network = placement.network,
                placementId = placement.placementId,
                timestampMs = clock.nowMs(),
            )
        )

        adapter.load(
            activity = activity,
            slot = slot,
            placement = placement,
            request = request,
            callback = object : AdLoadCallback {
                override fun onAdLoaded(ad: LoadedAd) {
                    cacheStore.put(ad)
                    eventListener.onAdEvent(
                        AdEvent(
                            type = AdEventType.LOADED,
                            slotId = slot.slotId,
                            format = slot.format,
                            network = ad.network,
                            placementId = ad.placementId,
                            timestampMs = clock.nowMs(),
                        )
                    )
                    callback.onAdLoaded(ad)
                }

                override fun onAdLoadFailed(error: AdError) {
                    eventListener.onAdEvent(
                        AdEvent(
                            type = AdEventType.LOAD_FAILED,
                            slotId = slot.slotId,
                            format = slot.format,
                            network = placement.network,
                            placementId = placement.placementId,
                            timestampMs = clock.nowMs(),
                            message = error.message,
                        )
                    )
                    // 单个平台加载失败不直接失败，继续走下一个平台，实现基础瀑布流降级。
                    loadFromPlacement(activity, slot, request, placementIndex + 1, callback)
                }
            }
        )
    }

    private fun showLoadedAd(
        activity: Activity?,
        slot: AdSlot,
        ad: LoadedAd,
        callback: AdShowCallback,
    ) {
        val adapter = adaptersByNetwork[ad.network]
        if (adapter == null) {
            callback.onAdShowFailed(
                AdError(
                    code = AdErrorCode.ADAPTER_NOT_FOUND,
                    message = "Adapter ${ad.network} is not registered.",
                    slotId = slot.slotId,
                    network = ad.network,
                    placementId = ad.placementId,
                )
            )
            return
        }

        adapter.show(
            activity = activity,
            ad = ad,
            callback = object : AdShowCallback {
                override fun onAdShown(event: AdEvent) {
                    frequencyController.recordShow(slot.slotId)
                    eventListener.onAdEvent(event)
                    callback.onAdShown(event)
                }

                override fun onAdClicked(event: AdEvent) {
                    eventListener.onAdEvent(event)
                    callback.onAdClicked(event)
                }

                override fun onRewarded(reward: RewardGrant) {
                    val decision = rewardGuard.markRewarded(reward)
                    if (decision.allowed) {
                        // 只有首次 rewardId 才透传给业务侧，避免 SDK 重复回调导致重复发奖。
                        callback.onRewarded(reward)
                    }
                }

                override fun onAdClosed(result: AdShowResult) {
                    eventListener.onAdEvent(result.event)
                    callback.onAdClosed(result)
                }

                override fun onAdShowFailed(error: AdError) {
                    eventListener.onAdEvent(
                        AdEvent(
                            type = AdEventType.SHOW_FAILED,
                            slotId = slot.slotId,
                            format = slot.format,
                            network = ad.network,
                            placementId = ad.placementId,
                            timestampMs = clock.nowMs(),
                            message = error.message,
                        )
                    )
                    callback.onAdShowFailed(error)
                }
            }
        )
    }

    /**
     * 修正请求中的 slotId，确保业务传错或复用请求对象时仍以当前广告位为准。
     */
    private fun AdRequest.normalized(slot: AdSlot): AdRequest {
        return if (slotId == slot.slotId) {
            this
        } else {
            copy(slotId = slot.slotId)
        }
    }
}
