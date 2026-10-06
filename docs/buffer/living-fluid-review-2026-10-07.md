# 活流体子系统收尾审查 + 修复（2026-10-07）

> **性质**：审查发现的修复批次（非新功能）。审查维度：契约接缝 / 引擎相位 / 时钟一致 /
> I·O 路径 / 落盘生命周期 / 性能 / 测试覆盖。

## §1 确有问题（修）

### 1.1 晋升的邻源计数**不分流体**（语义 bug）

`ContainerFluidData.countSourceNeighbors` 只看 `fe.isSource`，不判 `fe.fluid()`：
候选格本身按流体过滤了，但邻居没有 ⇒ **岩浆源会被算进水的「≥2 邻源」** ⇒
一格流动水夹在两个岩浆源之间会**错误晋升成水源**。
与 javadoc「水平相邻**同流体**源 ≥2」和 `WaterRegistration` 口径都不一致。

**修**：加 `fe.fluid() == fluid` 过滤（1 行）+ 一条守卫用例。

### 1.2 死代码

- `ContainerFluidData.exportFlowData()`：**全仓库零调用**（main + test）⇒ 删。

### 1.3 `onContainerClose` 用 `return` 而非 `continue`

`FluidFlowServerSync.java` 关闭清理里，`key == null` / `!CLIENT_ACTIVE.contains(key)`
两处直接 `return` ⇒ 首个槽位不满足就放弃整个清理。多容器菜单或未同步过的容器会漏清。
**修**：改 `continue` 试下一个槽位（成功发送后仍 `return` 保持"一个容器一条键"）。

### 1.4 `representativeFluid` 重复实现 + 潜在 NPE

`LivingBucketInteractSupport` 用 `ConcurrentHashMap` 缓存却可能 `put(type, null)`
（FluidType 未注册到 `BuiltInRegistries.FLUID` 时 `found == null`）⇒ **NPE**。
且它与 `ContainerFluidData.representativeFluidOf` 是两份重复实现（后者用了安全的
`HashMap + containsKey`）。

**修**：委托到 `ContainerFluidData.representativeFluidOf`，删掉本地缓存。

### 1.5 `isFluidValid(tank, stack)` 忽略 `tank`

NeoForge 契约是**按 tank** 回答；原实现遍历所有源 ⇒ 按 tank 过滤的消费者拿到跨 tank 答复。
**修**：只判断该 tank 对应的源（越界返回 false）。

## §2 审查结论：无问题（记录以便下次不必重审）

- 契约 9 个方法（`canFlow/maxLevel/flowSpeed/shouldPromote/transformItem/canBeReplacedBy/
  incinerateResult/frontierReaction/frontierSourceReaction`）**全部真被调用**，无死接缝；
- 相位读层全对：蔓延用目标+实际、晋升/推动/反应类读**实际层**、目标层不写 `flows`；
- CODEC 只存 `generatedSources`，未知 id 静默丢弃；`lastAdvance` 是实例字段、重载随实例重建
  ⇒ 无跨存档错拍；
- Dijkstra 被 `containerSize ≤ 54` + `maxLevel` 双重夹住，无退化输入；`representativeFluid`
  与 `vanillaTickDelay` 都缓存；
- "不驱逐"守卫仍被 ㊾/㊿ 钉住（未因到达时间抢占而失效）；
- 客户端预判 ↔ 服务端重验条件逐条一致（满桶+空槽 / 空桶+level 0）。

## §3 口径不一致（本次**改文档**，不改代码）

倒/汲只改 `generatedSources`、**不写 `flows`** ⇒ 源要下一拍 `ensureSourceCells` 才成立，
而焚毁的 `SPAWN_SOURCE` 与晋升都是直接写 `flows`（"源即时"）。
50ms 观感无差 ⇒ **文档改成"下一拍可见"**，不新增写入路径。

## §4 本次补的测试守卫

| # | 守卫 | 落点 |
|---|---|---|
| G1 | 晋升只数**同流体**邻源（岩浆源不算水的邻源） | `ContainerFluidDataTest` |
| G2 | 末影箱派发必须判 viewer（第三次泄漏修复的**接线**守卫） | `FluidFlowServerSyncTest` |
| G3 | `renderTargetOf` 三类上下文 → ENDER_CHEST / PLAYER_INV / BLOCK | 同上 |
| G4 | 到达时间抢占：同一格两流体竞争，**慢者让位** | `ContainerFluidDataTest` |

## §5 遗留（不本批处理）

- `FluidFlowServerSync.dispatch` 每 tick × 容器 × 全服玩家 × 菜单槽位跑 `isViewing`
  （与 `ContainerRuntimeCache` 同源的既有模式）⇒ 可观察，未优化；
- 玩家 / 末影箱落盘路径（`KEYED_CODEC` + 写回-回填）仍无用例；
- `ContainerContext.getNeighbors` 每次 `new int[]`，内层循环 GC churn（未优化）。
