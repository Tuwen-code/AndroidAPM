# KOOM Fork Dump HPROF 学习流程

本项目采用两条线学习 KOOM：

1. `memory-apm-koom` 通过 Maven 依赖接入 `koom-fast-dump-static:2.2.2`，用于在 Demo 中跑通 fork hprof dump。
2. `koom-shark` 已内置 KOOM 使用的 Shark 源码，用于端上 HPROF 解析；`third_party/KOOM` 只用于阅读、搜索和对照调用链，不参与当前 Gradle 编译。

## 本项目入口

- Demo 初始化：`app/src/main/java/com/example/memoryapm/MemoryApmDemoApplication.kt`
- Demo 触发按钮：`app/src/main/java/com/example/memoryapm/MainActivity.kt`
- 我们的 KOOM 适配层：`memory-apm-koom/src/main/java/com/codex/memoryapm/koom/KoomForkDumpManager.kt`
- KOOM 风格自动触发器：`memory-apm-koom/src/main/java/com/codex/memoryapm/koom/KoomAutoDumpUploader.kt`
- 端上 HPROF 解析依赖的 Shark 源码：`koom-shark/src/main/java/kshark`

Demo 点击 `Fork Dump HPROF` 后会执行：

```text
MainActivity
  -> KoomForkDumpManager.dumpAsync("manual-demo")
  -> ForkJvmHeapDumper.getInstance().dump(path)
  -> KOOM native fork dump
  -> 生成 files/memory-apm/koom-hprof/*.hprof
```

## 自动触发模式

本项目现在还接入了 `KoomAutoDumpUploader`，它会在每次 `MemoryApm` 采样上报时检查 Java heap 状态。

触发逻辑参考 KOOM 源码中的两个 tracker：

```text
HeapOOMTracker:
  Java heap 使用率持续超过阈值，连续 maxOverThresholdCount 次后 dump

FastHugeMemoryOOMTracker:
  Java heap 使用率超过极高水位，立即 dump
  或 Java heap 短时间增长超过 delta 阈值，立即 dump
```

当前 Demo 为了方便验证，把快速增长阈值设置成了 `48MB`：

```text
forceDumpJavaHeapDeltaBytes = 48MB
```

因此点击页面上的 `暴增 +64 MB（自动触发）`，会一次性增加 64MB Java heap，然后通过自动触发器调用：

```text
KoomAutoDumpUploader
  -> KoomForkDumpManager.dumpAsync()
  -> ForkJvmHeapDumper.getInstance().dump(path)
```

真实线上建议恢复 KOOM 默认量级：

```text
forceDumpJavaHeapDeltaThreshold = 350_000KB
maxOverThresholdCount = 3
loopInterval = 15_000ms 左右
每进程/每天最多 dump 1 次
```

## KOOM 源码阅读入口

优先看这些文件：

```text
third_party/KOOM/koom-fast-dump/README.zh-CN.md
third_party/KOOM/koom-fast-dump/src/main/java/com/kwai/koom/fastdump/ForkJvmHeapDumper.java
third_party/KOOM/koom-fast-dump/src/main/java/com/kwai/koom/fastdump/HeapDumper.java
third_party/KOOM/koom-fast-dump/src/main/cpp/native_bridge.cpp
third_party/KOOM/koom-fast-dump/src/main/cpp/hprof_dump.cpp
third_party/KOOM/koom-fast-dump/src/main/cpp/hprof_dump_impl.cpp
third_party/KOOM/koom-fast-dump/src/main/cpp/hprof_dump_below_r_impl.cpp
third_party/KOOM/koom-fast-dump/src/main/cpp/hprof_dump_v_impl.cpp
```

如果想看完整 Java 泄漏监控链路，再看：

```text
third_party/KOOM/koom-java-leak/src/main/java/com/kwai/koom/javaoom/monitor/OOMMonitor.kt
third_party/KOOM/koom-java-leak/src/main/java/com/kwai/koom/javaoom/hprof/ForkStripHeapDumper.java
third_party/KOOM/koom-java-leak/src/main/java/com/kwai/koom/javaoom/monitor/analysis/HeapAnalysisService.kt
```

## 核心流程

KOOM fast dump 的关键思想：

```text
主进程请求 dump
  -> suspend ART VM
  -> fork 子进程
  -> 主进程 resume ART VM
  -> 子进程执行 hprof dump
  -> 子进程通知父进程结果
  -> 子进程退出
```

这和直接 `Debug.dumpHprofData(path)` 的区别是：

```text
Debug.dumpHprofData:
  主进程自己 dump，App 更容易长时间 freeze

KOOM fork dump:
  主进程只承担 suspend/fork/resume 的短暂停顿，重的 dump 写文件工作放到子进程
```

注意：fork dump 仍然是重型操作，不适合线上常态触发。真实接入必须有远程开关、采样、后台限制、磁盘空间检查和限频。

## 建议断点

Java 层：

```text
KoomForkDumpManager.dumpAsync()
ForkJvmHeapDumper.dump()
ForkJvmHeapDumper.init()
```

Native 层：

```text
Java_com_kwai_koom_fastdump_ForkJvmHeapDumper_nativeInit
Java_com_kwai_koom_fastdump_ForkJvmHeapDumper_forkDump
```

如果当前项目用的是 Maven AAR，Android Studio 不一定能直接断进 KOOM native 源码。学习源码时可以先用日志和源码对照；等流程熟悉后，再单独打开 `third_party/KOOM` 工程跑官方 Demo。

## Logcat 关键字

点击 Demo 的 `Fork Dump HPROF` 后，重点过滤：

```text
MemoryApm
OOMMonitor_ForkJvmHeapDumper
hprof
JNIBridge
```

典型日志顺序：

```text
OOMMonitor_ForkJvmHeapDumper: dump xxx.hprof
OOMMonitor_ForkJvmHeapDumper: before suspend and fork
子进程: hprof heap dump starting
子进程: hprof heap dump completed
JNIBridge: process child-pid will exit
OOMMonitor_ForkJvmHeapDumper: dump true, notify from pid child-pid
```

## 导出 HPROF

Demo 默认会把文件写到外部 App 专属目录：

```text
/sdcard/Android/data/com.example.memoryapm/files/memory-apm/koom-hprof/*.hprof
```

这个目录不需要存储权限，比 `/data/user/0/...` 更容易定位。可以直接用 adb 查看和导出：

```bash
adb shell ls -lh /sdcard/Android/data/com.example.memoryapm/files/memory-apm/koom-hprof
adb pull /sdcard/Android/data/com.example.memoryapm/files/memory-apm/koom-hprof/your-file.hprof .
```

如果设备外部目录不可用，SDK 会自动回退到内部目录：

```text
/data/user/0/com.example.memoryapm/files/memory-apm/koom-hprof/*.hprof
```

内部目录需要用 `run-as` 导出：

```bash
adb shell run-as com.example.memoryapm ls files/memory-apm/koom-hprof
adb shell "run-as com.example.memoryapm cat files/memory-apm/koom-hprof/your-file.hprof" > your-file.hprof
```

导出后可以用 Android Studio Profiler、MAT 或 Shark 分析。

## 后续扩展方向

当前项目只接了 `koom-fast-dump-static`，用于手动 fork dump 学习。

如果后面想接完整 Java 泄漏监控，可以再新增一个开关接入：

```text
com.kuaishou.koom:koom-java-leak-static:2.2.2
```

然后研究：

```text
OOMMonitorConfig
OOMMonitor.startLoop()
OOMHprofUploader
OOMReportUploader
HeapAnalysisService
```
