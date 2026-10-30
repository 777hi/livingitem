# buffer 状态索引（中间层）

> **用途**：本目录 = **不稳定 / 未定案**（设计稿 · 计划 · 路线图 · 探讨）。
> 定案后搬稳定层，**已实施搬 `archive/`**。新会话**先读本页**，不要逐个文件猜状态。
>
> ⚠️ 本表是**导航快照**，状态以各文件头部的「状态：」行为准（改状态时同步本表）。
> 判据见 [docs/README.md](../README.md) §1；`doc_check` 第 11 项盯「已实施却留在 buffer」。

## 未定案（保留）

| 文件 | 状态 | 备注 |
|---|---|---|
| [code-doc-alignment.md](code-doc-alignment.md) | A1~A8 已收口（2026-10-27 审查台账） | 仍被 AGENTS / TODO / tech 指针引用；引用清零后可归档 |
| [2026-10-27-全项目审查报告.md](2026-10-27-全项目审查报告.md) | 待拍板 10 项已全部处置（2026-10-28） | **不可归档**：仍有文档冲突 ~50 处 / P3 ~40 项 / 测试缺口 6 项未结 |
| [2026-10-29-全项目审查整改计划.md](2026-10-29-全项目审查整改计划.md) | 进行中（批次 0 未开始） | 2026-10-29 审查问题台账 + 分批实施步骤；进度看 §8 |
| [2026-10-29-测试域梳理.md](2026-10-29-测试域梳理.md) | 进行中（盘点完成，策略待拍板） | 测试分布 / 覆盖矩阵 / 接缝与不变量缺口；建议见 §6 |
| [container-layout-resolution.md](container-layout-resolution.md) | 编排部分暂停 / 自动学部分复活为独立一步 | 待做 |
| [container-menu-introspection-topic.md](container-menu-introspection-topic.md) | 部分完成；列数探测未落地 | 唯一未落地项 |
| [framework-benchmark.md](framework-benchmark.md) | 未定案（对标记录，不是排期） | 参考输入 |
| [framework-evolution-plan.md](framework-evolution-plan.md) | 未定案、未排期 | 提案层 |
| [gui-interaction-redesign.md](gui-interaction-redesign.md) | 未定案、未实现 | 计划，勿当现状 |
| [living-fluid-review.md](living-fluid-review.md) | 探讨中（2026-10-30 建；2026-10-31 力场模型已结 4 条） | 推动与细节问题池，剩余 10 条；逐条拍板后再动代码 |
| [living-tnt-explosion-design.md](living-tnt-explosion-design.md) | 主设计定稿，**待拍板 3 项**（§7） | 代码未动 |
| [living-tool-design.md](living-tool-design.md) | 设计已收敛、实施中（问题池） | 技术结论以 `tech/living-tool-tech.md` 为准 |
| [living-weapon-design.md](living-weapon-design.md) | 设计池；蓄力型未定 | 近战核心链路已实现 |
| [open-plan.md](open-plan.md) | 部分落地（A3 / D1 / Q3~Q6 待办） | 对外开方计划 |
| [power-refactoring-plan.md](power-refactoring-plan.md) | 进行中（步骤 3 待做） | 红电包收口 |
| [redstone-evolution-roadmap.md](redstone-evolution-roadmap.md) | 路线图（2026-09） | AGENTS 子系统索引指向它 |
| [render-state-broadcast.md](render-state-broadcast.md) | 未定案（设计探讨） | 代码未动 |
| [活物品大战僵尸.md](活物品大战僵尸.md) | 探讨中（可行性审计完成 §10，MVP 口径已定 §8） | PvZ 式玩法；投影竞技场 + 桥实体；机制全部未实现 |
| [活潜影箱实现细节.md](活潜影箱实现细节.md) | 探讨中 | 随活物品扩展而生长的文档 |

## 已搬 archive（2026-10-28 整理）

| 文件 | 原因 | 现状去哪读 |
|---|---|---|
| [container-anchor-design.md](../archive/container-anchor-design.md) | 已实施（`D-container-15` / `D-container-16`） | [system-design/container-overview.md](../system-design/container-overview.md) §4 |
| [living-backpack.md](../archive/living-backpack.md) | 已实施并真机验收（2026-10-23） | [tech/living-backpack-tech.md](../tech/living-backpack-tech.md) |
| [living-button-container-info.md](../archive/living-button-container-info.md) | 已实施，现状已沉淀 | [system-design/gui-interaction-system.md](../system-design/gui-interaction-system.md) §6.2.2 |

## 维护判据

| 文件处于… | 动作 |
|---|---|
| 未定案、还在讨论 | 留 buffer，头部写「状态：未定案 / 探讨中」 |
| **已定案** | 结论搬稳定层（`tech/` / `system-design/` / `decisions.md`），本文件移走 |
| **已实施** | 移 `archive/`（`git mv` 保历史），同步改**活跃层**所有指针（第 1 项会兜底） |
| 被别的文档取代 | 头部写「已被 X 取代」+ 链接，本体可移 archive |
