# 实施计划：001-终局闭环总审计（Final Audit & Hardening）

## 技术栈与约束

- 纯 Android 客户端（Kotlin/Compose/GLES/Room/WorkManager），单 `:app` 模块。
- **不适用项声明**（用户提及的高并发 SaaS 栈）：本项目无服务端，Load Balancer / Redis / Kafka / CDN / Replication / Sharding / Rate Limiting / Circuit Breaker / 微服务可观测性**不适用**。真正适用的等价物列于下：
  | 概念 | 本项目对应物 |
  |---|---|
  | 高并发 | 高帧率热路径（每帧 60–144Hz）的内存/线程模型 |
  | 慢查询猎杀 | Room 查询与 JSON 落盘是否 O(n²)/全量重写 |
  | 降级/熔断 | EGL 资源恢复 `recoverEGLResources`、池耗尽回退 |
  | 可观测性 | Timber 日志、FPS 监控、前台服务通知 |
  | 健康检查 | 服务自检（MediaProjection/VirtualDisplay 失效自停） |

## 审计流水线（按节点推进，依序验收）

```
P-0 重建事实基础        → git/文件/权限/依赖真实盘点
P-1 反向审计(自我抨击)   → 列出"我之前可能的伪闭环"清单
P-2 全量深度扫描        → 主控精读核心文件 + 3 个并行独立审查子代理(六维)
P-3 问题分级汇总        → P0/P1/P2/P3 + 需求追踪矩阵
P-4 修复实施            → 按 P0→P1→P2 逐条修复, 每节点验证
P-5 独立复验            → 原审查线程复验修复点, 循环直至通过/明确卡点
P-6 真实验证            → 单测/lint/构建/模拟器 E2E(启动服务链路)
P-7 文档同步            → README/CLAUDE.md/宪法/.specify/记忆
P-8 交付物              → HTML 报告+测验, workflow→skill 沉淀
```

## 子代理编排（并行）

| 节点 | 子代理 | 职责（只审不改） | 输出 |
|---|---|---|---|
| P-2a | 需求/功能审计 | UI 每个控件是否真实联通；README vs 实现；伪实现猎手 | 问题清单 |
| P-2b | 逻辑/边界/生命周期审计 | 并发/竞态/资源释放/边界/异常路径/快速启停 | 问题清单 |
| P-2c | 质量/测试/安全审计 | 全量 lint、单测缺口、依赖/死代码/安全残余/凭证 | 问题清单 |

主控同步精读 `AutoFrameBoostService`/`FrameInterpolator`/`MainActivity`/`HistoryActivity`/`FloatingWindowService` 交叉核验。
P-5 由 2a/2b/2c 复验修复点，主控按其结果决定继续修或收口。

## 修复优先级策略

1. P0：核心链路断裂 / 崩溃 / 数据破坏 / 安全 / 无法构建运行
2. P1：高概率 bug、生命周期泄漏、伪实现、文档与事实严重不一致
3. P2：体验、提示、空态、次要链路
4. P3：建议级（ABI 拆分、UI 美化、性能微调等），记录不阻塞

## 兼容性护栏

- 不动 `minSdk/targetSdk`、包名、`applicationId`。
- 每项修复保持"只影响修复点及其直接调用链"；涉及共享类（池/插值器）改动必须重跑单测+构建。
- 屏幕隐私底线（不落盘/不上传）作为回归红线。