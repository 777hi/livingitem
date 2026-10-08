# container 去领域耦合方案（计划 ⑤）

*创建: 2026-10-08 · 状态: 📋 **调研完成，待拍板**（尚未改任何代码）*

> ### 📌 当前状态：只出方案，未动代码
>
> | 项 | 状态 |
> |---|---|
> | 现状核算 | ✅ 精确到类对（20 条，见 §1） |
> | 药方设计 | ✅ 三条原则 + 五步计划（见 §4/§5） |
> | 代码改动 | ❌ **未开始**（等用户拍板） |
>
> 上游：本方案是 [architecture-layering-plan.md](architecture-layering-plan.md) §2 计划 **⑤** 的细化，
> 同时回答该文 §6 **Q4**（「⑤ 抽什么接口？」）。

---

## 0. 一句话结论

**机制早就就绪，缺的是「归属」。**

`ContainerDataKey` + `ContainerDataStore` 已经把容器级数据做成了**泛化的、以 key 为下标的存储**，
`ContainerDataKeys` 自己的注释就写着「领域各自定义 key 是后续可选迁移，届时本类删除、机制不变」。
container 包仍然认识领域，**只因三件历史遗留**：
① 4 个 key 集中定义在一个 container 类里；② 若干「类型化便捷方法」放错了包；
③ `ContainerLivingItemHandler` 里还硬编码了 3 处领域生命周期钩子。

⇒ 药方**不是**新造机制，而是**把已有的泛化机制贯彻到底**。

---

## 1. 现状：20 条边，6 个文件

> ⚠️ **数字更正**：`architecture-layering-plan.md` §2 记的是 **22** 条
> （`ContainerLivingItemHandler` 8 / `TickContext` 5 / …）。实测现为 **20**
> —— 档 2（runtime 机制化，`5103baf`~`371bb94`）顺带消掉了 2 条。

**判据**（`tools/gen_code_map.py`，与 `check_layers.py` 同源）：
边的产生 = **import + 正文提及（tokens，含注释）**，不只是 import。

| 源文件 | 条数 | 指向的领域类 |
|---|---|---|
| `ContainerLivingItemHandler` | **7** | `ender.EnderChannelRegistry` · `power.ContainerPowerData` · `power.PhaseSnapshot` · `redstone.ContainerRedstoneData` · `redstone.LivingRedstoneFunction` · `water.ContainerFluidData` · `water.ContainerStressData` |
| `TickContext` | **4** | `water.ContainerFluidData` · `water.ContainerStressData` · `redstone.ContainerRedstoneData` · `power.ContainerPowerData` |
| `ContainerDataKeys` | **4** | `water.ContainerFluidData` · `water.ContainerStressData` · `redstone.ContainerRedstoneData` · `power.ContainerPowerData` |
| `SimpleContainerContext` | **3** | `water.ContainerFluidData` · `redstone.ContainerRedstoneData` · `power.ContainerPowerData` |
| `ContainerSnapshot` | **1** | `water.ContainerFluidData` |
| `MutableSnapshot` | **1** | `water.ContainerFluidData` |
| **合计** | **20** | 涉及 7 个领域类（water 2 / power 2 / redstone 2 / ender 1） |

**涉及的领域类只有 7 个**，且高度同质：
- 4 个是**容器级数据类**：`ContainerFluidData` / `ContainerStressData` / `ContainerRedstoneData` / `ContainerPowerData`
- 1 个是**全局注册表**：`EnderChannelRegistry`
- 1 个是**值对象**：`PhaseSnapshot`（电力相位快照）
- 1 个是**功能类**：`LivingRedstoneFunction`（⚠️ 见 §3 的「注释假边」）

---

## 2. 为什么说「机制已就绪」

| 已有件 | 位置 | 作用 |
|---|---|---|
| `ContainerDataKey<T>` | `container/` | 类型化 key，自带全局递增 `index` ⇒ **数组下标**（~1ns，无哈希） |
| `ContainerDataStore` | `container/` | 统一存储，`peek` / `getOrCreate` / `put`，**O(1)** |
| `ContainerContext.peekContainerData(key)`<br>`getOrCreateContainerData(key)` | `container/` | **泛化访问入口**（默认实现 + Simple 覆写） |
| `SnapshotProvider` | `container/` | ⭐ **去领域耦合的成功先例** —— 领域注册贡献者，`ContainerSnapshot` 只认接口 |
| `HasContainerData` | `api/` | ⭐ 容器级数据驱动接口，**按 priority 排序**，领域自行实现 |

⇒ **前两行说明「存储层已泛化」，后两行说明「注册制在这个包里有先例」**。
本方案只是把这两点用到还没用上的地方。

**`ContainerDataKeys` 的自我声明**（原文）：
> ⚠️ 目前是**集中定义**（新增一种数据要在此加一行）——「领域各自定义 key」是**后续可选迁移**，
> 届时本类删除、各 key 挪到对应的数据类里即可，**机制不变**。

---

## 3. 20 条边的分类与药方

| 组 | 来源 | 条数 | 性质 | 药方 | 难度 |
|---|---|---|---|---|---|
| **A** | `ContainerDataKeys` | 4 | key 集中定义（自认的待迁移） | 4 个 key 挪进各自数据类，**删本类** | 低 |
| **B** | `TickContext` 类型化便捷方法 | 4 | 领域便利 API 放错包 | 搬领域侧（直接用 `XxxData.KEY`） | 中 |
| **C** | `SimpleContainerContext` 类型化便捷方法 | 3 | 同上 | 同上 | 中 |
| **D** | `ContainerSnapshot` / `MutableSnapshot` | 2 | 快照塞了领域字段 | **移出** `fluidData` | 低 |
| **E** | `ContainerLivingItemHandler` | 7 | 混合（见下） | 生命周期钩子注册制 + 泛化 | **高** |

### 3.1 逐条明细

**A. `ContainerDataKeys`（4 条）** — 4 个 key 常量：

```java
FLUID   = persistentWith("fluid",   ContainerFluidData::new,   LivingComponents.CONTAINER_FLUID_DATA.value())
REDSTONE= persistent   ("redstone", ContainerRedstoneData::new)
POWER   = persistent   ("power",    ContainerPowerData::new)
STRESS  = of           ("stress",   ContainerStressData::new)   // tick 级
```
⇒ 挪成各自数据类的 `public static final ContainerDataKey<X> KEY`。**调用点约 30 处**（含测试）。

**B/C. 类型化便捷方法（7 条）**：

| 类 | 方法 | 调用者 |
|---|---|---|
| `TickContext` | `fluidData()` / `stressData()` / `powerData()` / `getOrCreateRedstoneData(ctx)` / `getOrCreatePowerData(ctx)` | 领域侧（farmland / water×2 / tnt / power / redstone）+ `ContainerLivingItemHandler` + 测试 |
| `SimpleContainerContext` | `getOrCreateFluidData()` / `getOrCreateRedstoneData()` / `getOrCreatePowerData()` | **几乎全是测试**（`PhaseInterpretationTest` / `NetworkTraversalTest` / `WaxedGeneratorFeedChainTest` 等） |

⇒ 这些方法**本质是领域自己的便利 API**，却住在 container 包 ⇒ container 被迫 import 领域。
**搬法**：领域侧提供静态辅助（如 `ContainerFluidHandler.data(tick)`），或调用点直接写
`tick.data(ContainerFluidData.KEY)`。

> 🔍 **顺带发现**：`SimpleContainerContext.getOrCreateFluidData()` 是**包私有且零调用者**
> ⇒ 疑似死代码，可随手删。

**D. 快照的 `fluidData` 字段（2 条）**：
`ContainerSnapshot` 持有 `ContainerFluidData fluidData` 字段 + `getFluidData()`，并出现在
`capture(...)` / `MutableSnapshot.build(...)` 的参数里。

> 🔍 **`ContainerSnapshot.getFluidData()` 全仓库无生产调用者**（grep 只命中定义处）
> ⇒ 疑似**死字段**。若确认，直接删字段 + 参数 ⇒ 2 条边消失，且 `ContainerSnapshot` 彻底不认识领域。

**E. `ContainerLivingItemHandler`（7 条）** — 唯一棘手的，逐条：

| 边 | 位置 | 真实性质 | 药方 |
|---|---|---|---|
| `EnderChannelRegistry` | `flushEnderChannels` L605 | **领域生命周期钩子**（每容器 tick 刷脏通道） | 抽钩子 / 移到每 tick 收口 |
| `ContainerFluidData` | L142~174（`getFluidData` 静态 + BE attachment 回填）、L209、L627~658（写回） | **领域生命周期**（持久化 + 写回） | 抽钩子 + 逻辑移入水领域 |
| `ContainerStressData` | L397 / L402 / L615 | **领域生命周期**（应力写回 BE / 玩家脚底） | 同上，移入水车领域 |
| `ContainerPowerData` | L211 / L242 / L245 / L281 / L672 | **类型化便捷方法** | 泛化（以 key 为参） |
| `ContainerRedstoneData` | L15 / L210 / L225 / L273 | **类型化便捷方法** | 同上 |
| `PhaseSnapshot` | L253 / L682~683 | **领域生命周期**（电力相位快照落盘） | 抽钩子 |
| `LivingRedstoneFunction` | L16（import）/ L503 / L533 | ⚠️ **注释假边** —— 只在**注释**里提到类名，代码零引用 | 删 unused import + 改注释措辞 |

> ⚠️ **重要发现：`LivingRedstoneFunction` 是「注释假边」**
> `check_layers` / `gen_code_map` 的边判据是 **tokens（含注释）**，
> 所以「在注释里写类名」也会产生一条边。此处的 import 本身还是 **unused**。
> ⇒ 但**只删 import 不够**：注释里的类名仍会被 token 命中，必须**改措辞**（如写「红石功能类」）。
> **这是本方案里唯一「为工具而改注释」的一处** —— 是否值得，见 §6 风险。

---

## 4. 三条药方原则

> 三条**全部对标项目已有先例**，不是新发明。

### 原则 1：key 归领域
`ContainerDataKeys` 删除，4 个 key 落到各自数据类（`ContainerFluidData.KEY` 等）。
**判据**：key 是「这个领域的数据长什么样」，属于领域知识。

### 原则 2：便捷方法泛化，不提供类型化方法
container 侧**不再出现** `getRedstoneData(ctx)` 这类返回具体领域类型的方法。
- 已有泛化入口：`peekContainerData(key)` / `getOrCreateContainerData(key)`
- 需补的：按位置查的版本泛化为 `peekContainerDataByPos(level, pos, key)`
  （现在叫 `getRedstoneDataByPos` / `getPowerDataByPos`，被 `mixin` 和领域侧调用 ——
  它要读框架私有缓存 `CONTAINER_DATA`，**必须留在框架侧**，只能泛化不能搬走）
- 领域想要的便利写法，在**自己包里**写静态辅助

### 原则 3：领域生命周期钩子注册制
`ContainerLivingItemHandler` 的阶段 3（末影刷脏）/ 阶段 5（应力 + 流体写回 + 相位落盘）
抽成注册钩子 —— **对标 `SnapshotProvider`**。
两个可选落点：
- **方案 a**：给 `HasContainerData` 加 `default void afterTick(ctx, tick) {}`（最小改动，领域已有实现者）
- **方案 b**：新建 `ContainerLifecycleHook` 接口 + 注册表（更干净，但多一个机制）

> 📌 **推荐方案 a** —— `HasContainerData` 已经是「容器级数据驱动」的正式接口，
> 写回本来就是「算完 → 落盘」的下一拍；加 default 方法**不破坏任何既有实现**。
> 阶段 3 的末影刷脏更接近「每 tick 一次的全局动作」⇒ 可参照档 2 的 D 步，
> **移到 L4 `LivingItem.onServerTick` 收口**（不再每容器调一次）。

---

## 5. 分步实施计划

> **每一步独立提交 + 独立可 revert**；每步硬门槛 = `551 全绿` + `check_layers` 数字下降 + `doc_check 9/9`。

| 步 | 内容 | 消边 | 预计改动面 |
|---|---|---|---|
| **1** | **A**：4 个 key 挪进各自数据类，删 `ContainerDataKeys` | **4** | 4 个领域数据类 + ~30 个调用点 |
| **2** | **D**：`ContainerSnapshot` / `MutableSnapshot` 去掉 `fluidData`（先确认无调用者） | **2** | 2 个类 + `capture` 调用链 |
| **3** | **B+C**：类型化便捷方法搬走 / 调用点改泛化 | **7** | `TickContext` / `SimpleContainerContext` + 领域侧 6 处 + **测试大量** |
| **4** | **E 机制**：`HasContainerData.afterTick` 钩子 + 写回/落盘逻辑移入领域 | **5** | `api/HasContainerData` + `ContainerLivingItemHandler` + 水/水车/电力领域 |
| **5** | **E 收尾**：末影刷脏移到每 tick 收口 + 删 unused import + 改注释措辞 | **2** | `ContainerLivingItemHandler` + `LivingItem` |

**完成后**：R1 **91 → 71**（减 20）；`container` 包对 `living/domain/*` 的依赖**归零**。

> ⚠️ **步 3 与步 4 之间不要合并**：步 3 是「搬运」（行为零变化，机械可验），
> 步 4 是「机制化」（有设计决策）。分开才能各自证明「没改坏」。

---

## 6. 风险与回滚

| # | 风险 | 缓解 |
|---|---|---|
| R1 | **tick 调度热路径**（`processContext` 每 tick 每容器跑） | 每步跑 `551 全绿`；阶段 4 本来就是注册制，可作模板 |
| R2 | 步 4 改「末影刷脏从每容器 → 每 tick」**可能不等价** | **先确认**：`flushDirtyChannels` 是否幂等、是否依赖容器 tick 的中间状态。不确定就**保持原位、只抽钩子**（不优化） |
| R3 | 测试大量依赖 `ctx.getOrCreatePowerData()` 等便捷方法 | 步 3 里同步改测试；**改测试断言 = 改行为判据**，需逐条确认语义未变 |
| R4 | 「注释假边」要改注释措辞才消 | **默认不做**（避免「为工具改代码」）。若用户认可，作为可选收尾 |
| R5 | `ContainerSnapshot.getFluidData()` 若其实有**反射/间接**调用者 | 步 2 前先 grep 全仓库（含测试）确认 |
| R6 | 改动面比档 2 大（预计 5 个提交 vs 档 2 的 4 个） | 分批提交，任一步出问题只 revert 该步 |

**回滚**：每步一个提交，`git revert <该步>` 即可。步 4 若不理想，可只回退步 4，保留步 1~3。

---

## 7. 验收

| 项 | 期望 |
|---|---|
| `./gradlew test --rerun` | **551 / 0 failures**（每步） |
| `python tools/check_layers.py` | R1 **91 → 71**；R3 不变（10）；R2/R4 恒 0 |
| `container → living/domain/*` | **0 条** |
| `python tools/doc_check.py` | 9/9 |
| 行为 | **零变化**（纯重构；步 4 的钩子化以「逻辑搬位置、不改语义」为准） |

**最终验收后**：`check_layers.py --update-baseline` 收紧基线（棘轮只降不升）。

---

## 8. 与在册路线图的关系

- 本文 = [architecture-layering-plan.md](architecture-layering-plan.md) §2 **⑤** 的细化，
  并**回答 §6 Q4**：抽什么接口 → 见 §4（**不新造接口，优先用 `HasContainerData` 加 default 钩子**）。
- 该文 §5 把 ⑤ 排在**最后**（「最贵」）—— 本文证实：**贵在 `ContainerLivingItemHandler`（E 组）**，
  而 A/B/C/D 四组（共 13 条，占 65%）**是低风险的归属搬运**，可以先做。
- §2 记的 22 条已过时 ⇒ 落地时应同步更正为 20。

---

## 9. 待用户拍板

| # | 问题 |
|---|---|
| **P1** | **是否开工**？若开工，**先做 A/B/C/D 四组**（13 条、低风险、纯搬运），还是**一次性 A~E 全做**？ |
| **P2** | 步 4 的钩子落点：**方案 a**（`HasContainerData` 加 default `afterTick`，推荐）还是**方案 b**（新建 `ContainerLifecycleHook` 注册表）？ |
| **P3** | 「末影刷脏」是否**顺带**从「每容器」改成「每 tick 收口」（R2）？还是**只抽钩子、不优化**？ |
| **P4** | 「注释假边」（R4）是否值得改注释措辞？**默认不做**。 |
