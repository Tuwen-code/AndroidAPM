package com.codex.memoryapm.koom

/**
 * KOOM fork hprof dump 的运行策略。
 *
 * 这是重型诊断能力，线上必须通过远程开关、采样率和限频控制。
 * Demo 为了学习流程可以放宽限制，但真实业务建议保持默认的保守策略。
 */
data class KoomDumpConfig(
    val enabled: Boolean = true,
    val allowForegroundDump: Boolean = false,
    val minDumpIntervalMs: Long = 30 * 60 * 1000L,
    val maxDumpCountPerProcess: Int = 1,
    val minFreeSpaceBytes: Long = 200L * 1024L * 1024L,
    /**
     * true 时优先写入外部 App 专属目录：
     * /sdcard/Android/data/<package>/files/<dumpDirectoryName>
     *
     * 这个目录比 /data/user/0/<package>/files 更容易通过 adb pull 或 Android Studio Device Explorer 找到，
     * 且不需要 READ/WRITE_EXTERNAL_STORAGE 权限。外部目录不可用时会自动回退到内部 filesDir。
     */
    val useExternalFilesDir: Boolean = true,
    val dumpDirectoryName: String = "memory-apm/koom-hprof",
    val fileNamePrefix: String = "koom-fork-dump",
)
