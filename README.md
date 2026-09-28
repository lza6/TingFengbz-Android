# 🚀 听风插帧 (TingFengbz-Android)

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![GitHub Repository](https://img.shields.io/badge/GitHub-TingFengbz--Android-green.svg)](https://github.com/lza6/TingFengbz-Android)

> Android 屏幕捕获 + 实时帧插值处理工具：将 MediaProjection 捕获的画面在设备端做插帧处理，并通过悬浮窗实时展示处理帧率统计。

## ⚠️ 能力边界（请先阅读）

本应用在**非 Root 的 Android 平台约束**下实现。请如实了解当前能力：

- ✅ **已实现**：MediaProjection 屏幕捕获 → ImageReader 逐帧读取 → OpenGL ES 插值处理（帧混合）→ 离屏 FBO 渲染 → **悬浮窗实时 FPS / 插帧数 / 状态统计**；历史帧率数据落盘并可查看图表。
- ⚠️ **未实现（平台限制 + 待产品决策）**：插值后的画面**尚未回投到真实屏幕**。非 Root 下无法把改写画面覆盖到其他应用的显示层（这是 Android 平台限制，需 root/系统权限或改用「悬浮窗播放插值画面」的形态）。当前刷新屏幕上**看不到**插值后的画面，仅能看到统计数字。
- ✅ **已承诺**：捕获的屏幕内容**只在设备内处理**，不落盘、不上传、不进日志。

> 若你的核心诉求是"在手机屏幕上真正看到补帧后的画面"，当前版本还不能满足；后续可从「悬浮窗内播放插值画面（Presentation/SurfaceView 回投）」方向演进（见路线图）。

## 快速上手

1. 下载 Release APK：`tingfeng-frame-release-signed.apk`（或安装 `tingfeng-frame-debug.apk` 验证）。
2. 安装后打开 App，开启「启动听风屏幕实时插帧」开关。
3. 点击「启动服务」，系统弹屏幕捕获授权框 → 允许。
4. 服务启动后：状态栏出现"服务运行中"通知（**Android 13+ 需授予通知权限**，App 已引导请求）；如需悬浮窗，开启「显示高级悬浮窗」并授予悬浮窗权限。
5. 开启「显示历史补帧插帧记录」后，可进入历史页查看帧率曲线（数据来自本机 `frame_data_*.json`）。
6. 「停止服务」按钮随时可停止并释放资源。

## 核心原理

```
用户屏幕 → MediaProjection/VirtualDisplay → ImageReader(每帧)
        → DirectBufferPool(直接内存池, 复用帧缓冲)
        → OpenGL ES 插值(GLSL mix 帧混合) → 离屏 FBO/PBuffer
        → FPS/插帧统计 → 悬浮窗 + 前台服务通知 + frame_data JSON
```

- **MediaProjection**：系统屏幕捕获能力，只读镜像，不写入屏幕。
- **DirectBufferPool**：纯 Kotlin 直接内存池（`ByteBuffer.allocateDirect`），帧缓冲池化复用，避免每帧大分配与 GC 抖动。
- **OpenGL ES**：帧纹理上传 + 插值渲染（当前为简单帧混合 shader）。
- **悬浮窗**：真实前台服务 + WindowManager 悬浮窗，可拖动，实时显示 FPS/插帧数。
- **历史数据**：每秒采样、内存缓冲、每 60s 落盘（有上限），HistoryActivity 展示曲线。

## 项目结构

```
app/src/main/java/com/example/tfgy999/
├── AutoFrameBoostService.kt   # 核心服务：捕获+渲染管线+生命周期
├── FrameInterpolator.kt       # 插值引擎：Choreographer 驱动、帧队列、GL 上传
├── DirectBufferPool.kt        # 直接内存池
├── FrameBufferCopy.kt         # 行拷贝（兼容带 rowStride 填充的设备）
├── MainActivity.kt            # 主界面 (Compose)
├── HistoryActivity.kt         # 历史帧率图表 (MPAndroidChart)
├── FloatingWindowService.kt   # 悬浮窗统计
├── FrameData.kt               # frame_data_*.json 解析模型
└── ui/                        # Compose 主题
```

## 构建

```bash
# 需要 JDK 17 + Android SDK (compileSdk 35)
# Windows: gradlew.bat ；mac/Linux: ./gradlew
./gradlew assembleDebug        # 构建 Debug APK（自动签名 debug key）
./gradlew assembleRelease      # 构建 Release APK（keystore.properties 存在时签名）
./gradlew test                  # JVM 单元测试
./gradlew lint                  # 静态检查
```

Release 签名：仓库根创建 `keystore.properties`（已 gitignore）：
```properties
storeFile=keys/tingfeng-release.jks
storePassword=xxx
keyAlias=xxx
keyPassword=xxx
```
缺失时 release 自动退化为 unsigned。

## 安全与隐私

- 屏幕内容不落盘、不上传、不进日志；日志仅为运行状态（帧率等）。
- 权限最小化：仅申请实际使用的权限（前台服务/通知/悬浮窗/唤醒）。
- 无网络权限、无外部存储访问、无相机/麦克风。
- 已移除：自动压枪外挂及相关无障碍注入、LeakCanary（曾致启动 ANR）、未使用重型依赖。

## 已知限制与路线图

| 项 | 状态 | 说明/计划 |
|---|---|---|
| 插值画面回投屏幕 | **未实现** | 非 Root 平台限制；候选方案：悬浮窗内播放插值画面、Presentation 双屏 |
| 真实运动矢量插补 | 未实现 | 当前为帧混合（mix）；如需可接入光流/神经网络（需性能/耗电预算） |
| ABI 拆分 | 建议 | 可设 `abiFilters` 减小包体 |
| 分应用配置 | 未实现 | 全局开关 → 按 App 配置 |
| 单元测试覆盖 | 核心约 40% | 纯函数（池/拷贝/插值因子）已覆盖，可扩展 |

## 贡献

欢迎提交 Issue 与 PR。请遵循项目根 `CLAUDE.md` / `.specify/memory/constitution.md` 的约定：真实实现不占位、"完成"须有验证证据、权限最小化、屏幕隐私红线。

## License

Apache-2.0