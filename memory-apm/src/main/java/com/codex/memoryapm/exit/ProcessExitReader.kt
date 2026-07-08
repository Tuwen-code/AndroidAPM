package com.codex.memoryapm.exit

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import com.codex.memoryapm.model.ProcessExitRecord

/**
 * 读取上一次进程退出原因。
 *
 * Android 11(API 30)+ 提供 ApplicationExitInfo，可以在下次启动时知道上次是低内存、crash、
 * native crash、ANR 还是用户/系统主动结束。这个信息对 OOM 归因非常关键。
 */
internal object ProcessExitReader {
    fun readLastExit(application: Application): ProcessExitRecord? {
        // 低版本没有官方历史退出原因 API，返回 null，由服务端按普通启动处理。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }

        // pid 传 0 表示读取当前包名下最近的进程退出记录；maxNum=1 只取最新一条，减少启动期开销。
        val activityManager = application.getSystemService(ActivityManager::class.java)
        val exitInfo = activityManager
            .getHistoricalProcessExitReasons(application.packageName, 0, 1)
            .firstOrNull()
            ?: return null

        return ProcessExitRecord(
            timestampMs = exitInfo.timestamp,
            reason = exitInfo.reason,
            reasonName = exitInfo.reasonName(),
            importance = exitInfo.importance,
            status = exitInfo.status,
            pssKb = exitInfo.pss,
            rssKb = exitInfo.rss,
            description = exitInfo.description,
        )
    }

    /**
     * 把系统 reason int 转成稳定可读的字符串，方便 Logcat 和服务端看板直接展示。
     */
    private fun ApplicationExitInfo.reasonName(): String {
        return when (reason) {
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            else -> "REASON_$reason"
        }
    }
}
