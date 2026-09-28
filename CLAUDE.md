# CLAUDE.md — 听风插帧（TFGY999）

Android 屏幕捕获 + 实时帧插值处理工具：`MediaProjection` 捕获 → `DirectBufferPool` 池化帧缓冲 → OpenGL ES 插值渲染 → 悬浮窗/FPS 统计。产品定位、**能力边界（插值画面未回投屏幕，非 Root 平台限制）** 与路线图见 `README.md`（中文），此处不重复。

## 规则（优先于其余章节）

- **安全底线**：屏幕内容只在设备内处理，不落盘、不上传、不写入日志。任何改动不得破坏此承诺。
- **实时管线纪律**：插帧是实时循环（`Choreographer` + EGL）。渲染/上传线程禁止阻塞操作（IO、GC 大分配、锁竞争）；帧内存一律走 `DirectBufferPool`，禁止在热路径 `new ByteArray`。
- **真实闭环**：禁止占位实现、幽灵广播（注册无发送方）、假设置（有 UI 无效果）。宣称"完成"须有构建/测试/日志证据。
- **改动范围**：只改任务要求的部分；发现无关死代码指出并清理，不擅自在共享链路做无验证的大改。
- **验证铁则**：改动后必须实际构建验证；未验证结论标注"待验证"。改核心链路（服务/插值/池）后必须跑单测+构建，必要时模拟器 E2E。

## 技术栈

| 层 | 内容 | 版本依据 |
|---|---|---|
| 构建 | Gradle 8.10.2 + AGP 8.8.2，Kotlin DSL，单 `:app` 模块；**无 CMake/NDK** | `gradle/libs.versions.toml`（唯一版本来源） |
| 语言 | Kotlin 2.0.0；Java/JVM 11 | 同上 |
| Android | minSdk 24 / target & compileSdk 35 | `app/build.gradle.kts` |
| UI | Jetpack Compose（Material3 + BOM）；HistoryActivity 用 MPAndroidChart + RecyclerView | `libs.versions.toml` |
| 数据 | 本地 JSON 文件（`frame_data_*.json`，每 60s 落盘有上限）；**无 Room、无 WorkManager** | `app/src/main/java` |
| 测试 | JUnit4（DirectBufferPoolTest / FrameBufferCopyTest，JVM 可跑） | `app/src/test` |

## 架构地图

| 文件 | 职责 | 注意 |
|---|---|---|
| `AutoFrameBoostService.kt` | 核心服务：MediaProjection + VirtualDisplay + ImageReader → GLES 渲染管线、FPS 广播、帧数据落盘 | 前台 `mediaProjection` 服务；DisplayListener/广播/线程均须对称释放 |
| `FrameInterpolator.kt` | 插值引擎：Choreographer 驱动、帧队列、纹理池、上传线程 FIFO 归还缓冲 | 目标帧率尊重用户选择；停止后线程不可复用（重建实例） |
| `DirectBufferPool.kt` | 直接内存池（纯 Kotlin `allocateDirect`，非 mmap） | 帧缓冲池化复用 |
| `FrameBufferCopy.kt` | 按行拷贝，兼容带 rowStride 填充的设备 | 纯函数，可 JVM 单测 |
| `MainActivity.kt` | 主界面（Compose）：开关/目标帧率/启动停止/历史入口 | 状态提升到类级 State，授权回调驱动 |
| `HistoryActivity.kt` | 历史帧率图表：读 `frame_data_*.json` | 无 Room |
| `FloatingWindowService.kt` | 悬浮窗：前台服务 + WindowManager 可拖动 FPS 统计 | 数据来自 FLOATING_WINDOW_UPDATE 本地广播 |
| `FrameData.kt` | JSON 解析模型 | 纯 data class |
| `ui/theme/` | Compose 主题 | |

## 常用命令（Windows；中文路径可直接构建）

```bash
./gradlew.bat assembleDebug    # 构建 Debug APK（自动签名 debug key）
./gradlew.bat assembleRelease   # 构建 Release APK（keystore.properties 存在时签名）
./gradlew.bat test             # JVM 单元测试
./gradlew.bat lint             # lint 检查
./gradlew.bat :app:connectedDebugAndroidTest   # 仪器测试（需真机/模拟器）
```

- 中文路径构建已可用（CMake 已移除）；`android.overridePathCheck=true` 仍必须保留。
- Release 签名：`keystore.properties`（gitignore）+ `keys/`（gitignore）；缺失自动 unsigned。
- 构建/出包/测试结果与陷阱：**改代码前先读 `VERIFICATION-LOG.md`**，命中已覆盖范围只做增量验证。

## 代码约定

- 遵循全局 `~/.claude/rules/`（Kotlin：`val` 优先、禁 `!!`、sealed 穷举 `when`、错误处理不静默吞）。
- 包/命名空间固定 `com.example.tfgy999`，单包结构。
- Compose：状态提升 + 单向数据流；UI 层不持有帧缓冲区引用。
- 广播链路必须"收发一一对应"：新增接收器必须有发送方，否则按幽灵广播清理。
- 热路径禁 `allocateDirect`/`new byte[]`（用 `DirectBufferPool`）。

## 边界与禁区

- **权限清单**（`AndroidManifest.xml`）：FOREGROUND_SERVICE、POST_NOTIFICATIONS、SYSTEM_ALERT_WINDOW、WAKE_LOCK、FOREGROUND_SERVICE_MEDIA_PROJECTION、FOREGROUND_SERVICE_DATA_SYNC。最小化维持，勿加未用权限。
- **Android 13+**：POST_NOTIFICATIONS 属运行时权限，`MainActivity.requiredPermissions` 已含；勿移除。
- **能力边界（P0-1）**：插值画面渲染在离屏 FBO，非 Root 下无法回投真实屏幕（平台限制）。改动渲染目标前先读 README「能力边界」；此项属产品决策，勿擅自大改核心架构。
- **release 未开 minify**（`isMinifyEnabled = false`）；proguard-rules.pro 为模板，启用 minify 前需补 Kotlin/Compose keep 规则。
- **无原生模块**：`cpp/`、`jniLibs/` 已整体移除，勿再引入 JNI/CMake（会复活中文路径构建障碍）。
- **图谱工具**：本仓库已配置 graft（用法见 `AGENTS.md`；`graft/` 是本地可再生缓存）。改代码前先用 graft 查调用关系与影响面。
- **需审批后才做**：删除/覆盖真实数据、改包名或 `applicationId`、发布/推送、改动受保护权限、大范围重构（含 P0-1 渲染回投改造）。