---
name: tingfeng-dev
description: 听风插帧(TFGY999)项目专属开发/审计工作流。改动本仓库代码前必须先调用：读取验证记录判断过时、确认构建命令与陷阱、遵循屏幕隐私与无伪实现红线、按审计清单验收。
---

# TingFeng 开发工作流（听风插帧）

作用于 `com.example.tfgy999` Android 补帧项目。**任何代码改动前必须先执行"前置读取"**，判断上下文是否过时，再决定直接编码或先更新文档。

## 前置读取（每次会话开始 / 任何改动前）

按序读取并判断过时：

1. `VERIFICATION-LOG.md` —— 验证记录（哪些模块验证过、结论、命令、陷阱）。命中"已覆盖范围"则只做增量验证，不盲目重跑。
2. `CLAUDE.md` —— 项目规则/技术栈/架构/边界（含 P0-1 能力边界、无原生模块、权限最小化）。
3. `workflow_status.md` —— 当前任务状态与问题清单；`docs/changes-report.html` —— 变更审计报告。
4. 若以上与当前代码不一致 → 先更新文档再编码；若过时（提及已删文件/类/依赖）→ 修正后继续。

## 构建与验证命令（中文路径可直接构建）

```bash
./gradlew.bat assembleDebug       # Debug APK（debug key 签名）
./gradlew.bat assembleRelease      # Release APK（keystore.properties 存在时签名）
./gradlew.bat test                 # JVM 单测（DirectBufferPoolTest / FrameBufferCopyTest）
./gradlew.bat lint                 # lint（目标 0 error）
```

- `android.overridePathCheck=true` 必须保留（中文路径）。
- 无 CMake/NDK/原生模块；勿引入 JNI/CMake（会复活构建障碍）。
- Release 签名：`keystore.properties`（gitignore）+ `keys/`；缺失自动 unsigned。

## 红线（宪法）

1. **屏幕隐私**：捕获画面只在设备内，不落盘/上传/日志。
2. **无伪实现**：禁占位/僵尸广播（注册无发送方）/假设置（UI 无效果）。加新广播必须"收发一一对应"。
3. **热路径纪律**：渲染/上传线程禁阻塞与每帧大分配；帧内存走 `DirectBufferPool`。
4. **权限最小化**：manifest 权限须有代码使用；Android 13+ 通知权限在 `MainActivity.requiredPermissions`。
5. **能力边界**：插值画面未回投屏幕是**平台约束 + 产品决策**（P0-1），勿擅自大改渲染目标。

## 审计检查清单（改完核心链路后过一遍）

- [ ] 启动/停止服务快速循环无崩溃、无资源累积（DisplayListener/Choreographer/Timer 对称释放）
- [ ] 广播链路收发一一对应
- [ ] 每个 UI 开关/按钮有真实行为与反馈（空态/错误态/禁用态）
- [ ] Room 不存在（已移除）；数据走 JSON 文件链路
- [ ] 单元测试命中改动逻辑（纯函数可测）
- [ ] `assembleDebug` + `test` 通过；影响权限/Manifest/服务时补模拟器 E2E
- [ ] `VERIFICATION-LOG.md` 追加本次验证范围与结论

## 常用陷阱速查

| 项 | 现状 |
|---|---|
| 中文路径构建 | ✅ 可用（CMake 已移除）；overridePathCheck 保留 |
| 目标帧率 | 尊重用户选择（服务 `coerceIn(24,240)`），勿按刷新率覆盖 |
| FPS 广播 | `AutoFrameBoostService.updateFloatingWindow` 同时发 `FPS_UPDATE` 与 `FLOATING_WINDOW_UPDATE` |
| 悬浮窗 | FloatingWindowService 前台服务 + WindowManager；数据来自本地广播 |
| 历史数据 | `frame_data_*.json`，每 60s 落盘有上限；HistoryActivity 读取 |
| 模拟器服务链路 | MediaProjection 授权在模拟器受限，服务运行时链路建议真机验证 |