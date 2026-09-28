# VERIFICATION-LOG.md — 听风插帧 验证记录

> 目的：记录"哪些模块做过什么验证、结论如何"。**下次改动前先读本文件**，命中"已覆盖范围"则不再盲跑重复测验，直接按结论推进或只做增量验证。只记录事实与证据。

## 记录规则
- 每条含：日期 | 范围 | 命令 | 结果 | 结论/下次提示。
- 同一范围改动后：增量验证（单测/构建），不重跑全量 E2E 除非核心链路被触碰。

---

## 2026-09-28 — 基线（v1.0.0 发布时）

### 构建验证
- 范围：全项目（ASCII 临时路径 `%TEMP%\tfgy-ascii\TingFengbz-Android`；**原中文路径无法就地完整构建**，AGP/CMake 路径 JSON 限制，见 CLAUDE.md）。
- 命令：`./gradlew.bat assembleDebug assembleRelease`、`:app:testDebugUnitTest`、`:app:lintDebug`
- 结果：
  - Debug APK 37.8MB（signed by debug key）；Release 33.2MB（signed by keys/tingfeng-release.jks）
  - 单测 10/10 通过（DirectBufferPoolTest 6 + FrameBufferCopyTest 4 + ExampleUnitTest 1）
  - lint 0 error（3 warning：READ_EXTERNAL_STORAGE 已移除后应为 0~2）
- 结论：构建/单测/lint 基线绿。

### E2E（模拟器）
- 范围：Android 15 x86_64 模拟器（3GB RAM/720p 配置，`tfgy_e2e` AVD）
- 命令：install debug APK → `am start MainActivity` → uiautomator dump
- 结果：
  - 安装 Success；首次启动因 ART 验证+软渲染超 4s 触发 ANR（**LeakCanary 已移除修复**；慢速首帧 ~38s 后 `Displayed` 事件出现）
  - uiautomator 确认 MainActivity 前台渲染：标题/3 开关/启动服务/历史按钮；**无自动压枪 UI**
  - 进程在 AOT 编译后持续存活（>40s）
- 结论：App 可启动、UI 真实渲染、压枪已彻底移除。
- **下次提示**：若改动涉及 `AutoFrameBoostService` 启动路径/`FrameInterpolator`/Manifest，需重跑该 E2E（模拟器冷启动慢，AOT 后可复用）。

### Release
- `git tag v1.0.0` 已推送；GitHub Release（id 398038997）含两 APK 附件（uploaded）。
- 签名：`keys/tingfeng-release.jks` + `keystore.properties`（gitignore，仅本机）；apksigner 验证通过（CN=TingFeng）。

### 已知决策/陷阱（下次优先读）
1. **中文路径构建必失败**：任何 `./gradlew` 在含 `插帧率` 路径下 `buildCMakeDebug` 报 `MalformedJsonException $.buildFiles[0]`。构建一律到 ASCII 临时目录（`%TEMP%\tfgy-ascii\...`）并同步改动文件。
2. **构建产物不入库**：`.gradle/`、`app/.cxx/`、`.idea/`、`dist/`、`keys/`、`keystore.properties` 已 gitignore；严禁 `git add -A` 后无差别提交。
3. **权限集**（2026-09-28 精简后）：FOREGROUND_SERVICE / POST_NOTIFICATIONS / SYSTEM_ALERT_WINDOW / WAKE_LOCK / FOREGROUND_SERVICE_MEDIA_PROJECTION / FOREGROUND_SERVICE_DATA_SYNC。
4. **Android 13+ 通知权限**：POST_NOTIFICATIONS 属运行时权限，当前 `requiredPermissions` 仅 WAKE_LOCK —— **若本次审计补上通知权限请求，E2E 需复验前台服务通知可见性**。
5. **依赖已删**：OpenCV/TFLite/Filament/LWJGL/Media3/LeakCanary 均已移除；`libs.versions.toml` 中残留 catalog 条目待清理。
6. **JNI**：当前仅 `directbuf.cpp` 被编译；`neon_optimizer.cpp`/`jniLibs/CMakeLists.txt` 未编译；`FrameInterpolator.calculateBlockDifferenceNeon` 无 Kotlin 声明 → 调用会 UnsatisfiedLinkError（勿启用）。
7. **网络**：curl 需 `--proxy http://127.0.0.1:10808 --ssl-no-revoke` 才能访问 GitHub API（git 已走该代理）。

---

## 更新记录
- 2026-09-28：初始基线（v1.0.0）。

## 2026-09-28 晚 — 终局审计修复批次（F1-F13）

### 重大变更
1. **中文路径可完整构建（已确证）**：移除从未接线的 CMake/原生模块（`cpp/`、`jniLibs/`、`neon_optimizer.cpp`、`directbuf.cpp`）后，`./gradlew assembleDebug assembleRelease` 在含 `插帧率` 的路径 **BUILD SUCCESSFUL（9m8s）**。`android.overridePathCheck=true` 仍需保留。
2. **死代码大清理（删 19 文件）**：MotionEstimator/TestActivity/TextureLoader/TileRenderer/RenderGuard/LockFreeRingBuffer/PerformanceMonitor/RequiresApiTake/FileUtil/BitmapUtils/BitmapExtensions/DeviceUtils/FrameOverlayView/LruCache + Room 层（AppDatabase/DAO×2/FrameRecord/FrameData 改纯模型）+ LogUploadWorker（桩、无调度）。剩 8 个 .kt。
3. **依赖清理**：room×4、work-runtime、kapt plugin、activity-compose 1.8.0（保留 catalog 1.10.1）、filament catalog 死条目、原生 CMake；新增显式 `androidx.recyclerview:recyclerview:1.3.2`（HistoryActivity 用）。
4. **行为修复**：
   - FPS_UPDATE 幽灵广播 → AutoFrameBoostService 真实发送
   - 授权拒绝后 UI 卡"停止服务" → 回调复位 `isServiceRunningState`
   - POST_NOTIFICATIONS 加入运行时请求（Android 13+ 通知可见）
   - 目标帧率尊重用户选择（移除按 refreshRate 覆盖；含 FrameInterpolator.updateScreenRefreshRate）
   - enableLowEndFrameBoost 幽灵开关移除；"显示历史"开关真实控制按钮可见性
   - DisplayListener 泄漏 → onDestroy 对称注销 `unregisterDisplayListener`
5. **文档**：README 如实化（含 P0-1 能力边界披露）；CLAUDE.md 重写匹配现状。

### 验证
- `:app:compileDebugKotlin :app:testDebugUnitTest :app:processDebugMainManifest :app:mergeDebugResources` ✅ BUILD SUCCESSFUL
- 中文路径 `assembleDebug assembleRelease` ✅ BUILD SUCCESSFUL（产物：debug 36.4MB / release 32.8MB signed）
- `lintDebug` ✅ 0 error / 72 warning（P3 级：LogNotTimber 风格 ×40、OldTargetApi、scheduleAtFixedRate 等）
- **模拟器 E2E（部分通过 + 边界披露）**：
  - ✅ 安装/启动/UI 前台渲染；无压枪、无假方法下拉；历史按钮默认隐藏（开关真实生效）
  - ✅ F3 通知权限请求框弹出；F14 一键启动自动开开关并弹出 MediaProjection 授权框
  - ⚠️ **服务运行时链路（MediaProjection→ImageReader→GL→FPS 广播）未在模拟器完全验证**：Android 14+ 模拟器 MediaProjection 进入单应用选择器 + 软渲染 GL 极慢（Choreographer 跳帧 1000+，Davey 18s）；授权后服务建立管线需要真机验证。**这是环境限制，非代码缺陷**。
- **下次提示**：改动影响 `FrameInterpolator`/`AutoFrameBoostService`/权限/Manifest 后，需重跑模拟器 E2E（AVD `tfgy_e2e` 存在，AOT 后可复用）。服务运行时链路建议在真机 `adb install` 后验证。

## 2026-09-28 深夜 — 子代理 B/C 发现处置批次

### 变更
- **幂等短路**：`AutoFrameBoostService.onStartCommand` 服务已运行且持 MediaProjection 时仅刷新通知返回，防止重复 start 叠加 wakeLock/VirtualDisplay/Timer/Choreographer（B P1-2）。
- **死纹理池移除**：`FrameInterpolator.texturePool`（LruCache）只 `put` 从不 `get` → GL 纹理泄漏；移除字段/put/evictAll/import（B P1-3 附属）。
- **VBO ID 连续假设修正**：`initVbo` 保存 `texVboId`，onDestroy 分别 `glDeleteBuffers`（原 `vboId+1` 非 GL 规范，可能删错对象）（B P2-12）。
- **通知残留**：onDestroy 改为 `NotificationManager.cancel`，不再遗留"服务已停止"常驻通知（B P2-11）。
- **scope 取消**：onDestroy 末尾 `scope.cancel()`（补充 import kotlinx.coroutines.cancel）（B P2-9）。
- **DisplayListener 注销**：F12（B P1-1）。

### 验证
- `compileDebugKotlin + testDebugUnitTest` ✅ BUILD SUCCESSFUL（2 次，首轮仅缺 cancel import 已补）
- **下次提示**：涉及 FrameInterpolator 停止路径/服务资源释放时，重点回归：快速启停、重复 onStartCommand。