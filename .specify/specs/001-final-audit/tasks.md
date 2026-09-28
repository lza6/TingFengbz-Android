# 实现任务：001-终局闭环总审计

> 状态标注：`[x]` = 已实现并验证；`[ ]` = 待办；`[P]` = 可并行。依赖与验收标准见 `spec.md`/`plan.md`。

## Phase 1: 规范与基线（全部 ✅）

- [x] 1.1 建立 `.specify` 宪法 / spec / plan
  - **Done**: constitution.md, spec.md, plan.md, workflow_status.md
- [x] 1.2 建立 VERIFICATION-LOG.md 验证记录（下次优先读）
- [x] 1.3 基线盘点（git 状态、文件清单、记忆区）

## Phase 2: 审计扫描（P-1/P-2）✅

- [x] 2.1 反向审计（10 项自我反驳）→ 6 项证实
- [x] 2.2 [P] 子代理 A：需求/功能审计 → 报告 P0-1 + 幽灵广播等
- [ ] 2.3 [P] 子代理 B：生命周期/并发审计（进行中）
- [ ] 2.4 [P] 子代理 C：质量/测试/安全审计（进行中）
- [x] 2.5 主控精读核心文件交叉核验

## Phase 3: 修复实施（P-4）✅ 大部分

- [x] 3.1 F1 FPS_UPDATE 真实广播接线
- [x] 3.2 F2 授权拒绝后 UI 状态复位
- [x] 3.3 F3 POST_NOTIFICATIONS 运行时请求
- [x] 3.4 F4/F5 幽灵开关清理与真实化（enableLowEndFrameBoost / 历史按钮）
- [x] 3.5 F6 目标帧率尊重用户选择（service + interpolator 双层）
- [x] 3.6 F7-F10 死代码清理（19 文件 + Room + Worker + 依赖清理）
- [x] 3.7 F11 移除 CMake/原生模块（中文路径可构建）
- [x] 3.8 F12 DisplayListener 对称注销
- [x] 3.9 F13 README/CLAUDE.md 如实化
- [ ] 3.10 合并子代理 B/C 发现并修复（待其返回）
- [ ] 3.11 P0-1 产品决策记录与 README「能力边界」复盘（待：用户拍板方向 A/B）

## Phase 4: 真实验证（P-4/P-6）

- [x] 4.1 `compileDebugKotlin + testDebugUnitTest + mergeDebugResources` ✅
- [x] 4.2 中文路径 `assembleDebug assembleRelease` ✅（9m8s）
- [x] 4.3 `lintDebug` ✅ 0 error / 72 warning（P3 级）
- [ ] 4.4 模拟器 E2E 复验（AVD tfgy_e2e，AOT 后安装修复版 APK，验证启动/服务/UI/幽灵广播链路）
- [ ] 4.5 子代理复验循环（B/C 返回后 P-5）

## Phase 5: 交付

- [ ] 5.1 HTML 报告（上下文/本能/改动清单 + 底部测验）
- [ ] 5.2 项目 workflow/skill 沉淀
- [ ] 5.3 git 提交与推送（含本轮修复 + 文档）
- [ ] 5.4 dist/ APK 刷新 + （可选）Release 附件更新

## Notes
- `[P]` 表示可并行；依赖链：3.10 依赖 2.3/2.4；4.4 依赖 3.x 全部完成。
- 验收口径：核心链路（启动服务→统计→停止）在模拟器进程稳定且无幽灵链路；P0 全修；P1 无回归；文档与实现一致。