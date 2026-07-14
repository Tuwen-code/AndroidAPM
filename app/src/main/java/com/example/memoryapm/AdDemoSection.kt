package com.example.memoryapm

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.codex.adapm.AdApm
import com.codex.adapm.AdError
import com.codex.adapm.AdEvent
import com.codex.adapm.AdEventListener
import com.codex.adapm.AdFormat
import com.codex.adapm.AdFrequencyRule
import com.codex.adapm.AdLoadCallback
import com.codex.adapm.AdNetworkConfig
import com.codex.adapm.AdPlacement
import com.codex.adapm.AdRequest
import com.codex.adapm.AdShowCallback
import com.codex.adapm.AdShowResult
import com.codex.adapm.AdSlot
import com.codex.adapm.LoadedAd
import com.codex.adapm.RewardGrant
import com.codex.adapm.mock.MockAdNetworkAdapter

@Composable
fun AdDemoSection(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    val activity = remember(context) { context.findActivity() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val logs = remember { mutableStateListOf<String>() }
    var initialized by remember { mutableStateOf(false) }
    var preloadStatus by remember { mutableStateOf("尚未预加载广告") }
    var showStatus by remember { mutableStateOf("尚未展示广告") }
    var currentRewardId by remember { mutableStateOf("尚未创建") }
    var rewardCount by remember { mutableIntStateOf(0) }

    val rewardSlot = remember {
        AdSlot(
            slotId = "demo_reward_video",
            format = AdFormat.REWARDED_VIDEO,
            placements = listOf(AdPlacement(MockAdNetworkAdapter.NETWORK, "mock_reward_video")),
        )
    }
    val interstitialSlot = remember {
        AdSlot(
            slotId = "demo_interstitial",
            format = AdFormat.INTERSTITIAL,
            placements = listOf(AdPlacement(MockAdNetworkAdapter.NETWORK, "mock_interstitial")),
            frequencyRule = AdFrequencyRule(minIntervalMs = 30_000L, maxShowsPerSession = 2),
        )
    }
    val splashSlot = remember {
        AdSlot(
            slotId = "demo_splash",
            format = AdFormat.SPLASH,
            placements = listOf(AdPlacement(MockAdNetworkAdapter.NETWORK, "mock_splash")),
            frequencyRule = AdFrequencyRule(minIntervalMs = 10_000L),
        )
    }
    val bannerSlot = remember {
        AdSlot(
            slotId = "demo_banner",
            format = AdFormat.BANNER,
            placements = listOf(AdPlacement(MockAdNetworkAdapter.NETWORK, "mock_banner")),
        )
    }
    val nativeSlot = remember {
        AdSlot(
            slotId = "demo_native_feed",
            format = AdFormat.NATIVE_FEED,
            placements = listOf(AdPlacement(MockAdNetworkAdapter.NETWORK, "mock_native_feed")),
        )
    }

    fun appendLog(message: String) {
        logs.add(0, message)
        while (logs.size > 8) {
            logs.removeAt(logs.lastIndex)
        }
    }

    DisposableEffect(context, isPreview) {
        if (isPreview) {
            initialized = true
            appendLog("预览模式：跳过 Mock AD 初始化")
            onDispose {}
        } else {
            val adapter = MockAdNetworkAdapter(
                rewardCallbackCount = 2,
            )
            AdApm.init(
                context = context.applicationContext,
                adapters = listOf(adapter),
                networkConfigs = listOf(
                    AdNetworkConfig(
                        network = MockAdNetworkAdapter.NETWORK,
                        appId = "mock-demo-app",
                    )
                ),
                eventListener = object : AdEventListener {
                    override fun onAdEvent(event: AdEvent) {
                        mainHandler.post {
                            appendLog(event.toDemoText())
                        }
                    }
                },
            )
            initialized = true
            appendLog("Mock AD 已初始化，激励广告会模拟 2 次奖励回调")
            onDispose {
                AdApm.shutdown()
            }
        }
    }

    fun nextRewardId(): String = "reward_${System.currentTimeMillis()}"

    fun preloadRewardAd() {
        val rewardId = nextRewardId()
        currentRewardId = rewardId
        rewardCount = 0
        AdApm.createRewardOrder(
            rewardId = rewardId,
            userId = DEMO_USER_ID,
            slotId = rewardSlot.slotId,
            rewardType = "coin",
            amount = 1,
        )
        preloadStatus = "激励视频预加载中..."
        AdApm.preload(
            activity = activity,
            slot = rewardSlot,
            request = AdRequest(
                slotId = rewardSlot.slotId,
                userId = DEMO_USER_ID,
                rewardId = rewardId,
            ),
            callback = object : AdLoadCallback {
                override fun onAdLoaded(ad: LoadedAd) {
                    preloadStatus = "激励视频预加载成功：${ad.network}/${ad.placementId}"
                    appendLog("预加载成功：${ad.slotId}，rewardId=$rewardId")
                }

                override fun onAdLoadFailed(error: AdError) {
                    preloadStatus = "激励视频预加载失败：${error.message}"
                    appendLog("预加载失败：${error.code} ${error.message}")
                }
            },
        )
    }

    fun showRewardAd() {
        val rewardId = currentRewardId.takeIf { it.startsWith("reward_") } ?: nextRewardId()
        currentRewardId = rewardId
        AdApm.createRewardOrder(
            rewardId = rewardId,
            userId = DEMO_USER_ID,
            slotId = rewardSlot.slotId,
            rewardType = "coin",
            amount = 1,
        )
        showStatus = "激励视频展示中..."
        AdApm.show(
            activity = activity,
            slot = rewardSlot,
            request = AdRequest(
                slotId = rewardSlot.slotId,
                userId = DEMO_USER_ID,
                rewardId = rewardId,
            ),
            callback = object : AdShowCallback {
                override fun onRewarded(reward: RewardGrant) {
                    rewardCount += 1
                    appendLog("发放奖励：${reward.rewardId}，次数=$rewardCount")
                }

                override fun onAdClosed(result: AdShowResult) {
                    showStatus = "激励视频已关闭，completed=${result.completed}"
                }

                override fun onAdShowFailed(error: AdError) {
                    showStatus = "激励视频展示失败：${error.message}"
                    appendLog("展示失败：${error.code} ${error.message}")
                }
            },
        )
    }

    fun preloadSlot(slot: AdSlot, label: String) {
        preloadStatus = "$label 预加载中..."
        AdApm.preload(
            activity = activity,
            slot = slot,
            request = AdRequest(slotId = slot.slotId, userId = DEMO_USER_ID),
            callback = object : AdLoadCallback {
                override fun onAdLoaded(ad: LoadedAd) {
                    preloadStatus = "$label 预加载成功：${ad.network}/${ad.placementId}"
                }

                override fun onAdLoadFailed(error: AdError) {
                    preloadStatus = "$label 预加载失败：${error.message}"
                }
            },
        )
    }

    fun showSlot(slot: AdSlot, label: String) {
        showStatus = "$label 展示中..."
        AdApm.show(
            activity = activity,
            slot = slot,
            request = AdRequest(slotId = slot.slotId, userId = DEMO_USER_ID),
            callback = object : AdShowCallback {
                override fun onAdShown(event: AdEvent) {
                    showStatus = "$label 已展示"
                }

                override fun onAdClosed(result: AdShowResult) {
                    appendLog("$label 已关闭，completed=${result.completed}")
                }

                override fun onAdShowFailed(error: AdError) {
                    showStatus = "$label 展示失败：${error.message}"
                    appendLog("$label 展示失败：${error.code} ${error.message}")
                }
            },
        )
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = "广告 AD Demo", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "使用 Mock 平台验证广告预加载、展示、插屏频控、激励视频 rewardId 幂等。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AdDemoStatusRow("初始化", if (initialized) "Mock 平台已就绪" else "初始化中")
            AdDemoStatusRow("奖励订单", currentRewardId)
            AdDemoStatusRow("奖励次数", "$rewardCount 次")
            AdDemoStatusRow("加载状态", preloadStatus)
            AdDemoStatusRow("展示状态", showStatus)

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(onClick = ::preloadRewardAd, modifier = Modifier.weight(1f)) {
                    Text("预加载激励")
                }
                Button(onClick = ::showRewardAd, modifier = Modifier.weight(1f)) {
                    Text("展示激励")
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = { showSlot(interstitialSlot, "插屏") },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("展示插屏")
                }
                OutlinedButton(
                    onClick = { showSlot(splashSlot, "开屏") },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("展示开屏")
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = { preloadSlot(bannerSlot, "Banner") },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("预加载 Banner")
                }
                OutlinedButton(
                    onClick = { preloadSlot(nativeSlot, "信息流") },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("预加载信息流")
                }
            }

            Text(text = "事件日志", style = MaterialTheme.typography.titleSmall)
            if (logs.isEmpty()) {
                Text(
                    text = "暂无广告事件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                logs.forEach { item ->
                    Text(
                        text = item,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AdDemoStatusRow(
    label: String,
    value: String,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private fun AdEvent.toDemoText(): String {
    return "${type.name} slot=$slotId network=${network.orEmpty()} placement=${placementId.orEmpty()}"
}

private const val DEMO_USER_ID = "demo_user"
