<!-- markdownlint-disable -->

# 活物品扩展点契约（API Contract）

> *创建: 2026-09-27 · §1 **已生效** / §2 **未生效**（A1 待拍板，不得当现状引用）*
>
> 📄 **定位**：本文是「第三方如何基于本模组扩展」的**契约层**——只记录
> **不变量 · 协议 · 口径（违反即 bug）**，实施背景与取舍过程不在此处铺陈。
>
> 📄 **路线与待决问题池**：[`../buffer/open-plan.md`](../buffer/open-plan.md)（未定案层）。
>
> ⚠️ 引用规则：本文只描述**现状**。未来方案一律标「未生效」并留在 §2，
> 避免 `docs/README.md` §1 那条实证过的误导（"未实现的方案写成现状口吻"）。

---

## 1. 已生效的契约

### 1.1 DataComponent 归属 —— `getOwnedComponentTypes()`

**机制**：每个 `LivingItemFunction` 自声明它挂到物品上的 DataComponent；
`LivingItemManager.clearLivingData` 遍历全部已注册功能统一清除，`IS_LIVING` 由框架单独清。

```java
// LivingItemFunction.java —— 默认空实现，有自带数据的功能覆盖它
default Set<DataComponentType<?>> getOwnedComponentTypes() { return Set.of(); }
```

**为何如此**（一句话）：原先是一份 31 行硬编码 `stack.remove` 清单，
javadoc 明写「新增 LivingItemFunction 必须回来手添」⇒ **第三方只能 fork 本仓库**
（open-plan.md §1.2 P0-1）。改为自声明后第三方零接触。

#### 不变量（违反即 bug）

| # | 断言 | 违反后果 |
|---|---|---|
| **I-C1** | 每个 DataComponent **必须**被恰好一个 `LivingItemFunction` 声明归属（`IS_LIVING` 除外） | 漏列 ⇒ 取消活化后组件残留，成为**孤儿数据**：组件还在、无人认领，再次活化时读到脏旧值 |
| **I-C2** | 同一组件**不得**被两个以上的功能声明 | 清理语义互相踩；语义归属不明 |
| **I-C3** | 任何功能**不得**声明 `IS_LIVING` | `IS_LIVING` 属框架本身，不是某个功能的数据 |

#### 守卫

`src/test/java/com/qiqi/li/living/api/ComponentOwnershipTest.java`（3 项）覆盖 I-C1/I-C2/I-C3。

> ⭐ **为什么必须有守卫**：旧写法「忘了加 remove」会立刻暴露；新写法「忘了声明」是**静默失败**。
> 没有守卫，这份契约一定会腐烂回去。
>
> ⭐ **为什么用反射而不是硬编码清单**：组件集合从 `LivingComponents` +
> `LivingItemManager` 两处的字段**反射得出**（A1 迁移后内容组件常量住注册站
> `LivingComponents`，框架级原始类型组件仍留 `LivingItemManager`），
> 新增 DataComponent **自动纳入**测试，不存在「第二份清单」需要同步
> （避免又一次两处维护漂移）。

#### 与相邻概念的区别

| 方法 | 语义 | 用途 |
|---|---|---|
| `getOwnedComponentTypes()` | 「这是我的」 | 取消活化时随之增删 |
| `getIgnoredComponentTypes()` | 「比较时别看我」 | 可堆叠性判定 |

二者**正交**，同一组件可同时出现在两边（如 `LIVING_FURNACE_BURNING`）。

---

### 1.2 现有注册式扩展点（现状盘点）

#### 包级边界口径（2026-09-28 定）：默认内部，例外对外

**对外包只有 5 个**：`living/api`、`living/container`、`living/transfer`、`living/model`、
`living/interaction`（各带 package-info 契约）；**其余一切包默认内部**，
第三方不得依赖。新类落内部包是例行公事、零负担；**新类要进对外包才是需要
拍板的决策**（写 javadoc、按契约审查）。

> 为什么不逐类标 `@ApiStatus.Internal`：曾批量标注 211 个内部类型后撤销 ——
> 包结构已画线，逐类标注是同一信息的第二份清单（必然漂移）。先例支持：
> Create（2539 文件仅 11 处标注）、AnvilCraft（2539 文件仅 4 处）均靠包结构
> 表达边界。兜底是 doc_check 第 9 项按**包路径**机械校验对外文档（不依赖标注）。

第三方今天可以走这些口，全部 public：

| 扩展点 | 位置 | 状态 |
|---|---|---|
| `LivingItemManager.registerFunction` | `LivingItemManager.java` | ✅ 可用 |
| **数据组件注册** `LivingComponents.*` | `components/LivingComponents.java`（A1 迁入 2026-09-28；**2026-10-08 ③ 从 transfer 迁出**） | ✅ 可用 —— 内容组件的常量与注册站；`LivingItemManager` 自此为纯框架类，不再牵出 domain 类型 |
| `InteractionRegistry.register` / `registerHandler` | `LivingItem.java` | ✅ 声明式规则 + 谓词收窄（活耕地案例已证明无需枚举物品类）；**规则数据已外置**（D2）：内置 `assets/living_item/interaction_rules.json`（13 条，兼格式示范）+ 玩家差异 `config/living_item/interaction_rules.json`，谓词 ID 化走 `InteractionPredicates`，配 `/livingitem interaction reload\|list` |
| `ContainerSnapshot.registerProvider` | `LivingItem.java` | ✅ 注册驱动，已解除 container 包对 domain 类的依赖 |
| 可选接口 `HasDirection` / `HasContainerData` | `living/api/` | ✅ 新增活物品无需改核心文件 |
| 可选接口 `RouteRegistrar`（标记） | `living/api/` | ✅（2026-11-01）实现者的槽位可作跨容器路由注册方（当前活漏斗）；路由表按能力自查注册方是否离场，不 import 注册方域 |
| `TransferTargetPolicies.register` | `living/transfer/` | ✅（2026-11-01）传输目标接管策略：目标槽在 canPlaceItem 模拟之后、Accessor 级分发的「改目的地」语义（形状对齐 `SlotInteractions`；两者分发阶段不可互换） |
| `MechanicalReceivers.register` | `living/transfer/` | ✅（2026-11-03）机械施压接收端：共享层提供原始本体源 + 实际交付量 q，领域只认中立请求；首个 matches 独占（返回0也不回落）。当前实现者：活漏斗；common setup 注册，按类去重 |
| `SlotInteractions.register` | `SlotInteractions.java` | ⚠️ public，但全仓仅 1 处调用（内置静态块）——**从未被外部验证** |
| `LivingIconRegistry.register` | `LivingIconRegistry.java` | ⚠️ public，但内置调用时机在 mod 构造函数，无时序契约 |
| 可选钩子 `onActivated` / `onDeactivated` | `living/api/LivingItemFunction.java` | ✅ default 空实现，契约见 §1.5（2026-10-04）—— ⚠️ 与其它钩子不同，它**有守卫测试**（`ActivationHookTest`） |

#### ⚠️ 已知缺口：注册时序是隐式的

三类扩展的注册时机各不相同，且**未有任何文档声明**：

| 扩展点 | 实际时机 |
|---|---|
| `registerFunction` | `FMLCommonSetupEvent` |
| `MechanicalReceivers.register` | `FMLCommonSetupEvent`（各领域 `*Registration.register`；按实现类去重） |
| `SlotInteractions.register` | **静态块** |
| `LivingIconRegistry.registerAll` | **mod 构造函数** |

⇒ 第三方若想给自己的活物品配图标，必须在 `LivingIconRegistry.onRegisterAdditionalModels`
消费 SPECS **之前**注册；这个窗口今天只能靠读源码推出（open-plan.md §4 A3 待补）。

---

### 1.3 tick 顺序契约（2026-11-06 校正作用域）

⭐ **两条顺序互相独立，不得互相推断**：

| 顺序 | 决定者 | 作用域 |
|---|---|---|
| **功能 `tick()` 执行序** | 容器槽位扫描序 —— 功能按「**在扫描中首次出现的槽位**」先后执行；同一槽位挂多个功能时，才按 `getTickPriority()` 非降序 | 扫描分组 → `runFunctionTicks` |
| **容器级数据执行序** | `HasContainerData#getPriority()`（0 流体 → 1 水车 → 2 红石 → 3 电力，**有物理因果**） | `runContainerDataTicks` |

**规则（已生效）**：`LivingItem#commonSetup` 在所有域注册完毕后调用
`LivingItemManager.sortFunctionsByPriority()` —— 按 `LivingItemFunction#getTickPriority()`
**稳定排序**（数值小的在前，相同优先级保持注册顺序）。`getAllFunctions()` 与
`getApplicableFunctions` 按此顺序返回功能列表。

⚠️ **本排序不决定功能 `tick()` 的跨槽执行序**：扫描按槽位升序、逐槽把命中的功能插入分组表，
所以一个功能即使在列表里排最后，只要它在第 0 槽出现，就先于「只在第 5 槽出现、但列表靠前」的功能执行。
⇒ **新增功能时，不得靠调整 `getTickPriority()` 改变它相对其它功能的执行时机**（跨槽无效）。

| 事实 | 说明 |
|---|---|
| 默认优先级全为 0 | **全库 0 个功能覆写**（由 `TickOrderTest` 断言）⇒ `getTickPriority()` 今天不产生可观察效果，只是未来声明点 |
| **只排序、不冻结注册** | 曾计划同时冻结（此后注册抛异常），实测发现会打断测试中注册 mock 功能的既有模式，而「防 tick 期间注册」防的是一个并不存在的问题 ⇒ 放弃。`registerFunction` 保持随时可注册 + 清缓存 |
| 为什么必须显式 | A1 把注册下放成 11 个分散文件后，**列表顺序**变成「主类调用次序 × 各文件内部排列」，没有任何一处能读出完整顺序 —— 本方法把它变成**可声明、可 grep、可断言**的 |

> 历史：2026-09-28 版写成「按 `getAllFunctions()` 的列表顺序分组调用 `tick()`」，
> 与扫描期分组的实际结构不符（[TODO §物品传输 3](../TODO.md) 2026-11-03 记录）。
> **2026-11-06 定案**：改契约承认扫描序，**不改扫描逻辑、不加排序、不加守卫** ——
> `getTickPriority()` 全库零覆写，为它做行为变更是给不存在的风险付钱；
> 而 `HasContainerData#getPriority()` 有 4 个真实实现者且顺序带物理因果，与本条是两套机制。

#### 1.3.1 同拍位移：旧扫描失效，不提供公平调度

容器先扫描槽位再按功能分组；任一功能通过容器移动原语成功搬动物品后，源槽与目标槽都会登记到本拍
`TickContext.movedSlots`。后续功能执行前，框架丢弃原槽已登记移动的扫描条目，且不会把目标位置临时补成
一条新扫描结果。这个集合只在本次容器 tick 内有效。

**契约边界**：

1. `movedSlots`是**过期扫描 / 槽位运行时失效标记**，不是槽位锁、所有权或禁止二次移动的预留表；
   后续功能仍可读取当前真实布局并再次机械搬运同一物品。
2. 被更早功能搬走的活物品，本拍不再按旧槽执行自己的功能；成功搬运后又撤销的槽也保守保持失效，
   不能因最终布局复原就复用中途已经过期的扫描对象。
3. 本机制只保证安全，**不保证功能间公平、轮换或最终获得执行机会**。执行序由§1.3的首次出现槽位决定；
   更早功能若持续搬动同一活物品，可以长期压制其后执行的自主功能。这不是数据冲突，也不积攒补偿 tick。
4. 新移动机制必须走会调用`onSlotsMoved`的容器原语；不得为让某功能“仍能行动”而绕开失效标记，
   否则可能让旧条目操作空槽、替代品或错误的槽位运行时。

活粘性活塞高频推拉活魂符是该契约的已接受玩法结果，具体口径见
[living-maid-tech.md §1.1](../tech/living-maid-tech.md#11-被动位移与机械压制)。

### 1.4 容器级数据的现状 —— ⚠️ 与 `framework-refactoring.md` 的描述不符

`TickContext`（第三方实现 `tick()` 时会拿到它）目前是 **4 个 public 可变字段**：

| 字段 | 使用者 |
|---|---|
| `fluidData` | 活水桶 / 活水车 / 活耕地 + `getSnapshot()` |
| `stressData` | 活水车 + `ContainerLivingItemHandler` 应力输出 |
| `redstoneData` | `getOrCreateRedstoneData()` —— 10+ 处红石功能 |
| `powerData` | `getOrCreatePowerData()`（电力层账本） |

> ⚠️ **`docs/archive/framework-refactoring.md` §3「TickContext Map 扩展」描述的
> `containerDataStore` + `getContainerData/setContainerData` —— 从未落地。**
> 该文档标记为「✅ 已完成」，但代码里至今仍是每加一种容器级数据就加一个字段
> （`powerData` 就是第四个，也是这么加的）。
>
> 同理，该文档说泛型 `getData/setData` 将「替代所有 `getXxxData` 方法」——
> 那一批便捷方法也一个没删，且都在正常使用。
>
> 这是 `docs/README.md` §1 警告过的形态：**未完成的设计写成了现状口吻**。

⇒ **这 4 个字段不是兼容包袱，不要删**（它们都在用）。真正的问题是
「要不要真的做那次重构」—— 见 §2.5。

### 1.5 活化时机自声明 —— `onActivated()` / `onDeactivated()`

**机制**：每个 `LivingItemFunction` 自声明「这件物品被活化 / 被取消活化的那一刻要挂什么」；
`LivingItemActivation.apply` 切换 `IS_LIVING` 后（或清除前）向
**认领该物品的功能**（`getApplicableFunctions`）派发。

```java
// LivingItemFunction.java —— 两个默认空实现
default void onActivated(ItemStack stack, Level level, @Nullable Player player, Via via) {}
default boolean onDeactivated(ItemStack stack, Level level, @Nullable Player player, Via via) { return true; }
```

**为何如此**（一句话）：与 §1.1 **同构**——组件归属自声明 + 活化时机自声明，
是同一件事的两半。收编前这五段是 `LivingTagPacket` 里的内联类型判断
（活箱子掉物 / 活末影箱绑定与解绑 / 活工具写主人），新增功能必须去改网络包。

**唯一入口**：`LivingItemActivation.apply(stack, level, player, via, activate)`
—— 活按钮、活耕地、活地图三个入口都走它。⚠️ 判定（`evaluate`）**故意不在**其中，
理由见 `LivingItemActivation` 类 javadoc（INTERNAL 途径按设计不受规则约束）。

#### 不变量（违反即 bug）

| # | 断言 | 违反后果 |
|---|---|---|
| **I-H1** | 派发时物品**必定处于「活」状态** ⇒ 活化必须「先写标记后派发」，取消必须「先派发后清标记」 | 判据（`canApply` 系列）都含 `isLivingItem` ⇒ 功能认不出物品（是否要在此基础上做别的，取决于功能自己） |
| **I-H2** | 派发目标是**认领该物品的功能**（`getApplicableFunctions`），**不得**用 `hasAnyFunctionFor` 的 copy 探针 | 探针结果会被写进按 `Item` 缓存的表（该表契约见 `getApplicableFunctions`） |
| **I-H3** | `onDeactivated` 返回 `false`（否决）时**不得已产生副作用** | 框架把所有认领功能问一遍，任一否决即整体中止 ⇒ 已产生的副作用无法回滚 |
| **I-H4** | `player` **可为 null**；覆盖钩子时若依赖玩家数据，必须显式定义缺席时的行为 | 无定义 ⇒ 将来出现「无玩家」调用路径时 NPE 或静默错绑 |

#### 上游承诺什么 / 不承诺什么 ⭐ 分层的关键

上游只提供**开关**，具体动作全部下游自决。框架的承诺**仅两条**：

| 承诺 | 内容 |
|---|---|
| **零改动** | `onDeactivated` 返回 `false` ⇒ 框架**不清任何数据、不改 `IS_LIVING`**，并如实返回「状态未被切换」 |
| **原样送达** | `stack` / `level` / `player` / `via` 原样送达，不做任何包装或加工 |

框架**不承诺**（下游自负）：

- ❌ 不承诺「否决时数据本来是否安全」——那是具体功能的数据语义，框架无从判断
- ❌ 不承诺玩家缺席时各功能该做什么——**逐功能的降级行为属下游**，写在该功能自己的 javadoc 里
- ❌ 不替下游准备玩家数据（不造上下文对象、不做补全）

> ⚠️ 因此「拒绝」与 `evaluate` 的**策略拒绝**（配置者写的黑白名单）是两件不同的东西：
> 后者是**配置意图**，前者是**下游自决**。否决走**返回值**，不占用 `Result` 枚举。

> ⚠️ 玩家为 null 的入口**今天不存在**（三个入口都持有 `player`）；
> 该约定是为**批量转化**预留的（`docs/TODO.md` 的未来活物品）。
> 上下文对象（能力袋）**刻意不做** —— 那是无终点的框架机制，触发条件见
> `activation-hook-refactoring-plan.md` §3.5。

#### 守卫

`src/test/java/com/qiqi/li/living/api/ActivationHookTest.java`（7 项）覆盖 I-H1~I-H4
（用 mock 功能验派发契约 + 下游真功能的现成行为）。

> ⭐ **为什么必须有守卫**：新写法「忘了覆盖钩子」是**静默失败** ——
> 该挂的东西没挂上，系统不报错。与 §1.1 的「忘了声明」同病。

#### 可测性边界

依赖真实玩家（`ServerPlayer`）的下游分支无法在单测里构造 ⇒ 只由游戏内验证覆盖。

---

### 1.6 构建依赖契约（2026-10-08 生效）

**第三方模组 jar 一律不进版本控制**——要么 Maven 坐标，要么运行时条件化；`libs/` 保持
gitignore（第三方**源码**树 `libs/src/` 同样不入库，仅本机阅读用）。gradle wrapper 自身的
jar 不在此限。引入例外只有一个前提：许可证允许再分发且体积可接受。
复算：`git ls-files 'libs/*.jar'` → 0；`-Plivingitem.devModsDir=nonexistent_dir_ci_sim`
本地复现「干净克隆」+ `--no-build-cache --rerun-tasks` 强制全量重编。

### 1.7 槽位数量操作原语（2026-11-05 生效）

`ContainerContext`（第三方 tick() 拿到的公开上下文）提供四个数量操作原语，
**数量增减必须走原语**，不得用裸 `setItem` 拼「先写目标、再无条件扣源」
（`setItem` 是覆盖语义，写失败无法表达——守恒不变量 `I-C12` 的根因）。
棘轮门禁 `tools/check_stack_ops.py` 把 domain/function 的存量裸写冻结为只减不增。

| 原语 | 签名 | 语义 / 契约 |
|---|---|---|
| `move` | `MoveResult move(from, to, amount[, MovePolicy])` | 槽→槽按数量搬运，**允许部分交付**（`Success.moved ≤ amount`）与同类合并；四态结果复用 `MoveResult`；`undo(token)` 可撤销。`STANDARD` 问容量+准入（`simulateInsertItem`）与抽取权限（`simulateExtractItem`）；`PHYSICAL` = A3 特例（流体推动）：容量只问 `getSlotLimit`，不问准入与抽取权限。**活物品隔离由调用方把关** |
| `produce` | `int produce(slot, prototype, amount)` | 产物放置（虚拟源 → 槽）：预算 = `simulateInsertItem`（I-C14①），返回**实际放入量**；差额只 WARN 不回滚（无源槽 ⇒ 无复制面，已放入部分是真产物） |
| `consume` | `int consume(slot, maxUnit)` | 输入消费（槽 → 虚空）：查抽取权限（I-C12 第三维），返回**实际扣减量**；写回不符恢复原状返回 0（调用方按 0 = 本拍不推进） |
| `transform` | `boolean transform(slot, fn)` | 原地整槽替换（I-C13 不可拆）：fn 收**副本**、不得改活引用；写后回读不符则还原并返回 false |

**变换顺序契约（熔炉型「输入→产物」功能）**：`consume` 先行、`produce` 后行，
产物量 = 实际扣减量 × 每单位产出。反向（先写产物再扣输入）在产物写失败时会留下
「扣了输入、产物凭空消失」；先写产物在 `actual` 非产出整数倍时会凭空多产物。
**丢 ≤ 复制**，所以 consume 先行（`I-C12` 边界：丢必留痕）。

守卫测试：`ContainerSlotOpsTest`（六场景 × 双容器实现 + 违约三态 + 负向验证）。

### 1.8 槽位物品组件提交（2026-11-06 生效）

**用途**：修改槽内同一种物品、同一数量的组件，并在确认写入后才对外报账。`getItem`
可能给活引用，也可能给副本；两者都不能替代明确的写入协议。这不是第五种数量操作原语，
不改变 §1.7 的四原语、`transform` 或旧 `setItem` 的语义。

`ContainerContext` 提供：

| 入口 | 契约 |
|---|---|
| `boolean supportsItemComponentWrite(int slot)` | **默认 false**；按槽声明是否具有直接、可修改的精确写入面。只读能力或只有插入/抽取能力，不足以声明支持 |
| `boolean trySetItemExact(int slot, ItemStack stack)` | **默认 false**；直接写入完整栈，不夹容量、不拆数量、不走插入/抽取或旧 `setItem` 回退；实现不得修改传入快照。返回值不能替代提交层的写后回读 |
| `boolean commitItemComponents(int slot, ItemStack before, ItemStack after)` | 按下列协议提交组件；只有返回 true，调用方才可把对应资产变化算作已交付 |

**提交协议（违反即 bug）**：

1. 槽位必须支持组件写入；`before` / `after` 必须**都非空、同 Item、同 count**。
   方法复制两份参数作内部快照；调用方、写入器与槽内引用不得污染这些快照。
2. 当前槽必须与 `before` 的物品、数量及组件完全一致；过期快照返回 false，**不得写槽**。
   当前匹配且 `before` / `after` 完全相同，返回 true，**不得调用写入器**。
3. 向直接写入面交付 `after` 的副本，随后回读并与 `after` 精确比较。
   只读拒绝、no-op 写入器、`UnsupportedOperationException`、容量截断或回读不符都不能算成功；
   **不得改走插入/抽取、容量夹断或旧覆盖入口**。
4. 失败且槽已部分改变时，用 `before` 的副本尝试精确恢复，并回读核验。
   恢复失败必须记 **ERROR**；提交仍返回 false。**不承诺任意第三方后端都能恢复**，
   更不承诺对恶意或违约写入器的普遍原子性；失败槽不得向调用方报作已交付。

**实现边界**：`SimpleContainerContext` 复用 `writeBackDirectly`，仅承认直接可修改的写入面，
并处理 `UnsupportedOperationException`；不得把能力不可写转成普通容器的数量操作回退。
实际写入并提交成功，或失败槽曾部分改变并执行了恢复尝试，必须向**全部关联 BE**标脏并同步
最终实际状态；未改变的拒写、无变化与过期快照不通知。
这不是能力存在性变更，不以反复 `invalidateCapabilities` 代替存储与同步。
实体上下文的直接写入必须遵守既有**逻辑槽 → 物理槽**映射，不能把逻辑槽号直接交给后端。

**调用方责任**：在副本上计算组件变化；提交 false 时停止相应交付，批量操作只返回此前
已成功提交的量。本入口只保证上述核验与恢复尝试，不替调用方回滚外部流体或其它资产。
选型见 [slot-ops §2.1](../guides/slot-ops.md#21-只改组件不改物品与数量)。

## 2. 待决 / 未生效（⛔ 不得当现状引用）

> 以下均为**计划**，尚未实施。拍板后回来标日期并把正文并入 §1。

### 2.1 A1 分组粒度

| 项 | 待决结论 |
|---|---|
| 注册入口单位 | **按现有包（域）**，出 12 个入口，不是 22 个 |
| 理由 | 域是天然内聚单位（22 个功能里 `domain/redstone` 独占 9 个）；`domain/` 已是项目既定的包边界，不发明第二套 |
| `living/function/`（打火石） | **保留，不搬** —— A1 的原则是「注册跟着实现走」，不是顺手重构包结构 |
| 红石域 | 暂用一个入口共 9 行；若将来涨到 20+ 再拆（渐进，不提前预测） |

### 2.2 `InteractionEntry` 是否随域下放

`target × trigger → handlerId` 本质是**四元组数据**，形态接近配方。
项目已有规则外部化先例（`ContainerRuleConfig` 的「模组自带 → 玩家本地」两级加载）。

| 步 | 内容 | 状态 |
|---|---|---|
| ① | 21 条规则 + 9 个 handler 随域下放 | ✅ **已完成（A1）** |
| ② | 规则数据文件化；handler 仍为代码 | ✅ **已完成（D2，2026-09-28）** —— 见 §1.2 交互规则行 |

② 当初的硬阻碍（谓词 `Tillables::canTillWith` 是 Java 方法引用、无法落 JSON）已被
**谓词 ID 化**（`InteractionPredicates`：can_till / can_plant）化解；配置者口径见
[guides/interaction-rules.md](../guides/interaction-rules.md)，设计与论证见
`docs/archive/interaction-rule-design.md`（已归档）。

### 2.3 主类保留形态

**结论倾向：保留「一行汇总」的显式目录，不做零接触。**

核心判据是一个常被搞错的认知：

> **addon 作者本来就零接触**（他在自己 `@Mod` 里调 `registerFunction`，碰不到你的主类）
> ⇒ 「零接触」机制（ServiceLoader 等）**只对 fork 者有价值**。

而显式目录的收益对所有人成立：注册可见、出错可查、**顺序确定**。

### 2.4 容器级数据：`TickContext` 是否收敛为统一访问？（2026-09-27 新增）

| 选项 | 做法 | 评价 |
|---|---|---|
| ① 维持现状 | 4 个 public 字段，新增就加字段 | 类型安全、直观；但每加一种容器级数据都要改 `TickContext`（且它对第三方可见） |
| ② 收敛为 Map | 实现 `framework-refactoring.md` §3 当年承诺的 `getContainerData(Class)` | 可扩展；但要 cast，**丢掉类型安全** |
| ③ 维持字段 + 收敛访问器 | 字段改 private，统一走 `getOrCreateXxx()` | 折中：类型安全 + 不再暴露可变字段给第三方 |

> 尚未拍板。注意 `framework-evolution.md` 曾定「`ContainerData<T>` 通用模式等第 3 个数据点」——
> 现在已有 4 个（fluid / stress / redstone / power），**数据点够了**，但改动面较大。

---

## 3. 复算命令（本文数字来源）

```bash
rg -c "registerFunction\("     src/main/java/com/qiqi/li/LivingItem.java
rg -c "new InteractionEntry"   src/main/java/com/qiqi/li/LivingItem.java
rg -c "stack.remove\("         src/main/java/com/qiqi/li/living/api/LivingItemManager.java
rg -c "getOwnedComponentTypes" src/main/java
./gradlew test --rerun --tests "com.qiqi.li.living.api.ComponentOwnershipTest"
python tools/doc_check.py
```
