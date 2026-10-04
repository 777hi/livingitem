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
| **数据组件注册** `LivingComponents.*` | `transfer/LivingComponents.java`（A1 迁入，2026-09-28） | ✅ 可用 —— 内容组件的常量与注册站；`LivingItemManager` 自此为纯框架类，不再牵出 domain 类型 |
| `InteractionRegistry.register` / `registerHandler` | `LivingItem.java` | ✅ 声明式规则 + 谓词收窄（活耕地案例已证明无需枚举物品类） |
| `ContainerSnapshot.registerProvider` | `LivingItem.java` | ✅ 注册驱动，已解除 container 包对 domain 类的依赖 |
| 可选接口 `HasDirection` / `HasContainerData` | `living/api/` | ✅ 新增活物品无需改核心文件 |
| `SlotInteractions.register` | `SlotInteractions.java` | ⚠️ public，但全仓仅 1 处调用（内置静态块）——**从未被外部验证** |
| `LivingIconRegistry.register` | `LivingIconRegistry.java` | ⚠️ public，但内置调用时机在 mod 构造函数，无时序契约 |
| 可选钩子 `onActivated` / `onDeactivated` | `living/api/LivingItemFunction.java` | ✅ default 空实现，契约见 §1.5（2026-10-04）—— ⚠️ 与其它钩子不同，它**有守卫测试**（`ActivationHookTest`） |

#### ⚠️ 已知缺口：注册时序是隐式的

三类扩展的注册时机各不相同，且**未有任何文档声明**：

| 扩展点 | 实际时机 |
|---|---|
| `registerFunction` | `FMLCommonSetupEvent` |
| `SlotInteractions.register` | **静态块** |
| `LivingIconRegistry.registerAll` | **mod 构造函数** |

⇒ 第三方若想给自己的活物品配图标，必须在 `LivingIconRegistry.onRegisterAdditionalModels`
消费 SPECS **之前**注册；这个窗口今天只能靠读源码推出（open-plan.md §4 A3 待补）。

---

### 1.3 tick 顺序契约（✅ 2026-09-28 已显式化）

`ContainerLivingItemHandler.processContext` 按 `getAllFunctions()` 的列表顺序分组调用 `tick()`。

**规则（已生效）**：`LivingItem#commonSetup` 在所有域注册完毕后调用
`LivingItemManager.sortFunctionsByPriority()` —— 按 `LivingItemFunction#getTickPriority()`
**稳定排序**（数值小的先执行，相同优先级保持注册顺序）。

| 事实 | 说明 |
|---|---|
| 默认优先级全为 0 | ⇒ 目前 tick 顺序**仍等于注册顺序**（零行为变化；由 `TickOrderTest` 断言） |
| **只排序、不冻结注册** | 曾计划同时冻结（此后注册抛异常），实测发现会打断测试中注册 mock 功能的既有模式，而「防 tick 期间注册」防的是一个并不存在的问题 ⇒ 放弃。`registerFunction` 保持随时可注册 + 清缓存 |
| 为什么必须显式 | A1 把注册下放成 11 个分散文件后，顺序变成「主类调用次序 × 各文件内部排列」，没有任何一处能读出完整顺序 —— 本方法把这条契约变成**可声明、可 grep、可断言**的 |

> 历史：这条曾是 §1.3 的「隐式契约」警告（顺序 = 22 行书写顺序），A1 下放后升级为必须修的债，
> 现已按原 §2.4 的方案解决。`HasContainerData#getPriority()` 把容器级数据顺序显式化在先，
> 本方法是同一类改造作用于 `tick()` 本身。

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
| **I-H1** | 派发时物品**必定处于「活」状态** ⇒ 活化必须「先写标记后派发」，取消必须「先派发后清标记」 | 判据（`isLivingChest` / `isLivingToolOrWeapon`）都含 `isLivingItem` ⇒ 箱子不掉物、末影箱不清绑定、功能认不出物品 |
| **I-H2** | 派发目标是**认领该物品的功能**（`getApplicableFunctions`），**不得**用 `hasAnyFunctionFor` 的 copy 探针 | 探针结果会被写进按 `Item` 缓存的表（该表契约见 `getApplicableFunctions`） |
| **I-H3** | `onDeactivated` 返回 `false`（否决）时**不得已产生副作用** | 框架把所有认领功能问一遍，任一否决即整体中止 ⇒ 已产生的副作用无法回滚 |
| **I-H4** | `player` **可为 null**，覆盖它时必须显式写出降级行为 | 见下面的降级表 |

#### 「玩家缺席」是合法态，不是错误

| 功能 | `player == null` 时 |
|---|---|
| 活工具 | 不写 owner ⇒ 无主，回放走 `FALLBACK_UUID` 通用 FakePlayer（tooltip 不显示主人行） |
| 活末影箱 | 不绑定 ⇒ 落回**路由模式（公共黑板）** |
| 活末影箱（取消） | 照常清绑定 —— 不需要玩家 |
| **活箱子（取消）** | ⚠️ **拒绝**（返回 false），保持活状态 |

**统一口径**：**无法安全降级时拒绝操作并提示，绝不静默销毁数据。**
「拒绝」与 `evaluate` 的**策略拒绝**（配置者写的黑白名单）是两件事：**数据安全** vs **配置意图**。
否决走的是**返回值**，不占用 `Result` 枚举。

> ⚠️ 玩家为 null 的入口**今天不存在**（三个入口都持有 `player`）；
> 这套口径是为**批量转化**准备的（`docs/TODO.md` 的活经验瓶 / 活凋零玫瑰 / 活纸）。
> 上下文对象（能力袋）**刻意不做** —— 那是无终点的框架机制，触发条件见
> `activation-hook-refactoring-plan.md` §3.5。

#### 守卫

`src/test/java/com/qiqi/li/living/api/ActivationHookTest.java`（7 项）覆盖 I-H1~I-H4
+ 端到端（箱子内容保住 / 末影箱不绑定 / owner 只由活工具钩子写）。

> ⭐ **为什么必须有守卫**：新写法「忘了覆盖钩子」是**静默失败** ——
> 主人不记、绑定不清、物品不掉，系统不报错。与 §1.1 的「忘了声明」同病。

#### 可测性边界（「有玩家」侧无自动化覆盖）

单测**造不出真实玩家**（`ServerPlayer` 需要服务器）⇒ 三条分支没有自动化覆盖：

| 分支 | 覆盖方式 |
|---|---|
| 工具写 owner + 名字缓存 | ✅ 游戏内验证（2026-10-04）：点活按钮后 tooltip 出现「赋灵者」行 |
| 末影箱在 GUI 内绑定 / GUI 外不绑定 | ✅ 游戏内验证（2026-10-04） |
| 箱子取消活化掉物 | ✅ 游戏内验证（2026-10-04）：内容与堆叠倍数返还与收编前一致 |

⇒ 这**不是遗留缺口，而是可测性边界**（`docs/guides/unit-testing.md`）：
要自动化它得先有可用的 level/player 替身，属另一件事。

---

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

| 步 | 内容 | 何时做 |
|---|---|---|
| ① | 21 条规则 + 9 个 handler 随域下放 | **A1 内做** |
| ② | 规则数据文件化；handler 仍为代码 | ⛔ **暂不做** |

不现在做第②步的理由，是项目自己定的原则 —— 「从重复中提取，不从想象中设计」。
且其实现有硬阻碍：规则里的谓词（`Tillables::canTillWith`）是 Java 方法引用，
无法直接落成 JSON，需先把谓词 ID 化注册到 predicate registry。

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
