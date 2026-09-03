用户觉得代码开始屎山了，让我看基础设施文档并找出可优化的代码逻辑。先读文档，再结合代码实际分析。
文档是"设计意图"，但用户说实际代码已经屎山了。我得看真实代码才能给出落地建议，不能只复述文档。先摸清核心文件规模和结构。
核心大文件已定位。我重点读中央编排器和上下文实现，找出真实坏味道。并行读取 orchestrator、context、以及红电大类的结构。
编排器已经暴露出很多坏味道。再看 `SimpleContainerContext` 的写入路径和盔甲槽 hack，以及红电大类的结构，确认分析落地。
编排器和上下文已经暴露大量坏味道。再看传输层 `CrossContainerTransfer` 和红电大类的结构，补全分析。
已经有足够具体的材料。最后量化一下红电大类的结构，确认"巨类"坏味道的严重程度。
我把真实代码读了一遍，对照文档发现不少**文档写的和实际代码已经对不上**的地方，而且有几处是实打实的隐患，不只是风格问题。按危害排序给你：

## P0 — 真 bug（藏在这种写法里，最该先动）

**1. `SimpleContainerContext.setItem` 是丢物品的 + 和文档自相矛盾**
- 位置：`SimpleContainerContext.java:196-213`
- 实际代码：`handler.extractItem(slot, MAX_VALUE)` 先全抽空，再 `handler.insertItem(...)`。但如果 insert 放不下全部（remainder 非空），除非是盔甲槽，否则直接 `LOGGER.warn` 然后**丢弃**（`SimpleContainerContext.java:207-210`）。
- 文档 §7.2 明明写了"setItem 采用 **Container 优先，IItemHandler 兜底**，用 `container.setItem` 精确写入"。**真实代码根本没走 Container 优先**——而 §7.2 自己说"容器内传输必须精确指定槽位，IItemHandler 的 slot 参数会被模组忽略"。也就是说容器内精确写入现在走的是不可靠的 IItemHandler 路径。这是文档漂移 + 潜在物品丢失。

**2. `simulateInsertItem` 盔甲槽分支不算已有物品**
- 位置：`SimpleContainerContext.java:238-242`
- 盔甲槽直接 `return min(count, maxStack)`，**完全忽略槽里已有的堆叠数**。会导致容器内往盔甲槽传输时容量算错、可能溢出或误判可装。

**3. `syncWorldContainer` 用引用相等匹配槽位**
- 位置：`SimpleContainerContext.java:419-443`，关键行 `slot.getItem() == stack`（435）
- 用 `==` 引用比较找"正在查看该容器的玩家对应槽位"，不可靠（可能匹配错槽或匹配不到）；且每次同步都 O(玩家数 × 菜单槽位数)。应按 container+slot 索引匹配。

## P1 — 结构债（屎山本体）

**4. `ContainerLivingItemHandler` 7 个静态 Map + 大量重复**
- `FLUID/REDSTONE/POWER_DATA_CACHE`、`POS_TO_CACHE_KEY`、`CONTAINER_REVISION`、`CONTAINER_CONTENT_SIG`、`SNAPSHOT_CACHE`（59-92 行）各自一套 get-or-create（`getFluidData/getRedstoneData/getPowerData` 几乎一字不差，133-212），`cleanupStalePosIndex` 把 `!FLUID && !REDSTONE && !POWER` 这个判定抄了 4 遍（318-331）。
- 建议：合成一个 `Map<String, ContainerEntry>` record（含 fluid/redstone/power/snapshot/rev/sig），配一个 `getOrCreate(key, factory)`，清理策略统一。能删 ~120 行，生命周期一处管。

**5. `processContext` 是 170 行上帝方法**
- 位置：`ContainerLivingItemHandler.java:427-599`
- 一个方法里混了：扫描 / `syncContentRevision` / 空容器红石归零特例 / 功能 tick / 通道 flush / 容器级数据 / 应力&流体写回 / 清理触发 / PerfMetrics / Monitor；散落 5 处 `instanceof SimpleContainerContext` 判断，3 处 `setTickContext(null)`/`flushDirtySlots`（**没有 try-finally**，tick 中途抛异常就泄漏 stale TickContext）。
- 建议：拆成 `scanLivingItems` / `runFunctionTicks` / `runContainerData` / `writebackBlockEntities` / `flushAndCleanup`，TickContext 生命周期包在 try/finally。

**6. `currentTickContext` 是挂在 context 上的可变全局态**
- `SimpleContainerContext` 存 `currentTickContext`，由 handler 手动 set/clear；`flushDirtySlots`（332 行）直接读 `currentTickContext.dirtySlots` **无 null 守卫**，若 tickContext 为 null 就 NPE。
- 建议：同步路径显式传 TickContext，或至少加 null 守卫 + try/finally 清理。

**7. `ContainerRedstoneData` 1339 行巨类**
- 6 个 phase 方法（371/418/560/649/756/1070）共享可变 `edgeGrid`/`prevEdgeGrid` + 5 个跨 tick 稳态字段。最近的 Bug A「迭代到稳定」、Bug B 补丁本质都是往这个巨类上贴创可贴。
- 建议（**渐进，不要重写**）：把稳态跳过状态抽到小的 `SteadyState` 值对象，让 `calculate` 不再直接 mutate 6 个字段；phase 方法已较独立，可进一步抽到 `RedstonePropagation` helper。这是最大的债，但收益/风险比最低，放最后。

## P2 — 重复 / 性能

**8. `findDoubleChestPositions` 两份一模一样的实现**
- `ContainerLivingItemHandler.java:690-705` 与 `CrossContainerTransfer.java:364-380` 完全相同。抽到 `DoubleChestPositions.find(level, pos)` 共享。

**9. 热路径重复查 Container / 反复 `getLevel()`**
- `simulateInsertItem`(244)、`tryPullFromNeighbor`(115) 在**每个槽位**里调 `ContainerContext.getContainer(...)`；而 `SimpleContainerContext.getLevel()`(299-312) 每次遍历 `associatedBlockEntities` 现算。
- 建议在 context 构造时缓存 `Level`；传输循环里每个 transfer 只查一次 Container，不要每槽查。

**10. 列数推断两套算法**
- `SimpleContainerContext.guessWidth` 用 `for (w=9; w>=1; w--)`（递减），文档里的 `resolveColumns` 用 `for (w=9; w<=13; w++)`（递增）——同一概念两处不同实现。合并成一个 `ColumnResolver`。

**11. 测试盲区**
- `SimpleContainerContext` 的盔甲 hack、`Container` 兜底、丢物品路径**完全没被测**——容器级测试全用 `FakeContainerContext`（绕过 `SimpleContainerContext`）。P0 那几个 bug 的回归测试补不上，根因就在这。

---

**我的建议优先级**：先动 **P0 的 1+2+3**（都是真隐患，且能顺着补测试），再动 **P1 的 4+5+6**（把 handler 的 7 张 Map 和上帝方法收口，屎山感会明显下降），**P1-7 红电巨类**和 **P2** 放后面渐进做。

要我从哪一块开始动手？我建议先啃 **P0（setItem 丢物品 + 盔甲容量 + 世界容器同步匹配）并补对应单元测试**——这几个危害最高、改动也最局部。喵

---

## 解决状态（2026-09-03 更新）

### P0 — 复查后发现 2 个误报 + 1 个真实但暂缓

- **P0-1（`setItem` 丢物品）**：**误报（文档漂移，非代码 bug）**。真实代码从 2026-08-17 起已移除 `Container.setItem` 写入路径，统一走 IItemHandler（大箱子左右半共享同一 `IItemHandler` 实例，走 `Container.setItem` 会双计 → 物品复制）。`setItem` 不再丢物品，原文描述已不成立。仅修正了文档 §7.2（见下）。**不要重新加回 `Container.setItem` 写入路径**，否则会复活双箱复制 bug。
- **P0-2（盔甲 `simulateInsertItem` 容量）**：**误报**。该分支仅对「空槽」调用（非空走普通堆叠逻辑），且返回前已按 `maxStack` 截断，不存在溢出；属于惰性分支，无实际危害。
- **P0-3（`syncWorldContainer` `==` 引用匹配）**：**真实 bug**，但涉及「逻辑槽 → 大箱子合并槽位」映射，需单独正确修复，**本日未做**，留作后续专项。

**P0 文档修正**：`docs/system-design/living-item-infrastructure.md` §7.2 改写为「物品写入策略：统一走 IItemHandler」，§5.6 相关说明同步更正。代码未改（避免复活双箱复制）。

### P1-4（7 个静态 Map 合并）—— 已完成（2026-09-03）

- 将 `FLUID_DATA_CACHE` / `REDSTONE_DATA_CACHE` / `POWER_DATA_CACHE` / `CONTAINER_REVISION` / `CONTAINER_CONTENT_SIG` / `SNAPSHOT_CACHE` 六张同键 Map 合并为单张 `Map<String, ContainerEntry> CONTAINER_DATA` + 嵌套 `ContainerEntry`（含 fluid/redstone/power/revision/contentSig/cachedSnapshotRevision/cachedSnapshot）。
- `getFluidData/getRedstoneData/getPowerData` 收敛为单一 `entry(ctx)` get-or-create + 各字段惰性创建；`cleanupStaleFluidData/RedstoneData/PowerData` 三法合并为 `cleanupStaleData`（整条条目过期才回收）；`cleanupStalePosIndex` 里「无流体且无红石且无电力」4 遍判定收敛为 `!e.hasData()`；`clearAllCaches` 由 7 次 `.clear()` 收敛为 2 次。
- `POS_TO_CACHE_KEY`（PosKey→cacheKey，键空间不同）**保持独立，未合并**。
- 公共方法签名（`getFluidData`/`getRedstoneDataByPos`/`getPowerDataByPos`/`getContainerRevision`/`bumpContainerRevision`/`syncContentRevision`/`getCachedSnapshot`/`removeDataByPos`/`clearAllCaches`）全部保留。`getContainerRevision` 保持纯读（不创建条目）。
- 编译 + 全量测试通过（含 Bug A/B 回归测试）。删约 120 行、生命周期一处管。
- 关联文档里旧的 `FLUID_DATA_CACHE` / `REDSTONE_DATA_CACHE` / `POWER_DATA_CACHE` 字段名引用需在后续 doc 维护时同步更新（见 `living-redstone-tech.md` / `living-power-tech.md` / `living-water-bucket-tech.md` / `living-water-wheel-tech.md` / `data-model.md`）。

### P1-5 / P1-6（processContext 瘦身 + TickContext 生命周期收口）—— 已完成（2026-09-03）

- 先核查确认必要性：P1-6 的 `flushDirtySlots` NPE 当前**不可达**（仅 `processContext` 两处调用、且都在 `setTickContext` 之后）；真正风险是主路径缺 try/finally——功能 tick 抛异常会跳过 `flushDirtySlots`（客户端脏槽不同步）+ `setTickContext(null)`（stale 上下文泄漏），且 `onServerTick` 无 try/catch 会向上抛。P1-5 整方法拆分是 cosmetic、且会动 `PerfMetrics.recordPhase` 精确边界（perf 失真风险）+「真实容器路径」零测试覆盖（FakeContainerContext 不触发 `instanceof SimpleContainerContext`）。
- 按用户选定范围（P1-6 + P1-5 安全提取）落地：
  - **P1-6**：`SimpleContainerContext.flushDirtySlots` 加 `if (currentTickContext == null) return;` 防御；`processContext` 主路径与空容器分支都用 **try/finally** 包住 `setTickContext(tick)` 之后的逻辑，finally 统一 `flushDirtySlots()` + `setTickContext(null)`。异常时仍能同步脏槽、清上下文。
  - **P1-5 安全提取**：`scanAndGroupLivingItems(context)`（原扫描+分组循环）与 `runContainerDataTicks(grouped, context, tick)`（HasContainerData 按 priority 排序后 tickContainerData）抽成 private 静态方法。**刻意不碰** `PerfMetrics.recordPhase` 边界、`instanceof SimpleContainerContext` 写回逻辑、early-return 生命周期——降约 30 行、零回归风险。
- **验证**：`./gradlew compileJava/test --offline` 全绿。
- **未做（递延）**：P1-5 激进拆分（拆成 scan/funcTick/containerData/writeback/flush 多方法并移动 perf 相位）——需先补 `SimpleContainerContext` 集成测试做安全网，否则缺测试保护。P0-3、P2 仍待办。

### P1-7（`ContainerRedstoneData` 1339 行巨类）—— 已完成（2026-09-03）

- **稳态跳过状态封装**：将 6 个跨 tick 散落字段（`everCalculated`、`lastRev`、`lastExternalSig`、`lastHadActiveTimers`、`steadySkipCount`、`lastTickTime`）收拢为不可变 `SteadyState` record，`calculate()` 内 `steady = steady.withRecord(...)` 原子更新，杜绝字段遗漏。
- **Phase 方法提取**：6 个 phase（`phase0CountdownDelays` ~ `phase5UpdateDisplay`）及所有辅助方法（`propagateDir`、`powerConductiveNeighbor`、`computeDustConnections`、`computeComparatorOutput`、`checkRepeaterLocked`、`canConnect`、`getOxidationLevel` 等）整体提取到新类 `RedstonePropagation`，持有共享可变引用（`edgeGrid`/`slotMask`/`faceInput`），用完即弃的薄状态对象。
- **`EdgeGrid` 可见性**：从 `private static class` 调整为 `static class`（包级），供 `RedstonePropagation` 访问。
- **文件精简**：`ContainerRedstoneData.java` 从 1318 行降至约 480 行（-64%），`RedstonePropagation.java` 约 500 行。编译 + 144 全量测试通过。
- **关联文档**：`living-redstone-tech.md` 中 phase 流程说明、`SteadyState` 描述、`living-copper-tech.md` 中 `canConnect`/`propagateDir` 引用已随重构同步更新。

### 待办（未做）
- **P1-5 激进拆分**（延后）：整方法拆多方法 + 移动 perf 相位，需先补 SimpleContainerContext 集成测试。
- **P0-3** 世界容器同步 `==` 匹配修复（需大箱子合并槽位映射）。
- **P2-8/9/10/11** 双箱位置重复实现、热路径重复查 Container、列数推断两套算法、盔甲 hack 测试盲区。