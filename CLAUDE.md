# CLAUDE.md — 听风插帧（TFGY999）

Android 全局实时补帧工具：通过 `MediaProjection` 捕获屏幕，用 OpenGL ES + 运动估算在设备端实时生成中间帧，把低帧率内容提升至高帧率。产品定位、技术原理与路线图见 `README.md`（中文），此处不重复。

## 规则（优先于其余章节）

- **安全底线**：屏幕内容只在设备内处理，不落盘、不上传、不写入日志。任何改动不得破坏此承诺。
- **实时管线纪律**：插帧是实时循环（`Choreographer` + EGL）。渲染/上传线程禁止阻塞操作（IO、GC 大分配、锁竞争）；帧内存一律走 `DirectBufferPool`（mmap），禁止在热路径 `new ByteArray`。
- **改动范围**：只改任务要求的部分；发现无关死代码（如已声明未接入的依赖）指出但不擅自删除。
- **验证铁则**：改动后必须实际构建验证，不得以"理论可行"冒充完成；未验证结论标注"待验证"。

## 技术栈

| 层 | 内容 | 版本依据 |
|---|---|---|
| 构建 | Gradle 8.10.2 + AGP 8.8.2，Kotlin DSL，单 `:app` 模块 | `gradle/libs.versions.toml`（唯一版本来源，勿内联复制） |
| 语言 | Kotlin 2.0.0 | 同上 |
| Android | minSdk 24 / target & compileSdk 35，Java/JVM 11 | `app/build.gradle.kts` |
| UI | Jetpack Compose（Material3 + BOM）、MPAndroidChart | `libs.versions.toml` |
| 数据 | Room 2.6.1（`AppDatabase`，`FrameData`/`FrameRecord` DAO）、WorkManager（`LogUploadWorker`） | `app/build.gradle.kts` |
| 原生 | C++11，CMake + JNI，`mmap` 内存池 | `app/src/main/cpp/` |
| 声明未接入 | OpenCV 4.9.0、TensorFlow Lite、LWJGL Vulkan、Filament、Media3 | 依赖已声明，未进核心管线（README 已标注） |

## 架构地图

| 文件 | 职责 | 注意 |
|---|---|---|
| `AutoFrameBoostService.kt` | 核心服务：MediaProjection + VirtualDisplay + ImageReader → EGL/GLES20 渲染管线 | 前台 `mediaProjection` 服务，生命周期/权限敏感 |
| `FrameInterpolator.kt` | 插帧引擎：Choreographer 驱动、shader 插值、纹理池、无锁帧队列 | 声明 native `calculateBlockDifferenceNeon`（见边界） |
| `MotionEstimator.kt` | 金字塔运动估算（块匹配） | 调 NEON native |
| `DirectBufferPool.kt` + `directbuf.cpp` | JNI `mmap` 直接内存池（`nativeAlloc`/`nativeFree`） | 当前唯一编译进包的 .cpp |
| `LockFreeRingBuffer.kt` / `LruCache.kt` / `PerformanceMonitor.kt` / `RenderGuard.kt` | 并发缓冲 / 缓存 / 性能监控 / 渲染保护 | |
| `MainActivity.kt` / `TestActivity.kt` / `HistoryActivity.kt` | Compose UI（启动 / 测试 / 历史） | 主题在 `ui/theme/` |

## 常用命令（Windows）

```bash
./gradlew.bat assembleDebug    # 构建 Debug APK（含 NDK/CMake）
./gradlew.bat installDebug     # 构建并安装到已连接设备
./gradlew.bat test             # JVM 单元测试（JUnit4，src/test）
./gradlew.bat lint             # lint 检查
./gradlew.bat :app:connectedDebugAndroidTest   # 仪器测试（需真机/模拟器）
./gradlew.bat build            # 全量构建（含 release）
```

构建失败先看 CMake/NDK 输出；`local.properties` 管本机 NDK/SDK 路径，不提交。

## 代码约定

- 遵循全局 `~/.claude/rules/`（Kotlin：`val` 优先、禁 `!!`、sealed 类型穷举 `when`、错误处理不静默吞；Java 同理）。
- 包/命名空间固定 `com.example.tfgy999`，单包结构，不新增子包除非有明确理由。
- **JNI 绑定不可随意改名**：C++ 函数名 = `Java_com_example_tfgy999_<类>_<方法>`。改 Kotlin 类名/方法签名必须同步改 `cpp/*.cpp`，否则 `UnsatisfiedLinkError`。
- Room：DAO 一律参数化 `@Query`，禁止字符串拼接 SQL。
- Compose：状态提升 + 单向数据流；UI 层不持有原生缓冲区引用。

## 边界与禁区

- **权限清单**（`AndroidManifest.xml`）含受保护权限：`CAPTURE_VIDEO_OUTPUT`、`SYSTEM_ALERT_WINDOW`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`、`FOREGROUND_SERVICE_DATA_SYNC`。新增/移除需明确理由。自动压枪（`AutoRecoilService`/`TouchAccessibilityService`）及其无障碍触控注入已整体移除，勿再引用。
- **Windows 中文路径**：项目位于含 `插帧率` 的路径，AGP 构建需 `android.overridePathCheck=true`（已在 `gradle.properties`）；且 `externalNativeBuild`（CMake）在本路径会因路径 JSON 转义损坏而失败，完整出 APK 需将项目移至纯 ASCII 路径（如 `D:\dev\`）。
- **release 未开 minify**（`isMinifyEnabled = false`）；改 release 配置前先确认混淆规则。
- **未实现功能**（README 已声明）：光流法 / 简单混合、TensorFlow Lite VFI 模型、Vulkan 管线均"已声明未接入"。接入前先读 `README.md` 现状，勿把声明当已完成功能维护。
- **NEON 陷阱**：`neon_optimizer.cpp` 与 `jniLibs/CMakeLists.txt` 不在当前 `cpp/CMakeLists.txt` 的构建目标内（当前仅编译 `directbuf.cpp`）。调用 `FrameInterpolator.calculateBlockDifferenceNeon` 会 `UnsatisfiedLinkError`；启用需先把源文件加进 CMake。
- **图谱工具**：本仓库已配置 graft（用法见 `AGENTS.md`；`graft/` 是本地可再生缓存）。改代码前先用 graft 查调用关系与影响面；codegraph 索引存在时优先使用。
- **需审批后才做**：删除/覆盖真实数据、改包名或 `applicationId`、发布/推送、改动受保护权限、大范围重构。
