# Repository Guidelines

## 基础工作流程

所有代码开发、需求修改和 bug 修复前，必须先生成一份标准化开发文档，不允许跳过文档直接改业务代码。开发文档统一保存到 `docs/dev_record/`；目录不存在时先创建。文档命名格式为 `{日期}_{功能模块}_{需求简述}.md`，例如 `20260708_user_login_接口重构.md`。如果同名文档已存在，只追加记录，不覆盖原内容。

开发完成后，必须在对应开发文档末尾追加实际变更记录，说明实现差异、验证结果和补充优化点。所有项目文档使用中文，结构清晰，不省略关键逻辑。

## 开发文档模板

每份开发文档必须包含以下模块：

1. `需求概述`：说明要实现或修改的功能、业务目标和使用场景。
2. `方案设计`：说明技术选型、目录结构变化、新增或修改文件清单、接口设计。
3. `风险与边界处理`：说明异常场景、兼容性、权限、性能和依赖冲突。
4. `实现步骤`：拆分最小可执行单元，列出分步编码流程。
5. `测试验证方案`：列出自测点、入参案例和预期结果。
6. `变更记录`：记录开发人、时间、修改内容和后续优化点。

## 项目结构与模块组织

这是一个 Android Gradle 项目。日常开发在 `MemoryAPM/` 目录下进行，该目录包含 `settings.gradle.kts` 和 Gradle Wrapper。主要模块如下：

- `app/`：示例 Android 应用，包含 Jetpack Compose UI、启动资源、单元测试和仪器测试。
- `memory-apm/`：核心内存监控库，源码位于 `src/main/java/com/codex/memoryapm`。
- `memory-apm-koom/`：KOOM 集成层和堆转储分析桥接代码。
- `koom-shark/`：内置的 Shark 风格 HPROF 解析代码，包名为 `kshark`。
- `buildSrc/`：自定义 Gradle/ASM 插桩插件 `com.codex.memoryapm.asm`。
- `docs/`：ASM 插桩和 KOOM fork dump 流程的实现说明。

不要提交 `build/`、`.gradle/`、`.idea/` 下的生成文件，也不要提交堆转储文件（`*.hprof`）。

## 构建、测试与开发命令

以下命令均在 `MemoryAPM/` 目录下执行：

- `.\gradlew.bat assembleDebug`：构建所有 debug 版本的 APK/AAR 输出。
- `.\gradlew.bat :app:installDebug`：将示例应用安装到已连接的设备或模拟器。
- `.\gradlew.bat testDebugUnitTest`：运行 debug 变体的本地 JVM 单元测试。
- `.\gradlew.bat connectedDebugAndroidTest`：运行 Android 仪器测试，需要可用设备或模拟器。
- `.\gradlew.bat lint`：运行 Android lint 静态检查。
- `.\gradlew.bat clean`：清理 Gradle 构建产物。

在 macOS 或 Linux 上使用 `./gradlew` 替代 `.\gradlew.bat`。

## 架构与设计原则

编写代码时，整体框架必须遵循高内聚、低耦合原则：同一模块内聚焦单一职责，跨模块通过清晰接口协作，避免业务逻辑、平台能力、存储、UI 和第三方集成互相穿透。新增能力优先放入职责匹配的模块；确需跨模块依赖时，应保持依赖方向清晰，不引入循环依赖。

设计和重构代码时必须符合常见软件设计六大原则：

1. `单一职责原则`：类、函数和模块只承担一个明确职责。
2. `开闭原则`：对扩展开放，对修改关闭，优先通过新增实现扩展行为。
3. `里氏替换原则`：子类或实现类必须可替换其父类或接口，不破坏调用方预期。
4. `依赖倒置原则`：高层策略依赖抽象，不直接依赖低层实现细节。
5. `接口隔离原则`：接口保持小而专用，避免调用方依赖不需要的方法。
6. `迪米特法则`：减少对象间不必要的了解，只暴露完成协作所需的最小信息。

## 编码风格与命名约定

Kotlin 代码使用 4 空格缩进，并遵循现有 Android/Kotlin 风格。包名应与模块职责一致：示例代码使用 `com.example.memoryapm`，库代码使用 `com.codex.memoryapm`，Shark 衍生代码使用 `kshark`。类和 Composable 使用 `PascalCase`，函数和属性使用 `camelCase`，内存和堆分析相关命名应清晰具体。

新增或修改关键函数时，应补充必要注释，说明入参、返回值和关键边界逻辑。不要添加重复代码含义的空泛注释。Gradle 文件使用 Kotlin DSL（`*.gradle.kts`），依赖版本优先维护在 `gradle/libs.versions.toml`。提交前运行 `.\gradlew.bat lint` 和相关测试。

## 测试规范

单元测试放在 `src/test/java`，仪器测试放在 `src/androidTest/java`，并镜像被测代码的包路径。测试文件命名为 `*Test.kt`，测试方法名应直接描述被验证的行为。修改插桩逻辑、堆解析、dump 调度或公开库 API 时，应同步新增或更新测试。

## 提交与 Pull Request 规范

当前历史只有一个简洁的描述性提交，因此后续提交信息也应短小、面向结果，例如 `Add KOOM dump scheduling guard`。Pull Request 应包含变更摘要、影响模块、测试结果、关联 issue；如果涉及应用 UI 或 dump 分析行为变化，还应附截图或关键日志。

## 安全与配置提示

不要提交 `local.properties`、本机设备路径、凭据或堆转储文件。库模块新增公开 API 时，应同步检查并更新 ProGuard consumer rules。
