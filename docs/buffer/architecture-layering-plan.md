# 架构分层诊断与优化计划（未定案 · 执行计划）

*创建: 2026-10-06 · 状态: **未定案**，代码尚未改动*
*配套工具: `tools/gen_code_map.py`（看）· `tools/check_layers.py`（守）*

> ⚠️ **本文是「探讨层」，不是现状描述。** 动代码前必须先回到本文逐条确认。
>
> ⚠️ **与并行文档的边界（2026-10-06）**：本文只谈**分层与模块归属**；
> [framework-benchmark.md](framework-benchmark.md) 谈**向 AnvilCraft / Cataclysm 学什么**
> （mixin 契约接口 / 注册期事件 / Nullness / reload 钩子…）。两份都指向"框架层优化"，
> 但**改动面基本不重叠**；若出现重叠，**以先动手的那份为准**，另一份同步降级。

---

## 0. 为什么会有这份文档

2026-10-06 用户提出「开发中各种逻辑散落各处、上下游纠缠，需要一种直观的方式来梳理」。
据此做了 `tools/gen_code_map.py`（代码关系图），由它**发现**了本文的优化点。

**它解决的问题**：AI 零记忆 ⇒ 没有这份记录，下个会话会把同一批分析**重做一遍**，
甚至可能把已经判断过的东西再"修"一次。

## 1. 现状（可复算）

```bash
python tools/check_layers.py      # 四规则 + 棘轮基线（tools/layer_baseline.txt）
python tools/gen_code_map.py      # 出 build/code-map.html（人看）/ --query（AI 查）
```

| 规则 | 现状 | 判定 |
|---|---|---|
| **R1** 跨模块方向违规（下层依赖上层） | **94 条 / 34 对** | 已减 19（①② 完成） |
| **R2** `@Mixin` 只能声明在 L4 / L5 | **0 条** | ✅ 健康，已锁死 |
| **R3** 领域互依赖 | **28 条 / 14 对** | 分情况（见 §3） |
| **R4** 注册入口唯一 | **0 条** | ✅ 健康，已锁死 |

**层剖面**（每层类数）：`L0=9 · L1=23 · L2=41 · L3=136 · L4=30 · L5=46`

> 📌 **定稿口径**：契约层薄、领域层厚、接线层薄 ⇒ **这个形状是对的**。
> 反例才是病：基础设施层比领域层还厚（框架比业务重），或契约层膨胀。

## 2. R1 的 113 条 → 6 个主题

| # | 主题 | 条数 | 成本 | 效果 |
|---|---|---|---|---|
| ① | `interaction` 的 9 个 handler 搬进各自领域 | ~~12~~ **0 ✅ 已做** | 低 | **真减 12**（113 → 101） |
| ② | `StaticCacheRegistry` 改为各领域自己登记 | ~~7~~ **0 ✅ 已做** | 低 | **真减 7**（101 → 94） |
| ③ | `LivingComponents` 挪出 `living/transfer` | ~~27~~ **0 ✅ 已做** | 极低 | **不减**（只修正归属） |
| ④ | `network/` 是第二个 interaction —— 拆包回领域 | ~10 | 中 | 减 ~10 |
| ⑤ | `container` 认识 8 个领域（分散在 6 个类） | 22 | 高 | 减 22 |
| ⑥ | 领域互依赖 | 28 | 中 | 分情况 |

> 🔑 **判据：搬家 ≠ 减违规。要减必须「同时反转依赖方向」。**
> - ①② 是**真减**：领域自己去 `register` ⇒ 边变成 `domain → interaction/util` = L3→L2 = **合规**
> - ③ 是**不减**：`transfer`(L2) → `components`(L2)，两边同层 ⇒ 违规数不变，只是归属对了

**各主题的实体**（实测，非推测）：

- ① `living/interaction/` 里 9 个类认识领域：`BonemealHandler` `PlantCropHandler` `TillToFarmlandHandler`（farmland）、
  `ButtonPressHandler` `LeverToggleHandler` `ComparatorToggleHandler` `RepeaterCycleHandler`（redstone）、
  `IgniteHandler` `IgniteCarriedHandler`（tnt）
- ② `StaticCacheRegistry` **直接 import 领域类**并自登记
  （`onServerStop(s -> LivingToolFakePlayerCache.clear())`）—— 修法 = 领域在 `XxxRegistration` 里登记
- ③ `LivingComponents` 认识 27 个领域类（它持有全部 `DataComponentType` 常量）——
  **这是 A1 迁移有意集中的结果** ⇒ 治本 = 反转 A1 决策，改动面大（所有调用点）
- ⑤ 分散：`ContainerLivingItemHandler` 8 / `TickContext` 5 / `ContainerDataKeys` 4 /
  `SimpleContainerContext` 3 / `ContainerSnapshot` 1 / `MutableSnapshot` 1 —— **贵在涉及 tick 调度热路径**

## 3. 跨领域耦合：四类，治法完全不同 ⭐

**28 条跨领域依赖不是同一种东西。** 先分类再决定要不要修：

| 类 | 特征 | 实例 | 条数 | 治法 |
|---|---|---|---|---|
| **A 抽象缺失** | 通用机制里写死了某个领域的特例 | `TransferPipeline` / `CrossContainerTransfer` **直接调** `LivingChestFunction` / `LivingEnderChestFunction` | 10 | **补抽象**（让箱子走标准 `SlotAccessor`） |
| **B 稳定机制**（不是债） | 跨领域共用的运行时数据管道 | `runtime`：tooltip 数据的构造 → 缓存 → 同步 → 渲染 | 12 | **做成正式机制**（见 §3.1） |
| **C 真·联动** | 双向互相需要 | `power ⇄ redstone`（发电读信号）、`redstone ⇄ hopper`、`tnt → redstone` | 6 | **端口**（已存在，见 §4） |
| **D 边界画错** | — | — | — | **已撤销，见 §4** |

**判据（可复算）**：
> **主要单向 = 抽象问题（可修）；双向 = 真联动（需要端口）。**

**跨领域联动的三种正规做法**（都不 import 对方实现类）：

1. **端口 / 接口** —— A 定义接口，B 实现并注册
2. **中介 / 编排** —— 逻辑放在**两个领域之上**的模块，不是塞进其中一个
3. **共享契约下移** —— 只是共用类型时，类型下移到框架层，两边都依赖它

> ⚠️ **B 的坑（动之前必须看清）**：原先的 `LivingItemRuntimeData` **反过来继承了 `LivingWaxedGeneratorData`**、
> 引用了 `TransformData` / `ResolvedSlotData` —— 它是**聚合所有领域运行时数据的 God data class**。
>
> ✅ **2026-10-08 已按下面「定稿」实现（档 2）**：God record 已删，拆成
> 「L2 机制 `living/runtime/` + 各领域自带 `XxxSegment`」。结果：R3 22 → 10。
> 实施记录见 [runtime-mechanization-plan.md](runtime-mechanization-plan.md) §7；本节保留为**决策依据**。

### 3.1 B 详析：`runtime` 不是领域，是**稳定机制**

> 📌 **2026-10-06 用户明确：「B 这条是有稳定需求的」** ⇒ 它不是"等技术债累积再改"，
> 而是**正当的长期机制** ⇒ **不该按 A 类那样「补抽象」，而应该做扎实**。

`LivingItemRuntimeData` 的 javadoc 自己写着：数据存 `ContainerRuntimeCache` → `LivingItemSyncPacket`
→ `LivingItemClientCache` → **供 Tooltip 渲染**。⇒ 它服务「tooltip 渲染」这个**跨领域需求**，
**是框架级机制，不是某个领域。**

```
领域 Function ──forXxx()──> LivingItemRuntimeData ──> ContainerRuntimeCache
                                                            │
                                                    LivingItemSyncPacket
                                                            ▼
                                  LivingItemClientCache ──> LivingItemTooltip
```

**唯一的领域耦合点是中间那个 record**：

```java
public record LivingItemRuntimeData(
    @Nullable LivingWaxedGeneratorData generatorTelemetry,   // power
    @Nullable HopperRuntime hopper,                          // hopper
    @Nullable FurnaceRuntime furnace)                        // furnace
```

**硬编码「三选一」联合体** —— 三个领域各调 `forGenerator` / `forHopper` / `forFurnace` 填自己那格
（= **贡献者模式的手工版**）。**真问题：加一个新领域的 tooltip ⇒ 必须改这个核心 record。**
与 [framework-benchmark.md](framework-benchmark.md) §3.2 是**同一个判据**
（「主类必须认识所有功能 ⇒ 与 `getOwnedComponentTypes()` 的自声明方向相反」）。

🔴 **定稿：做成正式机制。**
**方向**：框架层只做**不透明分组容器**（`Map<String, 各领域自带的载荷>`），
各领域**自带 codec 与 tooltip 渲染器并注册** ⇒ `runtime` 不再 import 任何领域，
加第 4 个领域**不用改核心**。

⚠️ **难点与成本（决定排期）**：
- 现在 `LivingItemRuntimeData` 是**静态 record**，codec 编译期确定；改成动态分组后需要**注册式序列化**
- 触碰 **`network/LivingItemSyncPacket`**（全库级）⇒ **必须排在流体领域改动之后**
- 涉及 4 个文件 + 3 个领域的构造点

⚠️ **单纯下移不算修好**：`runtime` 移到 `living/runtime/`(L1) ⇒ `domain → runtime` 变合规（R3 减 9），
但 `runtime → domain` 变成 R1 违规（**新增 3**）。那 3 条**本来就存在**，只是被「runtime 算领域」
这个错误标签掩盖了。⇒ **下移让归属诚实，但必须与上面的机制改造同做。**

## 4. ⚠️ 两条**已撤销**的判断（留痕，别再犯）

### D 类「领域边界画错」—— 撤销

**曾判定**：铜块 6 个类分在 `redstone` / `power` ⇒ 边界画错。

**查证后**：`redstone/LivingCopperFunction.canApply()` = `isUnwaxedCopperBlock(...)`；
`power/LivingWaxedCopperFunction` = 涂蜡。而 power 文档明写
**「活涂蜡铜块是电力层的载体：涂蜡 = 绝缘 = 不参与信号层」**。

⇒ **边界是对的**：未涂蜡 = 信号层 / 涂蜡 = 绝缘 = 电力层。**每个铜块形态两个版本、对称设计。**

**为什么判错**：只看包名（"铜块出现在两个领域"）就下结论，**没看语义**。
**这正是 [code-map.md](../guides/code-map.md) §4 写的「图只回答结构，不回答意图」** ——
⇒ **教训：图给线索，结论必须回代码。**

### C 类「需要端口」—— 端口早就建好了

**曾判定**：`power ⇄ redstone` 需要设计一个端口。

**查证后**：`RedstoneSensor` **已经是接口**（`ContainerRedstoneData implements RedstoneSensor`），
javadoc 明写「红电感知端口 —— 电力层与跨层消费者（漏斗锁定 / TNT 点燃）读取信号层的**唯一**接口。
设计动机（v19.1 架构演进 ②）…收口到一个端口后，边模型的后续重构只改端口实现」。

⇒ 那 6 条**不是耦合，是已设计好的端口**。

**但迁移没走完**：`TickContext.getSensor(ctx)` 已提供「不 import 具体类拿到端口」的入口 ——

| 消费者 | 状态 |
|---|---|
| `power` | ✅ 已迁移（`RedstoneSensor sensor = tick.getSensor(ctx)`） |
| `tnt` | ❌ 仍用 `tick.getOrCreateRedstoneData(context)` |
| `hopper` | ❌ 仍 import `ContainerRedstoneData` |

**治法**：小修 = 那 2 处改用 `tick.getSensor(ctx)`；
**大修（更小）= 把 `RedstoneSensor` 接口从 `domain/redstone/` 上移到 `living/api/`**
⇒ `power` / `hopper` / `tnt` 完全不依赖 redstone 领域，**6 条 R3 边全清**，
而这正是 roadmap 写的**「信号层 ⇄ 电力层解耦」**（属「共享契约下移」）。

> ✅ **2026-10-08 C 收尾已完成**（提交见 changelog）：接口上移 `living/api/` + 方向常量收归契约层
> ⇒ `power` / `hopper` **已完全不引用 redstone 域**，**R3 28 → 25**，顺带 **R1 94 → 93**。
>
> ⚠️ **实测比预想少 3 条，原因是两条边不属于本项**：
> | 边 | 性质 | 处置 |
> |---|---|---|
> | `tnt → redstone`（`LivingTntFunction` 调 `ContainerRedstoneData.calculate`） | **信号层内部方法**，不在端口上；且它挂在 `tickContainerData` 上**每 tick 强制重算** | **设计问题，未动** —— 需要先想清「TNT 为何要驱动信号层重算」 |
> | `redstone → hopper`（`ContainerRedstoneData` 调 `CrossContainerTransfer.getBlockFacing` / `worldToGrid`） | 那两个是**纯几何工具**，放在 hopper 域是**放错盒子** | **独立项**（属「共享契约下移」，可另开一批） |
> | `redstone → power`（`ContainerRedstoneData` 调 `LivingWaxedCopperFunction.isWaxedCopperBlock`） | redstone 需要「这是不是涂蜡铜块」= **power 侧应暴露的谓词** | **独立项**（端口/谓词下移） |

## 5. 推荐顺序与验证方式

**顺序**：`①②`（低垂果实，真减 19）→ `③`（修正归属）→ `C 收尾`（接口上移，减 6）→ `④`
→ `⑥` → **`B 机制化`**（§3.1，**框架级 + 碰 `LivingItemSyncPacket` ⇒ 必须排在流体领域之后**）
→ `⑤`（最贵放最后）

**每一批都是纯重构**：行为零变化 + 全量测试全绿 + 跑 `check_layers.py` 看 R1 数字下降。

| 批 | 验收判据 |
|---|---|
| **① ✅ 已完成** | R1 **113 → 101** ✓；`./gradlew test --rerun` **484 全绿** ✓；基线已收紧（42 对 → 39 对） |
| **② ✅ 已完成** | R1 **101 → 94** ✓；**534 全绿** ✓；基线已收紧（39 对 → 34 对）；铁律正文（infra §3.4）按「归属」重写 |
| **③ ✅ 已完成** | R1 **仍 94**（**同层搬迁，不减** —— 买的是归属诚实）；**534 全绿** ✓；配对数 34 → 37；48 个 import + 1 处同包引用 |
| **C 收尾 ✅ 已完成** | **R3 28 → 25** ✓（+ 顺带 **R1 94 → 93**）；**534 全绿** ✓；接口上移 `living/api/` + 方向常量收归契约层 |
| ①+② 之后 | 跑 `check_layers.py --update-baseline` 收紧基线 |
| C 收尾 | R3 **28 → 22** |

> 📌 **不许为了"数字好看"改判据** —— 改 `LAYERS` 表 = 改期望，必须单独一次提交 + 说明理由。

## 6. 待决问题池（本次未拍板）

| # | 问题 | 影响 |
|---|---|---|
| Q1 | ① 的 9 个 handler 搬走后，`InteractionRegistry` 要不要加确定性排序？（对齐 [framework-benchmark.md](framework-benchmark.md) §3.4） | ① 与那份文档的**唯一可能重叠点** |
| Q2 | ③ `LivingComponents` 是只搬家，还是治本（各领域自己声明并注册 `DataComponentType`）？ | ③ |
| Q3 | **B 的机制化设计**：框架层「不透明分组容器」的载荷用什么？（`CompoundTag` 不透明透传 vs 注册式 codec）—— 前者轻、后者类型安全 | §3.1 |
| Q4 | ⑤ `container → 领域` 抽什么接口？会动 tick 调度热路径 | ⑤ |
| Q5 | 领域内**不分子包**（2026-10-06 用户拍板）—— 判据是「>30 个类 **且** 存在跨组 <35% 的切法」 | 未来 |

## 7. 文档跟进清单

- [ ] 每批落地时：**先更新本文的「现状」与「推荐顺序」再动代码**（用户既定流程）
- [ ] 落地后按 [README.md](../README.md) §4 写 changelog 一行 + `AGENTS.md` 进展一行
- [ ] §3 的「四类分类 + 判据」若被反复用到 ⇒ 从 `buffer/` 升级到 `docs/system-design/`（它更像**口径**而非计划）
- [ ] 全部完成或放弃 ⇒ 本文从 `docs/buffer/` 搬 `docs/archive/`，并在 `docs/decisions.md` 留 supersedes 链
