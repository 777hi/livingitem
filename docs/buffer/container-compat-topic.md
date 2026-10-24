# 容器兼容性专题（计划 / 实施记录）

> **状态：主体完成**（2026-10-18 立项 → 10-19 完成 S1~S4 + S3/S3b，10-21~10-24 续做 S6~S9）。
> S5 已取消（`D-container-08`，见 §2）。
> 契约正文见 [system-design/container-boundary.md](../system-design/container-boundary.md)（不变量 **I-C1~I-C11**）。
> 本文只写**怎么做、做到什么算完**，不重复契约内容。
>
> ⚠️ 本文长期停在 10-19，10-21 之后的四个阶段补在 **§2.1** —— 那一段是本专题的后半程。

---

## 0. 目标与非目标

**目标**：让本模组对**任意符合 `IItemHandler` 契约**的容器都正确——而不是「支持精妙背包」。

**非目标**：逐个模组打补丁。若某个问题只能用 `if (mod == X)` 解决，说明契约没收敛，回到 I-C3/I-C5 重新看。

**判据**：新增一个从未见过的容器模组时，需要改的代码行数应为 **0**（除非它连 `IItemHandler`
契约都不守，那属于对方 bug，登记进 §5 台账）。

---

## 1. 范围

按契约 §4 的四条「明确不做」执行。范围内的是：

| # | 收敛点 | 契约条款 |
|---|---|---|
| 1 | 写回语义（`setItem` 走 `setStackInSlot` + 回退校验） | I-C3 / I-C7 |
| 2 | 模拟与真实同源 | I-C4 |
| 3 | 身份与 viewer 匹配改位置键 | I-C5 |
| 4 | 列数规则支持同一容器多容量 | I-C1 / I-C2（`getWidth` Tier 2 去 `Container` 依赖） |
| 5 | ~~扫描展开深度 1~~ **已取消**（属活潜影箱专属逻辑，`D-container-08`） | 契约 §3.0 |

**不在范围内**：活潜影箱的功能内部递归（独立立项，需 `BoundaryExchanger` + 深度预算 + 环检测）。

---

## 2. 阶段与验收判据

> 每阶段的判据都必须是**可复算**的（跑什么命令 / 哪个测试类红→绿），不能写「感觉对了」。

### S1 契约与守卫（纯文档 + 纯测试，不碰 `src/main`）✅ 2026-10-18

- [x] 契约草案（`container-boundary.md`）
- [x] 守卫测试：`SlotIgnoringHandler`（`insertItem(slot,…)` 忽略 slot）+ `FilteredExtractHandler`
      （`extractItem` 返回 `EMPTY`），驱动 `SimpleContainerContextTest`
- [x] 守卫测试在**当前代码上必须是红的**（实测三条全红，输出留档在测试类 javadoc）

**判据**：`./gradlew test --tests "*SimpleContainerContextTest*"` 出现**预期内的失败**，
且失败信息指向「物品落到了错误的槽」/「物品数量不守恒」而不是别的原因。
→ 实测失败信息：`expected: <1> but was: <0>`（错槽）、`expected: <1> but was: <2>`（复制）。

### S2 写回路径收敛（I-C3 / I-C4 / I-C7）✅ 2026-10-18

- [x] `SimpleContainerContext.setItem` 优先 `IItemHandlerModifiable.setStackInSlot`
- [x] 不可修改时的回退分支：校验 `extractItem` 返回值，失败则不插入、只告警
- [x] 写后校验（I-C7）：目标槽内容与期望不符 ⇒ 告警
- [x] S1 的守卫测试转绿
- [x] 附带：`LivingChestItemHandler` / `LivingEnderChestItemHandler` 补 `setStackInSlot`
      —— 前者的 `insertItem` 同样是**整库语义**（`LivingChestFunction.insertItem` 忽略 slot），
      属同一 bug 类的「自己的代码」

**判据**：全量测试绿 + S1 守卫绿 + `setItem` 实现中不再出现 `insertItem`（回退分支除外）。
→ 755（754 passed + 1 skipped，0 fail）。

> ⚠️ **S2 改动的语义边界**：`setItem` 现在是「让槽 i 变成这个」的**无条件精确写**，
> 不再经过 `isItemValid`。这符合所有调用方意图（熔炉产物 / 酿造 / 耕地 / 工具耐久 / 流体产物
> 都是「写自己容器的确定槽位」）。跨槽插入由 `simulateInsertItem` 先行把关（`PlainSlotAccessor`
> 就是先模拟再写）。若将来新增「往别人槽位塞东西」的调用方，必须先模拟，不能直接 `setItem`。

### S3 身份与 viewer（I-C5）✅ 2026-10-19（主轨）

- [x] `MenuSlotOwners`：「菜单槽 → 容器位置」解析器，**三级**（可靠 → 脆弱）：
      ① 结构化（原版 BE 直取 / 大箱子复用 `ContainerContexts.reflectHalves`，无反射）→
      ② **联动模块注册**（`registerMenuResolver`，编译期安全 —— **模组容器的正解**）
      （⚠️ 原③「核心反射兜底」已按 `D-container-09` 删除：核心不猜第三方 API 形状）
- [x] `ContainerContexts.isViewingPositions` + `ContainerRuntimeCache.findViewersByPositions`
- [x] `LivingItem.runtimeContainerPositions` 取代 `runtimeContainerInstances`（返回位置，不再要求 `Container`）
- [x] `LivingItemRuntimeSync.flush` 改位置口径（**tooltip 进度信息**）
- [x] `SimpleContainerContext.syncWorldContainer` 改位置口径（**活熔炉图标刷新**）
- [x] 精妙背包的菜单位置由**联动模块**取（`living/compat/sophisticated/`，反射
      `BackpackContainer#getBlockPosition`），核心不认识它；**无需 compileOnly 依赖**

**判据**：非 `Container` 方块实体的替身场景下 `isViewingPositions` 为 true（`MenuSlotOwnersTest`）；
原版大箱子 / 末影箱 / 玩家背包三条既有路径不变红。

### S3b 其余 viewer 轨 ✅ 2026-10-19

- [x] `FluidFlowServerSync` 改位置口径（背包里的活流体在 GUI 中不渲染）
- [x] `LivingBrewingStandFunction` 改位置口径（音效目标）
- [x] **删掉退场后的死代码**：`ContainerContexts.isViewing(Collection<Container>)`、
      `ownsContainer`、`ContainerRuntimeCache.findViewers / isViewing / menuContains`
      —— 其中 `menuContains` 在 S3 转成位置口径后就**已经是死代码**，这次一并清掉
- [x] 守卫迁移：大箱子防虚影测试从 `ContainerContextsTest.OwnsContainer` 迁到
      `MenuSlotOwnersTest.resolve_doubleChest_returnsBothHalves`（改为按位置匹配）

**判据**：全库 `grep "isViewing(\|ownsContainer\|findViewers(\|menuContains" src/main` 只剩
`isViewingPositions` / `isViewingEnderChest` / `MenuSlotOwners.isViewing` 三处。
→ 已达成。`Container` 剩余调用点全部是 I-C2 允许的原版语义用途。

### S4 列数规则多容量（`getWidth`）✅ 2026-10-18

- [x] 规则键从 `ResourceLocation` 改为 `(id, containerSize)`（`RULES` 由 `Map<id, rule>` 改为
      `Map<id, List<rule>>`；JSON 格式**不变**——每条的 `containerSize` 本来就是键的一部分）
- [x] `getWidth` 用 `findRule(id, getSize())` 精确匹配；`findRule(id)` 单参数版保留给「有没有注册过」与诊断
- [x] 内置规则补精妙背包六档（27/45/54/81 → 9 列；108/120 → 12 列）
- [x] 守卫：`sophisticatedBackpack_multiTier_coexist`（两档共存 + 各自列数）

**判据**：同一 BE id 的两档容量能同时生效（108→12、120→12 同时成立）。
→ 已达成。JSON 格式未变（铁律 6 也因此不必启用）。

> ✅ **遗留已清（2026-10-19）**：`USER_RULES` 改 `Map<id, List<rule>>`、`REMOVED_KEYS` 改
> 按 `(id, 容量)` 单档记（旧配置里的裸 id 记录按「该 id 全档移除」兼容读）
> ⇒ 玩家差异也能按档存，注册第二档不再挤掉第一档。守卫
> `multiTierUserRules_coexist` 含「重启后两档都还在」的回归。

> 🐛 **这一条是「活熔炉不烧」的根因**：燃料槽在 `slot + width`（默认 DOWN）。
> 108 格背包被算成 9 列时燃料槽是 `slot+9`，而 GUI 是 12 列、燃料在 `slot+12`
> ⇒ 永远读不到燃料 ⇒ 不烧。活漏斗只用 `slot ± 1` ⇒ 看起来完全正常。
> 这正是「只错上下、不错左右」的一类偏差，最难靠现象反推。

### S5 ~~扫描展开深度 1~~ ❌ 已取消（`D-container-08`，2026-10-19）

**容器内物品被 tick 是活潜影箱的专属逻辑，不是框架的通用能力。**

原计划「框架每 tick 展开物品形态容器」被否，理由见契约 §3.0：
性能（108 格 × N 容器要额外筛选）、玩法（会稀释活潜影箱「物品内部自成世界」的卖点）、
实现（活潜影箱走功能内部递归即可完全复用现有管线）。

⇒ 想做这件事，去实现 [活潜影箱](../buffer/活潜影箱实现细节.md)，不在这里。

---

## 2.1 S6~S9（2026-10-21 ~ 10-24，专题后半程）

> 这四段是在 S5 取消之后做的。它们不是原计划的延续，而是被真机问题逼出来的：
> 先是「实体容器」和「活背包」两种新形态，再是「世界锚点」这个被重复了 4 遍的解析。

### S6 实体容器（船 / 矿车 / 带箱驴骡）✅ 2026-10-21

`EntityContainerContext` 把「有 `ItemHandler.ENTITY` 能力的实体」包装成容器。
关键三条：`getBlockPos()` **刻意返回 null**（防船停箱子上被信标误判）、
马鞍槽偏移（16 槽 → 15 存储格）、`getDataHosts()` 返回实体本身（写回口径统一）。

### S7 物品形态容器 / 活背包 ✅ 2026-10-22 ~ 10-23（真机验收）

物品本身就是容器（精妙背包 / 潜影盒）。**框架零改动** —— 它只是一个普通活物品功能
（`LivingBackpackFunction`：`canApply` 认领物品形态容器，`tick` 里建内部 ctx 再递归
`processContext`），`scanAndGroupLivingItems` 天然会扫到它。
接入清单 11 条：**✅1~9 + ✅11**，仅 ❌10 明确不做（方向传输归活潜影箱）。

### S8 容器世界锚点 ✅ 2026-10-23（`D-container-15`）

普查发现「容器在世界里作用在哪」有 **4 份各写一遍**的解析（TNT 爆炸 / 水车应力 /
工具射线 / 信标光束），且 5 种形态在每份里处理都不同。
⇒ `ContainerAnchor` = **参考物**（`AtBlock`/`AtPlayer`/`AtEntity`/null），
框架只答「在哪」，取点归功能（射线要眼睛、应力要下方、爆炸要中心）。
物品形态另可**继承宿主锚点**（`getHostAnchor()`），但**默认谁都不用** ——
用它会变成加强玩法（背包里的工具自动挖矿 / 水车随身发电 / 信标随身光环），必须逐个拍板。
目前只有**活 TNT** 启用（爆炸点用继承值是修复，不是加强）。
详见 [container-anchor-design.md](container-anchor-design.md)。

### S9 掉落物准入改成功能自声明 ✅ 2026-10-24

真机：点燃的活 TNT 丢在地上**永不爆炸** —— 不是炸不了，是压根没被 tick
（准入判据写死成「是活工具/活武器」，同一处第二次漏改：2026-09-24 漏过活剑）。
⇒ `LivingItemFunction#supportsDroppedItem()`（默认 false）+ 准入/分发两处用它。

**⚠️ 这条只对掉落物生效，别扩散**（2026-10-24 审查确认）：

| 形态 | 过滤吗 | 为什么 |
|---|---|---|
| 掉落物（1 格壳、无界面） | ✅ | 大多数功能在里面没意义 ⇒ 需要例外清单 |
| 船 / 矿车（27 格 + GUI） | ❌ | 跟箱子同类，里面所有活功能都该跑 |
| 方块容器 / 玩家背包 / 活背包 | ❌ | 同上 |

⚠️ **判据是「容器类别」不是「锚点类型」**：船和掉落物锚点都是 `AtEntity`（都是"没方块位置、
只有实体位置"），但一个是格容器、一个是单栈壳。**锚点相同是巧合，不该推出同一准入规则。**
若把过滤扩散到船，26 个功能里只有 2 个声明过 ⇒ 其余 24 个（活熔炉 / 活漏斗 / …）在船里全部停掉。

---

## 3. 验证方式（两种，职责不同）

| | 替身测试（JUnit） | 真机（`runClient` + 真实 jar） |
|---|---|---|
| 能证明 | 我对第三方行为的**理解**被守住 | 我对第三方行为的理解**是否成立** |
| 成本 | 秒级、可自动化、进 CI | 分钟级、手工、不可回归 |
| 频率 | 每次改代码 | **一次性**（每个模组一次，把观察记进 §5） |
| 局限 | 替身照我的理解写 ⇒ 理解错则替身错 | 无法覆盖组合场景 |

**真机怎么挂**：`build.gradle` 的 devMods 列表（`runtimeOnly files(...)`，jar 不存在则静默跳过）
加入 `sophisticatedbackpacks` + `sophisticatedcore` 两个 jar 即可，与 `ironchest` 同款。
⚠️ 当前 `libs/` 只有两者的**源码**，没有 jar —— 需要人工从 CurseForge / Modrinth 下 1.21.1 版本。

---

## 4. 已定的实施选择（2026-10-18）

> 原「待拍板」4 项已定：1/2 由架构证据决定（见下），3 用户自领（清单见 §6），
> 4 已登记 `docs/decisions.md` 的 `D-container-01..06`。

1. **S3 解析器 = 注册式 Provider**。接口「菜单槽 → 容器位置」，内置覆盖原版四类
   （`Inventory` / `BlockEntity` / `CompoundContainer` / `PlayerEnderChestContainer`）；
   精妙走 compat 实现，**内部先用反射调 `getBlockPosition`**，避免引入 compileOnly 依赖。
   - 为什么不是纯反射：要每 tick 遍历菜单的 public 方法，脆且慢。
   - 为什么不是纯 compat `if`：就是契约 §0 反对的「逐个模组打补丁」。
2. **S5 键形 = 优先级制，不是二选一**：有稳定实例 id（精妙背包的 contents UUID 组件）用
   `bp:<uuid>/slot7`，没有则退回路径键 `player_<uuid>/slot3/slot7`。两者在**同一个键生成点**收敛。
   - 理由：路径键对任意模组通用，但精妙有排序/自动整理 ⇒ 物品换槽就丢进度；
     id 键让「背包挪个位置，里面的熔炉继续烧」，符合玩家直觉。
   - 可接受的退化：路径键失效只丢**进度**（燃料/引信归零），不丢物品；
     但有内部存储的活物品（活箱子/活潜影箱）必须用 id 键，否则丢的是物品。

---

## 5. 台账：第三方容器行为实录

> 真机观察到的、或从源码读到的**具体行为**记在这里。契约是不变量，本表是证据。
> 一行一条：模组 / 观察 / 结论。

| 模组 | 观察 | 对应不变量 |
|---|---|---|
| Sophisticated Backpacks（1.21.x） | `insertItem(slot,…,false)` 忽略 slot，走整库插入（同款部分满槽 → memory 槽 → 过滤器槽 → 最低空槽） | I-C3 / I-C4 |
| Sophisticated Backpacks | 带 IO 过滤器升级时 `extractItem` 返回 `EMPTY` | I-C3 / I-C7 |
| Sophisticated Backpacks | 方块背包 `BackpackBlockEntity` 不是原版 `Container`（只实现 `IControllableStorage`） | I-C1 / I-C5 |
| Sophisticated Backpacks | 能力面 handler 与 GUI 槽 handler 隔三层包装；GUI 槽是 `SlotItemHandler`（内部 `container` 为每槽匿名实例） | I-C5（⇒ 不可用实例身份反查） |
| ProjectE 炼金箱 | 有物品能力、非原版 `Container`（历史 crash 来源） | I-C1 / I-C5 |
| Sophisticated Backpacks | **真机 B2（2026-10-18，用户测）**：方块背包 GUI 悬停活熔炉 **无进度行** —— 证实 viewer 匹配确实依赖 `Container` 实例，非 `Container` 容器拿不到运行时遥测包 | I-C5 ✅ 判断成立 |
| *（其余真机项待填）* | | |

---

## 6. 真机基线清单（用户自测，2026-10-18 自领）

**准备**：从 CurseForge / Modrinth 下 1.21.1 版 `sophisticatedbackpacks-*.jar` +
`sophisticatedcore-*.jar` → 丢进 `libs/` → `./gradlew runClient`。
`build.gradle` 已加通配加载（`sophisticated*` 匹配，无匹配静默跳过）⇒ **不用改 build**。

> ⚠️ **现在测的是基线，不是验收**：S2 未做，B1/B3/E 组**预期就是红的**。
> 测完把现象填进 §5 台账；S2 完成后再跑一遍同一张表即为验收。

| # | 操作 | 现在预期（S2 前） | S2 后预期 | 证明 |
|---|---|---|---|---|
| A1 | 原版箱子放活熔炉 + 燃料 + 矿石 | 正常烧 | 不变 | 无回归基线 |
| B1 | 方块背包（放地上）放活熔炉，烧 1 个矿石 | 能烧，但**输入/输出物品跳到别的槽**（背包有更靠前空槽时尤其明显） | 稳定停在原槽，产物进输出槽 | I-C3 |
| B2 | 打开背包 GUI，悬停活熔炉 | **无进度行**（运行时遥测不发） | 有进度行 | I-C5 |
| B3 | 方块背包放活漏斗 + 一排物品，朝向设为向右 | 传输乱序 / 进错槽 | 逐格顺序传输 | I-C3 / I-C4 |
| C1 | 物品背包（拿手上）放活熔炉 | **完全不烧** | ⚠️ **会烧了**（2026-10-22 S7 活背包）—— 原「S5 未做 ⇒ 预期不烧」已过期 | 契约 §3 深度 0（被 S7 以「功能内部递归」实现，见 §2.1） |
| D1 | 钻石背包（108 格）放活漏斗，设朝向 | 邻居按 **9 列**算（错） | 按 **12 列** | I-C1 列数 |
| D2 | 对准方块背包敲 `/livingitem container register 12` | **成功**（2026-10-18 已修） | 成功 | 指令口径 |
| E1 | 方块背包里用活漏斗搬 64 个石头（纯传输，无转化） | 总数**变多或变少** | 恒为 64 | I-C7 |
| E2 | 背包插「输入/输出过滤器」升级，重复 E1 | 可能**复制** | 恒为 64 | I-C3 / I-C7 |

**⚠️ 反向验证（很重要）**：若 B2 实际**有**进度行，说明契约 I-C5 的判断错了
（viewer 匹配没我想的那么依赖 `Container`）⇒ 回来改契约，别改代码去迁就结论。
→ **2026-10-18 用户实测：B2 无进度行，与预期一致 ⇒ I-C5 成立**（证据已记 §5 台账）。
