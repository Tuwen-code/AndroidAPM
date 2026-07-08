# ASM 插桩改动逐行中文注释

这份文档用于逐行解释本次 ASM 插桩相关改动。之所以单独放在文档里，是为了让源码保持可维护，同时你又能逐行对照理解每句代码的含义和用途。

## `app/build.gradle.kts`

这里只解释本次新增的插件和配置。

```kotlin
id("com.codex.memoryapm.asm") // 应用本地 ASM Gradle 插件，让 app 模块在构建时执行字节码插桩。

memoryApmAsm { // 配置本地插件暴露出来的 memoryApmAsm DSL。
    enabled.set(true) // 打开当前模块的插桩开关。
    includePackages.add("com.example.memoryapm") // 只插桩这个 app 包名下的类，第一版接入范围小，风险更低。
    logInstrumentedClasses.set(false) // 正常构建时不打印每个被插桩的类；排查插桩范围时可以改成 true。
} // 结束 memoryApmAsm 配置块。
```

## `buildSrc/build.gradle.kts`

```kotlin
plugins { // 声明 buildSrc 自己构建时需要使用的 Gradle 插件。
    `kotlin-dsl` // 允许在 buildSrc 中用 Kotlin 编写 Gradle 插件代码。
    `java-gradle-plugin` // 让 Gradle 自动生成插件元信息，方便通过 id 应用插件。
} // 结束插件声明。

repositories { // 声明 buildSrc 下载依赖时使用的仓库。
    google() // 用于解析 Android Gradle Plugin API 等 Android 相关依赖。
    mavenCentral() // 用于解析 ASM 等常见 JVM 依赖。
    gradlePluginPortal() // 用于解析 Gradle 插件相关依赖。
} // 结束仓库声明。

dependencies { // 声明本地 ASM 插件代码编译时需要的依赖。
    implementation("com.android.tools.build:gradle-api:8.13.2") // 引入 AGP 8.13.2 的公开 Gradle API，其中包含新的 instrumentation API。
    implementation("org.ow2.asm:asm-commons:9.8") // 引入 ASM 工具库，使用 AdviceAdapter 更安全地插入方法入口和出口逻辑。
} // 结束依赖声明。

gradlePlugin { // 注册 buildSrc 提供的 Gradle 插件。
    plugins { // 一个容器，可以在里面声明一个或多个插件。
        create("memoryApmAsm") { // 创建一个名为 memoryApmAsm 的插件定义。
            id = "com.codex.memoryapm.asm" // 插件 id，app/build.gradle.kts 里就是通过这个 id 应用插件。
            implementationClass = "com.codex.memoryapm.asm.MemoryApmAsmPlugin" // Gradle 应用插件时实际实例化的 Kotlin 类。
        } // 结束当前插件定义。
    } // 结束插件定义容器。
} // 结束 Gradle 插件注册。
```

## `MemoryApmAsmExtension.kt`

```kotlin
package com.codex.memoryapm.asm // 当前文件属于 ASM 插件包。

import org.gradle.api.model.ObjectFactory // Gradle 提供的对象工厂，用来创建懒加载的 Property/ListProperty。
import org.gradle.api.provider.ListProperty // Gradle 的懒加载列表属性类型。
import org.gradle.api.provider.Property // Gradle 的懒加载单值属性类型。
import javax.inject.Inject // 让 Gradle 可以把 ObjectFactory 注入到构造函数中。

abstract class MemoryApmAsmExtension @Inject constructor(objects: ObjectFactory) { // 定义 memoryApmAsm { ... } 这个 DSL 的配置对象。
    val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true) // 插桩总开关，默认开启。
    val includePackages: ListProperty<String> = objects.listProperty(String::class.java).convention(emptyList()) // 包名白名单，默认空表示不额外限制，交给 exclude 决定。
    val excludePackages: ListProperty<String> = objects.listProperty(String::class.java).convention( // 包名黑名单，默认排除不适合插桩的基础包。
        listOf( // 开始声明默认排除列表。
            "android/", // 不插桩 Android framework 类。
            "androidx/", // 不插桩 AndroidX 类，后续如果扩大插桩范围也能避免影响依赖库。
            "kotlin/", // 不插桩 Kotlin 运行时类。
            "kotlinx/", // 不插桩 kotlinx 运行时或库类。
            "org/intellij/", // 不插桩 Kotlin/IDE 相关辅助类。
            "org/jetbrains/", // 不插桩 JetBrains 注解和运行时辅助类。
            "com/codex/memoryapm/trace/", // 不插桩我们自己的 trace hook，避免 hook 调自己导致递归。
        ), // 结束默认排除列表。
    ) // 结束 excludePackages 默认值声明。
    val logInstrumentedClasses: Property<Boolean> = objects.property(Boolean::class.java).convention(false) // 调试开关，用来打印每个被插桩的类名，默认关闭。
} // 结束 DSL 配置对象定义。
```

## `MemoryApmAsmParameters.kt`

```kotlin
package com.codex.memoryapm.asm // 当前文件属于 ASM 插件包。

import com.android.build.api.instrumentation.InstrumentationParameters // AGP ASM 插桩参数必须实现的标记接口。
import org.gradle.api.provider.ListProperty // Gradle 的懒加载列表属性类型。
import org.gradle.api.provider.Property // Gradle 的懒加载单值属性类型。
import org.gradle.api.tasks.Input // 标记这些参数是 Gradle 任务输入，会影响增量构建判断。

abstract class MemoryApmAsmParameters : InstrumentationParameters { // 定义传给 AGP ASM transform 任务的参数对象。
    @get:Input // 告诉 Gradle：enabled 会影响任务输出，需要参与 up-to-date 判断。
    abstract val enabled: Property<Boolean> // 是否启用插桩。

    @get:Input // 告诉 Gradle：includePackages 变化后需要重新执行 transform。
    abstract val includePackages: ListProperty<String> // 传给 ClassVisitorFactory 的包名白名单。

    @get:Input // 告诉 Gradle：excludePackages 变化后需要重新执行 transform。
    abstract val excludePackages: ListProperty<String> // 传给 ClassVisitorFactory 的包名黑名单。

    @get:Input // 告诉 Gradle：日志开关也是任务配置输入。
    abstract val logInstrumentedClasses: Property<Boolean> // 是否打印被插桩的类名。
} // 结束参数对象定义。
```

## `MemoryApmAsmPlugin.kt`

```kotlin
package com.codex.memoryapm.asm // 当前文件属于 ASM 插件包。

import com.android.build.api.instrumentation.FramesComputationMode // 控制 ASM 修改字节码后如何重新计算栈帧的枚举。
import com.android.build.api.instrumentation.InstrumentationScope // 控制只插当前工程还是连依赖一起插的枚举。
import com.android.build.api.variant.AndroidComponentsExtension // AGP 7+ 推荐使用的 variant API 入口。
import org.gradle.api.Plugin // Gradle 插件接口。
import org.gradle.api.Project // Gradle 传给插件的项目对象。

class MemoryApmAsmPlugin : Plugin<Project> { // 定义本地 Gradle 插件类。
    override fun apply(project: Project) { // 当 build.gradle.kts 中应用插件 id 时，Gradle 会调用这个方法。
        val extension = project.extensions.create("memoryApmAsm", MemoryApmAsmExtension::class.java) // 创建 memoryApmAsm { ... } 配置块。

        project.pluginManager.withPlugin("com.android.application") { // 等待 Android application 插件应用完成。
            configureInstrumentation(project, extension) // 给 app 模块注册 ASM 插桩逻辑。
        } // 结束 application 插件监听。
        project.pluginManager.withPlugin("com.android.library") { // 等待 Android library 插件应用完成。
            configureInstrumentation(project, extension) // 给 library 模块注册 ASM 插桩逻辑。
        } // 结束 library 插件监听。
    } // 结束 apply 方法。

    private fun configureInstrumentation(project: Project, extension: MemoryApmAsmExtension) { // app/library 共用的插桩注册逻辑。
        val androidComponents = // 保存 AGP 提供的 AndroidComponentsExtension。
            project.extensions.getByType(AndroidComponentsExtension::class.java) // 从当前 Android 模块里拿到 AGP variant API。

        androidComponents.onVariants(androidComponents.selector().all()) { variant -> // 对每个构建变体执行一次，例如 debug、release。
            variant.instrumentation.transformClassesWith( // 使用 AGP 官方 ASM instrumentation API 注册 class visitor。
                MemoryApmAsmClassVisitorFactory::class.java, // 指定负责创建 ClassVisitor 的工厂类。
                InstrumentationScope.PROJECT, // 只插当前模块编译出来的 class，不插依赖 jar。
            ) { parameters -> // 把 DSL 配置复制到 transform 任务参数里。
                parameters.enabled.set(extension.enabled) // 传递插桩总开关。
                parameters.includePackages.set(extension.includePackages) // 传递包名白名单。
                parameters.excludePackages.set(extension.excludePackages) // 传递包名黑名单。
                parameters.logInstrumentedClasses.set(extension.logInstrumentedClasses) // 传递类名打印开关。
            } // 结束参数设置。
            variant.instrumentation.setAsmFramesComputationMode( // 设置字节码修改后的栈帧处理方式。
                FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS, // 只重算被修改过的方法，兼顾安全和构建性能。
            ) // 结束栈帧计算模式设置。
        } // 结束 variant 注册。
    } // 结束 configureInstrumentation 方法。
} // 结束插件类。
```

## `MemoryApmAsmClassVisitorFactory.kt`

```kotlin
package com.codex.memoryapm.asm // 当前文件属于 ASM 插件包。

import com.android.build.api.instrumentation.AsmClassVisitorFactory // AGP 用来创建 ASM ClassVisitor 的接口。
import com.android.build.api.instrumentation.ClassContext // 当前正在处理的 class 上下文。
import com.android.build.api.instrumentation.ClassData // class 的轻量元信息，用来判断是否需要插桩。
import org.objectweb.asm.ClassVisitor // ASM 的 class 访问器基类。

abstract class MemoryApmAsmClassVisitorFactory : AsmClassVisitorFactory<MemoryApmAsmParameters> { // AGP 对每个 class 调用这个工厂。
    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor { // 为当前 class 创建真正执行插桩的 visitor。
        val className = classContext.currentClassData.className.toInternalName() // 把点号类名转成 JVM 内部的斜杠类名。
        if (parameters.get().logInstrumentedClasses.get()) { // 判断是否打开了插桩类名打印。
            println("MemoryApmAsm instrumenting $className") // 打印当前正在插桩的 class。
        } // 结束日志判断。
        return MemoryApmClassVisitor( // 返回我们自己的 ClassVisitor。
            api = instrumentationContext.apiVersion.get(), // 使用 AGP 当前选择的 ASM API 版本。
            nextClassVisitor = nextClassVisitor, // 把下游 visitor 传进去，保证处理完还能交还给 AGP。
            className = className, // 把当前类名传给方法 visitor，用于生成 trace 标签。
        ) // 结束 ClassVisitor 创建。
    } // 结束 createClassVisitor 方法。

    override fun isInstrumentable(classData: ClassData): Boolean { // 判断当前 class 是否应该被插桩。
        val params = parameters.get() // 读取 transform 任务参数。
        if (!params.enabled.get()) { // 如果总开关关闭。
            return false // 当前 class 不插桩。
        } // 结束开关判断。

        val className = classData.className.toInternalName() // 把类名转成斜杠格式，方便按包名前缀匹配。
        return className.isIncluded(params.includePackages.get()) && // 必须命中 include 白名单。
            !className.isExcluded(params.excludePackages.get()) // 同时不能命中 exclude 黑名单。
    } // 结束 isInstrumentable 方法。

    private fun String.isIncluded(includePackages: List<String>): Boolean { // 判断当前类名是否命中白名单。
        if (includePackages.isEmpty()) { // 如果用户没有配置白名单。
            return true // 默认允许，后续再交给黑名单过滤。
        } // 结束空白名单判断。
        return includePackages.any { startsWithPackage(it) } // 只要命中任意一个白名单包名就允许。
    } // 结束白名单判断函数。

    private fun String.isExcluded(excludePackages: List<String>): Boolean { // 判断当前类名是否命中黑名单。
        return excludePackages.any { startsWithPackage(it) } // 只要命中任意一个黑名单包名就排除。
    } // 结束黑名单判断函数。
} // 结束工厂类。
```

## `MemoryApmClassVisitor.kt`

```kotlin
package com.codex.memoryapm.asm // 当前文件属于 ASM 插件包。

import org.objectweb.asm.ClassVisitor // ASM 用来访问 class 结构的 visitor。
import org.objectweb.asm.MethodVisitor // ASM 用来访问 method 字节码的 visitor。
import org.objectweb.asm.Opcodes // JVM 字节码访问标记和操作码常量。

class MemoryApmClassVisitor( // 这个 visitor 负责遍历一个 class，并决定哪些方法需要插桩。
    api: Int, // ASM API 版本。
    nextClassVisitor: ClassVisitor, // AGP visitor 链里的下一个 visitor。
    private val className: String, // 当前正在处理的类名，使用 JVM 内部斜杠格式。
) : ClassVisitor(api, nextClassVisitor) { // 继承 ASM ClassVisitor，并把结果继续传给下游 visitor。
    override fun visitMethod( // ASM 每遇到一个方法都会回调这个方法。
        access: Int, // 方法访问标记，例如 public、static、native。
        name: String, // 方法名，例如 onCreate、invoke。
        descriptor: String, // JVM 方法描述符，包含参数类型和返回值类型。
        signature: String?, // 泛型签名，没有泛型时可能为空。
        exceptions: Array<out String>?, // 方法声明抛出的异常列表，可能为空。
    ): MethodVisitor { // 返回负责处理这个方法的 MethodVisitor。
        val methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions) // 先拿到下游原始 MethodVisitor。
        if (!shouldInstrument(access, name)) { // 判断这个方法是否适合插桩。
            return methodVisitor // 不适合插桩时，原样返回下游 visitor。
        } // 结束是否插桩判断。
        return MemoryApmMethodVisitor( // 适合插桩时，用我们自己的 MethodVisitor 包一层。
            api = api, // 传递 ASM API 版本。
            methodVisitor = methodVisitor, // 传递下游 MethodVisitor。
            access = access, // 传递方法访问标记。
            methodName = name, // 传递方法名，用于 trace 标签。
            methodDescriptor = descriptor, // 传递方法描述符，用于区分重载方法。
            className = className, // 传递所属类名，用于 trace 标签。
        ) // 结束 MethodVisitor 创建。
    } // 结束 visitMethod 方法。

    private fun shouldInstrument(access: Int, name: String): Boolean { // 判断某个方法是否应该插桩。
        if (name == "<init>" || name == "<clinit>") { // 构造函数和静态初始化方法的字节码规则更敏感。
            return false // 第一版先跳过构造函数和静态初始化方法，降低风险。
        } // 结束构造/静态初始化判断。

        val ignoredAccess = Opcodes.ACC_ABSTRACT or // abstract 方法没有方法体，无法插入字节码。
            Opcodes.ACC_NATIVE or // native 方法没有 JVM 字节码体，无法插入字节码。
            Opcodes.ACC_BRIDGE or // bridge 方法是编译器生成的桥接方法，通常噪声较大。
            Opcodes.ACC_SYNTHETIC // synthetic 方法是编译器生成方法，通常不是业务方法。
        return access and ignoredAccess == 0 // 只有不包含这些忽略标记的方法才会被插桩。
    } // 结束 shouldInstrument 方法。
} // 结束 ClassVisitor。
```

## `MemoryApmMethodVisitor.kt`

```kotlin
package com.codex.memoryapm.asm // 当前文件属于 ASM 插件包。

import org.objectweb.asm.MethodVisitor // ASM 用来写入或转发方法字节码的 visitor。
import org.objectweb.asm.Opcodes // JVM 操作码常量，例如 ATHROW。
import org.objectweb.asm.Type // ASM 类型工具，用来表示 JVM 类型和描述符。
import org.objectweb.asm.commons.AdviceAdapter // ASM 工具类，提供 onMethodEnter/onMethodExit 这种安全插入点。
import org.objectweb.asm.commons.Method // ASM 方法描述工具。

class MemoryApmMethodVisitor( // 这个 visitor 负责给一个具体方法插入入口和出口代码。
    api: Int, // ASM API 版本。
    methodVisitor: MethodVisitor, // 下游 MethodVisitor。
    access: Int, // 当前方法访问标记。
    private val methodName: String, // 当前方法名，用于 trace 标签。
    private val methodDescriptor: String, // 当前方法描述符，用于区分重载。
    private val className: String, // 当前方法所属类名。
) : AdviceAdapter(api, methodVisitor, access, methodName, methodDescriptor) { // 继承 AdviceAdapter，简化入口/出口插入逻辑。
    private var startTimeLocalIndex = -1 // 保存新建 long 本地变量的槽位索引，用来存方法开始时间。

    override fun onMethodEnter() { // AdviceAdapter 在方法开头回调这里。
        startTimeLocalIndex = newLocal(Type.LONG_TYPE) // 申请一个新的 long 类型本地变量，用来存 startNs。
        push(className) // 把 className 压入操作数栈，作为 onMethodEnter 的第一个参数。
        push("$methodName$methodDescriptor") // 把方法名加描述符压入操作数栈，作为第二个参数。
        invokeStatic(TRACE_TYPE, ON_METHOD_ENTER) // 调用 MemoryApmMethodTrace.onMethodEnter(className, methodName)。
        storeLocal(startTimeLocalIndex, Type.LONG_TYPE) // 把 onMethodEnter 返回的开始时间保存到本地变量。
    } // 结束方法入口插入逻辑。

    override fun onMethodExit(opcode: Int) { // AdviceAdapter 在每个方法退出点前回调这里。
        if (opcode == Opcodes.ATHROW) { // 判断当前退出是否是抛异常退出。
            return // 第一版先跳过异常退出路径，让出口插桩逻辑更简单。
        } // 结束异常退出判断。
        push(className) // 把 className 压入操作数栈，作为 onMethodExit 的第一个参数。
        push("$methodName$methodDescriptor") // 把方法名加描述符压入操作数栈，作为第二个参数。
        loadLocal(startTimeLocalIndex, Type.LONG_TYPE) // 从本地变量中读取方法开始时间，作为第三个参数。
        invokeStatic(TRACE_TYPE, ON_METHOD_EXIT) // 调用 MemoryApmMethodTrace.onMethodExit(className, methodName, startNs)。
    } // 结束方法出口插入逻辑。

    private companion object { // 定义所有 MethodVisitor 实例共享的常量。
        private val TRACE_TYPE = Type.getObjectType("com/codex/memoryapm/trace/MemoryApmMethodTrace") // 运行时 hook 类的 JVM 内部类型名。
        private val ON_METHOD_ENTER = Method("onMethodEnter", "(Ljava/lang/String;Ljava/lang/String;)J") // onMethodEnter 的方法名和 JVM 描述符，返回 long。
        private val ON_METHOD_EXIT = Method("onMethodExit", "(Ljava/lang/String;Ljava/lang/String;J)V") // onMethodExit 的方法名和 JVM 描述符，返回 void。
    } // 结束 companion object。
} // 结束 MethodVisitor。
```

## `StringNames.kt`

```kotlin
package com.codex.memoryapm.asm // 当前文件属于 ASM 插件包。

internal fun String.toInternalName(): String { // 把类名或包名转成 JVM 内部名称格式。
    return replace('.', '/') // JVM 字节码里用斜杠分隔包名，例如 com/example/Foo，而不是 com.example.Foo。
} // 结束名称转换函数。

internal fun String.startsWithPackage(packageName: String): Boolean { // 判断当前内部类名是否属于某个包名。
    val prefix = packageName.toInternalName().trimEnd('/') + "/" // 统一把配置的包名转成斜杠格式，并保证末尾只有一个斜杠。
    return this == prefix.trimEnd('/') || startsWith(prefix) // 支持精确匹配类名，也支持匹配某个包下的所有类。
} // 结束包名前缀判断函数。
```

## `MemoryApmMethodTrace.kt`

```kotlin
package com.codex.memoryapm.trace // 当前文件属于 memory-apm SDK 的 trace 包。

import android.util.Log // 使用 Android Log 输出慢方法日志。

object MemoryApmMethodTrace { // 单例运行时 hook，插桩后的字节码会直接调用这里的方法。
    private const val TAG = "MemoryApmTrace" // Logcat 中显示的日志 tag。

    @Volatile // 保证多线程读取 enabled 时能看到最新值。
    private var enabled: Boolean = false // 运行时开关，默认关闭，这样即使已经插桩也不会默认打印或统计。

    @Volatile // 保证多线程读取 logSlowMethod 时能看到最新值。
    private var logSlowMethod: Boolean = true // 是否打印慢方法日志。

    @Volatile // 保证多线程读取 slowThresholdNs 时能看到最新值。
    private var slowThresholdNs: Long = 16_000_000L // 慢方法阈值，单位纳秒，默认 16ms。

    @JvmStatic // 生成真正的 static 方法，方便 ASM 插入的字节码直接调用。
    fun configure( // 对外提供运行时配置方法。
        enabled: Boolean, // 是否启用方法耗时 trace。
        slowThresholdMs: Long = 16L, // 慢方法阈值，单位毫秒。
        logSlowMethod: Boolean = true, // 是否把慢方法输出到 Logcat。
    ) { // 开始 configure 方法体。
        this.enabled = enabled // 保存 trace 开关。
        this.slowThresholdNs = slowThresholdMs.coerceAtLeast(0L) * 1_000_000L // 把毫秒转成纳秒，并避免负数。
        this.logSlowMethod = logSlowMethod // 保存日志开关。
    } // 结束 configure 方法。

    @JvmStatic // 生成真正的 static 方法，方便插桩代码调用。
    fun setEnabled(enabled: Boolean) { // 提供一个只切换开关的轻量方法。
        this.enabled = enabled // 保存 trace 开关。
    } // 结束 setEnabled 方法。

    @JvmStatic // 生成真正的 static 方法，方便插桩代码调用。
    fun onMethodEnter(className: String, methodName: String): Long { // 每个被插桩方法进入时会调用这里。
        if (!enabled) { // 如果运行时 trace 没打开。
            return 0L // 返回 0 作为哨兵值，出口处看到 0 就直接跳过。
        } // 结束开关判断。
        return System.nanoTime() // 返回高精度开始时间。
    } // 结束入口 hook。

    @JvmStatic // 生成真正的 static 方法，方便插桩代码调用。
    fun onMethodExit(className: String, methodName: String, startNs: Long) { // 每个被插桩方法正常返回前会调用这里。
        if (!enabled || startNs == 0L) { // 如果 trace 关闭，或者入口处没有记录开始时间。
            return // 直接返回，不做任何统计或日志。
        } // 结束有效性判断。

        val costNs = System.nanoTime() - startNs // 计算当前方法耗时，单位纳秒。
        if (logSlowMethod && costNs >= slowThresholdNs) { // 只有打开日志且耗时超过阈值时才打印。
            Log.d(TAG, "${className.replace('/', '.')}#$methodName cost=${costNs / 1_000_000.0}ms") // 把类名转回点号格式，并输出方法耗时，单位毫秒。
        } // 结束慢方法日志判断。
    } // 结束出口 hook。
} // 结束运行时 hook 单例。
```

## `memory-apm/consumer-rules.pro`

```proguard
-keep class com.codex.memoryapm.trace.MemoryApmMethodTrace { # 保留运行时 hook 类，因为插桩后的字节码会直接调用它。
    public static *; # 保留所有 public static 方法，例如 onMethodEnter 和 onMethodExit。
} # 结束 keep 规则。
```
