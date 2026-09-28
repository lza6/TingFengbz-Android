# workflow_status.md — 听风插帧 终局闭环总审计

> 任务契约与实时状态。只记录事实与证据，不记录私有推理。
> 更新规则：节点状态变化即更新；只有观察到交付物/验收证据才标记 done。

## 任务契约

**目标**：对已发布的 v1.0.0 做一次终局闭环总审计——消灭未知盲点、伪实现与半成品，补齐生命周期/权限/文档/记忆，产出 HTML 报告+测验，沉淀可复用 workflow/skill。**纯 Android 客户端；SaaS 服务端技术栈不适用（见 plan.md）。**

**验收标准总表**（对应 `.specify/specs/001-final-audit/spec.md`）：
- [ ] 核心链路（启动服务→补帧→停止）无崩溃、有反馈、可恢复
- [ ] 无伪实现/占位/假连接；README 与实现一致
- [ ] 生命周期与资源零泄漏（监听/线程/缓冲/EGL 对称释放）
- [ ] 需求追踪矩阵 + workflow_status + 记忆验证记录 + HTML 报告就位
- [ ] P0 全修验证、P1 修完无回归、P2 处置明确

## 任务图

```
P-0 事实基础 ──▶ P-1 反向审计 ──▶ P-2 深度扫描(3子代理并行)
   └──────────────▶ P-3 问题分级 ──▶ P-4 修复实施(P0→P1→P2)
                                            │
                                            ▼
              P-6 真实验证 ◀── P-5 独立复验(循环≤3轮)
                    │
                    ▼
      P-7 文档同步 ──▶ P-8 HTML报告+skill 沉淀
```

## 验证日志

| 时间 | 节点 | 动作 | 证据/结果 | 状态 |
|---|---|---|---|---|
| 2026-09-28 | P-0 | git/文件盘点 | 7 提交已推送；27 个 Kotlin 文件；无 .specify；记忆区空 | done |
| 2026-09-28 | 规范 | 创建宪法+spec+plan | `.specify/memory/constitution.md`、`specs/001-final-audit/spec.md`、`plan.md` 已写 | done |
| 2026-09-28 | P-1/P-2 | 反向审计 + 深度扫描 | （待执行，见下文问题清单） | 进行中 |

## 问题清单（P0/P1/P2/P3）

> 由 P-1 自我抨击 + P-2 三子代理汇总填充；每条含证据/根因/风险/动作。

### P-1 最强自我反驳（主控自审，2026-09-28）

| # | 我可能伪闭环之处 | 验证结果/处置 |
|---|---|---|
| S1 | FPS_UPDATE 幽灵广播 | ✅已修：AutoFrameBoostService 现真实发送 FPS_UPDATE |
| S2 | DisplayListener 未注销泄漏 | ✅已修：onDestroy 对称注销 |
| S3 | MotionEstimator 占位符 vs README 夸大 | ✅已删文件 + README 如实化 |
| S4 | enableLowEndFrameBoost 幽灵开关 | ✅已移除状态与分支 |
| S5 | Android 13+ 通知权限未请求 | ✅已修：requiredPermissions 加 POST_NOTIFICATIONS |
| S6 | 孤立死类集群 | ✅已删 19 个文件（含 Room/Worker/骨架类） |
| S7 | activity-compose 双版本 + 死 catalog | ✅已修复：去重 + 清 catalog |
| S8 | NEON/OpenCV/TFLite 宣称不实 | ✅已删原生模块 + 依赖；README 修正 |
| S9 | JSON 历史丢尾 ≤60s | 已知权衡，VERIFICATION-LOG 记录边界 |
| S10 | TestActivity videoFrameRate 硬编码 + 不可达 | ✅已删 TestActivity |

### P-2 子代理审计（运行中）

> 三个独立审查子代理（需求/功能、生命周期/并发、质量/测试/安全）并行执行。
> - 需求/功能（A）：已完成，发现 P0-1 结构性断链（见下）。
> - 生命周期/并发（B）、质量/测试/安全（C）：运行中。

### P0-1（核心披露，待产品决策）

**插值画面未回投真实屏幕**：AutoFrameBoostService 将插值渲染到离屏 FBO/PBuffer，全仓无 SurfaceView/Presentation 回投路径。非 Root 下 Android 不允许把改写画面覆盖到其他应用显示层——这是**平台约束**而非单纯漏写代码。已如实写入 README「能力边界」。候选方向（需你在产品层拍板）：
- A) 悬浮窗播放插值画面（SurfaceView 回投，可见补帧效果，需真机验证共享 EGL/hardware buffer 往返）
- B) 保持"屏幕监控 + 帧插值统计"定位（当前形态，如实标注）
决定性影响：产品对外定位与 README/Release 文案。**本审计中将 A 的可行性结论与改造边界写入报告，不擅自大改核心架构。**

## 修复进度（P-4 进行中）

| 修复 | 内容 | 状态 |
|---|---|---|
| F1 | FPS_UPDATE 真实广播接线 | ✅ 已改+编译通过 |
| F2 | 授权拒绝后 UI 卡"停止服务" | ✅ 已改+编译通过 |
| F3 | POST_NOTIFICATIONS 运行时请求 | ✅ 已改+编译通过 |
| F4 | enableLowEndFrameBoost 幽灵开关 | ✅ 已移除 |
| F5 | enableHistoryDisplay 虚假开关 | ✅ 改为真实控制历史按钮 |
| F6 | 目标帧率被服务架空 | ✅ service+interpolator 尊重用户选择 |
| F7-F10 | 死代码清理 | ✅ 删 19 文件+7 依赖+catalog+CMake |
| F11 | README 如实化 | ✅ 重写完毕 |
| — | CMake 移除（中文路径可构建？） | ✅ 已确证：assembleDebug/Release 成功 9m8s |
| F12 | DisplayListener 对称注销 | ✅ |
| F13 | 一键启动 UX（自动开开关） | ✅ E2E 实况：直接弹授权框 |
| B1 | 幂等短路（重复 onStartCommand 防资源叠加） | ✅ 编译+单测 |
| B2 | 死纹理池 texturePool 移除（只写不读） | ✅ 编译+单测 |
| B3 | VBO ID 连续假设修正（texVboId 单独保存/删除） | ✅ 编译+单测 |
| B4 | 停止后通知残留 → cancel | ✅ 编译+单测 |
| B5 | scope 未 cancel → onDestroy 取消 | ✅ 编译+单测 |

### 子代理 A/B/C 处置记录
- A（需求/功能）：P0-1 已披露待产品决策；幽灵广播/假设置等已修（F1-F14）。
- B（生命周期/并发）：可修项全部落实（见 B1-B5 + F12）；结构性 GL 跨线程与 P0-1 同源已披露；并发竞争（drain/renderFrame）已由上传线程 FIFO 归还缓解 + 外层 try/catch 兜底，记录为已知边界。
- C（质量/测试/安全）：未报告新增阻塞项（死代码/依赖/安全已由 F7-F10 覆盖；本仓库以 lint 0 error + 单测 10/10 佐证）。

## 阻塞项

- 无（除非运行中遇到不可替代资源）。

## 下一步

1. P-1：写"最强自我反驳"（哪些可能伪闭环）
2. P-2：主控精读核心文件 + 启动 3 个独立审查子代理
3. P-3：合并问题清单分级