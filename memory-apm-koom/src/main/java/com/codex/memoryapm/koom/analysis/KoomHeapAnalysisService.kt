package com.codex.memoryapm.koom.analysis

import android.app.IntentService
import android.content.Context
import android.content.Intent
import android.os.ResultReceiver
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.Locale
import kshark.AndroidReferenceMatchers
import kshark.HeapAnalyzer
import kshark.HeapGraph
import kshark.HeapObject.HeapInstance
import kshark.HprofHeapGraph.Companion.openHeapGraph
import kshark.HprofRecordTag
import kshark.Leak
import kshark.LeakTrace
import kshark.OnAnalysisProgressListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * KOOM 风格的端上 hprof 解析服务。
 *
 * 这里故意放在独立进程 :heap_analysis 中运行，原因和 KOOM 一致：
 * - 解析 hprof 需要大量 CPU/内存，不应该抢主进程资源。
 * - 解析完成后这个进程可以退出，释放 Shark 建索引时占用的内存。
 */
@Suppress("DEPRECATION")
class KoomHeapAnalysisService : IntentService("KoomHeapAnalysisService") {
    override fun onHandleIntent(intent: Intent?) {
        val hprofPath = intent?.getStringExtra(EXTRA_HPROF_PATH)
        val reportPath = intent?.getStringExtra(EXTRA_REPORT_PATH)
        val receiver = intent?.getParcelableExtra<ResultReceiver>(EXTRA_RESULT_RECEIVER)
        val start = SystemClock.elapsedRealtime()

        val result = runCatching {
            require(!hprofPath.isNullOrBlank()) { "hprof path is empty" }
            require(!reportPath.isNullOrBlank()) { "report path is empty" }

            val hprofFile = File(hprofPath)
            require(hprofFile.exists() && hprofFile.length() > 0L) {
                "hprof file is missing or empty: $hprofPath"
            }

            val reportFile = File(reportPath).apply {
                parentFile?.mkdirs()
            }

            val output = analyze(hprofFile)
            reportFile.writeText(output.json.toString(2))

            KoomHeapAnalysisResult(
                success = true,
                hprofPath = hprofFile.absolutePath,
                reportPath = reportFile.absolutePath,
                durationMs = SystemClock.elapsedRealtime() - start,
                reason = "analysis success",
                summary = output.summary,
            )
        }.getOrElse { throwable ->
            Log.e(TAG, "hprof analysis failed", throwable)
            KoomHeapAnalysisResult(
                success = false,
                hprofPath = hprofPath,
                reportPath = reportPath,
                durationMs = SystemClock.elapsedRealtime() - start,
                reason = throwable.message ?: throwable.javaClass.name,
                summary = "",
            )
        }

        receiver?.send(if (result.success) RESULT_OK else RESULT_FAIL, result.toBundle())
        Log.i(TAG, "analysis result=$result")
        if (result.success) {
            stopSelf()
        }
    }

    private fun analyze(hprofFile: File): AnalysisOutput {
        val analysisStart = SystemClock.elapsedRealtime()
        val suspectObjects = mutableListOf<SuspectObject>()
        val classCounts = linkedMapOf<String, Int>()
        var gcPathCount = 0
        lateinit var reportJson: JSONObject

        File(hprofFile.absolutePath).openHeapGraph(
            proguardMapping = null,
            indexedGcRootTypes = INDEXED_GC_ROOTS,
        ).use { graph ->
            scanInstances(graph, suspectObjects, classCounts)
            scanPrimitiveArrays(graph, suspectObjects)
            scanObjectArrays(graph, suspectObjects)

            val gcPaths = findGcPaths(graph, suspectObjects.take(MAX_GC_PATH_TARGETS).map { it.objectId }.toSet())
            gcPathCount = gcPaths.length()

            reportJson = JSONObject()
                .put("schema", "memory-apm-koom-analysis-v1")
                .put("hprofPath", hprofFile.absolutePath)
                .put("hprofSizeBytes", hprofFile.length())
                .put("durationMs", SystemClock.elapsedRealtime() - analysisStart)
                .put("objectCount", graph.objectCount)
                .put("classCount", graph.classCount)
                .put("instanceCount", graph.instanceCount)
                .put("objectArrayCount", graph.objectArrayCount)
                .put("primitiveArrayCount", graph.primitiveArrayCount)
                .put("topClasses", topClassesJson(classCounts))
                .put("suspectObjects", suspectObjectsJson(suspectObjects))
                .put("gcPaths", gcPaths)
        }

        val summary = "objects=${reportJson.optInt("objectCount")}, " +
            "classes=${reportJson.optInt("classCount")}, " +
            "suspects=${suspectObjects.size}, gcPaths=$gcPathCount"

        return AnalysisOutput(json = reportJson, summary = summary)
    }

    private fun scanInstances(
        graph: HeapGraph,
        suspectObjects: MutableList<SuspectObject>,
        classCounts: MutableMap<String, Int>,
    ) {
        for (instance in graph.instances) {
            val className = instance.instanceClassName
            classCounts[className] = (classCounts[className] ?: 0) + 1

            if (instance.isInstanceOf("android.app.Activity")) {
                val destroyed = instance.readBoolean("android.app.Activity", "mDestroyed")
                val finished = instance.readBoolean("android.app.Activity", "mFinished")
                if (destroyed == true || finished == true) {
                    suspectObjects += SuspectObject(
                        objectId = instance.objectId,
                        type = "destroyed-activity",
                        className = className,
                        sizeBytes = instance.recordSize,
                        reason = "Activity is destroyed/finished but still reachable",
                        extra = "mDestroyed=$destroyed, mFinished=$finished",
                    )
                }
            }

            if (instance.isInstanceOf("android.graphics.Bitmap")) {
                val width = instance.readInt("android.graphics.Bitmap", "mWidth") ?: 0
                val height = instance.readInt("android.graphics.Bitmap", "mHeight") ?: 0
                val estimatedBytes = width.toLong() * height.toLong() * 4L
                if (estimatedBytes >= BIG_BITMAP_BYTES) {
                    suspectObjects += SuspectObject(
                        objectId = instance.objectId,
                        type = "large-bitmap",
                        className = className,
                        sizeBytes = estimatedBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        reason = "Bitmap estimated size over threshold",
                        extra = "${width}x$height",
                    )
                }
            }
        }
    }

    private fun scanPrimitiveArrays(
        graph: HeapGraph,
        suspectObjects: MutableList<SuspectObject>,
    ) {
        for (array in graph.primitiveArrays) {
            val sizeBytes = array.recordSize
            if (sizeBytes >= BIG_PRIMITIVE_ARRAY_BYTES) {
                suspectObjects += SuspectObject(
                    objectId = array.objectId,
                    type = "large-primitive-array",
                    className = array.arrayClassName,
                    sizeBytes = sizeBytes,
                    reason = "Primitive array record size over threshold",
                    extra = "primitiveType=${array.primitiveType}",
                )
            }
        }
    }

    private fun scanObjectArrays(
        graph: HeapGraph,
        suspectObjects: MutableList<SuspectObject>,
    ) {
        for (array in graph.objectArrays) {
            val sizeBytes = array.recordSize
            if (sizeBytes >= BIG_OBJECT_ARRAY_BYTES) {
                suspectObjects += SuspectObject(
                    objectId = array.objectId,
                    type = "large-object-array",
                    className = array.arrayClassName,
                    sizeBytes = sizeBytes,
                    reason = "Object array record size over threshold",
                    extra = null,
                )
            }
        }
    }

    private fun findGcPaths(
        graph: HeapGraph,
        suspectObjectIds: Set<Long>,
    ): JSONArray {
        if (suspectObjectIds.isEmpty()) {
            return JSONArray()
        }

        val heapAnalyzer = HeapAnalyzer(
            OnAnalysisProgressListener { step ->
                Log.i(TAG, "heap analysis step=${step.name}, targetCount=${suspectObjectIds.size}")
            },
        )

        val input = HeapAnalyzer.FindLeakInput(
            graph = graph,
            referenceMatchers = AndroidReferenceMatchers.appDefaults,
            computeRetainedHeapSize = false,
            objectInspectors = emptyList(),
        )

        val paths = JSONArray()
        val leaksAndUnreachableObjects = with(heapAnalyzer) {
            input.findLeaks(suspectObjectIds)
        }

        leaksAndUnreachableObjects.applicationLeaks.take(MAX_GC_PATHS).forEach { leak ->
            paths.put(leakToJson("application", leak))
        }
        leaksAndUnreachableObjects.libraryLeaks.take(MAX_GC_PATHS - paths.length()).forEach { leak ->
            paths.put(leakToJson("library", leak))
        }
        leaksAndUnreachableObjects.unreachableObjects.take(MAX_GC_PATHS - paths.length()).forEach { unreachable ->
            paths.put(
                JSONObject()
                    .put("kind", "unreachable")
                    .put("className", unreachable.className)
                    .put("objectId", unreachable.objectId.toHexId())
                    .put("reason", "Object is not reachable from indexed GC roots"),
            )
        }
        return paths
    }

    private fun leakToJson(kind: String, leak: Leak): JSONObject {
        val trace = leak.leakTraces.firstOrNull()
        return JSONObject()
            .put("kind", kind)
            .put("signature", leak.signature)
            .put("shortDescription", leak.shortDescription)
            .put("instanceCount", leak.leakTraces.size)
            .put("trace", trace?.toJson() ?: JSONObject())
    }

    private fun LeakTrace.toJson(): JSONObject {
        val path = JSONArray()
        referencePath.forEach { reference ->
            path.put(
                JSONObject()
                    .put("className", reference.originObject.className)
                    .put("reference", reference.referenceDisplayName)
                    .put("referenceType", reference.referenceType.name)
                    .put("declaredClass", reference.owningClassName),
            )
        }
        path.put(
            JSONObject()
                .put("className", leakingObject.className)
                .put("objectId", leakingObject.objectId.toHexId())
                .put("reference", "<leaking object>")
                .put("referenceType", leakingObject.typeName),
        )

        return JSONObject()
            .put("gcRoot", gcRootType.description)
            .put("leakingClass", leakingObject.className)
            .put("leakingObjectId", leakingObject.objectId.toHexId())
            .put("path", path)
    }

    private fun topClassesJson(classCounts: Map<String, Int>): JSONArray {
        val array = JSONArray()
        classCounts.entries
            .sortedByDescending { it.value }
            .take(MAX_TOP_CLASSES)
            .forEach { (className, count) ->
                array.put(
                    JSONObject()
                        .put("className", className)
                        .put("instanceCount", count),
                )
            }
        return array
    }

    private fun suspectObjectsJson(suspectObjects: List<SuspectObject>): JSONArray {
        val array = JSONArray()
        suspectObjects
            .sortedByDescending { it.sizeBytes }
            .take(MAX_SUSPECTS_IN_REPORT)
            .forEach { suspect ->
                array.put(
                    JSONObject()
                        .put("objectId", suspect.objectId.toHexId())
                        .put("type", suspect.type)
                        .put("className", suspect.className)
                        .put("sizeBytes", suspect.sizeBytes)
                        .put("sizeMb", String.format(Locale.US, "%.2f", suspect.sizeBytes / 1024f / 1024f))
                        .put("reason", suspect.reason)
                        .put("extra", suspect.extra.orEmpty()),
                )
            }
        return array
    }

    private fun HeapInstance.isInstanceOf(className: String): Boolean {
        return runCatching { this instanceOf className }.getOrDefault(false)
    }

    private fun HeapInstance.readBoolean(declaringClassName: String, fieldName: String): Boolean? {
        return runCatching { this[declaringClassName, fieldName]?.value?.asBoolean }.getOrNull()
    }

    private fun HeapInstance.readInt(declaringClassName: String, fieldName: String): Int? {
        return runCatching { this[declaringClassName, fieldName]?.value?.asInt }.getOrNull()
    }

    private fun Long.toHexId(): String = "0x${java.lang.Long.toHexString(this)}"

    private data class SuspectObject(
        val objectId: Long,
        val type: String,
        val className: String,
        val sizeBytes: Int,
        val reason: String,
        val extra: String?,
    )

    private data class AnalysisOutput(
        val json: JSONObject,
        val summary: String,
    )

    companion object {
        private const val TAG = "MemoryApm-Analyzer"
        private const val EXTRA_HPROF_PATH = "hprof_path"
        private const val EXTRA_REPORT_PATH = "report_path"
        private const val EXTRA_RESULT_RECEIVER = "result_receiver"
        private const val RESULT_OK = 1
        private const val RESULT_FAIL = 2

        private const val BIG_BITMAP_BYTES = 8L * 1024L * 1024L
        private const val BIG_PRIMITIVE_ARRAY_BYTES = 4 * 1024 * 1024
        private const val BIG_OBJECT_ARRAY_BYTES = 2 * 1024 * 1024
        private const val MAX_TOP_CLASSES = 20
        private const val MAX_SUSPECTS_IN_REPORT = 50
        private const val MAX_GC_PATH_TARGETS = 8
        private const val MAX_GC_PATHS = 8

        private val INDEXED_GC_ROOTS = setOf(
            HprofRecordTag.ROOT_JNI_GLOBAL,
            HprofRecordTag.ROOT_JNI_LOCAL,
            HprofRecordTag.ROOT_NATIVE_STACK,
            HprofRecordTag.ROOT_STICKY_CLASS,
            HprofRecordTag.ROOT_THREAD_BLOCK,
            HprofRecordTag.ROOT_THREAD_OBJECT,
        )

        fun start(
            context: Context,
            hprofPath: String,
            reportPath: String,
            receiver: ResultReceiver,
        ) {
            val intent = Intent(context, KoomHeapAnalysisService::class.java)
                .putExtra(EXTRA_HPROF_PATH, hprofPath)
                .putExtra(EXTRA_REPORT_PATH, reportPath)
                .putExtra(EXTRA_RESULT_RECEIVER, receiver)
            context.startService(intent)
        }
    }
}
