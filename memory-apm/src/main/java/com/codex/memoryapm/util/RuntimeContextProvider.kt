package com.codex.memoryapm.util

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import com.codex.memoryapm.model.AppInfo
import com.codex.memoryapm.model.DeviceInfo
import com.codex.memoryapm.model.RuntimeContext

/**
 * 生成每条上报都需要携带的稳定上下文。
 *
 * 这些信息变化频率很低，所以在 SDK 启动时读取一次即可。
 */
internal object RuntimeContextProvider {
    fun create(application: Application): RuntimeContext {
        return RuntimeContext(
            app = application.appInfo(),
            device = DeviceInfo(
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                sdkInt = Build.VERSION.SDK_INT,
                release = Build.VERSION.RELEASE.orEmpty(),
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
            ),
        )
    }

    private fun Application.appInfo(): AppInfo {
        // getPackageInfo 在 Android 13 起改为 PackageInfoFlags，这里做版本兼容。
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }

        // versionCode 在 Android 9 起升级为 longVersionCode，避免大版本号溢出。
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        return AppInfo(
            packageName = packageName,
            versionName = packageInfo.versionName.orEmpty(),
            versionCode = versionCode,
        )
    }
}
