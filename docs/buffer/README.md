# buffer 状态索引（中间层）

> **用途**：本目录 = **不稳定 / 未定案**（设计稿 · 计划 · 路线图 · 探讨）。
> 新会话**先读本页**，不要逐个文件猜状态；终态按下方「维护判据」三分支分流。
>
> ⚙️ 下方「未定案」表由 `tools/gen_buffer_index.py` **自动生成**——状态摘自各文档头部的
> 「状态：」行（**单一来源**）。改状态 = 改文档头部 + 重跑生成器；`doc_check` 第 12 项盯不一致。
> **状态行只写文档性质，不写进度**（口径见文末「状态行口径」）。
> 判据见 [docs/README.md](../README.md) §1；`doc_check` 第 11 项盯「已实施却留在 buffer」。

<!-- buffer-index:begin（本区由 tools/gen_buffer_index.py 生成；改状态请改各文档头部「状态：」行后重跑本脚本，勿手改本区） -->

## 未定案（保留）

| 文件 | 状态（头部「状态：」行摘录） |
|---|---|
| [2026-10-27-全项目审查报告.md](2026-10-27-全项目审查报告.md) | 原始台账（2026-10-27）—— 全项目审查的观察底稿，不是现状。 |
| [2026-10-29-全项目审查整改计划.md](2026-10-29-全项目审查整改计划.md) | 整改计划（2026-10-29）—— 本文是计划文档，不是现状。 |
| [2026-10-29-测试域梳理.md](2026-10-29-测试域梳理.md) | 分析探讨（2026-10-29）—— 本文是测试域的建议，不是测试规范定案。 |
| [architecture-evolution-roadmap.md](architecture-evolution-roadmap.md) | 未定案 —— 本文是演进编排与排期依据，不是现状描述，也不是已批准的执行计划 |
| [container-menu-introspection-topic.md](container-menu-introspection-topic.md) | 设计论证原稿（2026-11-07 复核）—— 无实施计划，保留查证、翻案与否决 |
| [entity-host-behavior-design.md](entity-host-behavior-design.md) | 已实施 —— 批次 A / B / A2 全部落地（2026-11-12），结论 |
| [framework-evolution-plan.md](framework-evolution-plan.md) | 提案（buffer）——未定案、未排期，别当现状读**。 |
| [gui-interaction-redesign.md](gui-interaction-redesign.md) | 设计稿（已定案未实施）—— E2 交互设计，不是现状。 |
| [living-axe-strip-design.md](living-axe-strip-design.md) | 设计提案 —— 本文供审阅，不是已生效玩法或执行授权。 |
| [living-tnt-explosion-design.md](living-tnt-explosion-design.md) | 设计稿（局部定案，整体实施方案待确认）—— TNT 爆炸规模化提案，不是现状，也 |
| [living-weapon-design.md](living-weapon-design.md) | 设计探讨层（buffer）。⚠️ 描述的是**计划 / 未决**，不是现状。 |
| [open-plan.md](open-plan.md) | 未定案** —— 对外开放计划探讨稿，别当现状读。 |
| [redstone-evolution-roadmap.md](redstone-evolution-roadmap.md) | 路线图 —— 红电架构演进探讨稿，别当现状读。 |
| [活滴水石锥.md](活滴水石锥.md) | 探讨中 —— 滴水石锥设计稿，不是现状。 |
| [活潜影箱实现细节.md](活潜影箱实现细节.md) | 探索性实现提案 —— 不是现状、玩法定案或代码实施授权。 |
| [活物品大战僵尸.md](活物品大战僵尸.md) | 探讨中（2026-10-30 头脑风暴沉淀）。本文描述的全部机制均未实现，别当现 |
| [活魂符坐下互动设计.md](活魂符坐下互动设计.md) | 设计稿（已拍板未实施）—— 活魂符"坐下"互动与行动暂停，不是现状；实施前别当 |
| [活魂符换位玩法设计.md](活魂符换位玩法设计.md) | 设计稿（已拍板未实施）—— 活魂符"换位"动词与食物语义翻转，不是现状；实施前别 |

<!-- buffer-index:end -->

## 已搬 archive（2026-10-28 整理 · 2026-10-31 / 2026-11-11 增补）

| 文件 | 原因 | 现状去哪读 |
|---|---|---|
| [container-anchor-design.md](../archive/container-anchor-design.md) | 已实施（`D-container-15` / `D-container-16`） | [system-design/container-overview.md](../system-design/container-overview.md) §4 |
| [living-backpack.md](../archive/living-backpack.md) | 已实施并真机验收（2026-10-23） | [tech/living-backpack-tech.md](../tech/living-backpack-tech.md) |
| [living-button-container-info.md](../archive/living-button-container-info.md) | 已实施，现状已沉淀 | [system-design/gui-interaction-system.md](../system-design/gui-interaction-system.md) §6.2.2 |
| [living-fluid-review.md](../archive/living-fluid-review.md) | 问题池条目全部清零（2026-10-31） | [tech/living-fluid-tech.md](../tech/living-fluid-tech.md) §3.3 / §0 规则 10 · [TODO 活流体 §9-10](../TODO.md) |
| [container-layout-resolution.md](../archive/container-layout-resolution.md) | 自动学落地为 Tier 1.5；收编部分维持暂缓（2026-10-31） | [TODO §5](../TODO.md) · [infrastructure §6.2](../system-design/living-item-infrastructure.md) |
| [活观测者-design.md](../archive/活观测者-design.md) | 旧「整容器内容签名」方案已被 D-redstone-01 取代（2026-11-11） | [tech/living-redstone-tech.md](../tech/living-redstone-tech.md) §0 规则 8-10 · [decisions D-redstone-01](../decisions.md) |

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
| 未定案、还在讨论 | 留 buffer；头部状态行按文末「状态行口径」只写文档性质 |
| **已定案** | 结论搬稳定层（`tech/` / `system-design/` / `decisions.md`），本文件按总判据分流 |
| **已实施** | 结论已在稳定层 ⇒ 原件**删**；仅「带日期的原始观测」留 `archive/`。同步改**活跃层**所有指针（第 1 项会兜底） |
| 被别的文档取代 | 头部写「已被 X 取代」+ 链接；取代理由提炼进 `decisions.md` 后删原件 |

## 状态行口径（2026-11-08 定）

> ⭐ **这里不写会变的东西。** 状态行只写文档性质（未定案 / 探讨中 / 已定案未实施 /
> 设计论证原稿）与「别当现状读」这类使用边界 —— 它们不随任何动作变化。
>
> 想查做到哪了，走 `TODO.md`（未做）/ `changelog.md`（已做）/ 代码与测试（生效与否），
> **不靠文档自述**：自述的进度会随一次拍板或提交失效，比空白更糟 ——
> 空白只是要你去查，过期断言会让你基于它做事。
