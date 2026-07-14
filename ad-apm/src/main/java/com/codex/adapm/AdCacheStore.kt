package com.codex.adapm

/**
 * 广告缓存容器。
 *
 * 按 slotId + format 维度缓存已加载广告，展示时先进先出消费，并自动丢弃过期广告。
 */
class AdCacheStore(
    private val clock: AdClock = SystemAdClock,
) {
    private val lock = Any()
    private val adsBySlot = LinkedHashMap<String, ArrayDeque<LoadedAd>>()

    /**
     * 写入一条已加载广告。
     */
    fun put(ad: LoadedAd) {
        synchronized(lock) {
            val queue = adsBySlot.getOrPut(key(ad.slotId, ad.format)) { ArrayDeque() }
            queue.addLast(ad)
        }
    }

    /**
     * 取出一条可展示广告。
     *
     * 已过期广告会被丢弃，不会返回给业务展示。
     */
    fun take(slotId: String, format: AdFormat): LoadedAd? {
        synchronized(lock) {
            val key = key(slotId, format)
            val queue = adsBySlot[key] ?: return null
            while (queue.isNotEmpty()) {
                val ad = queue.removeFirst()
                if (!ad.isExpired(clock.nowMs())) {
                    return ad
                }
            }
            adsBySlot.remove(key)
            return null
        }
    }

    /**
     * 返回缓存广告数量。
     */
    fun size(slotId: String? = null): Int {
        synchronized(lock) {
            if (slotId == null) {
                return adsBySlot.values.sumOf { it.size }
            }
            return adsBySlot
                .filterKeys { it.startsWith("$slotId:") }
                .values
                .sumOf { it.size }
        }
    }

    /**
     * 清理指定广告位或全部广告缓存。
     */
    fun clear(slotId: String? = null) {
        synchronized(lock) {
            if (slotId == null) {
                adsBySlot.clear()
            } else {
                adsBySlot.keys
                    .filter { it.startsWith("$slotId:") }
                    .toList()
                    .forEach { adsBySlot.remove(it) }
            }
        }
    }

    private fun key(slotId: String, format: AdFormat): String = "$slotId:${format.name}"
}
