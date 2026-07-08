package com.codex.memoryapm.util

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process

/**
 * 获取当前进程名。
 *
 * 多进程 App 中，内存曲线必须带 processName，否则主进程、WebView/播放器进程、推送进程的数据会混在一起。
 */
internal object ProcessNameProvider {
    fun get(application: Application): String {
        // Android 9+ 提供直接 API，优先使用它，避免遍历进程列表。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }

        // 旧系统通过 ActivityManager 反查当前 pid；失败时退回包名，至少保证字段非空。
        val activityManager = application.getSystemService(ActivityManager::class.java)
        val myPid = Process.myPid()
        return activityManager.runningAppProcesses
            ?.firstOrNull { it.pid == myPid }
            ?.processName
            ?: application.packageName
    }
}
