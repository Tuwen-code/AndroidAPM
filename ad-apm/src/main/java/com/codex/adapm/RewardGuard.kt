package com.codex.adapm

/**
 * 激励广告发奖信息。
 *
 * rewardId 是幂等关键字段，同一个 rewardId 在进程内只会触发一次有效发奖回调。
 */
data class RewardGrant(
    val rewardId: String,
    val userId: String,
    val slotId: String,
    val rewardType: String,
    val amount: Int,
    val network: String,
    val placementId: String,
    val serverSideVerificationData: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(rewardId.isNotBlank()) { "rewardId must not be blank." }
        require(userId.isNotBlank()) { "userId must not be blank." }
        require(slotId.isNotBlank()) { "slotId must not be blank." }
        require(rewardType.isNotBlank()) { "rewardType must not be blank." }
        require(amount > 0) { "amount must be greater than 0." }
    }
}

/**
 * 奖励订单状态。
 */
enum class RewardOrderState {
    PENDING,
    REWARDED,
    GRANTED,
}

/**
 * 激励广告奖励订单。
 *
 * 推荐在展示激励视频前创建订单，服务端也使用相同 rewardId 做最终幂等校验。
 */
data class RewardOrder(
    val rewardId: String,
    val userId: String,
    val slotId: String,
    val rewardType: String,
    val amount: Int,
    val state: RewardOrderState,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * 奖励回调幂等判断结果。
 */
data class RewardDecision(
    val allowed: Boolean,
    val order: RewardOrder,
    val reason: String? = null,
)

/**
 * 激励广告奖励幂等保护器。
 *
 * 它解决的是客户端进程内重复回调问题；线上完整方案仍应由服务端 rewardId 幂等兜底。
 */
class RewardGuard(
    private val clock: AdClock = SystemAdClock,
) {
    private val lock = Any()
    private val orders = linkedMapOf<String, RewardOrder>()

    /**
     * 创建奖励订单。
     *
     * 如果相同 rewardId 已存在，直接返回已有订单，避免覆盖旧状态。
     */
    fun createOrder(order: RewardOrder): RewardOrder {
        synchronized(lock) {
            val existing = orders[order.rewardId]
            if (existing != null) {
                return existing
            }
            orders[order.rewardId] = order
            return order
        }
    }

    /**
     * 标记广告已满足奖励条件。
     *
     * 同一个 rewardId 首次进入会返回 allowed=true，后续重复回调返回 allowed=false。
     */
    fun markRewarded(reward: RewardGrant): RewardDecision {
        synchronized(lock) {
            val existing = orders[reward.rewardId]
            if (existing?.state == RewardOrderState.REWARDED ||
                existing?.state == RewardOrderState.GRANTED
            ) {
                return RewardDecision(
                    allowed = false,
                    order = existing,
                    reason = "Reward has already been handled.",
                )
            }

            val baseOrder = existing ?: RewardOrder(
                rewardId = reward.rewardId,
                userId = reward.userId,
                slotId = reward.slotId,
                rewardType = reward.rewardType,
                amount = reward.amount,
                state = RewardOrderState.PENDING,
                createdAtMs = clock.nowMs(),
                updatedAtMs = clock.nowMs(),
                metadata = reward.metadata,
            )

            val rewardedOrder = baseOrder.copy(
                state = RewardOrderState.REWARDED,
                updatedAtMs = clock.nowMs(),
            )
            orders[reward.rewardId] = rewardedOrder
            return RewardDecision(allowed = true, order = rewardedOrder)
        }
    }

    /**
     * 标记业务发奖已完成。
     *
     * 可在服务端发奖接口成功后调用，形成 PENDING -> REWARDED -> GRANTED 的完整闭环。
     */
    fun markGranted(rewardId: String): Boolean {
        synchronized(lock) {
            val order = orders[rewardId] ?: return false
            if (order.state == RewardOrderState.GRANTED) {
                return false
            }
            orders[rewardId] = order.copy(
                state = RewardOrderState.GRANTED,
                updatedAtMs = clock.nowMs(),
            )
            return true
        }
    }

    /**
     * 返回当前奖励订单快照。
     */
    fun snapshot(): List<RewardOrder> {
        synchronized(lock) {
            return orders.values.toList()
        }
    }

    /**
     * 清空全部奖励订单。
     */
    fun clear() {
        synchronized(lock) {
            orders.clear()
        }
    }
}
