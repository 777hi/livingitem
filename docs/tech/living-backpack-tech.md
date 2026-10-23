# Living Backpack (活背包) 技术文档

> **文档版本**: 2026-10-23 v1.0
> **最后更新**: 2026-10-23
> **适用版本**: Minecraft 1.21.1

## 目录
0. [玩法定义](#-0-玩法定义)
1. [架构概览](#1-架构概览)
2. [三层数据模型](#2-三层数据模型)
3. [身份优先的形态迁移](#3-身份优先的形态迁移)
4. [菜单解析与 viewer 匹配](#4-菜单解析与-viewer-匹配)
5. [接入清单勾选状态](#5-接入清单勾选状态)
6. [已知问题与未定案](#6-已知问题与未定案)

---

## §0 玩法定义

> **实现入口**：`living/domain/backpack/LivingBackpackFunction`（功能入口）·
> `living/container/ItemFormContainers`（来源注册口）· `living/container/ItemFormContainerContext`（ctx）·
> `living/container/ContainerDataSavedData`（持久层）· `living/container/ContainerUuids`（身份）·
> `living/compat/sophisticated/SophisticatedBackpackSource`（精妙联动）

> **性质**：本节「规则」是**契约**（违反即 bug），不是实现细节。

**语义**：**活背包 = 精妙背包作为一个活物品功能**。它不是「让背包支持流体」的特例代码，
而是「物品形态容器」的第一等公民实现 —— 里面的活熔炉会烧、活桶会续、流体会流，
**关着 GUI 也照常运转**。

**激活**：活按钮（把背包拖到鼠标上 + 开任意容器 GUI + 点按钮）。**活化门槛即性能闸门**：
没活化的背包是普通物品，根本不进 `grouped` ⇒ 不 tick、零开销。

**核心循环**：宿主容器（玩家背包 / 箱子 / 掉落物 / 实体容器）被 tick
⇒ `scanAndGroupLivingItems` 扫出活背包 ⇒ `LivingBackpackFunction.tick` 为每个背包
构建内部 ctx 并 `processContext` **递归** —— 与活熔炉、活漏斗走同一条 tick 管线。

**真机验收**（2026-10-23 用户实测，四项全通）：带两个活背包的倒汲 / tooltip / 槽位设置 ·
关界面不再刷第三方 ERROR · 储罐插件动态列数（各尺寸背包 + 方块形态）·
活漏斗高级 tooltip 显示容器信息。

**规则**（违反即 bug）：

1. **框架零特殊化**。活背包不新增框架机制：tick 驱动 / ctx 身份 / 嵌套治理全部复用既有抽象。
   任何「为活背包加框架分支」的改动都先回到
   [设计铁律：新增机制前先对应物](../buffer/living-backpack.md) §0 自查。
2. **身份优先迁移**。形态变化（破坏 / 拾取 / 放置 / 第三方交接）只搬**容器 UUID**，
   容器级数据始终留在 SavedData 里，零搬运。
3. **取消活化 = 清理干净**。`ContainerUuids.discard(stack)`：先删 SavedData 条目、再剥 UUID。
   两半缺一不可 —— 只剥留孤儿数据，只删让物品指向空身份。
4. **拿不到 `IAttachmentHolder` 不是异常**，是一种正常宿主（`D-container-13`）：
   物品栈不是附件持有者，持久层是 SavedData 而非 BE 附件。

---

## 1. 架构概览

```
宿主容器 tick（既有管线）
   │  scanAndGroupLivingItems 扫出「活化的精妙背包」
   ▼
LivingBackpackFunction.tick(entries, ctx, tick)
   │  对每个背包：ItemFormContainers 问来源要内容视图 + 身份
   ▼
ItemFormContainerContext(handler, uuid, level)      键 = cuuid_<uuid>
   │  processContext 递归 —— 里面活熔炉/活桶/活流体照常 tick
   ▼
ContainerFluidWriteback → ctx.persist(key, value) → ContainerDataSavedData（按 UUID）
```

**两道闸**（都是 ThreadLocal）：`MAX_DEPTH = 1`（嵌套先只做单层）+ UUID 链环检测。
单个背包抛异常不连累其它（I-C7 留痕不静默）。

**分层**：核心不认识精妙类型（`D-container-09`）。内容视图由联动模块
`SophisticatedBackpackSource` 用反射提供（`BackpackWrapper.fromStack(stack).getInventoryHandler()`
返回值恰好是标准 `IItemHandler`）。没装精妙 ⇒ 来源不注册 ⇒ `canApply` 恒 false ⇒ 零影响。

---

## 2. 三层数据模型

| 层 | 载体 | 键 | 生命周期 |
|---|---|---|---|
| **身份** | `CONTAINER_UUID`（物品组件）/ `CONTAINER_UUID_ATTACHMENT`（BE 附件） | UUID | 懒创建；跨形态跟着搬 |
| **数据** | `ContainerDataSavedData` | `Map<UUID, 按 key 编码的 NBT>` | 全局一份（Overworld DataStorage），`setDirty` 后随存档落盘 |
| **缓存** | 内存表 `CONTAINER_DATA` | `cuuid_<uuid>` | LRU 120 秒可回收，回收不丢 SavedData |

- **谁能进 SavedData**：`ContainerDataKey.isSaved()`（attachment + codec 都声明）才收。
  应力 / 红石 / 电力是运行时数据，进不来（与 I-C9「只有带 serialize 的才搬」同源）。
  当前只有 `fluid` 一个 key 满足。
- **空条目不写盘**：保存与加载都跳过空值（借精妙 `BackpackStorage` 的加载期过滤）。
- **附件默认值陷阱**：`CONTAINER_UUID_ATTACHMENT` 的默认值**不能是 `null`** ——
  NeoForge 会认为「每个宿主都有数据」从而给每个 BE 尝试序列化 null，
  实测刷了 22307 条 ERROR 且 UUID 根本存不进存档（2026-10-22 真机）。
  现用哨兵 `NO_CONTAINER_UUID`（全零 UUID），`ContainerUuids.ofHost` 翻译成 null。

---

## 3. 身份优先的形态迁移

```
方块形态：位置键 chest_x_y_z（现状不变）
   │  破坏/拾取：moveOut 把 BE 附件里的 UUID 写进物品组件（一个字符串）
   ▼
物品形态：UUID 键
   │  Open/交互时从 SavedData 回填；Close 后数据仍在 SavedData
   ▼
再放置：moveIn 从物品读 UUID → 写进新 BE 附件
```

- 位置键与 UUID 键**从不直接对话**，中间永远隔着物品组件 ⇒ 方块侧一行不改。
- **没有 UUID 时走兜底**：老存档 / 未活化容器仍搬数据组件（行为与 2026-10-21 一致）。
- **复制 remove 范式**：任何「复制容器物品」的路径必须先 `ContainerUuids.stripForCopy(stack)`
  再 copy，否则两个物品共享一份容器级数据。
- **UUID 的真正价值**是跨「活跃期」保持身份：关闭 GUI 再打开是同一条内存表记录，
  熔炉进度天然续上（没有它，每次打开都是一条新键）。

---

## 4. 菜单解析与 viewer 匹配

两条**独立路径**，这是 2026-10-22 首测「能 tick 但交互全废」的根因：

| 路径 | 谁触发 | 拿 ctx 的方式 |
|---|---|---|
| **tick** | 宿主容器每 tick | `LivingBackpackFunction` 自建 `ItemFormContainerContext` |
| **交互** | GUI 点击 / tooltip / 倒汲 | `ContainerContexts.resolve(player, slot)` |

交互侧要能解析出容器，否则 tooltip 空、倒汲失效、槽位设置错位。链路：
`ContainerContexts.resolve` 五条分支全不命中 ⇒ `ItemFormContainers.handleOf(menu, player)`
⇒ 问各来源「这个菜单是不是你的」。

- **默认实现**（扫玩家物品栏 + 唯一一件才认）只兜「无锚点」的常态；
  带两个同类活容器必然返回 null。
- **精妙覆写**：`BackpackContainer#getBackpackContext() → getBackpackWrapper(player).getBackpack()`
  直接给出「GUI 正在看的那一个」，多背包 / 子背包都认得对。
  ⚠️ **不是背包菜单时坚决不扫物品栏** —— 否则只带一个活背包时会把 AE2 / ProjectE 的
  槽位错认成它（错认比解析不出更糟）。

**viewer 匹配**（#7）：物品形态没有世界位置，按 `positions` 匹配会直接返回空 ⇒ 永不发包。
改为「按容器 UUID 反查在线玩家」：`ctx.getContainerUuid()` 非空时逐个在线玩家问
`handleOf(player.containerMenu, player)`，UUID 相同即为 viewer。

⚠️ **这条轨有两条，别只修一条**（2026-10-23 真机实录）：
| 轨 | 修没修 | 不修的表现 |
|---|---|---|
| `FluidFlowServerSync.viewersOf`（流体快照） | ✅ 10-22 修 | 流体层不渲染 |
| `LivingItemRuntimeSync.flush`（运行时数据：冷却 / slotInfo / 过滤链） | ✅ 10-23 修 | **活漏斗高级 tooltip 不显示容器信息**（源槽/目标槽/容器尺寸）—— 服务端攒了脏数据，但按位置匹配 viewer 恒为空 ⇒ 永不发客户端 ⇒ tooltip 回落 DataComponent（那里只有 EMPTY） |

---

## 5. 接入清单勾选状态

> 全表见 [guides/container-compatibility.md](../guides/container-compatibility.md)
> §新容器形态接入清单（11 条线）。**逐条打勾才算接完**，不是「能 tick」就算完。

✅ 1 tick 驱动 · ✅ 2 ctx 身份 · ⚠️ 3 槽位几何 · ✅ 4 内容读写 · ✅ 5 数据持久化 ·
✅ 6 菜单解析 · ✅ 7 viewer 匹配 · ⚠️ 8 客户端同步（部分）· ✅ 9 激活与清理 ·
❌ 10 世界语义（**已定不做**：方向传输归活潜影箱）· ✅ 11 嵌套

---

## 6. 已知问题与未定案

### ✅ 列数已改权威值（#3，2026-10-23 修）

原来 `getWidth()` 三层推断全不命中（没有 inventory / 没有 BE / 没注册规则）⇒ 掉到
Tier 3 启发式 `{9,10,12,13,8,7,6,11,5,4,3,2,1}` 找第一个能整除 `getSize()` 的。
启发式会猜错 —— 真实列数是精妙 GUI 的「每行格数 − 升级占列数」：

| 背包 | 占列升级 | 存储槽 | 真值 | 启发式（旧） | |
|---|---|---|---|---|---|
| 27 格 | 无 / 1× / 2× | 27 / 21 / 15 | 9 / 7 / 5 | 9 / 7 / 5 | 碰巧对 |
| **54 格** | **2×** | **30** | **5** | **10** | **✗** |
| **81 格** | **2×** | **45** | **5** | **9** | **✗** |

（占列数来源：`BatteryUpgradeItem` / `TankUpgradeItem` 的 `getInventoryColumnsTaken() = 2`。）

**修法**：`ItemFormContainers.Source#columns(stack)` —— 能给出权威值的来源就给，
核心不猜。精妙的实现是 **`存储槽数 ÷ 行数`**（handler 大小就是 `行数 × 每行格数`，
除一下即真值，**不需要复刻上游的布局公式**）；除不尽 / 拿不到 ⇒ 返回 0
⇒ ctx 回落到启发式（不比修之前差）。

### ⚠️ 上游 bug：精妙背包在「背包物品离开原槽」时会崩客户端（2026-10-23 真机）

精妙的 `BackpackWrapper`（**上游类，不在本仓库**）在 `cacheSlotNumbers()` 里有一句硬转型：

```java
BackpackItem backpackItem = (BackpackItem) backpackStack.getItem();   // 不判 instanceof
```

GUI 开着的时候那个槽里的背包变成空（air）⇒ 服务端同步一个空栈下来 ⇒
`ClientboundContainerSetSlotPacket → BackpackContainer.onStorageItemStackChanged →
setBackpackStack → cacheSlotNumbers` ⇒ **ClassCastException**。
本次真机日志实录：先被包处理器捕获打成一条 ERROR，随后客户端 FATAL 崩溃。

**不是我们的 bug**（我们没有、也无法改它的转型），但**我们放大过它**：
`FluidFlowServerSync.onContainerClose` 原来在逐槽循环里问物品形态解析
⇒ 同一个菜单被问 60 遍，每遍都调到精妙的 `getBackpackWrapper`，
锚点失效时它每次都打一条 ERROR ⇒ 关一次界面刷 60 条。
已修为**循环外问一次**（物品形态解析是菜单级的，与槽位无关），并有回归测试钉住。

⚠️ **同类风险还在**：`FluidFlowServerSync.viewersOf` 每 tick 每个在线玩家问一次
`handleOf`。锚点失效时它会持续每 tick 打一条精妙的 ERROR（约 20 条/秒）直到界面关闭。
本次实录未见（那种状态下客户端已经崩了），**未预先优化** —— 真出现了再说。

### 未定案

1. **嵌套上限放开时机**：现写死 `MAX_DEPTH = 1`，等活潜影箱立项一起放开。
2. **handler 获取开销**：`BackpackWrapper.fromStack` 每 tick 每背包一次，未实测；
   先不缓存，量了再决定（别预先优化）。

### 相关

- 设计稿与四轮纠偏留档：[buffer/living-backpack.md](../buffer/living-backpack.md)
- 活潜影箱（含自建多窗口 GUI 的锚点方案）：[buffer/活潜影箱实现细节.md](../buffer/活潜影箱实现细节.md)
