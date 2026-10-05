> 📦 **已归档（2026-10-04）**：本次基础设施重构（1a 地基 + 1b 通用流体框架 + Q6 边界带 + B.5 落盘）
> **框架侧与流体侧均已收口**（460 测试全绿、游戏内验证功能正常）。**现状见稳定层** ——
> [living-item-infrastructure.md](../system-design/living-item-infrastructure.md)（1a/1b 契约）、
> [container-identity.md](../system-design/container-identity.md)（Q6）、
> [../tech/living-fluid-tech.md](../tech/../tech/living-fluid-tech.md)（流体机制）。
> 本文保留**执行记录 / 拍板记录 / 分工与契约底稿 / 自检清单**，供追溯。

<!-- markdownlint-disable -->

# 基础设施重构方案（infrastructure refactoring plan）

*创建: 2026-10-03 · 完成: 2026-10-04（框架侧 1a/1b + Q6 + B.5；流体侧 F1–F5）*

> ⚠️ §1「现状量化」是 **2026-10-03 的重构前快照**（多数已修复、标 ✅）—— 属**地层**，勿当现状读。

---

## 0. 为什么会有这份文档

2026-10-03 做底层架构审查，发现三笔结构性债；同日评审「活水源」设计稿
（[idea.md](../idea.md) §四.5）时确认：**其中两笔会直接挡住下一步开发**。

触发点是活水源要求「容器级永久资产」在**没有任何活物品在场**时仍然 tick。
现状是容器级逻辑的收集依赖活物品分组（`grouped`），空容器直接短路。
用户的原话诊断：「**数据是容器级的，驱动权却是活水桶的**」。

这不是活水源独有的问题 —— 它是基础设施层「**容器级逻辑没有独立身份**」这个
结构性缺失的第一个受害者。**修一次，后面所有领域受益。**

---

## 1. 现状量化

> 行号为 **2026-10-03 快照**，会漂移；复算命令随附。

### 1.1 容器级逻辑的收集依赖活物品分组 ⭐ 直接阻塞活水源

```java
// ContainerLivingItemHandler:704-718  runContainerDataTicks
for (var entry : grouped.entrySet())
    if (entry.getKey() instanceof HasContainerData) hcdEntries.add(entry);
```

遍历的是 `grouped`（**按活物品分组**的 Map）⇒ **无活物品 = 容器级逻辑不跑**。
`processContext` 的 `grouped.isEmpty()` 短路（`:446-449`）把这条路彻底堵死。

⚠️ 注意：`HasContainerData` 这个扩展点本身是**对的**（`instanceof` 收集 + priority 排序），
问题在于**它的收集源**是活物品分组。

### 1.2 容器级数据硬编码在三处

> ✅ **已修复（2026-10-03，1a-4）** —— 三处字段全部并入统一的 `ContainerDataStore`
> （按 `ContainerDataKey` 的**数组下标**存取，非哈希）。新增一种容器级数据 =
> 在 `ContainerDataKeys` 加一行。**397 测试全绿**。

**（修复前）**

| 位置 | 硬编码字段 |
|---|---|
| `ContainerLivingItemHandler.ContainerEntry` | `fluid` / `redstone` / `power` |
| `SimpleContainerContext` | `fluidData` / `redstoneData` / `powerData` |
| `TickContext` | `fluidData` / `stressData` / `powerData` |

**新增一种容器级数据 = 改 3 个类。**

⚠️ 这是「接口化只做了一半」：`HasContainerData` 完成了**调度**的接口化，
**存储与生命周期**仍是硬编码。

### 1.3 层次倒置：基础设施层反向依赖领域层

| 包 | → `domain` 的文件数 |
|---|---|
| `living/interaction` | 9 |
| `living/container` | 5 |
| `living/transfer` | 3 |
| `living/api` | 1 |
| **合计** | **18** |

另有**双向依赖**：`container ↔ transfer`（4 : 3）、`api ↔ container`（2 : 1）。

复算：

```bash
grep -rl "import com.qiqi.li.living.domain" \
  src/main/java/com/qiqi/li/living/container/ | wc -l    # → 5
```

### 1.4 `ContainerLivingItemHandler` = God Class

824 行，5 种职责：

| 职责 | 代表方法 |
|---|---|
| tick 调度 | `processContainer` / `processContext` |
| 容器级数据存储 | `CONTAINER_DATA` 静态表 |
| 位置反向索引 | `POS_TO_CACHE_KEY` |
| 内容签名 / 修订计数 | `computeContentSignature` / `bumpContainerRevision` |
| 大箱子位置解析 | `findDoubleChestPositions` |

全为 `static` 全局状态 ⇒ **单测须显式 reset 的来源**。

### 1.5 抽象泄漏

> ✅ **已修复（2026-10-03，1a-3）** —— 新增 `TickableContainerContext` 子接口，
> `processContext` 等参数类型改为它。该文件里的 `instanceof` 从 **9 处降到 2 处**
> （余下 2 处属 §1.2 的容器级数据范围，由 1a-4 处理）。**397 测试全绿**。

**（修复前）**`processContext` 中 `instanceof SimpleContainerContext` 出现 **4 次**
（`:453` / `:482` / `:503`，另 `writebackBlockEntities` 内 3 处）。

`ContainerContext` 缺三样能力，调用方只能认具体实现：
绑 TickContext / 刷脏槽 / 取关联 BlockEntity。

### 1.6 `containerKey` 第三档身份不稳定 ⭐ 落盘后升级为正确性问题

> ✅ **已修复（2026-10-03，1a-2）** —— 该分支已删除，改为显式抛异常；
> 测试替身改走新增的显式构造器。**397 个测试全绿**。详见 §2 的 Q3。

```java
// SimpleContainerContext  buildContainerKey ← 修复前的旧实现（第三档）
return "container_" + Integer.toHexString(handler.hashCode());
```

**对象身份哈希** ⇒ BE 重建后键漂移 ⇒ 落盘的容器级数据**静默丢失**（不报错）。

当前只是「性能与缓存命中率」问题；一旦容器级数据落盘
（活水源 §8 要做 `Map<containerKey, Set<slot>>`），**升级为数据正确性问题**。

### 1.7 每 tick 两次全槽位遍历（可合并）

`processContext` 每 tick 对每个容器至少遍历全部槽位**两次**：

| 遍历 | 位置 | 目的 |
|---|---|---|
| 1 | `scanAndGroupLivingItems`（`:684`） | 找出活物品并按功能分组 |
| 2 | `computeContentSignature`（`:296`） | 算内容哈希，决定是否 bump revision |

第 3 次遍历已被 `getCachedSnapshot`（`:317`）用 revision 挡住了 —— **这是已有的优化**。

⇒ 所以**空容器不是零开销**：它是两次 O(槽位数) 遍历。
⇒ **可合并**：签名与分组能在同一次遍历里完成，直接省掉一半扫描。

⚠️ 另注：`processBlockEntities` 的 javadoc（`:785`）仍写着「惰性 Tick 优化：标记容器为活跃」，
但该机制在代码里**已不存在**（`grep 活跃标记` 无实现）—— 这是第 3 处 javadoc 漂移
（另两处：`living.core.LivingFunctionConfig`、`doc_check.py` 的 docstring）。

---

## 2. 待决问题池 ⭐ 拍板前不写代码

| # | 问题 | 推荐（**待拍板**） | 阻塞 |
|---|---|---|---|
| **Q1** | 「容器级逻辑」的注册形态 | ⭐ **不新增接口**：给 `LivingItemFunction` 加一个 `default boolean shouldTickWithoutOwnItems(ContainerContext)`（默认 `false` ⇒ **现有 22 个功能全部不受影响**）。**活水源 = 没有物品载体的活物品** —— 接口本就允许 `entries` 为空，只差收集路径 | 1a-1 | ✅ **已完成 2026-10-03** |
| **Q2** | 容器级数据的存储形态 | **类型化 key + 单一存储**（`ContainerDataKey<T>` + `ctx.getData(key)`）—— 现状三处是**同一份数据的三个镜像** | 1a-4 |
| **Q3** | `containerKey` 第三档 | ⭐ **直接删，改为显式抛异常**（**无需观测期** —— 已静态证明不可达） | 1a-2 |
| **Q4** | `ContainerContext` 补哪些方法 | **抽 `TickableContainerContext` 子接口**（不污染只读的 `ContainerContext`） | 1a-3 |
| **Q5** | 纯源容器渲染的同步轨 | ⭐ **不用验证 attachment** —— 复用已有的 `ContainerRuntimeCache.flushToClients` + `LivingItemSyncPacket` | 活水源二期 |
| **Q6** | 多方块容器边界带「身份解析」收敛（流体侧移交，2026-10-03） | ⭐ 新增 `ContainerContexts`（container/ 包）共享内核 + 客户端对偶 `ClientSlotResolve`，收编 6+1 处重复消费者（详见 §2.1-Q6） | ✅ **全部完成 2026-10-04**（A 服务端 `resolve`/`isViewing`；B `ownsContainer`/`isSameSlotSpace`；C 客户端 `ClientSlotResolve`）|

**Q3 的不可达证明**：`buildContext` 唯一调用点传 `player.getInventory()`（inventory 恒非 null）；
`processContainerAt` 的 else 分支（`:759-768`）**无条件** `positions.add(pos)` ⇒ positions 恒非空。

**Q3 的处置**（2026-10-03，✅ **已完成**）：**删掉第三档，改为显式抛 `IllegalStateException`**。
理由：既无背包、又无位置、又无 BE 的容器**本质上没有稳定身份**（下次拿到的是另一个对象），
跨 tick 数据本就留不住 ⇒ **失败比静默丢数据诚实**。

⚠️ **实施时的重要修正**：原判据「不可达」**只对生产代码成立** —— 首次实施后 **43 个测试失败**。
根因：测试用**替身**（`FakeHandler` + 空 positions/entities）构造 context，走的正是第三档，
且 `SimpleContainerContextTest` 有一个**专门覆盖该分支**的用例。
⇒ **「生产不可达」≠「不可达」—— 测试也是调用者。**

**最终方案**：新增**测试专用构造器** `SimpleContainerContext(IItemHandler)` /
`(IItemHandler, Level)`（自动生成 `test#N` 唯一 key）；生产构造器保持「必须有身份」。
测试改动 **35 处**（机械替换）；`SimpleContainerContextTest` 的 key 用例改为
「测试替身应拿到自动生成的唯一 key」。
**验证**：**397 个测试全绿**（0 失败 / 0 错误）。

**Q4 的接口设计**（2026-10-03）：新增 `TickableContainerContext extends ContainerContext`，
补 4 个方法：`setTickContext(TickContext)`（传 null = 解绑）/ `flushDirtySlots()` /
`getAssociatedBlockEntities()` / `getInventory()`。
`processContext` 的参数类型改为它 ⇒ **7 处 `instanceof` 全部消失**
（4 处在主流程 + 3 处在 `writebackBlockEntities`）。
⚠️ **不塞进 `ContainerContext`** —— 那个接口是**只读能力**（读槽位 / 同步 / 身份），
混入 tick 生命周期会破坏其语义，且所有实现类被迫实现。

**Q4 的裂缝预判成真**（§6.3-A）：`ItemEntityContainerContext`（掉落物）必须新增这 4 个方法 ——
`getAssociatedBlockEntities()` 返回**空列表**、`getInventory()` 返回 **null**、
另两个空实现。⇒ 验证了当时的判断：**空列表是诚实语义，不是妥协**；
抽象划的是「tick 生命周期」而非「必须有方块实体」，故成立。

**实施结果**（2026-10-03，✅ 已完成）：`ContainerLivingItemHandler` 里的
`instanceof SimpleContainerContext` 从 **9 处降到 2 处**（比原估的 7 处多 2 处 ——
`:148` / `:216` 也在用 `getAssociatedBlockEntities()`，但它们属于容器级数据范围，留给 1a-4）。
`flushDirtySlots()` 顺带从**包级私有改为 public**（接口方法要求）。
**397 测试全绿**。

**Q1 的关键约束**：`shouldTickWithoutOwnItems()` **必须免扫描**（不得遍历槽位）—— 否则会把
§1.7 的两次遍历变成三次。详见 §6.3-B。

**Q1 的关键机制**（为什么只加 4 行就够）：把自持功能 `computeIfAbsent` 塞进 `grouped`
（其 `entries` 为空列表）⇒ **短路条件 `grouped.isEmpty()` 一个字不用改**，
后续所有阶段（功能 tick / 容器级数据 / 写回）**原样复用**。

**Q1 的方案演进留痕**：原推荐是「新增 `ContainerLevelLogic` 接口 + 独立注册表」；
用户提出视角「**活水源 = 没有具体物品的活物品**」后改为现方案 —— 概念数不增、
复用 `getTickPriority` 排序、`processContext` 只改 4 行。
⚠️ 方案 A 曾声称的附带收益「消掉层次倒置一大半」**是误判** —— 那是 Q2 的职责。

**Q1 的性能实测与最终决策**（2026-10-03）：若每 tick 逐功能判定，需遍历 **22 个**已注册功能
（`LivingItemManager.FUNCTIONS`，实测）≈ 110–220 ns/容器。100 容器仅 0.02–0.04%，
**但按用户「上万容器」前提**（大型整合包跑图）⇒ **10,000 容器 = 1.1–2.2 ms/tick ≈ 2–4% 预算**。
⇒ **最终改为注册期预计算**：`LivingItemManager` 在注册 / 排序时算出静态「自维持清单」
（`SELF_SUSTAINING_FUNCTIONS`），每 tick 只遍历该清单（当前 **0~1 个**）⇒ 开销恒定、**与功能总数无关**。
（注：热路径上 `getApplicableFunctions` 有 `APPLICABLE_CACHE`、`getCachedSnapshot` 有 revision 机制 ——
本循环曾是唯一无缓存的一段，现已消除。**1a-1 已完成，见 §3。**）

**Q2 的关键决策**（2026-10-03）：

- **放 `living/container/`**（不放 `api`）—— `ContainerDataKey` / `ContainerDataStore` 本质是
  容器层机制；这样只需 `api → container`（**该依赖已存在**）⇒ **不新增跨包依赖**。
- **数组下标而非哈希**：`ContainerDataKey` 自带全局递增 `index`，`ContainerDataStore` 用
  `Object[]` 存储 ⇒ 访问成本 ≈ 原字段读（~1ns）⇒ **Q2 不给热路径加负担**（不进 TODO 性能清单）。
- **key 自己声明层级**（`persistent(...)` 带 attachment / `of(...)` 为 tick 级）⇒
  `TickContext.data(key)` 的查找路径**确定**，无需"逐层兜底"。
- **落盘零硬编码**：`writebackBlockEntities` 改为遍历 `ContainerDataKey.all()`，
  按 key 声明的 attachment 写回 ⇒ **新增一种落盘数据 = 改 0 个核心文件**。
- **改造面实测**：四类数据全项目仅 **14 处**字段访问（`fluidData` 10 / `stressData` 3 /
  `powerData` 1 / `redstoneData` **0** —— 红电走 `getOrCreateRedstoneData()` 方法），
  分布 6 个文件 ⇒ **不需要过渡期，可直接改到位**。

> 📌 `getOrCreateRedstoneData()` / `getOrCreatePowerData()` 等**保留方法签名**（内部改走
> `getOrCreate(key)`）⇒ 红电的调用点不用动。

**实施结果**（2026-10-03，✅ 已完成）：

- 新增 3 个类：`ContainerDataKey`（类型化 key + 数组下标）/ `ContainerDataStore`（统一存储）/
  `ContainerDataKeys`（4 个 key 清单，**集中定义** —— 「领域各自定义」留作后续可选迁移）
- `ContainerContext` 加 2 个 default 方法（`peekContainerData` / `getOrCreateContainerData`）⇒
  `TickContext` 与功能类通过**统一入口**取数，无需按类型 switch
- `ContainerEntry` 3 字段 → 1 个 store；`SimpleContainerContext` 3 字段**删除**（改为委托）；
  `TickContext` 4 字段 → store + `data()` / `fluidData()` / `stressData()` / `powerData()`
- 功能类访问点 `tick.fluidData` → `tick.fluidData()`（**加括号即可**，语义不变）
- ⚠️ **偏差**：落盘「遍历 key」只覆盖声明了 attachment 的 FLUID；应力 / 相位快照的写回
  **保持硬编码**（它们有各自的特殊逻辑，不属于通用落盘）
- **397 测试全绿**（0 失败）

> Q5 不阻塞本次重构，但**阻塞活水源二期** —— 建议尽早做最小实验。

**Q6 的发现（2026-10-03，流体侧移交——本会话按分工不实施，只交底）**：

原版/NeoForge 的多方块容器**没有统一身份句柄**——Container 实例、BlockEntity+位置、
合并 IItemHandler 三个视图互不可达（`CompoundContainer` 不给两半访问器、菜单不暴露位置）。
每个需要「菜单槽位 / 同步 / 世界 ↔ 容器状态」的消费者被迫各付一次，现存 **6 处**：

| # | 消费者 | 解法 | 场景 |
|---|---|---|---|
| 1 | `DoubleChestPositions.find` | 位置级找伙伴（LEFT/RIGHT 规范序） | 上下文构建 / 面选取 |
| 2 | `ContainerRuntimeCache.isViewingContainer` | `CompoundContainer.contains` 特判 | 查看者匹配（v19.1） |
| 3 | `SimpleContainerContext.slotBelongsTo` | 容器归属匹配 | 大箱子 tooltip 同步（2026-09-09） |
| 4 | `CrossContainerTransfer.getBasePosCandidates` | 候选基准块列表 | 跨容器面选取（hopper §6.4） |
| 5 | `isSameSlotSpaceAsHandler` 探针 | 槽位体系一致性仲裁 | 活漏斗静默不传输（hopper §10.25） |
| 6 | `LivingBucketInteractSupport.compoundContext` | 反射掏两半 + find 规范化 | 汲/倒反查活流体数据（流体侧批次二，2026-10-03） |

另客户端 `resolveContainerSlot`（SlotWrapper 反射，创造模式）同构 —— 包装器把
「槽位 ↔ 容器」直达链路藏起来，是同一个病的另一种形态。

**核心（tick 主链路）不乱**：上下文以 positions/BEs/合并 handler 单一事实构建，
§10.25 后「以合并 handler 为唯一槽位体系」已成口径。乱的是**边界带**：每个 GUI 级
新功能都重新踩一遍，且教训散落四处（hopper §10.25/§6.4、container-compatibility、
changelog）——流体侧实现汲/倒时是把先例全部翻过一遍才敢下手的。

**推荐方案（原提案；已按此实装，见下方批次 A/B/C 实施记录）**：新增 `ContainerContexts`（container/ 包）共享内核——

```
ContainerContexts
  ├─ resolve(Slot|Container, player/level) → TickableContainerContext
  │    （底稿 = LivingBucketInteractSupport.compoundContext，现成可用）
  ├─ isViewing(player, context)        （底稿 = ContainerRuntimeCache.isViewingContainer）
  ├─ ownsContainer(context, Container) （底稿 = slotBelongsTo）
  └─ resolveMenuSlot(menu, containerSlot)（客户端 SlotWrapper 解析，底稿 = GuiInteractionHelper）
```

> ⚠️ **实装差异（2026-10-04）**：实际方法名 / 签名与此草图略有出入 ——
> 服务端 `ownsContainer(Container, Collection<Container>)`（非 `ownsContainer(context, Container)`）、
> 并新增 `isSameSlotSpace(Container, IItemHandler, int)`；客户端 `resolveMenuSlot` 因
> `SlotWrapperAccessor` 是**客户端 Mixin**（common 包引用会让专用服务端崩）⇒
> 实装为 `client/util/ClientSlotResolve.resolveContainerSlot(Slot)`（**被迫分居两侧**）。

要点：
- **收编是搬家不是重写** —— 四个实现全是已验证的现成代码，含各自的坑位注释；
- 单一 javadoc 契约 + 一份「多方块容器身份解析」文档（收编 hopper §10.25 的教训：
  按逻辑槽位读写先验证两套体系；模拟与真实写入同源）；
- 验收判据可复算：6 个消费者改走新入口（或保留薄委托），`grep CompoundContainer`
  的特判从 6 处收敛到 1 处；
- **触发时机建议**：第 7 个消费者落地前。流体侧管线里的活活塞（推活物品进源格）、
  岩浆桶、转化自动化都冲着这条边界来，且流体侧不排除把 `compoundContext` 的
  反射底稿直接上交。

**批次 A 实施（2026-10-04，✅ 已完成）**：只落地服务端两个入口，原则「搬家不是重写」——
- 新增 `container/ContainerContexts.java`（共享内核），`resolve(player, slot)` 与 `isViewing(player, containers)`
  均逐字迁移自已验证实现（`LivingBucketInteractSupport.compoundContext` /
  `ContainerRuntimeCache.isViewingContainer` 与 `FluidFlowServerSync.isViewingContainer` 两份同构），
  含各自坑位注释（反射掏两半、`DoubleChestPositions.find` 规范化、v19.1 大箱 `CompoundContainer.contains` 特判）；
- 三个消费者改薄委托：`LivingBucketInteractSupport.resolveContext` → `ContainerContexts.resolve`；
  `ContainerRuntimeCache.isViewingContainer` / `FluidFlowServerSync.isViewingContainer` → `ContainerContexts.isViewing`；
- 验收（可复算）：`grep CompoundContainer` 特判由 **3 处**（原 compoundContext + 2 处 isViewing，
  不含客户端 SlotWrapper）收敛到 **1 处**（`ContainerContexts` 内部）；全量单测回归绿。
- 末影箱水网仍为已知缺口（`resolve` 解析不出 `EnderChestContainerContext` 覆写的玩家键 → 返回 null）。
- **稳定层文档已建**：`docs/system-design/container-identity.md`「多方块容器身份解析（边界带）」——
  收编问题陈述 / 内核契约（含两条不变量）/ 消费者清单 / 通用教训 / 已知缺口。本文（buffer）继续承载
  **未定案**的后续批次方案与拍板记录，稳定后按 buffer 收敛三步法并入。

**批次 B 实施（2026-10-04，✅ 已完成）**：补服务端两个入口，同样「搬家不是重写」——
- `ContainerContexts.ownsContainer(Container, Collection<Container>)`：收编 `SimpleContainerContext.slotBelongsTo`
  （组件同步归属验证）；并把 `isViewing` 的逐槽判据提取为它（两者同源：`isViewing` = 菜单里任一槽 `ownsContainer`）。
- `ContainerContexts.isSameSlotSpace(Container, IItemHandler, int)`：收编 `SimpleContainerContext.isSameSlotSpaceAsHandler`
  （hopper §10.25 两级探针）。
- 两个消费者改薄委托：`SimpleContainerContext.slotBelongsTo` → `ownsContainer`；
  `SimpleContainerContext.isSameSlotSpaceAsHandler` → `isSameSlotSpace`。
- 新增 `ContainerContextsTest`（10 项：`ownsContainer` 单箱/大箱 `CompoundContainer`/防跨容器虚影/空集；
  `isSameSlotSpace` 槽位数不一致/越界/同空/同物品/一空一非空/异物品）。
- 验收（可复算）：`grep -rn CompoundContainer src/main/java` 的**代码特判**收敛到 **1 处**
  （`ContainerContexts`，其余全为注释）；全量单测回归绿。
- ⚠️ **`CrossContainerTransfer.getBasePosCandidates` 不迁移**（判断）：它不是重复项（仅本处使用），
  属传输「面选取」而非「身份解析」，且不在 Q6 推荐 API 内；其通用教训（跨容器面候选不做结构假设）
  已在 `container-identity.md` §4 收编，`living-hopper-tech.md` §6.4 亦已加指针。
**批次 C 实施（2026-10-04，✅ 已完成）**：客户端收尾——
- ⚠️ **不能放 `ContainerContexts`**：`SlotWrapperAccessor` 是客户端 Mixin，common 包引用它会让**专用服务端**崩
  ⇒ 新建客户端对偶 `client/util/ClientSlotResolve.resolveContainerSlot(Slot)`（**被迫分居两侧**）。
- 收编 `GuiInteractionHelper.resolveContainerSlot` + `AbstractContainerScreenMixin.living_item$resolveContainerSlot`
  （后者多一层反射兜底，统一保留；顺带删除 Mixin 里已死的 `WATER_LOGGER` / `SLOT_WRAPPER_FIELD_CACHE` 字段）。
- 两个消费者改薄委托；客户端无自动化测试，靠游戏内验证（与 tooltip 客户端路径同口径）。
- **Q6 三个批次至此全部落地**。

---

## 3. 分步计划（草案，待 §2 拍板）

### 1a · 地基（**纯重构，行为不变**）

| 步骤 | 内容 | 验收判据（可复算） |
|---|---|---|
| 1a-1 | ✅ **已完成 2026-10-03** —— `LivingItemFunction` 加 `shouldTickWithoutOwnItems`（默认 `false`）+ `LivingItemManager` 注册期算静态自维持清单 + `processContext` 在 `grouped.isEmpty()` 前把清单塞进 `grouped`（空 entries） | **397 测试全绿**（0 失败 / 0 错误）；当前无函数覆写（活水源在 1b）；**完全空容器仍短路**；每 tick 只遍历静态清单（0~1 个），非逐函数判定 |
| 1a-2 | ✅ **已完成 2026-10-03** —— 删 `containerKey` 第三档改抛异常 + 新增测试专用构造器 | **397 测试全绿**（0 失败 / 0 错误） |
| 1a-3 | ✅ **已完成 2026-10-03** —— 抽 `TickableContainerContext`，消掉 7 处转型 | 该文件里 `instanceof` **9 → 2**（余 2 处属 1a-4）；**397 测试全绿** |
| 1a-4 | ✅ **已完成 2026-10-03** —— 容器级数据并入 `ContainerDataStore`（新增 3 个类） | 新增一种数据 = 改 **1 行**（`ContainerDataKeys`）；**397 测试全绿** |

**1a 全部是行为不变的重构** —— 靠现有 397 个测试回归验证，不引入新功能。

### 1b · 通用流体框架（落地方案，2026-10-03）

> ⚠️ **协作边界（先读这条）**：本节只写**框架** —— 容器级流体的**基础设施** + **给流体侧的契约**。
> **流体本身的行为**（接哪些流体、流速参数、跨流体交互、转化表）由**另一路 AI** 负责，
> 设计稿见 [idea.md](../idea.md)。**框架先行** —— 框架就位，流体侧才能推进。
> 本节的「契约」必须留在**仓库**（不是工具侧记忆）：换个 AI 工具之后它仍要可见。

**方向定案：混合（身份复用 NeoForge + 引擎自建泛化 + 行为分档）**

| 层 | 来源 | 理由 |
|---|---|---|
| 流体身份 `FluidStack` / `FluidType` | **复用 NeoForge** | 生态标准；模组流体天然可用；自建 = 重造轮子 |
| 容器内流动引擎 | **自建（泛化）** | NeoForge **不提供**「容器格子内蔓延」，是本项目独有发明 |
| 活空桶 | **复用 NeoForge 做法** | 存 `FluidStack`（`SimpleFluidContent` 组件），与 NeoForge 桶同构 |

⚠️ **关键解耦：「能倒出」≠「会流动」** —— 水 / 岩浆会流；很多模组流体**静止**。
⇒ 行为**分档**：会流动的走扩散引擎，静止的只做「源」。**不做分档就接不住模组流体。**
⚠️ **不违背「不为假设的第三方做扩展点」**（§5）：本节**不新增项目专属扩展点**，只**改用生态标准类型**。

> 1a 与 1b **必须分开做**：合在一起出问题，分不清是重构引入的还是流体逻辑的锅。

#### B.1 分工表（谁做什么）

| 项 | 动作 | 归属 |
|---|---|---|
| `ContainerFluidData` 泛化（隐式水 → 按流体类型索引） | 改 | **框架（本节）** |
| 流动引擎泛化（BFS 只在同种流体内扩散） | 改 | **框架** |
| 行为分档**接口**（「流动 / 静止」的判定与参数入口） | 定义接口 | **框架（契约）** |
| 容器级流体 tick 的归属与排序（B.2） | 改 | **框架** |
| 红石归零解耦（B.3） | 改 | **框架** |
| `CONTAINER_FLUID_DATA` 落盘（B.5） | 改 | **框架** |
| 具体流体（水 / 岩浆 / 模组）的注册、流速、交互、转化表 | 实现 | **流体侧（另一 AI）** |
| 活空桶的汲 / 倒**交互规则** | 实现 | **流体侧** |

#### B.2 容器级流体 tick 的归属与排序 ⭐（框架契约）

**原则**：容器级流体状态（源 / 流动）的 tick 由**容器级函数**驱动，**不挂在桶物品上**
（现状「数据是容器级的、驱动权却是桶的」—— idea.md §四.5）。

**机制（1a-1 已就位）**：自维持通道 —— `LivingItemFunction.shouldTickWithoutOwnItems`
+ 注册期静态清单 + 塞进 `grouped`。
**决策**：**框架提供通用驱动函数**（自维持 + `HasContainerData`），流体侧**只填行为、不写驱动**
（避免每种流体各写一份 tick 逻辑）。框架自动让它在**纯源容器**（无物品）里也跑。

**排序**：容器级数据由 **`HasContainerData.getPriority()`** 稳定排序（**不是** `getTickPriority()`）：

| prio | 功能（现状） | 说明 |
|---|---|---|
| 0 | 水桶 | 流体 BFS 先算 |
| 1 | 水车 | 读流体算应力 |
| 2 | 红石 ×9 | — |
| 3 | 铜 / 电力 | 读红石算电力 |

⇒ **流体侧新增的容器级函数必须声明合适 prio**：**先于**读它结果的功能、**后于**它读的功能。
⚠️ 排序**只能**靠 `HasContainerData.getPriority()` —— `getTickPriority()` 管不到容器级数据
（自维持函数被追加到 `grouped` 末尾，见 §6.3-D 终审修正）。

#### B.3 终审前提之一：红石归零解耦

`handleEmptyContainer` 的红石归零目前**寄生在 `grouped.isEmpty()` 短路分支**里
（peek REDSTONE 数据 → `rd.calculate()` 归零）。含活水源的容器 `grouped` 非空 ⇒ 该分支被跳过
⇒ 残留红石（此前放过的活红石移走后留下）**得不到归零**。
⇒ 抽出 `zeroResidualRedstone(ctx)`，判据改为「容器有 REDSTONE 数据 **但** `grouped` 里无
`LivingRedstoneFunction`」，**与 `grouped` 是否为空无关**。⚠️ 这顺带修掉一个**既有**潜在缺陷
（有其它活物品 + 残留红石的容器，今天也不归零）。**1a-1 本身无此问题**（清单恒空，短路照旧）。

#### B.4 流体无关引擎（框架）

**存储决策**：`ContainerFluidData` 用**单张 map 带流体类型**（一槽一流体，类比「一个方块位置只装一种流体」），
**不**用「每流体一张 map」。

**引擎**：
- `ContainerFluidData`：「隐式水」→「**按流体类型索引**」—— 源 / 流动都带流体类型（`FluidStack` / `FluidType`）。
- 流动引擎：BFS **只在同种流体**内扩散；跨流体交互（水 + 岩浆 → 石 / 黑曜石）作为**可选钩子**。
- **契约**：引擎只认「流体类型 + 行为入口」，**不认具体是水还是岩浆** ⇒ 新增流体**零改引擎**。

**行为分档（每流体声明，取值由流体侧填）**：

| 参数 | 含义 | 备注 |
|---|---|---|
| `canFlow` | 会流动 / 静止 | 静止的只做「源」，不进扩散 |
| `maxLevel` | **level 上限 = 流动距离** | 水 7、岩浆 3（各自不同）—— 用户明确要 |
| `flowSpeed` | 流速（扩散节拍） | ⚠️ **预留字段**，当前用途不明，先加上（同 idea.md §三 的 `interval` 思路） |

**具体流体行为的取值**（水 / 岩浆的 `maxLevel`、流速、跨流体交互、转化表、晋升规则）→ **流体侧（另一 AI）**。

#### B.5 落盘（框架）

- ✅ `ContainerFluidData` 加 `CODEC`；`CONTAINER_FLUID_DATA` 加 `.serialize(CODEC)`
  （照 `CONTAINER_PHASE_SNAPSHOT` 的既有先例 —— 它已带 `.serialize`，可作模板）。**2026-10-03 完成**。
- ⚠️ **只落「源」，不落「流动」**：流动每 tick 由 BFS 重算，落盘无正确性价值（源才是资产）。
- ✅ **玩家背包 / 末影箱**（2026-10-04 完成）：新增 **Player attachment**
  `CONTAINER_FLUID_DATA_PLAYER`（`Map<容器键, ContainerFluidData>`，`ContainerFluidData.KEYED_CODEC`）
  —— 倒水主场景在背包，此项必做。
  ⚠️ **与原草案的偏差**：草案写 `Map<containerKey, Set<slot>>`，实装为 `Map<containerKey, ContainerFluidData>`
  —— 只存 slot 集合会**丢流体类型**（派生源 = 槽位 → 流体），故直接存整份流体数据（值仍只含派生源）。

#### B.6 分期（框架侧）

| 期 | 内容 | 验证 |
|---|---|---|
| **1b-1 引擎泛化** | ✅ **已完成 2026-10-03** —— `ContainerFluidData` 条目带流体类型（单张 map），水行为零变化 | **403 测试全绿**（397 + 新增 6 条行为快照） |
| **1b-2 框架契约** | 行为分档接口 + 自维持函数骨架 | 新测试：**排序断言**（source 先于 bucket 先于 redstone）+ **红石归零解耦回归** |
| 流体侧 | 接水 / 岩浆 / 模组流体 | 另一 AI |

> **1b-1 是纯重构**：靠「397 绿 + 行为不变」验收 —— **可被非程序员验证**。

#### B.7 待定 / 风险

- **Q5（不阻塞一期，偏流体侧）**：**纯源容器渲染**的第二条同步轨 —— NeoForge **BE** attachment 的
  客户端同步语义待验证；一期可先只做桶轨。
- `ContainerFluidData.CODEC` 是否需序列化「流动」：**否**（见 B.5）。
- 背包「每 tick 无条件 `processContainer`」路径能否承载自维持函数：**应可以**（同一 `grouped` 通道），
  实现时验证。

#### B.8 框架侧任务清单（供流体侧对照）

**1b-1 引擎泛化（纯重构，水行为不变）** ✅ **已完成 2026-10-03**

1. ✅ `ContainerFluidData` 条目带流体类型（**单张 map**：`FlowEntry.fluid`）。
2. ✅ `recalculate()` 播种 / BFS 扩散按类型：`registerSource(slot, fluid)`；同槽换流体**整条覆盖**；已占格**不被别的流体覆盖**。
3. ✅ 验收：**403 测试全绿**（397 + 新增 6 条 `ContainerFluidDataTest` 行为快照）。

> ⚠️ **关键做法**：本类此前**零单测** ⇒ 先补 6 条「行为快照」（golden master）钉住水行为，**再**重构 ——
> 否则「397 绿」只证明别的层没坏、证明不了引擎行为没变。
> 多流体的「行为分档 / 每流体上限 / 桶内容判定 / 跨流体交互」**仍归 1b-2**（本步按水处理，`isLivingBucketOf` / `MAX_FLOW_LEVEL` 保持水常量，已标 TODO）。

**1b-2 框架契约（新增接口）**

4. ✅ **行为分档接口**（2026-10-03）—— `FluidFlowBehavior`（`canFlow` / `maxLevel` / `flowSpeed`）
   + `FluidFlowBehaviors` 注册表；**未注册流体 = 静止**（安全默认）；水在 `WaterRegistration` 注册（上限 7）。
   引擎改用接缝（`ContainerFluidData` **不再硬编码上限**）。**405 测试全绿**。
5. ✅ **通用驱动函数**（2026-10-03）—— 新增 `LivingFluidFunction`（**自维持** + `HasContainerData` **prio 0**）：
   驱动容器级流体 BFS，与桶**解耦**（桶的 `tickContainerData` 只留 `postTickSync`，prio **0→1**）。
   ⇒ 桶不在场时容器级流体照样推进。**已端到端验证**（`ContainerFluidIntegrationTest` 走真实
   `processContext`）；**411 测试全绿**。
6. ✅ **红石归零解耦**（2026-10-03）—— 抽出 `zeroResidualRedstone(grouped, ctx, tick)`：容器有
   REDSTONE 数据但 `grouped` 里无 `LivingRedstoneFunction` ⇒ 主动归零，**判据与 `grouped` 是否为空无关**。
   ⚠️ 该问题已被 **1b-2b 提前触发**（自维持驱动使 `grouped` 恒非空 ⇒ 原 `handleEmptyContainer` 分支
   **永不执行**）。**412 测试全绿**（含 1b-2c 回归守卫，已实测「禁用即挂」）。
7. ✅ **API**（2026-10-03）—— `ContainerFluidData` 补**查询**接口 `isSource(slot)` / `sourceFluid(slot)` /
   `hasAnySource()`（**改**已有 `registerSource` / `removeSource`）。供汲 / 倒处理器用。**413 测试全绿**。
8. ✅ **落盘**（2026-10-03，**框架侧**）—— 流体侧已备好 `generatedSources: Map<Integer, FluidType>`；
   框架接上 `ContainerFluidData.CODEC`（**只序列化派生源**，桶源 / 流动表不落）+ `CONTAINER_FLUID_DATA`
   附件 `.serialize`。
   ✅ **背包 Player attachment 已补**（2026-10-04，B.5 第三项）—— `CONTAINER_FLUID_DATA_PLAYER`
   （`Map<容器键, ContainerFluidData>`，`KEYED_CODEC`）挂 **Player**，覆盖背包 + 末影箱（一个玩家两个容器）。
9. ✅ **同步轨泛化（Q5）** —— **已由流体侧落地**（`FluidFlowSyncPacket` + `FluidFlowServerSync`
   + `FluidFlowClientCache`，挂 `LivingFluidFunction.tickContainerData` 尾部，零框架文件改动）。
10. ✅ **引擎接缝**（2026-10-03，**框架侧**）—— 流体侧点名的两条，都是 `FluidFlowBehavior` 的
   **default no-op**（现有行为零变化）：
   - `shouldPromote(slot, sourceNeighborCount)` —— 引擎 `recalculate` 加**晋升收敛循环**（升格为源后重跑 BFS）；
   - `transformItem(item)` —— 引擎 `tick` **每流体拍**在源格调用，产物写回该格。

**之后**：流体侧接水 / 岩浆 / 模组流体。

> **📌 流体侧进展登记（2026-10-03 晚，另一路 AI / 批次一，419 测试全绿）**
>
> - **⑧ 落盘的前置已就位**：流体侧已在 `ContainerFluidData` 定义并实现
>   `generatedSources: Map<Integer, FluidType>`（含生命周期 API
>   `registerGeneratedSource` / `removeGeneratedSource` / `isGeneratedSource` /
>   `hasGeneratedSources` / `getGeneratedSources()` 只读视图）—— **CODEC 只序列化这张 map
>   即可接线**（flow 表仍不落）。渲染同步轨⑨也已由流体侧落地
>   （`FluidFlowSyncPacket` + `FluidFlowServerSync` + `FluidFlowClientCache`，挂在
>   `LivingFluidFunction.tickContainerData` 尾部，零框架文件改动）。
> - **向框架侧的接缝请求**（流体侧行为填充用，见 idea.md §〇 第 4 条）：
>   ① 晋升 hook —— `recalculate` 收敛循环问行为「该格是否升源」（默认 no-op）；
>   ② 转化 hook —— 每流体拍在源格问行为「格上物品是否转化」（默认 no-op）。
>   二者作为 `FluidFlowBehavior` 可选 default 方法，不破坏「流体侧只填行为」分工。
> - **桶源退役已拍板**（idea.md §〇.5）但**顺延**至流体侧批次二（与汲/倒同批）——
>   批次一期间 `isLivingBucketOf` 桶源路径保持现状，删它时连同旧桶轨渲染回退一起清。

---

## 7. 框架侧待办移交（2026-10-04，流体侧）

> 本文档主体已归档，但这两件是**新增框架侧待办**，规格在此移交（流体侧游戏实测为验收）。
> 完成后结论沉淀 `living-item-infrastructure.md`，本节删除。

### F-1 `ContainerContexts` 补末影箱分支 ⭐（解锁活流体的末影箱汲/倒）

**现状**：`resolve(player, slot)` 对末影箱菜单返回 null（无分支）——
`EnderChestContainerContext` 是 `ContainerLivingItemHandler` 的 **private 嵌套类**，
流体侧无法自行构造。流体侧其余部分**已全部就绪**：汲/倒处理器、Player attachment 落盘
（键 = `player_<uuid>_ender_chest`）、路由模式共享水网语义——分支一到位即通。

**规格**：
1. **识别**：末影箱 GUI 菜单槽位的 `slot.container instanceof PlayerEnderChestContainer`
   （原版末影箱界面容器是 `PlayerEnderChestContainer`，**不是 BE**——这正是它走不进
   `resolve` 现有背包 / 单 BE / CompoundContainer 三分支的原因）；
2. **构造**：`new InvWrapper(player.getEnderChestInventory())` +
   `new EnderChestContainerContext(handler, player, level)`（参照 `processEnderChest` 现有构建）；
   类可见性需从 private 提升——建议顺手迁出 `ContainerLivingItemHandler`（迁哪由框架侧定）；
3. **键语义**：`player_<uuid>_ender_chest` 覆写已存在；一个玩家一个末影箱容器；
   路由模式多箱共享同一键（涌现：共享水网，idea.md §四）。

**验收**：流体侧游戏实测——末影箱 GUI 内倒水 / 汲水 / 渲染。
⚠️ 配套（**流体侧自留**）：`FluidFlowServerSync.flushAfterTick` 的派发当前只有
背包（直发）/ BE（菜单匹配）两条路，分支到位后需补第三条
（`EnderChestContainerContext.player` 直发）——已登记流体侧 TODO，分支合入即做。

### F-2 缓存清理注册表（TODO.md A2，治本）

**背景**：两次漏挂事故（Q3「测试也是调用者」、流体侧 `CLIENT_ACTIVE` 未挂
`ServerStopped`）。现有清理散落两处：`LivingItem.onServerStopped`（服务端 7 项）+
`FluidClientCacheCleanup`（客户端 LoggingOut，1 项）——全是「自愿挂靠」，靠记性。

**规格**：
1. 注册表：服务端 / 客户端各一张生命周期表（`ServerStopped` 遍历执行、
   `LoggingOut` 遍历执行）；表结构设计自由度框架侧定，约束是**登记一行即被覆盖**；
2. 首批条目迁移：`onServerStopped` 现有 7 项 + `FluidClientCacheCleanup` 现有 1 项
   （迁移 = 改调用点，行为不变）；
3. A1 成文：**新增 static 缓存 ⇒ 必须登记**（落「排查铁律」或
   `living-item-infrastructure.md`）；
4. 验收：新缓存登记一行即被两个生命周期覆盖；全量测试绿；doc_check 过。

### F-3（预告，未拍板，不动手）跨流体交互钩子

水遇岩浆语义若最终选「原版完全对齐」（蔓延接触生成石头/黑曜石），需要引擎在
`spread()` 阻挡/覆盖时向行为问「相遇产物」的钩子（`FluidFlowBehavior` 第三条
default no-op）。流体侧拍板前**不动**——当前口径是 v1 接受互不侵犯（idea.md §〇 第 5 条）。

---

## 4. 每一步都必须做的验证协议

沿用 [power-refactoring-plan.md](../buffer/power-refactoring-plan.md) §6 的三条：

1. **改前先量化** —— 把现状数字写进 §1（行数 / 调用点 / grep 计数），改完复算对比。
2. **改后跑全量测试** —— `./gradlew test --rerun`（`FROM-CACHE` 是**假绿**），
   再跑 `python tools/doc_check.py`。
3. **证明调用点真的经过新路径** —— 把新原语临时退回旧实现，应**恰好**挂掉 N 个测试；
   数字对不上说明还有旁路没接上。

---

## 5. 明确不做

- ❌ **不为「假设的第三方」做扩展点** —— 等第一个真实第三方出现再动
  （见 [open-plan.md](../buffer/open-plan.md) §C 的既有结论）。
- ❌ **不消灭 `container ↔ transfer` 的双向依赖** —— 成本高于收益，记录在案即可。
- ❌ **不顺手拆 `RecipeBookComponentMixin`**（1293 行）—— 那是客户端侧的另一笔债，
  与本次基础设施改造无交集，另案处理。

---

## 6. 重构后的预期与自检清单

> ⭐ **供下次架构审查时对照**：哪些预测对了、哪些漏了。
> 这份清单的价值不在"记录结论"，而在"**可被证伪**"。

### 6.1 会消失（4 条）

| 现状债（§1） | 消失原因 |
|---|---|
| 1.1 容器级逻辑收集依赖活物品分组 | `shouldTickWithoutOwnItems` + 塞进 `grouped`（**不新增接口**） |
| 1.2 容器级数据硬编码在三处 | 类型化 key + 单一存储 |
| 1.5 抽象泄漏（4 处 `instanceof`） | `TickableContainerContext` 子接口 |
| 1.6 `containerKey` 第三档 | 死代码，直接删 |

### 6.2 会残留（2 条）

- **1.3 层次倒置只解决 1/4**：`container` 5 → 0，但 `transfer` 3 / `api` 1 / **`interaction` 9** 不动。
  ⚠️ `interaction` 的 9 个依赖**不是"债"，是"错位"** —— 它是「通用框架 + 各领域交互实现」混装
  （`PlantCropHandler` / `IgniteHandler` / `RepeaterCycleHandler` …）。
  下次审查应指出：**该再拆一次 —— 框架留下、实现下放各 domain。**
- **1.4 God Class 只缓解**：824 行 → 约 600 行，仍剩 **4 个职责**
  （调度 / 位置索引 / 内容签名 / 大箱子解析）。
- ~~**多方块容器边界带的身份解析重复**~~（新发现，2026-10-03 流体侧移交）：6 处消费者
  各写一次匹配逻辑，见 §2.1-Q6 —— 与「interaction 该再拆一次」同属
  「框架留下、实现收拢」的整理，收编方案已在 Q6 给出底稿清单。
  → **✅ 已由 Q6 收编（2026-10-04，批次 A/B/C 全部落地）**，见
  [container-identity.md](../system-design/container-identity.md)。

### 6.3 会新暴露（4 条）

| # | 新问题 | 说明 |
|---|---|---|
| **A** | `getAssociatedBlockEntities()` 对掉落物容器返回空 | 空列表是诚实语义，但审查会停下来看这里算不算又一次抽象不完整 |
| **B** | 性能上限从"没有"变成"有一个数" | ⚠️ **本轮判断已修正**：空容器**本来就不是零开销**（§1.7 两次全槽位遍历）。最终设计**不在每 tick 逐功能判定**（注册期算静态清单，每 tick 只遍历 **0~1 个**）⇒ **不新增每容器每 tick 开销**。真正的优化机会是**合并 §1.7 那两次遍历**，与本次重构正交 |
| **C** | 注册点可能成为新膨胀源 | `LivingItem.commonSetup` 继续变长（注册式扩展的固有代价） |
| **D** | `getTickPriority()` 第二次被点名 | ⚠️ **终审修正（1a-1 后）**：原判「活水源声明 priority ⇒ `sortFunctions` 首次生效」**不准确**。① 红石「prio 2」实为 **`HasContainerData.getPriority()`**（`runContainerDataTicks` 里排序，**早已在用**），与 `getTickPriority()` 是**两套机制**；② `runFunctionTicks` 遍历的是 `grouped`（**插入序**，非 `FUNCTIONS` 排序序），且自维持函数被**追加到最后** ⇒ `getTickPriority()` **管不到**自维持函数的执行位置。⇒ 1b 若需「水源先于红石」，须靠 **`HasContainerData.getPriority()`**（容器级数据路径），而非 `getTickPriority()`。**`getTickPriority()` 至今仍未被真正实践** |

### 6.4 够不到（本次范围外）

`RecipeBookComponentMixin` 1293 行 · javadoc 漂移（已发现 3 处）· client 8002 行仅 1 个测试 ·
`SlotAccessor.unwrap()` · `LivingItemFunction` 8 个方法 · `container ↔ transfer` 双向依赖 ·
18 个 Mixin 的平台耦合。

### 6.5 审查性质的变化 ⭐

| | 现在 | 重构后 |
|---|---|---|
| 找什么 | **结构性缺陷** | **边界与权衡** |
| 例子 | 层次倒置 / God Class / 硬编码 | `interaction` 定位 / 新抽象的裂缝 / N 的上限 |
| 性质 | **必修**（不修就挡开发） | **选修**（结构已不挡新功能） |

⚠️ 下次审查可能质疑「**这个 `default` 方法是不是在掩盖一个缺失的抽象**」：
`shouldTickWithoutOwnItems` 目前**只有活水源会覆盖**（1 个），其余 22 个功能都吃默认 `false`
—— 用一个 `default` 方法 + 一张静态清单承载「容器级逻辑」这个新概念，够不够显式？

> **本文的判断：够（Plan B 优于原 Plan A）。** 理由：① **概念数不增** —— 复用
> `LivingItemFunction`（其 `entries` 本就允许为空）+ `getTickPriority` 排序，不引入新类型；
> ② 若将来出现第 2、3 个自维持功能（电力层可能跟进），**静态清单机制自然扩展**，无需新接口；
> ③ 反之若永远只有 1 个，新增 `ContainerLevelLogic` 接口反而是**为罕见形态引入的过度抽象**。
> ⇒ **判据是「第 2 个是否真会出现」，不是「现在有几个」。**（方案 A→B 的演进见 §2 Q1 留痕。）
