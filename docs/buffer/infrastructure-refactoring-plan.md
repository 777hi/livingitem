# 基础设施重构方案（infrastructure refactoring plan）

*创建: 2026-10-03 · 状态: 未执行（待 §2 待决问题拍板后按序推进）*

> **计划书**：未执行，**勿当现状读**。本文随重构推进成长；执行后结论沉淀到
> [living-item-infrastructure.md](../system-design/living-item-infrastructure.md)，
> 本文按 buffer 收敛三步法归档（见 [README.md](../README.md) §1）。
>
> ⚠️ 本文**尚未登记** `AGENTS.md` 子系统索引 —— 该索引声明「下方全部子系统均已实现」，
> 未执行的计划进索引会误导。重构开工时再补。

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

`processContext` 中 `instanceof SimpleContainerContext` 出现 **4 次**
（`:453` / `:482` / `:503`，另 `writebackBlockEntities` 内 3 处）。

`ContainerContext` 缺三样能力，调用方只能认具体实现：
绑 TickContext / 刷脏槽 / 取关联 BlockEntity。

### 1.6 `containerKey` 第三档身份不稳定 ⭐ 落盘后升级为正确性问题

```java
// SimpleContainerContext:128  buildContainerKey
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
| **Q1** | 「容器级逻辑」的注册形态 | **拆两类**：`HasContainerData` 保留（功能驱动，要 `entries`，**15 个实现者不动**）；新增 `ContainerLevelLogic`（容器自持，**必须带 `shouldRun()`**） | 1a-1 |
| **Q2** | 容器级数据的存储形态 | **类型化 key + 单一存储**（`ContainerDataKey<T>` + `ctx.getData(key)`）—— 现状三处是**同一份数据的三个镜像** | 1a-4 |
| **Q3** | `containerKey` 第三档 | ⭐ **直接删，改为显式抛异常**（**无需观测期** —— 已静态证明不可达） | 1a-2 |
| **Q4** | `ContainerContext` 补哪些方法 | **抽 `TickableContainerContext` 子接口**（不污染只读的 `ContainerContext`） | 1a-3 |
| **Q5** | 纯源容器渲染的同步轨 | ⭐ **不用验证 attachment** —— 复用已有的 `ContainerRuntimeCache.flushToClients` + `LivingItemSyncPacket` | 活水源二期 |

**Q3 的不可达证明**：`buildContext` 唯一调用点传 `player.getInventory()`（inventory 恒非 null）；
`processContainerAt` 的 else 分支（`:759-768`）**无条件** `positions.add(pos)` ⇒ positions 恒非空。

**Q1 的关键约束**：`shouldRun()` **必须免扫描**（不得遍历槽位）—— 否则会把 §1.7 的
两次遍历变成三次。详见 §6.3-B。

> Q5 不阻塞本次重构，但**阻塞活水源二期** —— 建议尽早做最小实验。

---

## 3. 分步计划（草案，待 §2 拍板）

### 1a · 地基（**纯重构，行为不变**）

| 步骤 | 内容 | 验收判据（可复算） |
|---|---|---|
| 1a-1 | 容器级逻辑解耦成独立注册表 | `processContext` 内**无任何 `domain.*` import** |
| 1a-2 | 消除 `containerKey` 第三档 | 加 WARN 观察期后归零；或改为显式抛异常 |
| 1a-3 | 补全 `ContainerContext` 接口 | `processContext` 内**无 `instanceof SimpleContainerContext`** |
| 1a-4 | 容器级数据存储抽离（Q2 拍板后） | **新增一种容器级数据 = 改 0 个类** |

**1a 全部是行为不变的重构** —— 靠现有 384 个测试回归验证，不引入新功能。

### 1b · 活水源功能

见 [idea.md](../idea.md) §五 一期清单：`generatedSources` + CODEC + attachment 落盘 + 四机制。

> 1a 与 1b **必须分开做**：合在一起出问题，分不清是重构引入的还是水源逻辑的锅。

---

## 4. 每一步都必须做的验证协议

沿用 [power-refactoring-plan.md](power-refactoring-plan.md) §6 的三条：

1. **改前先量化** —— 把现状数字写进 §1（行数 / 调用点 / grep 计数），改完复算对比。
2. **改后跑全量测试** —— `./gradlew test --rerun`（`FROM-CACHE` 是**假绿**），
   再跑 `python tools/doc_check.py`。
3. **证明调用点真的经过新路径** —— 把新原语临时退回旧实现，应**恰好**挂掉 N 个测试；
   数字对不上说明还有旁路没接上。

---

## 5. 明确不做

- ❌ **不为「假设的第三方」做扩展点** —— 等第一个真实第三方出现再动
  （见 [open-plan.md](open-plan.md) §C 的既有结论）。
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
| 1.1 容器级逻辑收集依赖活物品分组 | 拆两类 + `shouldRun()` |
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

### 6.3 会新暴露（4 条）

| # | 新问题 | 说明 |
|---|---|---|
| **A** | `getAssociatedBlockEntities()` 对掉落物容器返回空 | 空列表是诚实语义，但审查会停下来看这里算不算又一次抽象不完整 |
| **B** | 性能上限从"没有"变成"有一个数" | ⚠️ **本轮判断已修正**：空容器**本来就不是零开销**（§1.7 两次全槽位遍历），新增的 N 次 `shouldRun()` 相对可忽略 —— **前提是 `shouldRun()` 免扫描**。真正的优化机会是**合并 §1.7 那两次遍历**，与本次重构正交 |
| **C** | 注册点可能成为新膨胀源 | `LivingItem.commonSetup` 继续变长（注册式扩展的固有代价） |
| **D** | `getTickPriority()` 第二次被点名 | 它至今**零使用**（15 个实现者全默认 0）—— 除非 `ContainerLevelLogic` 的排序真的用上它 |

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

⚠️ 下次审查可能质疑「**是否过度抽象**」：`HasContainerData` 有 15 个实现者，
而 `ContainerLevelLogic` 只有 1 个 —— 为一个罕见形态引入新接口值不值？

> **本文的判断：值。** 活水源是第一个，电力层可能跟进；而且拆开后的收益不只是活水源，
> 还有 §6.1 里那 4 条债的同时消解。
