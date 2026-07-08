package com.codex.memoryapm.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.codex.memoryapm.model.AppState
import com.codex.memoryapm.model.MemoryEventType

/**
 * Activity 生命周期适配器。
 *
 * 这里不直接采集内存，只把 Android 回调转换成 SDK 内部事件。
 * 这样页面追踪逻辑可以独立测试，也方便以后增加 Fragment/Compose Navigation 追踪。
 */
internal class ActivityTracker(
    private val listener: Listener,
) : Application.ActivityLifecycleCallbacks {
    // startedCount > 0 说明至少有一个 Activity 可见，用它近似判断 App 前后台。
    private var startedCount = 0

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        listener.onActivityEvent(activity, MemoryEventType.ACTIVITY_CREATED, activity.pageName())
    }

    override fun onActivityStarted(activity: Activity) {
        startedCount += 1
        // 从 0 到 1 是进入前台；多 Activity 切换时不会重复发送前台事件。
        if (startedCount == 1) {
            listener.onAppStateChanged(AppState.FOREGROUND)
        }
        listener.onActivityEvent(activity, MemoryEventType.ACTIVITY_STARTED, activity.pageName())
    }

    override fun onActivityResumed(activity: Activity) {
        listener.onActivityEvent(activity, MemoryEventType.ACTIVITY_RESUMED, activity.pageName())
    }

    override fun onActivityPaused(activity: Activity) {
        listener.onActivityEvent(activity, MemoryEventType.ACTIVITY_PAUSED, activity.pageName())
    }

    override fun onActivityStopped(activity: Activity) {
        listener.onActivityEvent(activity, MemoryEventType.ACTIVITY_STOPPED, activity.pageName())
        startedCount = (startedCount - 1).coerceAtLeast(0)
        // 所有 Activity 都 stopped 后认为 App 进入后台，此时后台 PSS 对系统回收风险更敏感。
        if (startedCount == 0) {
            listener.onAppStateChanged(AppState.BACKGROUND)
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        listener.onActivityEvent(activity, MemoryEventType.ACTIVITY_DESTROYED, activity.pageName())
    }

    private fun Activity.pageName(): String {
        // localClassName 更适合 demo 和日志阅读；取不到时退回完整类名保证可定位。
        return localClassName.ifBlank { javaClass.name }
    }

    /**
     * Runtime 通过 Listener 接收生命周期事件，避免 ActivityTracker 依赖采集器或上报实现。
     */
    interface Listener {
        fun onAppStateChanged(newState: AppState)

        fun onActivityEvent(
            activity: Activity,
            eventType: MemoryEventType,
            pageName: String,
        )
    }
}
