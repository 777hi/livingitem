# buffer 状态索引（中间层）

> **用途**：本目录 = **不稳定 / 未定案**（设计稿 · 计划 · 路线图 · 探讨）。
> 定案后搬稳定层，**已实施搬 `archive/`**。新会话**先读本页**，不要逐个文件猜状态。
>
> ⚙️ 下方「未定案」表由 `tools/gen_buffer_index.py` **自动生成**——状态摘自各文档头部的
> 「状态：」行（**单一来源**）。改状态 = 改文档头部 + 重跑生成器；`doc_check` 第 12 项盯不一致。
> 判据见 [docs/README.md](../README.md) §1；`doc_check` 第 11 项盯「已实施却留在 buffer」。

<!-- buffer-index:begin（本区由 tools/gen_buffer_index.py 生成；改状态请改各文档头部「状态：」行后重跑本脚本，勿手改本区） -->

## 未定案（保留）

| 文件 | 状态（头部「状态：」行摘录） | 最后动过 |
|---|---|---|
| [2026-10-27-全项目审查报告.md](2026-10-27-全项目审查报告.md) | 台账 —— 待拍板 10 项已全部处置（2026-10-28）；文档冲突 ~50 | 2026-10-31 |
| [2026-10-29-全项目审查整改计划.md](2026-10-29-全项目审查整改计划.md) | 进行中（0/1/2/4/5 完成 · 3 待复核 · 6 设计已定待实现）—— | 2026-11-01 |
| [2026-10-29-测试域梳理.md](2026-10-29-测试域梳理.md) | 进行中（盘点完成，策略待拍板）—— 本文是测试域的分析与建议，不是测试规范定案。 | 2026-10-30 |
| [code-doc-alignment.md](code-doc-alignment.md) | A1~A8 已收口（2026-10-27 台账）；仍被 AGENTS / TOD | 2026-10-31 |
| [container-menu-introspection-topic.md](container-menu-introspection-topic.md) | 部分完成（2026-10-21）** —— 用户拍板「B+B」：位置广播经查证证 | 2026-10-27 |
| [framework-benchmark.md](framework-benchmark.md) | 未定案**，本文只是**对标记录**，不是执行排期 | 2026-10-08 |
| [framework-evolution-plan.md](framework-evolution-plan.md) | 提案（buffer）——未定案、未排期，别当现状读**。 | 2026-10-23 |
| [gui-interaction-redesign.md](gui-interaction-redesign.md) | 第一、二步已实施（2026-10-10，见 §6 表 ✅）；步骤 3/4（活指针 | 2026-10-31 |
| [living-tnt-explosion-design.md](living-tnt-explosion-design.md) | 未定案** —— 量化分析已完成 · 主设计已定稿（惰性谓词 + 纪元水印）· | 2026-10-31 |
| [living-weapon-design.md](living-weapon-design.md) | 设计探讨层（buffer）。⚠️ 描述的是**计划 / 未决**，不是现状。 | 2026-10-31 |
| [move-api-minimal.md](move-api-minimal.md) | 框架侧已实现（2026-11-03）**，等活塞接入验证后转稳定层。 | 2026-11-03 |
| [open-plan.md](open-plan.md) | 部分落地** —— Q1/Q2 已拍板、A1/A2/D1/D2 已完成（D1 见 | 2026-10-31 |
| [power-refactoring-plan.md](power-refactoring-plan.md) | 进行中** —— 步骤 1 ✅ · **步骤 4 第 1~5 刀 ✅** · | 2026-10-10 |
| [redstone-evolution-roadmap.md](redstone-evolution-roadmap.md) | 路线图 —— §1 SensorPort 已完成；规划①③截至 2026-10- | 2026-10-31 |
| [render-state-broadcast.md](render-state-broadcast.md) | 未定案**，本文只是形状提案，不是排期。 | 2026-10-26 |
| [活活塞.md](活活塞.md) | 方向、runtime与函数适配已测；未全局启用，压送与渲染未接。 | 2026-11-03 |
| [活滴水石锥.md](活滴水石锥.md) | 探讨中 —— 待拍板 7 项（§5）；本文是设计稿，不是现状。 | 2026-10-30 |
| [活潜影箱实现细节.md](活潜影箱实现细节.md) | 探讨中 —— 活潜影箱**兼容所有活物品**（而非当前阶段已实现的活物品），本随 | 2026-10-28 |
| [活物品大战僵尸.md](活物品大战僵尸.md) | 探讨中（2026-10-30 头脑风暴沉淀）。本文描述的全部机制均未实现，别当现 | 2026-10-31 |

<!-- buffer-index:end -->

## 已搬 archive（2026-10-28 整理 · 2026-10-31 增补三行）

| 文件 | 原因 | 现状去哪读 |
|---|---|---|
| [container-anchor-design.md](../archive/container-anchor-design.md) | 已实施（`D-container-15` / `D-container-16`） | [system-design/container-overview.md](../system-design/container-overview.md) §4 |
| [living-backpack.md](../archive/living-backpack.md) | 已实施并真机验收（2026-10-23） | [tech/living-backpack-tech.md](../tech/living-backpack-tech.md) |
| [living-button-container-info.md](../archive/living-button-container-info.md) | 已实施，现状已沉淀 | [system-design/gui-interaction-system.md](../system-design/gui-interaction-system.md) §6.2.2 |
| [living-fluid-review.md](../archive/living-fluid-review.md) | 问题池条目全部清零（2026-10-31） | [tech/living-fluid-tech.md](../tech/living-fluid-tech.md) §3.3 / §0 规则 10 · [TODO 活流体 §9-10](../TODO.md) |
| [container-layout-resolution.md](../archive/container-layout-resolution.md) | 自动学落地为 Tier 1.5；收编部分维持暂缓（2026-10-31） | [TODO §5](../TODO.md) · [infrastructure §6.2](../system-design/living-item-infrastructure.md) |
| [living-tool-design.md](../archive/living-tool-design.md) | 回放链路已实施；残留 J/H 组登记 TODO（2026-10-31，首次按落地同步表流程清点） | [tech/living-tool-tech.md](../tech/living-tool-tech.md) · [TODO 活工具 · 系列扩展](../TODO.md) |

## 维护判据

| 文件处于… | 动作 |
|---|---|
| 未定案、还在讨论 | 留 buffer，头部写「状态：未定案 / 探讨中」 |
| **已定案** | 结论搬稳定层（`tech/` / `system-design/` / `decisions.md`），本文件移走 |
| **已实施** | 移 `archive/`（`git mv` 保历史），同步改**活跃层**所有指针（第 1 项会兜底） |
| 被别的文档取代 | 头部写「已被 X 取代」+ 链接，本体可移 archive |
