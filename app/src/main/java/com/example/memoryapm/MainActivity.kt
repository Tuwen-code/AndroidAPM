package com.example.memoryapm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.codex.memoryapm.MemoryApm
import com.codex.memoryapm.koom.KoomDumpResult
import com.codex.memoryapm.koom.KoomForkDumpManager
import com.codex.memoryapm.koom.analysis.KoomHeapAnalysisManager
import com.codex.memoryapm.model.MemoryEventType
import com.codex.memoryapm.model.MemorySnapshot
import com.example.memoryapm.ui.theme.MemoryAPMTheme
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Demo 用强引用持有 ByteArray，模拟业务页面中的图片缓存/大对象暂存。
    private val allocations = mutableListOf<ByteArray>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MemoryAPMTheme {
                var snapshot by remember { mutableStateOf(MemoryApm.lastSnapshots().lastOrNull()) }
                var allocatedMb by remember { mutableIntStateOf(0) }
                var dumpStatus by remember { mutableStateOf("尚未触发 fork hprof dump") }
                var analysisStatus by remember { mutableStateOf("尚未解析 hprof") }
                var isDumping by remember { mutableStateOf(false) }
                var isAnalyzing by remember { mutableStateOf(false) }
                var isBursting by remember { mutableStateOf(false) }
                val coroutineScope = rememberCoroutineScope()

                // Demo 页面每秒刷新一次本地 ring buffer 的最后一条快照，方便运行时直接观察数值变化。
                LaunchedEffect(Unit) {
                    while (true) {
                        snapshot = MemoryApm.lastSnapshots().lastOrNull()
                        delay(1_000L)
                    }
                }

                DisposableEffect(Unit) {
                    val removeListener = KoomDumpEventCenter.addListener { result ->
                        dumpStatus = "自动触发：${result.toDisplayText()}"
                    }
                    onDispose {
                        removeListener()
                    }
                }

                Scaffold { innerPadding ->
                    MemoryDashboard(
                        snapshot = snapshot,
                        allocatedMb = allocatedMb,
                        onCapture = {
                            MemoryApm.capture(
                                eventType = MemoryEventType.MANUAL,
                                note = "manual capture from demo",
                            )
                            snapshot = MemoryApm.lastSnapshots().lastOrNull()
                        },
                        onAllocate = {
                            // 每次分配 8MB，让 Java heap/PSS 变化更明显，方便验证 SDK 采样。
                            allocations += ByteArray(8 * 1024 * 1024)
                            allocatedMb += 8
                            MemoryApm.capture(
                                eventType = MemoryEventType.MANUAL,
                                note = "allocated ${allocatedMb}MB in demo",
                            )
                        },
                        onBurstAllocate = {
                            if (!isBursting) {
                                isBursting = true
                                dumpStatus = "已记录暴增前基线，马上分配 +64MB 并等待自动 dump..."

                                // 先采一次暴增前基线，再延迟分配，保证自动触发器比较的是“分配前 -> 分配后”的 delta。
                                MemoryApm.capture(
                                    eventType = MemoryEventType.MANUAL,
                                    note = "baseline before 64MB burst",
                                )

                                coroutineScope.launch {
                                    delay(500L)
                                    repeat(8) {
                                        allocations += ByteArray(8 * 1024 * 1024)
                                    }
                                    allocatedMb += 64
                                    dumpStatus = "已分配 +64MB，等待 MemoryApm-KOOM 自动触发 fork dump..."
                                    MemoryApm.capture(
                                        eventType = MemoryEventType.MANUAL,
                                        note = "burst allocated ${allocatedMb}MB in demo",
                                    )
                                    delay(1_000L)
                                    snapshot = MemoryApm.lastSnapshots().lastOrNull()
                                    isBursting = false
                                }
                            }
                        },
                        onClear = {
                            // 清空强引用后主动 GC，便于 Demo 中观察内存回落。线上业务代码不应依赖手动 GC。
                            allocations.clear()
                            allocatedMb = 0
                            Runtime.getRuntime().gc()
                            MemoryApm.capture(
                                eventType = MemoryEventType.MANUAL,
                                note = "cleared demo allocations",
                            )
                        },
                        onForkDump = {
                            isDumping = true
                            dumpStatus = "正在调用 KOOM fork dump，请观察 Logcat 中的 OOMMonitor_ForkJvmHeapDumper..."
                            KoomForkDumpManager.dumpAsync(reason = "manual-demo") { result ->
                                isDumping = false
                                dumpStatus = result.toDisplayText()
                            }
                        },
                        onAnalyzeHprof = {
                            isAnalyzing = true
                            analysisStatus = "正在启动 :heap_analysis 进程解析最新 hprof，请观察 Logcat 中的 MemoryApm-Analyzer..."
                            KoomHeapAnalysisManager.analyzeLatestHprof(applicationContext) { result ->
                                isAnalyzing = false
                                analysisStatus = result.toDisplayText()
                            }
                        },
                        dumpStatus = dumpStatus,
                        analysisStatus = analysisStatus,
                        isDumping = isDumping,
                        isAnalyzing = isAnalyzing,
                        isBursting = isBursting,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryDashboard(
    snapshot: MemorySnapshot?,
    allocatedMb: Int,
    onCapture: () -> Unit,
    onAllocate: () -> Unit,
    onBurstAllocate: () -> Unit,
    onClear: () -> Unit,
    onForkDump: () -> Unit,
    onAnalyzeHprof: () -> Unit,
    dumpStatus: String,
    analysisStatus: String,
    isDumping: Boolean,
    isAnalyzing: Boolean,
    isBursting: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Memory APM Demo",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "SDK 状态：${if (MemoryApm.isRunning()) "运行中" else "未启动"}",
                style = MaterialTheme.typography.bodyMedium,
            )

            AdDemoSection()

            MetricCard(title = "页面说明") {
                DescriptionText("这个页面用于演示线上内存监控 SDK 的基础能力：启动采样、页面生命周期采样、周期采样、系统低内存回调和本地最近快照展示。")
                DescriptionText("点击“采样”会手动记录一次内存快照；点击“+8 MB”会持有一块 8MB 的 ByteArray，用来模拟业务页面中的大对象增长；点击“清理”会释放 Demo 持有的强引用并触发一次 GC。")
                DescriptionText("点击“暴增 +64 MB”会先记录暴增前基线，再分配 64MB 并采样，用于触发 KOOM 风格的 FastHugeMemory 自动 fork dump。")
                DescriptionText("运行后可以在 Logcat 中搜索 MemoryApm，查看 SDK 上报的 event、page、Java heap、PSS、native heap 和 warning。")
                DescriptionText("如果自动 dump 没生成文件，先搜索 MemoryApm-KOOM，它会打印 heap delta、阈值、触发或跳过原因。")
                DescriptionText("点击“Fork Dump”会通过 KOOM 的 ForkJvmHeapDumper 生成 hprof 文件，重点观察 Logcat 中 OOMMonitor_ForkJvmHeapDumper 的 suspend、fork、child dump、notify 日志。")
                DescriptionText("点击“解析最新 HPROF”会启动独立 :heap_analysis 进程，用 KOOM fork 版 Shark 在端上解析 hprof，并输出 JSON 报告。")
            }

            MetricCard(title = "指标说明") {
                DescriptionText("Java heap：Java/Kotlin 对象所在的托管堆，越接近 max 越容易触发 Java OOM。")
                DescriptionText("Total PSS：系统视角下进程实际内存压力，更适合做线上版本、机型和页面维度的 P95/P99 聚合。")
                DescriptionText("Native heap：native malloc 区域的分配量，常见来源包括 JNI、图片/音视频库、WebView 等 native 侧内存。")
                DescriptionText("GC：ART 垃圾回收次数和耗时，GC 明显增多通常意味着对象分配过多或内存抖动。")
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(onClick = onCapture, modifier = Modifier.weight(1f)) {
                    Text("采样")
                }
                Button(onClick = onAllocate, modifier = Modifier.weight(1f)) {
                    Text("+8 MB")
                }
                OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                    Text("清理")
                }
            }

            Button(
                onClick = onBurstAllocate,
                enabled = !isBursting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isBursting) "暴增采样中..." else "暴增 +64 MB（自动触发）")
            }

            Button(
                onClick = onForkDump,
                enabled = !isDumping,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isDumping) "Fork Dump 中..." else "Fork Dump HPROF")
            }

            Button(
                onClick = onAnalyzeHprof,
                enabled = !isAnalyzing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isAnalyzing) "解析 HPROF 中..." else "解析最新 HPROF")
            }

            MetricCard(title = "KOOM Fork Dump") {
                DescriptionText(dumpStatus)
            }

            MetricCard(title = "HPROF 本地解析") {
                DescriptionText(analysisStatus)
            }

            MetricCard(title = "Demo 分配") {
                MetricRow(label = "当前持有", value = "$allocatedMb MB")
            }

            MetricCard(title = "最新快照") {
                if (snapshot == null) {
                    Text("等待首次采样")
                } else {
                    MetricRow("事件", snapshot.eventType.name)
                    MetricRow("页面", snapshot.pageName.orEmpty())
                    MetricRow("状态", snapshot.appState.name)
                    MetricRow(
                        "Java heap",
                        "${snapshot.javaHeap.usedBytes.formatBytes()} / ${snapshot.javaHeap.maxBytes.formatBytes()}",
                    )
                    MetricRow(
                        "Java ratio",
                        String.format(Locale.US, "%.1f%%", snapshot.javaHeap.usageRatio * 100f),
                    )
                    MetricRow("Total PSS", snapshot.pss.totalPssKb.formatKb())
                    MetricRow("Native heap", snapshot.nativeHeap.allocatedBytes.formatBytes())
                    snapshot.gc?.let {
                        MetricRow("GC", "count=${it.gcCount}, time=${it.gcTimeMs}ms")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DescriptionText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MetricCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun Long.formatBytes(): String {
    return String.format(Locale.US, "%.1f MB", this / 1024f / 1024f)
}

private fun Int.formatKb(): String {
    return String.format(Locale.US, "%.1f MB", this / 1024f)
}

private fun KoomDumpResult.toDisplayText(): String {
    return if (success) {
        "成功：${fileSizeBytes.formatBytes()}，耗时 ${durationMs}ms，文件：$path"
    } else {
        "失败：$reason，耗时 ${durationMs}ms，文件：${path.orEmpty()}"
    }
}

@Preview(showBackground = true)
@Composable
private fun MemoryDashboardPreview() {
    MemoryAPMTheme {
        MemoryDashboard(
            snapshot = null,
            allocatedMb = 0,
            onCapture = {},
            onAllocate = {},
            onBurstAllocate = {},
            onClear = {},
            onForkDump = {},
            onAnalyzeHprof = {},
            dumpStatus = "尚未触发 fork hprof dump",
            analysisStatus = "尚未解析 hprof",
            isDumping = false,
            isAnalyzing = false,
            isBursting = false,
        )
    }
}
