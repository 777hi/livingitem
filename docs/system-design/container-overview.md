# 容器七问 —— 专题结项总览

> **状态：结项（2026-10-26）** —— 2026-10-18 立项；S1~S4 + S3b（10-19）、S6~S9（10-21~24）、
> 自动学 Tier 1.5 与共同布局链（10-25/26）已落地；**S1b/S2 收编暂停**（用户「原点」）。
>
> 本文按**七问**组织 —— 时间线已归档到 [archive/container-compat-stages.md](../archive/container-compat-stages.md)
> （S1~S9 的验收判据、真机基线清单在那）。
>
> ⚠️ **本文只写「结论一行 + 指针」**：不变量正文在 [container-boundary.md](container-boundary.md)
> （I-C1~I-C11），实现现状在 [living-item-infrastructure.md](living-item-infrastructure.md) §5/§6，
> 列数规则的操作方式在 [guides/container-compatibility.md](../guides/container-compatibility.md)。
> **不要在这里复述正文** —— 那会造出第 2 份答案，翻转时改一处漏一处（本专题最痛的模式）。
>
> 📍 **改容器层代码前**：先读 §0 找到你的问题属于哪一问 ⇒ 只改那一问的唯一入口，
> 别在调用点另写一份判断。

---

## §0 七问

**这个专题从头到尾只回答七个问题。** 每个问题都曾经散成多份答案，每次收编都遵循同一条判据：
**同一个问题出现第 2 份答案 ⇒ 收成一处，让「漏一条」在结构上不可能。**

既有记忆里的「容器三问」（谁在看 / 在世界里哪 / 什么形状）是其中第 **2~4** 问。

| # | 问题 | 收敛成什么（唯一入口） | 状态 | 正文 |
|---|---|---|---|---|
| 1 | **谁是容器** | `I-C1` 能力面 + 四类宿主 + 键形 `ContainerKeys`；实体侧白名单 `EntityContainers` | ✅ 生效 | 契约 I-C1 · infra §5 |
| 2 | **容器是什么形状** | `ContainerLayoutResolver` 统一链（实例→方块规则→学习→内建→启发式）+ `I-C10`/`I-C11` | ✅ 生效（收编暂停） | infra §6.2 |
| 3 | **谁在看这个容器** | `ContainerViewers.of(players, cacheKey, positions)` | ✅ 生效 | 契约 I-C5 · `D-container-14` |
| 4 | **容器在世界里哪** | `ContainerAnchor`（参考物，不是坐标）+ 四个 `SERVER` 开关 | ✅ 生效 | [container-anchor-design.md](../buffer/container-anchor-design.md) · `D-container-15/16` |
| 5 | **怎么读写它的槽** | `I-C3` 写回≠插入 · `I-C4` 模拟同源 · `I-C7` 写后校验 | ✅ 生效 | 契约 I-C3/4/7 |
| 6 | **容器级数据存哪** | `I-C9` + `ContainerContext#persist` + `ContainerDataMigration`/`ContainerHandoff` | ✅ 生效（2 项未修） | 契约 I-C9 · `D-container-12/13` |
| 7 | **容器里套容器** | 不做通用扫描展开，归活潜影箱（功能内部递归） | ⏸ 明确不做 | `D-container-08` |

**两条不属于七问、但同样重要**：`#8 客户端同步` 与 `#9 激活与清理`（来自 11 条接入清单）。
它们今天**没有唯一入口**，分散在各功能里 —— 这是本专题唯一没被收编的横切面，
新形态接入时容易漏（见 [guides/container-compatibility.md](../guides/container-compatibility.md) 接入清单）。
其中 `#8` 的规则已在 §0.1 定下来。

---

## §0.1 内容可见性（横切规则 · `#8 客户端同步`）

> 不回答「容器是什么」，而回答「**客户端能不能自己看到容器内容**」——
> 它决定每条渲染通道走「本地读」还是「S2C」。

| 容器形态 | 客户端可见内容？ | 渲染数据来源 | 为什么 |
|---|---|---|---|
| 方块容器（未开 GUI） | ❌ | S2C | 原版只在开菜单时同步槽位 |
| 玩家背包（**顶层物品**） | ✅ | 本地读 | 玩家物品栏本来就同步给本人 |
| 掉落物（单栈） | ✅ | 本地读 | 实体同步 |
| 实体容器（船 / 矿车 / 驴） | ❌ | S2C | 同方块容器：未开 GUI 就不同步 |
| **物品形态容器**（活背包） | ❌ | **S2C，且不区分自己 / 别人** | 内容在服务端 SavedData（精妙 `BackpackStorage`），客户端只有按需填充的副本 |

**三条推论（违反即 bug，三条都已在 2026-10-26 真机踩过）**：

1. **「自己的东西本机渲染负责」这个优化只对可见形态成立。**
   物品形态必须例外 —— 否则活信标在自己背包里**没有光束**（`LivingBeamRenderer`
   跳过 `owner == mc.player`，而本机扫描只认物品栏顶层）。**2026-10-26 已修**：
   `BeaconPlayerHostSync` / `renderRemotePlayerHosts` 的跳过条件都加了 `!fromItemForm()`。
2. **渲染通道的键必须能区分「同一宿主里的不同容器」**（`HostRef.hostSlot`）。
   只按宿主做键，两个背包撞成一条 ⇒ 后写覆盖先写 ⇒ **只画一个**。
   不能用容器 UUID 代替：中键复制的两个背包共享同一 UUID。
3. **取点必须带 `partialTick`，且按宿主种类定**（`client/render/HostOrigin`）。
   用 `Entity#position()` 会得到**脚底**且**不插值** ⇒ 模型从脚踝出来、走动时一顿一顿。
4. **「操作者是谁」不能用「ctx 有没有 inventory」判断**（2026-10-27）。
   物品形态容器的 `inventory` 恒为 null，但它背在玩家身上时**操作者就是那个玩家**。
   ⇒ 统一判据：服务端 tick 用**锚点是不是 `AtPlayer`**，客户端渲染用 **`HostRef.PLAYER`**
   （`HostOrigin#playerOf`），容器级数据用 `getOwnerPlayer()`。
   踩过的两个实例：**射线微调**（bearer 恒 null ⇒ 背包里的工具永远从眼睛出、不认配置）、
   **被动模式辅助**（三条辅助线只扫物品栏顶层 ⇒ 背包里的工具不挖矿、不补刀）。
   「随身携带」的定义也收在 [CarriedLivingItems](../../src/main/java/com/qiqi/li/living/domain/tools/CarriedLivingItems.java)：
   **物品栏顶层 + 已活化容器物品的内容（只展开一层）**。

   ⚠️ **双端事件必须两端同源**（`BreakSpeed` / 破坏判定都是双端）：服务端读容器、
   客户端读 S2C（K2）。客户端**不能**自己去读容器内容 —— `clientStorageCopy` 时有时无
   ⇒ 速度时快时慢 ⇒ 客户端进度条满、服务端拒绝 ⇒ **方块挖不下来**（2026-10-27 真机）。

---

## §1 谁是容器（发现 · 枚举 · 身份）

**结论**：容器 = 某**宿主**能取到物品能力面。四类宿主各有一套能力面与身份键，
键形权威清单只在 `ContainerKeys` 的类注释里，别处不得再解析。

| 宿主 | 能力面 | 身份键 |
|---|---|---|
| 方块 | `ItemHandler.BLOCK` | `chest_x_y_z` / `container_<dim>_x_y_z` |
| 实体 | `ItemHandler.ENTITY` | `entity_<uuid>`（位置不进键，每 tick 现取） |
| 掉落物 | `ItemHandler.ITEM` | `item_entity_<uuid>` |
| 玩家 | `ItemHandler.ENTITY` | `player_<uuid>` / `player_<uuid>_ender_chest` |

**为什么不能按 `instanceof Container` 判**：那是第二套口径，与能力面口径必然错位
⇒ 非 `Container` 的模组容器（精妙背包方块、ProjectE 炼金箱）会被判成「不是容器」，
于是列数推断、指令校准、viewer 匹配全线失效（`D-container-01`）。

**唯一的收紧例外**：`ItemHandler.ENTITY` 对**所有** `LivingEntity` 都有 fallback
⇒ 能力面非空是常态、不是判据。实体宿主必须再过白名单（`ContainerEntity` + `hasChest()`），
方向是**宁可漏判不可误判** —— 误判的代价是给僵尸主手槽倒水。

**踩坑**：`LivingItemContainerCommand` 曾用 `instanceof` 判容器（2026-10-18 改）。

---

## §2 容器是什么形状（几何）

**结论**：所有形态走**同一个选择器** `ContainerLayoutResolver`：

```
实例来源（活对象现算）→ 方块规则表 → 方块学习值 → 内建约定 → 启发式
```

对外只暴露 `size` / `columns`，逻辑槽与物理槽靠 `toPhysicalSlot`/`toLogicalSlot`
一对互逆映射转换（`I-C10`）；列数必须 ≥ 1、空容器必须跳过（`I-C11`）。

**为什么收编（把 7 种答案来源合成一条链）却停了**：触发它的真问题（精妙背包 30 存储槽被猜成
10 列，真值 5）已由 `D-container-17`「放置态也由联动来源给权威值」修掉；剩下的是
**组织性气味**。而合表（S1b）会让第三方继承原版 BE 的子类静默劣化
（实例：`twilightforest:cinder_furnace` 靠 `instanceof` 拿到正确的 1 列）——
不为「表更纯净」付行为代价。

**踩过的三个坑**（都是「隐形错」：搬错格子但仍在搬）：

1. 精妙 108 格被算 9 列 ⇒ 熔炉燃料槽在 `slot+9` 而 GUI 是 `slot+12` ⇒ **永远不烧**。
   活漏斗只用 `slot±1`，看起来完全正常 ⇒ 只错上下不错左右，最难反推。
2. 54 格背包 + 2 个占列升级 ⇒ 30 存储槽被启发式猜 10 列（真值 5）⇒ 全线错位。
3. 驴的 16 槽含前置马鞍槽 ⇒ 不排除鞍槽时 `16 % 8 = 0` 得 8 列（真值 5×3），
   且往鞍槽倒水会得到玩家取不出的幽灵物品。

**自动学（Tier 1.5）的两条硬局限**（机制性，非调参可绕）：
① `Slot.x/y` 客户端 Screen 可以随便重排（fxntstorage 服务端一横排、客户端 12 列）；
② 拿不到容器身份就学不了（NeoForge `SlotItemHandler` 把 `container` 设成共享静态空容器）。
⇒ 这类模组只能注册菜单解析器（逐模组约 10 行），或继续靠规则/启发式。

**指针**：infra §6 / §6.2 · [container-layout-resolution.md](../buffer/container-layout-resolution.md) §10.6

---

## §3 谁在看这个容器（viewer）

**结论**：唯一入口 `ContainerViewers.of(players, cacheKey, positions)`，按缓存键种类分派
（玩家态 ⇒ 主人 + 末影箱门控 · 物品形态 ⇒ 菜单锚点 · 其余 ⇒ 位置）。两条数据轨
（流体快照 / 运行时遥测）都只调它。

**为什么**：这个判定原本在三条轨里各写一份 if-chain，加新形态时漏改一条就**静默失效**。
2026-10-23 一天内连踩两次：流体轨修了、运行时轨没修 ⇒ 真机「流体正常但 tooltip 空」。

**踩坑**：曾经用 `slot.container` 是否等于某个 `Container` 实例来判 —— 对多层包装的模组容器
永远匹配不上（精妙 GUI 槽是 `SlotItemHandler`，内部 `container` 是每槽匿名实例）。

**指针**：契约 I-C5 · `D-container-14` · `MenuSlotOwners`

---

## §4 容器在世界里哪（锚点）

**结论**：`ContainerAnchor` 是**参考物**不是坐标 —— `AtBlock` / `AtPlayer` / `AtEntity` / null。
框架只答「在哪」，**取哪个点归功能**（射线要眼睛、应力要下方、爆炸要中心）。

**为什么**：这个解析原本有 4 份各写一遍（TNT 爆炸 / 水车应力 / 工具射线 / 信标光束），
且 5 种容器形态在每份里处理都不同 ⇒ 同一功能在不同形态下的行为没人有完整视图。
定义成坐标则四处取点差异无法收拢，而这个差异是对的。

**宿主锚点继承**：物品形态（活背包）可 `getHostAnchor()` 递下去，但**默认谁都不用** ——
用它会变成加强玩法（背包里的工具自动挖矿 / 水车随身发电 / 信标随身光环），
四个消费方各由一个 `SERVER` 型开关控制、默认全关（`D-container-16`）。
目前只有**活 TNT** 启用（爆炸点用继承值是修复，不是加强）。

**踩坑**：真机「活背包里的活 TNT 点燃后重复倒计时、不爆炸」——追根因才发现这不是孤例。

---

## §5 怎么读写它的槽（写回语义）

**结论**：三条不变量 —— **写回 ≠ 插入**（改自己槽位用 `setStackInSlot`，`insertItem` 只表达
「放进这个容器」）、**模拟与真实同源**（模拟只是上界，不是承诺）、**写后校验**（对未知模组兜底）。

**为什么**：`IItemHandler` 契约允许「槽位只是建议位置」。两套模型相遇产生的不是兼容性瑕疵，
而是**数据损坏**（写回错槽、物品被吞、物品被复制）。

**实证**（第三方行为，见 §8 台账）：精妙背包 `insertItem(slot,…,false)` 忽略 slot 走整库插入
（同款部分满槽 → memory 槽 → 过滤器槽 → 最低空槽）；带 IO 过滤器时 `extractItem` 返回 `EMPTY`。
**这不是它的 bug** —— 整库插入对「管道往里塞东西」是正确且更好的语义，错的是我们把它当成了「写回指定槽位」。

**踩坑**：S1 的三条守卫测试（`SlotIgnoringHandler` / `FilteredExtractHandler`）在动手前
**必须先红** —— 失败信息要指向「物品落到错误槽」/「数量不守恒」，否则守卫守的不是这件事。

---

## §6 容器级数据存哪（持久化）

**结论**：持久化方式**由 ctx 自己声明** —— `ContainerContext#persist(ContainerDataKey, T)`：
默认走 `getDataHosts()` 写附件，物品形态覆写为写 `SavedData`（`D-container-13`）。
领域**不得假设有 BE**：拿不到 `IAttachmentHolder` 不是异常，是一种正常宿主。

跨形态迁移走两条路：标准事件路径 `ContainerDataMigration`（`BlockDropsEvent` / `EntityPlaceEvent`），
以及模组自己搬容器时的**双向交接** `ContainerHandoff`（`D-container-12`）。

**两个必须记住的坑**：

1. **内存表是唯一真相源**，附件只是每 tick 被覆盖的副本。放置时「只写附件」= 下一 tick 被空对象盖掉。
   这个 bug 与精妙无关，是**所有容器放置路径共有的**。
2. **注册点用方法引用时，接口里不能留 default 实现** —— `registerMover(Mover::moveOut)`
   只覆盖了 `moveOut`，`moveIn` 是 default 空实现 ⇒ 放置方向**静默失效**（真机第 4 轮才找到）。
   修法：去掉 default（漏实现编译期报错）+ 反射守卫。

**未修**（移交 [TODO](../TODO.md)）：大箱子两半相位可能各得一份（3.1）；
`ContainerFluidData` 当数据组件值违反 NeoForge 约定（3.3，缺 equals/hashCode，只在开发校验下暴露）。

---

## §7 容器里套容器（嵌套）

**结论**：框架**不做**通用「扫描展开」（`D-container-08`）。容器内物品被 tick 是
**活潜影箱的专属逻辑** —— 它走功能内部递归，深度预算由功能自己声明（4 层）。

**为什么**：通用展开要遍历每个容器的每个物品查 `ItemHandler.ITEM`（108 格 × N 容器），
性能需额外筛选；且「物品内部自成世界」本就是活潜影箱的卖点，泛化会稀释它。

**注意**：`D-container-08` 只约束「不做扫描展开」，**不约束 tick 宿主范围** ——
框架的 tick 宿主已扩到实体形态（`D-container-10`）。

**去向**：[活潜影箱实现细节.md](../buffer/活潜影箱实现细节.md)（独立立项，不在本专题内）。

---

## §8 台账：第三方容器行为实录

> 真机观察到的、或从源码读到的**具体行为**。契约是不变量，本表是证据。
> 一行一条：模组 / 观察 / 结论。

| 模组 | 观察 | 对应不变量 |
|---|---|---|
| Sophisticated Backpacks | `insertItem(slot,…,false)` 忽略 slot，走整库插入（同款部分满槽 → memory 槽 → 过滤器槽 → 最低空槽） | I-C3 / I-C4 |
| Sophisticated Backpacks | 带 IO 过滤器升级时 `extractItem` 返回 `EMPTY` | I-C3 / I-C7 |
| Sophisticated Backpacks | 方块背包 `BackpackBlockEntity` 不是原版 `Container`（只实现 `IControllableStorage`） | I-C1 / I-C5 |
| Sophisticated Backpacks | 能力面 handler 与 GUI 槽 handler 隔三层包装；GUI 槽是 `SlotItemHandler`（内部 `container` 为每槽匿名实例） | I-C5（⇒ 不可用实例身份反查） |
| Sophisticated Backpacks | **真机（2026-10-18）**：方块背包 GUI 悬停活熔炉**无进度行** —— 证实 viewer 匹配确实依赖 `Container` 实例 | I-C5 ✅ 判断成立 |
| Sophisticated Backpacks | 菜单对象**可反查**正在看的背包（`BackpackContainer#getBackpackContext()`）⇒ 联动模块应覆写 `handleOfMenu`。默认实现「扫物品栏 + 唯一一件才认」在玩家带**两个**同类活容器时必然返回 null（2026-10-23 真机：两个活背包 ⇒ 活流体倒不了） | 接入清单 #6 |
| ProjectE 炼金箱 | 有物品能力、非原版 `Container`（历史 crash 来源） | I-C1 / I-C5 |
| twilightforest | `cinder_furnace` 继承 `FurnaceBlockEntity`、复用原版熔炉菜单 ⇒ 靠 Tier 2 的 `instanceof` 拿到正确的 1 列（2026-10-25 查证，合表会静默劣化） | I-C2 · S1b 暂停依据 |
| fxntstorage | 服务端菜单是一横排（`x = 序号×18, y = 0`），**客户端 Screen 重排成 12 列**并竖向滚动 ⇒ 自动学到的未必是玩家所见 | 自动学局限① |
| fxntstorage | 全部容器用 NeoForge `SlotItemHandler`，其 `container` 是**共享静态 `SimpleContainer(0)`** ⇒ 拿不到身份，学不了 | 自动学局限② |

---

## §9 还剩什么

> **归属说明**：未完成事项的**唯一归属是 [TODO.md](../TODO.md) 容器边界章节**（`D-doc-06`）。
> 本表只是汇总一行结论 + 编号，细节与触发条件去 TODO 看 —— 别在这里写第二份。

| # | 事项 | TODO 编号 |
|---|---|---|
| 1 | 大箱子两半相位可能各得一份 | 3.1 |
| 2 | `ContainerFluidData` 当数据组件值违反 NeoForge 约定 | 3.3 |
| 3 | 实体容器列数**校准入口**没做（`register`/`inspect` 只认准心方块） | 3.4 |
| 4 | 自动学两条硬局限：fxntstorage 全部 + twilightforest 三件落在启发式 | 5.5 |
| 5 | 同键多布局（槽位会变的容器），用户点名「慢慢讨论」 | 5.1 |
| 6 | 池端点（AE2 ME / RS 网络），**不做**池宿主（`D-container-07`/`I-C8`） | 5 |
| 7 | 真机台账稀疏：11 条接入清单多数靠替身测试 | 5.6 |

**明确不做**（不是待办，是已否的边界）：

- 通用「扫描展开」容器内物品 —— 归活潜影箱（`D-container-08`）
- 容器能力白/黑名单 —— 排除 = 放弃兼容 + 永久适配债（`D-container-05`）
- 物品池当 tick 宿主（`D-container-07` / `I-C8`）
- **超大堆叠兼容不立项**（2026-10-26 用户判定）：[超大堆叠审计](oversized-stack-audit.md)
  已定稿（09-07），通道层干净、14 项是设计内缩放、仅 1 个真实缺陷（熔炉超堆叠燃料）。
  堆叠语义属「设计活物品之初就该考虑」的范畴。

---

## §10 暂停线复盘（S1b / S2）

**为什么停**：S1b（Tier 2 原版表并入规则表）查证时翻车 —— Tier 2 是 `instanceof` 判据，
**对第三方继承原版 BE 的子类也生效**，删表会让这类子类静默掉到启发式。用户在「家族兜底保留
vs 全进规则表」的分叉上直接说「原点」，收编重构不做。

**复盘结论**：这次重构的本质是**一个组织性气味驱动的重构，不是一个还活着的问题驱动的修复** ——
唯一已发生的实例（30 槽猜成 10 列）已用 `D-container-17` 修掉，剩下要防的是「将来可能过期」，
而代价是未知第三方子类静默劣化。

**可复用的自检**（下次重构前问一句）：

> **触发这次重构的那个问题，现在还活着吗？**
> - **还活着** ⇒ 修它，重构是顺带的
> - **已经修掉了** ⇒ 剩下的是组织性气味，先写文档把「每层为什么存在」记清楚，
>   不要为「更纯净」付行为代价

（与 `AGENTS.md` 的 RAM 原则同源：不为想象中的问题铺路。）

---

## §11 文档族地图

> 判据：**想知道「现在是什么」，读本目录；想知道「为什么走到这里」，读 buffer/archive。**

| 想知道什么 | 读哪 |
|---|---|
| 违反即 bug 的不变量（I-C1~I-C11） | [container-boundary.md](container-boundary.md) |
| 多方块容器的身份句柄、边界带 | [container-identity.md](container-identity.md) |
| 实现现状：模组容器兼容 / 列数自动解析 | [living-item-infrastructure.md](living-item-infrastructure.md) §5 / §6 |
| 列数规则怎么配、指令怎么用、新形态接入清单 11 条 | [guides/container-compatibility.md](../guides/container-compatibility.md) |
| 超大堆叠的逐领域审计（09-07 定稿） | [oversized-stack-audit.md](oversized-stack-audit.md) |
| 活背包（物品形态容器）的现状 | [tech/living-backpack-tech.md](../tech/living-backpack-tech.md) |
| 锚点的普查过程与取舍 | [buffer/container-anchor-design.md](../buffer/container-anchor-design.md) |
| 服务端菜单自省（实体容器已落地 / 列数探测延后） | [buffer/container-menu-introspection-topic.md](../buffer/container-menu-introspection-topic.md) |
| 列数收编的完整论证 + 两轮审查闭环 | [buffer/container-layout-resolution.md](../buffer/container-layout-resolution.md) |
| 活潜影箱（嵌套容器，独立立项） | [buffer/活潜影箱实现细节.md](../buffer/活潜影箱实现细节.md) |
| S1~S9 的验收判据与真机基线清单 | [archive/container-compat-stages.md](../archive/container-compat-stages.md) |
| 每个决策的为什么 | [decisions.md](../decisions.md) `D-container-01..17` |

---

## §12 阶段索引（一行一条 → 归档稿）

| 阶段 | 一句话 | 日期 |
|---|---|---|
| S1 | 契约草案 + 三条守卫测试**先红**（错槽 / 复制） | 10-18 |
| S2 | 写回路径收敛（`setStackInSlot` + 回退校验 + 写后校验），守卫转绿 | 10-18 |
| S3 / S3b | 身份与 viewer 改位置口径；三条 viewer 轨全部改完，死代码清除 | 10-19 |
| S4 | 规则键改 `(id, 容量)` 两段，精妙六档共存 | 10-18 |
| S5 | ❌ 取消 —— 扫描展开归活潜影箱（`D-container-08`） | 10-19 |
| S6 | 实体容器（船 / 矿车 / 带箱驴骡）成为第四类 tick 宿主 | 10-21 |
| S7 | 物品形态容器 / 活背包（框架零改动，功能内部递归） | 10-22~23 |
| S8 | 世界锚点收成 `ContainerAnchor`（`D-container-15`） | 10-23 |
| S9 | 掉落物准入改成功能自声明 `supportsDroppedItem()` | 10-24 |
| 后续 | 自动学 Tier 1.5（L1~L4）、两轮审查闭环、共同布局选择入口 | 10-25~26 |
