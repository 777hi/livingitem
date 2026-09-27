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
> ⭐ **为什么用反射而不是硬编码清单**：组件集合从 `LivingItemManager` 的字段反射得出，
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

第三方今天可以走这些口，全部 public：

| 扩展点 | 位置 | 状态 |
|---|---|---|
| `LivingItemManager.registerFunction` | `LivingItemManager.java` | ✅ 可用 |
| `InteractionRegistry.register` / `registerHandler` | `LivingItem.java` | ✅ 声明式规则 + 谓词收窄（活耕地案例已证明无需枚举物品类） |
| `ContainerSnapshot.registerProvider` | `LivingItem.java` | ✅ 注册驱动，已解除 container 包对 domain 类的依赖 |
| 可选接口 `HasDirection` / `HasContainerData` | `living/api/` | ✅ 新增活物品无需改核心文件 |
| `SlotInteractions.register` | `SlotInteractions.java` | ⚠️ public，但全仓仅 1 处调用（内置静态块）——**从未被外部验证** |
| `LivingIconRegistry.register` | `LivingIconRegistry.java` | ⚠️ public，但内置调用时机在 mod 构造函数，无时序契约 |

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

### 1.3 ⚠️ 已知隐式契约：tick 顺序

`LivingItemManager.FUNCTIONS` 是一个 `ArrayList`，`ContainerLivingItemHandler.processContext`
按列表顺序分组调用 `tick()`。

⇒ **今天的功能 tick 顺序 = `registerFunction` 的调用顺序。**

这个契约今天是**隐式**的（写在 `LivingItem.java` 那 22 行的排列里，看不出哪些相邻是有意的、
哪些只是巧合）。注意 `HasContainerData#getPriority()` 已经把**容器级数据**的顺序显式化了 ——
说明「顺序需要显式声明」这件事项目里已经认过一次，只是 `tick()` 本身还没有。

> ⚠️ **这是下一个要修的债**：一旦把注册下放成分散文件（A1），
> 顺序会变成「主类的调用次序 + 各文件内部排列」，从此再也读不出来。
> 处置见 §2.4。

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

### 2.4 tick 优先级显式化

| 项 | 结论 |
|---|---|
| 做什么 | 给 `LivingItemFunction` 加 `default int getTickPriority()`，注册完成后排序并**冻结**列表 |
| 解决了什么 | 补偿 A1 下放后「顺序不再可见」（§1.3）；并把口径从「书写顺序」变成「显式声明」 |
| 顺带 | 冻结后即消解 open-plan.md §1.2 **P1-1**（tick 期间注册会 `ConcurrentModificationException`） |
| 性质 | 与 §1.1 同类改造：**把隐式契约显式化** |

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
