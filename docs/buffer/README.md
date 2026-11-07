# buffer 状态索引（中间层）

> **用途**：本目录 = **不稳定 / 未定案**（设计稿 · 计划 · 路线图 · 探讨）。
> 新会话**先读本页**，不要逐个文件猜状态；终态按下方「维护判据」三分支分流。
>
> ⚙️ 下方「未定案」表由 `tools/gen_buffer_index.py` **自动生成**——状态摘自各文档头部的
> 「状态：」行（**单一来源**）。改状态 = 改文档头部 + 重跑生成器；`doc_check` 第 12 项盯不一致。
> 判据见 [docs/README.md](../README.md) §1；`doc_check` 第 11 项盯「已实施却留在 buffer」。

<!-- buffer-index:begin（本区由 tools/gen_buffer_index.py 生成；改状态请改各文档头部「状态：」行后重跑本脚本，勿手改本区） -->

## 未定案（保留）

| 文件 | 状态（头部「状态：」行摘录） |
|---|---|
| [2026-10-27-全项目审查报告.md](2026-10-27-全项目审查报告.md) | 台账 —— 待拍板 10 项已全部处置（2026-10-28）；文档冲突 ~50 |
| [2026-10-29-全项目审查整改计划.md](2026-10-29-全项目审查整改计划.md) | 进行中（0/1/2/4/5 完成 · 3 待复核 · 6 设计已定待实现）—— |
| [2026-10-29-测试域梳理.md](2026-10-29-测试域梳理.md) | 进行中（盘点完成，策略待拍板）—— 本文是测试域的分析与建议，不是测试规范定案。 |
| [architecture-evolution-roadmap.md](architecture-evolution-roadmap.md) | 未定案 —— 本文是演进编排与排期依据，不是现状描述，也不是已批准的执行计划 |
| [container-menu-introspection-topic.md](container-menu-introspection-topic.md) | 设计论证原稿（2026-11-07 复核）—— 无实施计划，保留查证、翻案与否决 |
| [framework-benchmark.md](framework-benchmark.md) | 未定案**，本文只是**对标记录**，不是执行排期 |
| [framework-evolution-plan.md](framework-evolution-plan.md) | 提案（buffer）——未定案、未排期，别当现状读**。 |
| [gui-interaction-redesign.md](gui-interaction-redesign.md) | 第一、二步已实施（2026-10-10，见 §6 表 ✅）；步骤 3/4（活指针 |
| [living-tnt-explosion-design.md](living-tnt-explosion-design.md) | 未定案** —— 量化分析已完成 · 主设计已定稿（惰性谓词 + 纪元水印）· |
| [living-weapon-design.md](living-weapon-design.md) | 设计探讨层（buffer）。⚠️ 描述的是**计划 / 未决**，不是现状。 |
| [open-plan.md](open-plan.md) | 部分落地** —— Q1/Q2 已拍板、A1/A2/D1/D2 已完成（D1 见 |
| [redstone-evolution-roadmap.md](redstone-evolution-roadmap.md) | 路线图 —— §1 SensorPort 已完成；规划①③截至 2026-10- |
| [活滴水石锥.md](活滴水石锥.md) | 探讨中 —— 待拍板 7 项（§5）；本文是设计稿，不是现状。 |
| [活潜影箱实现细节.md](活潜影箱实现细节.md) | 探讨中 —— 活潜影箱**兼容所有活物品**（而非当前阶段已实现的活物品），本随 |
| [活物品大战僵尸.md](活物品大战僵尸.md) | 探讨中（2026-10-30 头脑风暴沉淀）。本文描述的全部机制均未实现，别当现 |
| [活观测者-design.md](活观测者-design.md) | 探讨中 —— 待拍板 4 项（§6）；本文是设计稿，不是现状。 |
| [活魂符-design.md](活魂符-design.md) | 设计细化中 —— 玩法已定；技术路线见§8，运行接缝待验证，未实现。 |

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

> ⭐ **第 0 步（2026-11-07 补）：先问「生效了没」** —— **已定案但未实施的设计 ⇒ 留 buffer**。
> 稳定层只放已生效的口径；把未落地的定案设计搬进去 = 把未实现写成现状（铁律 0 头号误导）。
> 这类文档由 `TODO.md` 记一行 + 指针，正文留在设计稿（实例：`gui-interaction-redesign.md`
> §5 Q1~Q6 已定案、步骤 3/4 未实现，`TODO.md` §GUI 交互已登记）。
> **过了这一关，才用下面的三分支。**
>
> ⭐ **总判据（2026-11-07 定）**：这份内容，读者找它时问的是「**哪天**」还是「**为什么**」？
>
> - 问「**哪天**」⇒ `archive/`：时间线 / 带日期的原始观测（真机验收、审查台账、事故现场记录）。
> - 问「**为什么**」⇒ 稳定层：`decisions.md`（决策与否决留痕）/ `tech` 定案记录 / `system-design` 契约。
> - **都不问** ⇒ **删**：可重推的复述、已兑现的落地步骤、清单型内容。
>
> ⚠️ **「怕丢历史」不是归档的理由**：`git mv` 与 `rm` 在 git 里留痕等价
> （`git log --all -- 文件名` / `git show 旧提交:文件路径` 都能找回）。
> 归档真正的价值只有一个：**让内容在当前工作区仍可读** —— AI 零记忆，不会去 `git log` 考古；
> 一份零活跃层引用的 archive 文件，对新会话等于不存在。
> ⚠️ 反过来：**否决过的方案 / 用户拍板 / 事故根因是不可重推的**，删之前必须先在稳定层安家。
>
> ⚠️ **「结论已在稳定层」必须由代码或测试复核** —— 文档互引只证明「有人写过」，
> 不证明「现在仍成立」。判断前对带行为承诺的条目（签名 / 四态 / 不变量 / 边界）
> 抽查源码与守卫测试名；本项目已有实证：审查台账 B5 / B6 / B12 指出的旧表述至今仍挂在 tech 文档。

| 文件处于… | 动作 |
|---|---|
| 未定案、还在讨论 | 留 buffer，头部写「状态：未定案 / 探讨中」 |
| **已定案** | 结论搬稳定层（`tech/` / `system-design/` / `decisions.md`），本文件按总判据分流 |
| **已实施** | 结论已在稳定层 ⇒ 原件**删**；仅「带日期的原始观测」留 `archive/`。同步改**活跃层**所有指针（第 1 项会兜底） |
| 被别的文档取代 | 头部写「已被 X 取代」+ 链接；取代理由提炼进 `decisions.md` 后删原件 |
