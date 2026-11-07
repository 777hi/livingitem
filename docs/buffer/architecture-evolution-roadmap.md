# 架构演进与重构路线图（总纲）

*创建: 2026-11-06 · 状态: 未定案 —— 本文是演进编排与排期依据，不是现状描述，也不是已批准的执行计划*

> ⚠️ **中间层文档**：本文**不新增设计**。每条路线的正文在既有文档，本文只回答
> 「**先做什么 / 什么条件下才做 / 做完凭什么算完 / 哪些明确不做**」。
> 现状以 `docs/system-design/` 与代码为准；需求池唯一入口仍是 [TODO.md](../TODO.md)（D-doc-06），
> 本文的条目表**不是第二份需求池**。
>
> 📄 被编排的文档：[framework-benchmark](framework-benchmark.md) ·
> [framework-evolution-plan](framework-evolution-plan.md) · [open-plan](open-plan.md) ·
> [redstone-evolution-roadmap](redstone-evolution-roadmap.md) ·
> [2026-10-29 整改计划](2026-10-29-全项目审查整改计划.md) ·
> [TODO.md](../TODO.md)

---

## §0 现状基线（2026-11-06 实测）

| 维度 | 实测 | 复算 |
|---|---|---|
| 主源码 | **453 文件 / 64,077 行** | `find src/main -name "*.java" \| wc -l`；`-exec cat {} + \| wc -l` |
| 测试 | **210 文件 / 38,525 行 / 1659 用例**（0 失败、1 跳过、200 类） | `python tools/doc_check.py` 第 2/3 项 |
| 文档 | 109 篇；`AGENTS.md` **18,424 / 20,000 字符** | `doc_check` 第 5 项 |
| 层纪律 | R1 **19 条 / 7 对**（存量、棘轮接受）；R2 / **R3 0** / R4 / R5 **全 0** | `python tools/check_layers.py` |
| 文档门禁 | **15 项全绿、0 警告** | `python tools/doc_check.py` |
| Mixin | 24 文件 / 1,894 行；最大 **271 行**；`@Accessor/@Invoker` 7、`@Inject` 50 | `find src/main -path "*mixin*" -name "*.java" -exec wc -l {} +` |
| 组件提交入口 | `commitItemComponents` 仅 **5 处调用**（power 2 / sponge 3）+ 1 处定义 | `grep -rn "commitItemComponents" src/main` |
| 注册口 | `public static … register…` **20+ 处**，形状已分化（K→V 表 / resolver / hook 链） | `grep -rn "public static void register" src/main/java/com/qiqi/li/living` |

> ⭐ **诊断结论：这不是「架构崩坏」型项目。** 棘轮门禁 + 15 项文档校验 + 1659 测试 +
> 领域互依赖清零，说明底座健康；`architecture-layering-plan.md` §8 判定的
> 「**薄契约层 / 厚领域层 / 11+ 领域平铺**」形状**仍然正确**。
> ⇒ 本路线图**不提重写、不重画分层、不抽独立库**，只处理下面三类裂缝。

---

## §1 三个真问题（按危害排序）

### 1.1 契约层领先于实现层：同一事实有多个来源

| 裂缝 | 说法 A（契约 / 注释） | 说法 B（实际代码） | 出处 |
|---|---|---|---|
| ~~**tick 顺序**~~ ✅ **已收口（2026-11-06）** | 旧：`api-contract.md` §1.3 称「按 `getAllFunctions()` 的列表顺序分组调用 `tick()`」；`runFunctionTicks` javadoc 也写「按优先级」 | `scanAndGroupLivingItems` 用 `LinkedHashMap` 按**槽扫描遭遇序**插入，`runFunctionTicks` 直接遍历它 | [TODO §物品传输 3](../TODO.md) · `api-contract.md` §1.3（已校正） |
| **两套优先级** | `LivingItemFunction#getTickPriority()`（manager 序，默认全 0） | `HasContainerData#getPriority()`（0~3，容器级数据专用） | 两套数字互不引用，`TickOrderTest` 只断言了 manager 列表顺序 |
| **组件写回** | `api-contract.md` §1.8 已定共同入口 `commitItemComponents`（复制计算 → 写回 → 回读确认才报账） | 只有 `ContainerEnergyStorage` / `SpongeContainerFluidHandler` 接入；其余 getter 活引用修改路径仍走旧写法 | [TODO 活海绵 4](../TODO.md) · [海绵 §12.3](../tech/living-sponge-tech.md) |
| **DataComponent 值** | `framework-benchmark` §3.16：组件须成对声明 `persistent` + `networkSynchronized`，缺则写明理由 | `WaterComponents.CONTAINER_FLUID_DATA_ITEM` 的值是**可变 class** `ContainerFluidData`（无 `equals`/`hashCode`），且只声明 `persistent` | [TODO 容器边界 3.3](../TODO.md) |

> 🔑 **四条的共同性质：正确入口已经存在，存量没走完。** 这是「**口径一次定对**」没走完，
> 不是缺抽象。⇒ 修法优先是**对齐口径**，不是再建一层机制。
> ⚠️ AI 零记忆下这类裂缝最贵：下个会话会**照契约写代码**，而契约与实现不一致。

### 1.2 验证债 > 代码债

「替身测试证明『我对第三方行为的**理解**被守住』，真机才证明『理解**是否成立**』」
—— [TODO 容器边界 5.6](../TODO.md) 自己的判据。当前欠账：

- ~~**R3 领域互依赖清零**（2026-11-01）实机待验：漏斗×末影箱路由（公共/专属/直连）+ 拆合堆叠频道切换~~
  ✅ **已验 2026-11-07 用户实测** —— [AGENTS 子系统索引 R3 行](../../AGENTS.md) · [EnderChest §9](../tech/living-ender-chest-tech.md)
- [整改计划 §6](2026-10-29-全项目审查整改计划.md)：M-3 / M-5 / M-6 / M-7、M-9a/b/c（缓存 sweep 三条）未闭环
  （M-1 / M-2 GUI 存放已于 2026-11-07 用户实测通过关闭，R-02 触发前提确认不可复现、代码不动）
- 批次 3 的教训已写在案：**静态推断的触发前提未确认就动了代码**

⇒ 大量重构「**门禁绿但未验真**」，这是当前最大的隐性风险，且它不会被任何一条自动门禁发现。

### 1.3 规划池自身在漂移（AI 零记忆下的头号误导）

| 文档写的「现状」 | 2026-11-06 实测 | 后果 |
|---|---|---|
| `framework-benchmark` §1：主源码 **290** / 测试 **55 文件 473 用例** | **453** / **210 文件 1659 用例** | 数字偏离 56% / 282% / 250% |
| §3.1：`RecipeBookComponentMixin` **约 50 KB**、`AbstractContainerScreenMixin` 34 KB ⇒ 「**定稿：做，P0**」 | **225 行 / 271 行**（配方书逻辑已拆进 `LivingChestRecipeTab`） | **该 P0 项的前提已消失**，照做就是给不存在的病动刀 |
| §3.13：「**4 处**扩展点各造一遍」 | 20+ 注册口，且形状已分化 | 「抽一个 `SimpleRegistry` 四处复用」的形状判断需要重做 |
| 规划稿里的「主类 1174 行」快照 | 主类已 476 行（步骤 4 第 1~5 刀完成） | 章节正文是**地层**，不是现状 |

> 📌 `docs/README.md` §7 已定：「**漂移 = 该归档的内容还留在活跃层**」。
> 带「定稿：做」的条目若不复核前提，下个会话会照着过期结论动手 —— 这比没有规划更危险。

---

## §2 演进原则（可否决后续提议）

| # | 原则 | 项目实证 |
|---|---|---|
| **P1** | **口径一次定对 > 加守卫。** 每条成本必须回答「买的是什么」：买「我改错时的安全垫」⇒ 不做；买「口径正确 / 不返工」⇒ 可做 | 被否：状态同步「每 N tick 保底覆盖」、写入处「旧值非空则拒写」；被采纳：条目身份用容器键字符串（[用户偏好 2026-10-22/26]） |
| **P2** | **抽象必须有第二个真实用户**，不许为抽象而抽象 | [framework-evolution-plan §5](framework-evolution-plan.md)；[D-render-01](../decisions.md) 已否 `TargetedBroadcast` 骨架与协议分段 |
| **P3** | **不建「防我改错」的分支。** 落地的东西必须自身可解释（看代码就知道为什么） | 上下文压缩后临时补丁的动机会消失，只剩代码 |
| **P4** | **纯重构 = 行为零变化 + 全量测试绿 + 棘轮数字只减不增**；每批一个可独立回滚的提交 | [整改计划 §2/§5](2026-10-29-全项目审查整改计划.md) |
| **P5** | **规划文档的快照数字必须带复算命令 + 日期**，否则写成「当时的结论」而非现状 | 本文 §0；`docs/README.md` 铁律 4 |
| **P6** | **定案先于代码**：先更新对应文档状态，再动代码；改完 `changelog` 一行 + `AGENTS` 一行 | `docs/README.md` §4 |
| **P7** | **验收分三层**：单测 / 门禁 / **真机**；替身不算真机。明确「不做」也要留痕，避免下次重新讨论 | [TODO 5.6](../TODO.md) · [decisions.md](../decisions.md) 翻转留痕 |

---

## §3 目标架构：不重画，只收口

保持 [architecture-layering-plan §8](../archive/architecture-layering-plan.md) 判定的形状，
目标只有**三个单一来源**：

| 目标 | 今天 | 收口后 |
|---|---|---|
| ~~**顺序只有一个声明点**~~ ✅ **已收口（2026-11-06）** | 契约说 manager 序、注释说按优先级、实际按遭遇序；两套优先级数字 | 契约 / javadoc / 实现三者已一致；`TickOrderTest` 不再声称守 tick 顺序（见 §4 阶段 0.2） |
| **组件写回只有一个入口** | 共同入口已建，存量分叉 | 有结算风险的域走 `commitItemComponents`；**不因写法相似全库扫**（用户 2026-11-06 拍板） |
| **规划状态只有一个来源** | 7 份文档各写「现状」，数字互为地层 | 状态行 + `gen_buffer_index.py`；规划文档只写「当时的结论 + 复算命令」 |

---

## §4 分阶段路线

> **用「门」而不是日历排期**：项目是间歇开发，日期不含信息（[docs/README.md §4](../README.md) 已判）。
> 阶段 0 是共同前置；阶段 2/3/4 由**外部触发**开启，互不等待，但**不同提交**。

### 阶段 0 · 规划池与契约对表（最高优先，不写业务代码）

| # | 项 | 进入条件 | 完成判据 |
|---|---|---|---|
| 0.1 | **规划池审计**：把 §0 的 7 份文档逐条对代码，分三类处置 —— ① 前提消失 ⇒ 关闭并留痕（如 §3.1 巨型 mixin）② 仍成立 ⇒ 保留 + 更新可复算快照 ③ 已落地 ⇒ 搬 `archive/` | 无 | 不存在「定稿：做」但前提已消失的条目；`doc_check` 15 项 + `gen_buffer_index` 全绿 |
| 0.2 | ✅ **已完成（2026-11-06）**：tick 顺序口径定案 —— 选 **(a) 契约承认遭遇序**。判据：`getTickPriority()` 全库 **0 个生产覆写**，`HasContainerData#getPriority()` 有 4 个且带物理因果 ⇒ (b) 是给零用户的抽象做行为变更、(c) 是把有因果的机制与空抽象焊一起，两者都在给不存在的风险付钱 | 本轮直接核实（0.1 未完成，但本项结论不依赖它） | §1.3 改为描述真实两阶段；`ContainerLivingItemHandler` / `LivingItemManager` / `LivingItemFunction` 注释与 `TickOrderTest` 声明同步校正；**不改扫描逻辑、不加排序、不加守卫、不新增测试**；TODO §物品传输 3 已关账 |
| 0.3 | 把「规划文档不得写不可复算的现状快照」写进 [docs/README.md](../README.md) 铁律 4（已有则只补指针） | 0.1 完成 | 新增/修改的规划文档头部带「基线日期 + 复算命令」 |

**不做**：不重画分层、不改包结构、不动 `LivingItemManager` 注册链。

### 阶段 1 · 正确性收口（当前主线延伸，紧接阶段 0）

> 当前提交序列（容器传输守恒 → 四原语 → 统一缓存回收 → 共同组件提交 → 铜灯/海绵写回）
> 就是在做这件事；本阶段的任务是**把它走完并验真**。

| # | 项 | 触发 / 排序 | 完成判据 |
|---|---|---|---|
| 1.1 | `commitItemComponents` **定向迁移**（[TODO 活海绵 4](../TODO.md)） | **触发门**：出现虚报收付 / 复制 / 丢失的结算证据，或明确要支持的玩法场景；**不因写法相似自动排全库重构** | 每域负向回归（旧写法红）、[api-contract §1.8](../system-design/api-contract.md) 口径不变 |
| 1.2 | `ContainerFluidData` **组件值不可变化**（[TODO 3.3](../TODO.md)） | alpha 阶段无存档兼容负担 ⇒ **成本最低窗口，宜早不宜迟** | 组件值改为不可变快照（与 `PhaseSnapshot` 同构）或补齐值语义；`networkSynchronized` 成对声明或注释写明「仅服务端」；破坏/放置迁移 + GUI 拖拽实测 |
| 1.3 | **真机验证债集中清算** | 1.1 / 1.2 之后，一次集中回归轮 | R3 漏斗×末影箱四场景 + 拆合堆叠频道切换；整改计划 M-1/2/3/5/6/7/9a/b/c 逐条勾掉或**注明无法复现** |
| 1.4 | 大箱相位「两半各得一份」（[TODO 3.1](../TODO.md)） | 排本阶段最后（极端边缘场景） | 特征化测试先钉住现状，再选 ① writeback 只写一半 或 ② 破坏时判先手 |

**验收**：`./gradlew test --rerun` 全绿；5 条提交前门禁全绿；R1 只减不增；实机清单有记录。

### 阶段 2 · 结构收敛（触发门：出现第二个真实用户）

| # | 项 | 触发条件 | 完成判据 |
|---|---|---|---|
| 2.1 | **扩展点先分类再抽原语**：按形状分 K→V 注册表 / resolver 注册 / hook 链三类，只对 **≥2 个真同构**的抽；不建统一大注册表 | 出现第 3 个同形状「注册 + 排序 + 兜底」扩展点（[framework-benchmark §6](framework-benchmark.md)） | 打乱注册顺序结果不变；新增第 3 个调用点**不改框架** |
| 2.2 | 红电规划 ① 跳变检测前移到写入点（事件流） | 下次电力层加新用电侧元件时**顺路**（[redstone-evolution-roadmap §2.5](redstone-evolution-roadmap.md)） | 先统一边写入口径；电源/记账不变量 [power-invariants](../system-design/power-invariants.md) 全绿 |
| 2.3 | 红电规划 ③ 信号层元件接口化 | **铜门 / 新信号元件真的立项**（同文档 §3.5） | 新元件只加类；phase1-5 行为等价性证明 |
| 2.4 | 框架提案 **A** 客户端推导遥测 | 第二个「内容派生型」域开工前（[framework-evolution-plan §5](framework-evolution-plan.md)） | 推流回退保留；纯函数与面板同源 |
| 2.5 | 框架提案 **B** 统一宿主模型 | 第 5 个「世界可见物」出现，或必须改动现有四套同步之一时，**动之前先抽内核**（用户 2026-10-18 定：暂缓） | `HostKey` 形状先定（per-item 数据 / per-container 副作用分离，见该文 §4）；热路径零每帧分配 |
| 2.6 | 渲染态第 5 通道验证 + 包体实测 | 新增世界可见活物品时 | 不新增包类 / 缓存类（[渲染态定案](../tech/living-tool-tech.md) §11.5）；tooltip 通道是否纳入另行拍板 |

### 阶段 3 · 性能（触发门：有实测数据，且「大型整合包上万容器」目标仍成立）

| # | 项 | 判据 |
|---|---|---|
| 3.1 | 7a：`getProcessableChunks` / `processLevelContainers` 复用缓冲（[TODO 性能 2/3](../TODO.md)） | `PerfMetrics.recordPhase` 前后对比，不凭感觉 |
| 3.2 | 7b：合并 `scanAndGroupLivingItems` 与 `computeContentSignature` 的全槽遍历（省 50% 扫描） | 内容签名测试 + 实机容器行为 |
| 3.3 | 7c：`syncWorldContainer` 按 tick 缓存「菜单 → 槽位 → 归属位置」 | 多人开界面下 `flush_slots` 耗时 |
| 3.4 | 配方书每帧分配（整改计划批次 8-④） | **先定缓存失效条件再动**；客户端无背包变动事件，失效条件答不上来就不做 |

**不做**：无实测支撑的预防性优化、引入每 tick 新分配的新机制。

### 阶段 4 · 生态 / 1.0（触发门：1~2 个真实 addon）

| # | 项 | 说明 |
|---|---|---|
| 4.1 | [open-plan §4 A3](open-plan.md) 注册时序契约 | C 组挂起的前提；「在哪注册」今天第三方仍在猜 |
| 4.2 | Q3 / Q4 / Q6 拍板（example-addon 形态、独立 SemVer、逻辑拆分） | 判据：**前置库先是契约，再是发布物，最后才是仓库**（open-plan §4.1） |
| 4.3 | C1 / C3 / C4（maven 发布 / 兼容哨兵 / addon-guide） | 等第一个真实 addon 作者的问题清单来喂内容 |
| 4.4 | 落盘结构留 `version` 字段（[framework-benchmark §3.7](framework-benchmark.md)） | **1.0 前必须在位**；定位是「给未来留挂载点」，**不是迁移逻辑** —— alpha 铁律 6 不做旧存档兼容 |
| 4.5 | 规则 JSON 迁 `data/living_item/`（§3.9 / Q6~Q8） | 触发：整合包作者 / 服主提出覆盖需求；Q8 必须实机验证非标准 JSON 不被原版解析 |

> ⛔ **物理拆分前置库（Q6 第三步）不在本路线图内** —— 需真实 addon 数据点后另立，
> 现在切 core/内容边界大概率切错，错了再改付双倍。

---

## §5 明确不做（带理由，防止反复讨论）

| 不做 | 理由 / 出处 |
|---|---|
| 重写 / 分层重画 / 抽独立框架库 | §0 诊断：形状正确、底座健康；[framework-benchmark §9.1](framework-benchmark.md) 元判据「我们的复杂度是深的，不是宽的」 |
| 统一大注册表 `SimpleRegistry<K,V>` 直接套 20+ 注册口 | 形状已分化，先分类（2.1）；半套统一 = 两套口径 |
| Mixin 业务逻辑契约接口化（§3.1「定稿 P0」） | **前提已消失**：最大 mixin 271 行；原触发条件是「NeoForge 大版本升级时大面积报错」，届时重新评估 |
| 全库 `package-info.java` Nullness（§3.6） | 现只有 5 个对外包且内容是契约说明；半套约定比没有更糟（项目铁律 6） |
| gametest（§3.17） | 等出现「只能在真实游戏环境复现」的 bug；不为对齐大模组而做 |
| power 单位值类型 `MilliFe` / `Re` | 牵动 DataComponent 序列化；`mulDivFloor` 已收窄溢出面，边际收益不足 |
| 自定义 datapack registry（§3.5）、网络包拆包器（§3.8） | 无真实需求 / 无症状；alpha 结构未定案，早做等于固化 |
| `TargetedBroadcast<T>` 骨架 / 协议分段 / 状态段每 N tick 保底覆盖 | [D-render-01](../decisions.md) 已否；「比较单位 = 发送单位」后保底是给不存在的风险付钱（P1） |
| 因「写法相似」全库迁移组件写回 | 用户 2026-11-06 明确：按结算证据定向迁移（1.1） |
| 恢复「容器扫描展开」通用能力 | [D-container-08](../decisions.md) 已定：那是活潜影箱专属逻辑 |

---

## §6 依赖与次序

```
阶段 0  规划池与契约对表（无前置 · 最高优先 · 零业务代码）
   │
   └─ 阶段 1  正确性收口（当前主线：写回守恒 → 组件值 → 真机验真）
        │
        ├─[门：第二个真实用户]→ 阶段 2  结构收敛
        ├─[门：PerfMetrics 实测]→ 阶段 3  性能
        └─[门：真实 addon]    → 阶段 4  生态 / 1.0

阶段 2 / 3 / 4 相互独立，可并行开发，但不得合进一个提交。
阶段 0 是共同前置：没对表就动结构，等于照着过期的定稿值施工。
```

**为什么 0 必须最先**：§1.3 已证「定稿：做」的条目里有前提消失的；
在 AI 零记忆下，规划池的漂移会**先于代码债**被下一个会话采纳。

---

## §7 每阶段通用完成定义（DoD）

沿用 [整改计划 §5](2026-10-29-全项目审查整改计划.md) 的 7 条，另加两条：

1. `./gradlew test --rerun` 全绿，且 `AGENTS.md` 测试条数按实测更新（`FROM-CACHE` 是假绿）
2. 五条提交前门禁全绿：`check_layers` / `check_mixins` / `check_container_keys` / `check_stack_ops` / `doc_check`；层基线**只允许收紧**
3. 口径 / 能力边界有变化 ⇒ `changelog` 一行 + `AGENTS` 进展一行（保持 ≤10 条）
4. 行为约束变化写进对应 `docs/tech/*.md` §0；架构口径变化写进 `docs/system-design/`
5. 实机清单中与本批相关的项已勾掉；**无法复现必须注明，不得静默略过**
6. 提交信息两段式：`<子系统>：<结论>（<根因>）`
7. 一个批次 = 一个可独立回滚的提交，不夹带无关重构
8. **本文状态行同步**：阶段推进后改头部 + `python tools/gen_buffer_index.py`
9. **本文的「明确不做」如有翻转**，走 [decisions.md](../decisions.md) §4 四步留痕，不直接删行

---

## §8 本文的维护

- 头部「状态：」行是 [buffer/README.md](README.md) 索引的**单一来源**（`gen_buffer_index.py`）
- **每批落地时先更新本文的阶段表，再动代码**（P6）
- 与既有文档冲突时：**契约 / 现状以 `system-design/` 与代码为准**，本文只管「先后与触发」
- 全部完成或决定放弃 ⇒ 本文移 `docs/archive/`，并在 `decisions.md` 留 supersedes 链
