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
   [设计铁律：新增机制前先对应物](../archive/living-backpack.md) §0 自查。
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

⚠️ **观众判定已收编成唯一入口**（2026-10-23，`D-container-14`）：
`ContainerViewers.of(players, cacheKey, positions)` 按缓存键种类分派
（玩家态 ⇒ 主人 + 末影箱门控 · 物品形态 ⇒ 菜单锚点 · 其余 ⇒ 位置）。
两条数据轨都只调它，**新形态只要在它里面加一个分支，两条轨同时生效** ——
「漏一条」这个错误类别在结构上消失。

收编前的现场：这个判定散在两条轨里各一份 if-chain，10-23 一天内连踩两次
（流体轨修了 #7、运行时轨没修 ⇒ 真机「流体正常但活漏斗 tooltip 不显示容器信息」）。

⚠️ **还有一处同类未纳入**：`LivingBrewingStandFunction.brewSoundTargets`
（酿造完成音效的目标选择）—— 它问的是同一个问题，但用途是**音效不是数据同步**，
且改它会把末影箱音效也纳入 viewer 门控（行为变化）。**故意不动**，等真需要时再说。

---

## 5. 接入清单勾选状态

> 全表见 [guides/container-compatibility.md](../guides/container-compatibility.md)
> §新容器形态接入清单（11 条线）。**逐条打勾才算接完**，不是「能 tick」就算完。

✅ 1 tick 驱动 · ✅ 2 ctx 身份 · ✅ **3 槽位几何**（`Source#columns` 权威列数，10-23 真机验收）·
✅ 4 内容读写 · ✅ 5 数据持久化 · ✅ 6 菜单解析 · ✅ 7 viewer 匹配 ·
✅ **8 客户端同步**（内容层靠原版槽位同步；**组件级实时性不成立** ⇒ 见 [TODO 6.1](../TODO.md)）·
✅ 9 激活与清理 · ❌ 10 世界语义（**已定不做**：方向传输归活潜影箱）· ✅ 11 嵌套

---

## 5.5 宿主锚点的玩法开关（2026-10-24）

活背包**没有自己的世界位置**，它借宿主的位置（`ContainerContext#getHostAnchor()`）。
借来干什么属于**玩法**，所以全部做成了 `SERVER` 型配置（自动同步客户端、可按存档覆盖），
**默认全关**：装了模组但没动配置的玩家，行为与之前完全一致。

```toml
# config/living_item-server.toml（或存档的 serverconfig/）
[host_anchor]
    tnt = false              # 背包里的炸弹炸在宿主处（背在身上 = 炸脚下）
    water_wheel = false      # 水车向宿主位置注入应力（随身动力）
    tool_and_weapon = false  # 工具/武器用宿主位置作射线起点（背包里也能按记忆挖矿）
    beacon = false           # 信标用宿主位置开光（随身光环）
```

| 开关 | 关掉时的表现 |
|---|---|
| `tnt` | 引信走完就**熄灭**，不爆炸、不消耗；挪到有锚点处不会自爆（机制见 [living-tnt-tech](living-tnt-tech.md) §4.1） |
| `water_wheel` | 应力只显示在 tooltip（理论产能），不进入 Create 网络 |
| `tool_and_weapon` | 背包里的工具不重放射线 |
| `beacon` | 不开光 |

⚠️ **`tool_and_weapon` 同时作用于工具和武器** —— 两者共用一个 `LivingToolFunction`，
做不到只开一半（要那样得先拆功能）。

⚠️ **上表描述的是服务端行为**。四个开关全开后实测（2026-10-24）：TNT / 水车 / 工具主动
模式 / 信标 buff 全部生效，但**活工具模型不显示、活信标没有光束** —— 客户端渲染器
没有「物品形态」这条数据源（详见 §6 未定案第 3 条 + [TODO.md](../TODO.md) 活背包 6.1）。
也就是说：**开关打开 = 逻辑通了，视觉还没通**。

⚠️ **配置值缓存在静态字段**（加载/重载事件刷新）：`ConfigValue#get()` 在配置未加载时会抛异常，
而这些值在 tick 路径上每容器每 tick 都读。读路径只读字段，未加载时是默认值 `false`。

---

## 5.6 复制品与容器 ID 自查（2026-10-26）

**现象**：创造模式中键复制会把 `CONTAINER_UUID` 组件一起复制 ⇒ 两个背包共享同一份
容器级数据与缓存键 ⇒ 渲染异常（两个背包的工具画在同一位置、容器级数据互相覆盖）。

**为什么不防**：拦截所有复制路径不可行（中键 / `/give` / NBT 粘贴 / 第三方管道），
半吊子防护比不防更难查；而且这与精妙「复制品共享背包内容」是自洽的 ——
精妙的 `STORAGE_UUID` 同样被复制，两边行为一致。

**给玩家的是自查手段**，不是自动修复：

| 提示级别 | 显示什么 |
|---|---|
| 普通 | `容器 ID: 3f2a1b9c`（UUID 前 8 位十六进制） |
| 高级（F3+H） | 完整 UUID + 「复制品会共享同一 ID；渲染异常时取消活化再重新活化」 |

两个背包 ID 相同 ⇒ 是复制品；**取消活化再重新活化**（`discard` → 重新 `getOrCreateItem`）
即拿到新身份。

⚠️ **只给可比的数字，不替玩家判定「这是复制品」** —— 判它要遍历全服容器，成本高且不可靠。
⚠️ **短码必须 `String.format("%016x", …)` 再截 8 位**：`Long.toHexString` 会丢前导零，
`substring(0, 8)` 直接越界。回归 `LivingBackpackTooltipTest` 钉住这两条。

---

## 5.7 同一宿主的多个背包（2026-10-26）

**问题**：`K2` 上报的键原来是「宿主」——`HostRef.ofPlayer(玩家)`。而**一个宿主能装多个背包**：
玩家物品栏放两个活背包，`LivingBackpackFunction` 会为每个背包各跑一次内层 `processContext`
⇒ 两次上报的键完全相同 ⇒ **后写覆盖先写** ⇒ 客户端只画一个背包里的工具。

**根因是键的语义不一致**：方块形态上报「容器自己的位置」（两个箱子 = 两个 pos，不撞），
物品形态上报「宿主」（两个背包 = 同一个玩家，撞了）。宿主是**容器住在哪**，不是**容器是谁**。

**修法**：`HostRef` 加 `hostSlot`（宿主容器里的第几格），来源是
`ContainerContext#getHostSlot()`：

| 路径 | hostSlot |
|---|---|
| tick（`LivingBackpackFunction`） | `entry.slotIndex()` —— 它本来就知道背包在外层容器第几格 |
| GUI 反查（`ContainerContexts.resolve`） | `-1` —— 那条路不发渲染包，不需要 |

**为什么不用容器 UUID**：中键复制的两个背包**共享同一 UUID**（组件被一起复制），
而它们必然在不同槽位 ⇒ 槽位才是唯一区分（**一个槽位只能放一件物品**）。

⚠️ **三种锚点都要带 slot**（2026-10-26 审查补漏）：`AtPlayer` / `AtEntity` 之外，
**`AtBlock` 也要带** —— 背包放在**箱子里**时锚点就是 `AtBlock(箱子位置)`，
同一个箱子装两个背包仍然会撞（一个箱子一个 pos）。回归
`sameBlockHostDifferentSlot_areDistinct` 钉这条。

⚠️ **已知局限（视觉，不是数据）**：同一方块容器里的两个背包，位置仍取**方块中心**
⇒ 两个背包的工具模型画在同一个点、**重叠**。条目本身是两条（数据不丢），
要分开得按槽位算偏移 —— 那是渲染布局问题，等真机看过再决定要不要做。

⚠️ 这是**宿主容器**的槽位，不是背包内部的槽位 —— 后者是另一个维度。
⚠️ 槽位只在同一宿主内比较，所以 `HostRef` 的相等性是 (kind, pos/entityId, hostSlot) 三元组。

---

## 5.8 「随身携带」的定义（2026-10-27）

被动模式的辅助挖掘 / 补刀、远程可见性、背后环，问的都是同一个问题：**这个人身上有哪些活物品**。

原来的答案是三份各写一遍的 `for (slot : player.getInventory())` —— 那等于把「随身携带」
定义成「物品栏顶层」。物品形态容器打破了这个定义：工具在背包里、背包在物品栏里，
**工具不在顶层** ⇒ 三条线一起失效（被动模式在背包里完全不辅助）。

⇒ 定义收进 `CarriedLivingItems` 一处：

```
随身携带 = 物品栏顶层 + 已活化容器物品的内容（只展开一层）
```

**两个边界**（都刻意的）：

| 边界 | 理由 |
|---|---|
| 只展开**已活化**的容器（有 `CONTAINER_UUID`） | 未活化的背包不进 tick 管线、零开销 —— 展开它等于替玩家把没启用的东西算进战斗力 |
| 只展开**一层** | 与 `MAX_DEPTH = 1` 一致；背包里的背包等活潜影箱放开深度时一起改 |

**写回必须带位置**：返回的是 `Carried(stack, outerSlot, innerSlot)` 而不是裸 `ItemStack`。
容器里的栈来自 handler，`hurtAndBreak` 就地改不一定生效 ⇒ 不写回就等于
**白干活不掉耐久**。写回走 `setStackInSlot`（`I-C3`：写回 ≠ 插入）。

### 两端必须同源（2026-10-27 真机）

辅助挖掘跑在 `PlayerEvent.BreakSpeed` 上，这是**双端事件** —— 客户端算进度、服务端校验。
如果两端拿到的工具集合不同，就会出现「客户端进度条满了、服务端拒绝」⇒ **方块挖不下来**。

| 端 | 容器内容从哪来 |
|---|---|
| 服务端 | 直接读 handler（`BackpackStorage`，一定有） |
| **客户端** | **从 K2 的 S2C 拿**（`LivingToolHostClientCache` 里宿主是自己的条目） |

⚠️ 客户端**不能**自己去读容器内容：`clientStorageCopy` 只在开过 GUI / 请求过 tooltip 之后才有，
时有时无 ⇒ 速度时快时慢（表现为"挖得快但有延迟"）。

⚠️ 因此 K2 的上报**不能受 `tool_and_weapon` 开关控制**（`LivingToolFunction` 里已把它挪到
`origin == null` 判断之前）—— 那个开关管的是"借宿主位置做射线起点"，与辅助挖矿是两件事。

⚠️ **已知副作用**：上报提前后，**开关关时背包里的活工具也会被渲染**（模型 / 记忆射线）。
这是"辅助不受开关控制"的自然结果 —— 通道只有一条，渲染与辅助共用它。
若要区分得给包加"仅辅助"标记，暂不做（"背包里有活工具就该看得见"本身也说得通）。

⚠️ **主手那一格只跳「工具本身」，不能跳容器展开**（2026-10-27 审查修）：
`skipMainHand` 曾把选中槽整个 `continue`，而客户端的 K2 条目没有「主手」概念、照算
⇒ 手持活背包挖矿时客户端偏快 ⇒ 同一"破坏又恢复"症状。原版计入的是**手持工具**，
不是**手持容器的内容** —— 修复后两端对称（手持背包的内容两端都算）。

⚠️ **末影箱不算随身**（2026-10-27 定案「存放处」，`D-container-18`）：
背包放进末影箱 = 收起来，不上报 K2、两端都不算随身。
原先 `EnderChestContainerContext` 的锚点**默认退化成 AtPlayer**（从未拍板），
K2 把它当「玩家宿主」上报 ⇒ 客户端算随身、服务端不展开末影箱 ⇒ 两端不同源。
详见 [living-ender-chest-tech.md](living-ender-chest-tech.md) §15。

⚠️ **活末影箱在背包里**（2026-10-28）：直连模式不受影响；路由模式下**取货**正常
（与容器形态无关），**背包里的货也能被路由出去** —— 提取端按容器 UUID 从
`ItemFormLiveViews` 取「本 tick 的活视图」（`LivingBackpackFunction` 每 tick 登记）。
**边界**：背包不 tick（放进不 tick 的容器 / 玩家退出）时登记过期 ⇒ 取不到货。
口径见 [living-ender-chest-tech.md](living-ender-chest-tech.md) §0 规则 9。

⚠️ **两层宿主语义**（`D-container-19`，2026-10-28 用户定）：
**活背包是其中内容物的主容器宿主** —— 容器身份 = 背包 `cuuid`，运行进度 / 流体 / 红石数据都按它存；
**背包自己的宿主**（玩家身上 / 方块 / 实体）是**另一套语义**，只提供锚点与上报位置，
**不改变容器身份**。⇒ 背包换宿主时内容物的运行数据**保留并继续**（放箱子里也照常 tick ——
箱子被扫描 ⇒ 本功能展开内容）；只有**取消活化 / 容器销毁**才清。
⚠️ 不要把外层宿主键当成内容物的容器键：那样两个背包放同一个箱子里会串数据。

### 多把工具的合并规则（2026-10-27 用户定）

| 维度 | 物品栏顶层 | 活背包里 |
|---|---|---|
| **挖掘速度** | **全部相加** | **只取最高一把**（不参与相加） |
| 材质门槛 | 任一满足 | 任一满足 |
| 掉落 / 经验 | 按**最靠前**那把（位置优先，玩家可控） | 同左 |
| 耐久 | 每把出过力的都扣 1 | 同左 |

**为什么背包只取最高**：背包容量（27~120）远大于物品栏（36），若也相加就成"塞满铲子 = 倍速挂件"
⇒ 背包是「便携工具架」，不是「物品栏扩容」。

⚠️ **「在不在容器里」必须是显式字段，不能从槽位推断**（2026-10-27 真机）：
客户端从 S2C 补的条目**没有位置**（`outerSlot = innerSlot = -1`），但它语义上仍是「背包里的工具」，
必须参与「只取最高」那条分层。曾经用 `innerSlot >= 0` 推断 ⇒ 客户端把它当"顶层"、参与相加
⇒ **客户端比服务端快一倍**（诊断日志实录：客户端 `bonus=16` / 服务端 `bonus=8`）
⇒ 服务端 `getDestroyProgress` 不够 ⇒ **方块破坏又恢复**。

**为什么掉落按位置而不是按能力**（用户 2026-10-27 否掉了"自动挑时运最好的那把"）：
玩家不是笨蛋 —— 想用哪把就放前面，**可预期、可控**比"系统替我选"更好。

**附魔的作用面**（不存在"优先级"，只有"作用在哪一维"）：

| 附魔 | 速度 | 掉落 | 耐久 |
|---|---|---|---|
| 效率 | ✅ 等级²（仅基础速度 > 1 时加） | — | — |
| 时运 / 精准采集 | — | ✅ 只看**被选中那把** | — |
| 耐久 | — | — | ✅ 概率不减 |

### 已知不生效 / 不覆盖的项（2026-10-27 评估，**暂不处理**）

| 项 | 现状 | 影响 |
|---|---|---|
| **急迫 / 潮涌 / 挖掘疲劳**（药水） | 服务端 FakePlayer 镜像 `BLOCK_BREAK_SPEED` 属性（药水修饰符**随之生效**），客户端 `localDigSpeed` 只算基础速度 + 效率、**不读该属性** ⇒ **两端并不一致**（2026-10-27 审查更正，本表原记「两端都不算」是错的） | 急迫 ⇒ 服务端快（客户端进度条偏慢，安全）；**挖掘疲劳 ⇒ 客户端偏快 ⇒ 有回弹风险**（见 TODO）。修法 = 客户端公式补读 `BLOCK_BREAK_SPEED`，待复核后动 |
| **饰品 / 装备的 `MINING_EFFICIENCY`** | 服务端镜像、客户端不读 | 服务端略快 ⇒ 客户端进度条偏慢（服务端校验仍通过，不回弹） |
| **性能** | 分层「只取最高」要**全部算一遍**（每把都跑一次 `syncOwnerAttributes` + `equipTool`） | 背包塞几十把工具时，每次 `BreakSpeed` 开销线性增长，手感发木。可选优化：按 `getDestroySpeed` 预筛前 N 把 |
| **掉落归属** | 按**最靠前**那把（位置优先，玩家可控） | 时运 / 精准只看被选中那把；玩家要自己把好工具排前面 |

⚠️ **为什么不修**：都是边缘场景（药水 buff / 饰品 / 塞满背包）。⚠️ 例外：**挖掘疲劳**那行
是两端不一致（客户端偏快 ⇒ 有回弹风险），已挂 [TODO.md](../TODO.md) 待修；
其余只是"效果没给到"或"略慢"，属于玩法调优，等真机反馈再说。

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

⚠️ **放置态一度漏修（2026-10-24 用户实测：物品形态对、放置态错）**：上面这条只覆盖
**物品形态**（`ItemFormContainerContext.getWidth()`）。背包放在地上走的是方块容器
`SimpleContainerContext`，它的三层推断（规则表 / 已知类型表 / 启发式）都不认识
第三方 BE ⇒ 又猜回 10 列。已补：`Source#columnsOfBlockEntity(BlockEntity)` + 
`SimpleContainerContext#getWidth()` 的 **Tier 0**（见 `D-container-17`）。
⇒ **同一物理容器的两种形态必须用同一套算法**，这是本次教训：修了一边要主动想另一边。

### ✅ #8 客户端同步：不需要我们自己发（2026-10-24 结案）

原先标「⚠️ 部分」但**没写清缺什么**。查过之后结论是：**不缺**，理由是物品形态容器的内容
一定处在某个**已被原版同步的槽位**里：

| 场景 | 谁在同步 |
|---|---|
| 玩家开着背包 GUI | `BackpackContainer` 的槽位由原版 `AbstractContainerMenu#broadcastChanges` 每 tick 比对并下发 |
| 背包在玩家物品栏里（没开 GUI） | 组件变化 ⇒ `ItemStack` 不等 ⇒ 原版玩家物品栏同步下发（精妙自己也在若干处显式调 `inventoryMenu.broadcastChanges()`） |
| 背包在箱子船 / 驴的格子里 | 同上，那个槽位也在某个被原版同步的菜单里 |

我们自己的槽位通道（`SimpleContainerContext#syncWorldContainer`）对物品形态是 **no-op**
（`getAssociatedBlockPositions()` 为空 ⇒ 直接 return）—— 在这个形态下这是**正确的**：
物品形态没有世界位置，它的内容不可能"脱离所有菜单"存在。

⚠️ 唯一的前提：物品形态容器必须始终处在某个被原版同步的槽位里。目前成立
（物品栏 / 打开的菜单 / 实体容器格）。**将来若出现"不在任何槽位里的物品形态容器"，
这条要重新评估** —— 那种形态需要自己的下发通道。

⚠️ **边界补充（2026-10-24 实测后更正）**：上面证明的是「内容一定处在被同步的槽位里」，
**不证明「同步会及时发生」**。槽位同步走的是原版 `broadcastChanges()` 的
`ItemStack.matches` 比对，而它**检测不到自定义组件的变化**（项目已知坑，
`LivingToolFunction#syncStateFlip` 的注释有记）。⇒ 内层写回进度 / 动作组件时，
外层那个装着背包的槽位**不会**被判为脏、不会被重新下发，客户端手里的背包内容
停在旧版本。这不影响上文三条场景的结论（那些是「物品进出」层面的同步），
但**影响一切依赖实时组件状态的客户端渲染** —— 见 §6「未定案」第 3 条。

🔴 **更根本的一条（2026-10-26 查证，推翻本小节的隐含前提）**：**客户端读不到背包内容**。
精妙的内容源是 `BackpackWrapper.BackpackStorageContentsSource` ⇒
`BackpackStorage extends SavedData`（服务端全局 `Map<UUID, CompoundTag>`），
物品栈上只存 `STORAGE_UUID` 组件；客户端只有一份 `clientStorageCopy`，
由精妙自己的 `BackpackContentsPayload` 填充（开 GUI 时 `BackpackContainer` 发、
悬停 tooltip 时 `RequestBackpackInventoryContentsPayload` 请求）。
⇒ 「内容在物品组件里、客户端本地读得到」**是错的**；「让客户端扫背包自己算」
这条路不存在。物品形态的任何实时视觉都必须由**我们**下发（见 TODO 6.1）。

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

⚠️ **同类风险还在**：观众判定（现已收编到 `ContainerViewers.of` 的物品形态分支）
每 tick 对**每个脏容器 × 每个在线玩家**问一次 `handleOf`，走的是精妙反射链。
锚点失效时它会持续每 tick 打一条精妙的 ERROR（约 20 条/秒）直到界面关闭。
本次实录未见（那种状态下客户端已经崩了），**未预先优化** —— 真出现了再说。

### 未定案

1. **嵌套上限放开时机**：现写死 `MAX_DEPTH = 1`，等活潜影箱立项一起放开。
2. **handler 获取开销**：`BackpackWrapper.fromStack` 每 tick 每背包一次，未实测；
   先不缓存，量了再决定（别预先优化）。
3. 🔴 **客户端渲染没有「物品形态」数据源**（2026-10-24 实测）：开关全开后
   服务端逻辑全通（TNT 炸 / 水车出力 / 工具按记忆挖 / 信标 buff），
   但**活工具模型不显示、活信标没有光束** —— 客户端压根不知道背包里有什么
   （工具模型与信标光束渲染器各只枚举四条数据源，物品形态不在其中）。
   完整定位与分档实施方案（脏消息递出 / 客户端第五条数据源 / 远程 S2C）见
   [TODO.md](../TODO.md) 活背包 **6.1**。

   ✅ **工具通道已实施（2026-10-26）**：查证推翻了「客户端本地读得到」的前提（见 §6 上方的 🔴 段），
   改为**由我们下发** —— `K2` 宿主键 `BlockPos` → `HostRef`（方块 / 玩家 / 实体），
   客户端 `HostOrigin` 一处解析宿主坐标（**眼睛** / 方块中心 / 实体中心，带 `partialTick`）。**待真机复验**。
   ✅ **信标光束已接（同日）**：`BeaconPlayerHostPacket.Entry` 加 `containerUuid`，
   `BeaconPlayerHostSync` 只在「普通形态」时跳过自己（物品形态的内容客户端读不到）。
4. ✅ **宿主锚点只接了一半**（2026-10-25 已修）：信标的下游分派与工具的
   「别挖自己容器」黑名单原来都在问容器自己的身份，物品形态两者皆空。修法统一为
   「宿主是谁只问 anchor」：抽出 `effectiveAnchor(context)`，取点与下游分派共用。
   ⚠️ 遗留：大箱两半拿不全（锚点是单个 `BlockPos`）；背包在玩家身上时信标
   **背的人自己看不到**（客户端只扫玩家背包，归 6.1-b）。
   见 [TODO.md](../TODO.md) 活背包 **6.2** 与
   [container-anchor-design.md](../archive/container-anchor-design.md) §5-5。

### 相关

- 设计稿与四轮纠偏留档：[archive/living-backpack.md](../archive/living-backpack.md)
- 活潜影箱（含自建多窗口 GUI 的锚点方案）：[buffer/活潜影箱实现细节.md](../buffer/活潜影箱实现细节.md)
