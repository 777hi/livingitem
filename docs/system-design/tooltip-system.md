<!-- markdownlint-disable -->

# 活物品 Tooltip 系统设计

> **文档版本**: 2026.09 v1.1（v19.2 相位圆盘）
> **最后更新**: 2026-09-07
> **适用版本**: Minecraft 1.21.1 + NeoForge 21.1.x
> **关联文档**: [living-power-tech.md](../tech/living-power-tech.md)（发电遥测语义）、[data-model.md](data-model.md)（DataComponent 体系）

## 目录

- [活物品 Tooltip 系统设计](#活物品-tooltip-系统设计)
  - [目录](#目录)
  - [1. 概述与设计目标](#1-概述与设计目标)
  - [2. 双层渲染架构（文本层 + 图形层）](#2-双层渲染架构文本层--图形层)
    - [2.1 事件顺序与数据获取方式](#21-事件顺序与数据获取方式)
    - [2.2 运行时数据（LivingItemRuntimeData）](#22-运行时数据livingitemruntimedata)
  - [3. 数据链路：服务端 → 客户端](#3-数据链路服务端--客户端)
    - [3.1 服务端：telemetry 构建 → 运行时缓存 → 脏标记](#31-服务端telemetry-构建--运行时缓存--脏标记)
    - [3.2 大箱子匹配（CompoundContainer）](#32-大箱子匹配compoundcontainer)
    - [3.3 客户端：单键缓存模型](#33-客户端单键缓存模型)
  - [4. 显示读数口径（v19.1）](#4-显示读数口径v191)
    - [4.1 问题：脉冲记账下的读数纹波](#41-问题脉冲记账下的读数纹波)
    - [4.2 口径分离：记账 EMA 与显示窗口均值](#42-口径分离记账-ema-与显示窗口均值)
    - [4.3 毫 FE（mFE）定点显示](#43-毫-femfe定点显示)
  - [5. 相位圆盘与展开条（v19.2）](#5-相位圆盘与展开条v192)
  - [6. 量化降脏](#6-量化降脏)
  - [7. 设计原则与坑清单](#7-设计原则与坑清单)
  - [关键文件](#关键文件)
  - [测试覆盖](#测试覆盖)

---

## 1. 概述与设计目标

活物品的 tooltip 分为**文本层**（各功能类 `addToTooltip` 输出的文字读数）与**图形层**（F3+H 高级模式下的仪器面板，如涂蜡发电机的相位圆盘 / 相位展开条与锈级柱状图）。两层的数据都来自同一条展示链路：

```
服务端功能类（tick 时构建 telemetry）
    → ContainerRuntimeCache（运行时缓存，脏标记）
    → flushToClients（LivingItemSyncPacket，按玩家菜单匹配过滤）
    → 客户端 LivingItemClientCache（单键快照）
    → 悬停槽位定位 → 文本层 / 图形层渲染
```

**设计目标**：

1. **遥测不写 DataComponent**——运行时展示数据（EMA 功率、检测周期、共振读数等）每 tick 变化，写入物品组件会造成 NBT 膨胀与同步风暴；全部走运行时缓存 + 网络包；
2. **数字稳定**——记账是脉冲式的（跳变门控），显示读数必须与记账口径分离，否则高频场景数字闪烁；
3. **原版容器形态全覆盖**——大箱子的菜单容器是合成包装（CompoundContainer），玩家匹配必须兼容；
4. **精度足够**——K=1/16 的 RE→FE 换算下，整数 FE 粒度会吞掉小功率发电的显示，需毫 FE（mFE）定点。

---

## 2. 双层渲染架构（文本层 + 图形层）

### 2.1 事件顺序与数据获取方式

两个渲染入口挂在**不同的 NeoForge/原版事件**上，触发顺序与上下文生命周期不同——这是本系统最重要的坑：

| | 文本层 | 图形层（仪器面板） |
|---|---|---|
| 事件 | `ItemTooltipEvent`（原版 tooltip 构建内触发） | `RenderTooltipEvent.GatherComponents`（NeoForge，原版构建**之后**） |
| 挂载点 | `LivingItemTooltip.onItemTooltip` | `LivingItemClient.onGatherTooltipComponents` |
| 数据获取 | **ThreadLocal**：`LivingItemClientCache.setCurrentTooltipData(...)` 在 try 前设置、finally 清除，`addToTooltip` 内用 `getCurrentTooltipData()` 读取 | **ThreadLocal 已被 finally 清空**——必须自行按 `getSlotUnderMouse()` 定位悬停槽位，再 `LivingItemClientCache.get(containerSlot)` |
| 内容 | 各功能类 `addToTooltip(context, adder, flag, stack)` | `LivingWaxedCopperTooltipComponent` → `LivingWaxedCopperTooltipRenderer` |
| 显示条件 | 物品是活物品 | 涂蜡发电机 + F3+H 高级模式 |

**规则**：图形层入口**不能**依赖 `getCurrentTooltipData()`（恒为 EMPTY），也不能读 DataComponent（v19.1 起发电遥测不写组件，会拿到全 0 → 面板整块消失）。悬停定位模式：

```java
Minecraft mc = Minecraft.getInstance();
if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
    Slot hoveredSlot = containerScreen.getSlotUnderMouse();
    if (hoveredSlot != null && hoveredSlot.getItem() == stack) {
        var runtimeData = LivingItemClientCache.get(hoveredSlot.getContainerSlot());
        ...
    }
}
```

### 2.2 运行时数据（LivingItemRuntimeData）

per-slot 的运行时快照 record，按数据种类分变体（generatorTelemetry / hopperRuntime / …）：
- **服务端**：`ContainerRuntimeCache.update(containerKey, slot, data)`，telemetry 由各功能的容器级 tick 构建（如 `LivingWaxedCopperFunction.buildTelemetry`——纯函数，可 JUnit 驱动）；
- **客户端**：`LivingItemSyncPacket` → `LivingItemClientCache.update`。

---

## 3. 数据链路：服务端 → 客户端

### 3.1 服务端：telemetry 构建 → 运行时缓存 → 脏标记

```
processContext（每容器每 tick）
  ├─ 阶段 4：runContainerDataTicks（priority 排序）
  │    ├─ priority 2：红石 calculate（edgeGrid 双缓冲）
  │    ├─ priority 3：LivingWaxedCopperFunction.tickContainerData
  │    │    ├─ BFS 采样 → 相位解读 → 门控记账 → 发电直存
  │    │    ├─ buildTelemetry（检测值快照，纯函数）      ← 遥测构建
  │    │    └─ ContainerRuntimeCache.update(...)         ← 标脏
  │    └─ 阶段 4.5：flushToClients（脏容器 → 同步包）
```

- 运行时缓存按 `containerKey`（稳定位置身份，如 `chest_x1_y1_z1_x2_y2_z2`）+ `slot`（**容器绝对槽位索引**，大箱为 0–53 全箱索引）存储；
- `update` 标脏，`flushToClients` 每容器 tick 末尾调用一次（发完即清脏）。

### 3.2 大箱子匹配（CompoundContainer）与玩家背包直发

**原版大箱子的菜单容器不是 BE 本体**：`ChestBlock.MENU_PROVIDER_COMBINER` 用 `new CompoundContainer(左半BE, 右半BE)` 构建双箱菜单——玩家菜单槽位的 `slot.container` 是 CompoundContainer，`containerInstances.contains(slot.container)`（containerInstances = 两个 ChestBlockEntity）**永远匹配失败**。

后果：`LivingItemSyncPacket` 从不发给打开大箱的玩家 → 客户端遥测缓存为空 → tooltip 全部显示 0/无数据；单箱菜单容器就是 BE 本体，故单箱正常——**「单箱正常、大箱异常」的典型症状即此坑**。

修复：利用原版 `CompoundContainer.contains(Container)` 访问器：

```java
if (containerInstances.contains(menuContainer)) return true;
if (menuContainer instanceof CompoundContainer compound) {
    for (Container be : containerInstances) {
        if (compound.contains(be)) return true;
    }
}
```

**玩家背包的发送**：背包无 BE 实例可匹配（`containerInstances` 为空），且背包菜单就是 `player.inventoryMenu` 本体——**遥测包改为直发背包主人**（key = `player_UUID` → 从 key 提取 UUID → `getPlayerList().getPlayer(uuid)`），不依赖菜单匹配，也不受 `inventoryMenu` 排除影响。

### 3.3 客户端：双缓存模型（通用 + 玩家背包）

`LivingItemClientCache` 按包来源分两份缓存（v19.1）：

| 缓存 | 数据来源 | 读取场景 |
|---|---|---|
| **通用缓存**（`update` / `get`） | BE 容器的同步包（单箱/大箱/木桶等） | 容器 GUI 悬停 |
| **player 缓存**（`updatePlayer` / `getPlayer`） | `player_UUID` 键的同步包 | 玩家背包 GUI / 创造模式物品栏 tab |

分两份的原因：玩家背包的遥测包**直发本人**（服务端无法知道玩家是否打开背包 GUI，故无条件直发）——若与 BE 容器包共用一份缓存，玩家开着箱子时自己的背包包会**覆盖箱子数据**（串台）。独立缓存后互不干扰。

**玩家背包的悬停定位**：`InventoryScreen` / `CreativeModeInventoryScreen` 的槽位 `getContainerSlot()` = **Inventory 索引 0-35**（`PlayerInvWrapper` 的槽位索引与 Inventory 索引一一对应）——与服务端同步包的 slot 语义完全对齐。特殊 GUI（创造模式物品栏 tab 等）槽位索引与 Inventory 不对齐时，按**物品引用匹配**兜底：`inv.getItem(i) == stack` → `getPlayer(i)`（引用必然相等，因为 GUI 显示的就是真实背包物品）。

---

## 4. 显示读数口径（v19.1）

### 4.1 问题：脉冲记账下的读数纹波

v19.1 跳变门控记账是**脉冲式**的：事件 tick 入账（合因子 × P × 跳变数）、间隔 tick 为 0。快记账 EMA（α=1/8，8 tick 记忆）对周期 P 的信号有固定峰谷纹波（谷/峰 ≈ 0.875^(P−1)），P=2 时约 1.4×——远超 3 位有效数字的量化步长 → tooltip 数字高频闪烁，**这是设计权衡的必然，不是 bug**。

### 4.2 口径分离：记账 EMA 与显示窗口均值

**记账 EMA（α=1/8）与显示读数分离，各管各的**：

| 口径 | 实现 | 消费者 |
|---|---|---|
| **记账 EMA**（快响应） | `GeneratorState.emaPowerRe`（α=1/8） / `ContainerPowerData.emaPowerByOxidation` | 诊断日志、测试、共振建立期前的基础口径保留 |
| **显示窗口均值**（零纹波） | `GeneratorState.displayEmaRe`（窗口 = ceil(32/pref)×pref，**偏好周期整倍数**） / `ContainerPowerData.displayEmaByOxidation`（32t 固定窗口） | tooltip 全部功率读数、锈级柱状图、**共振读数（平衡度 s / 增益 R² / 活跃锈级数 N）** |

**窗口对齐原则**：窗口取偏好周期的整倍数且 ≥32 tick → 稳态下窗口内恰含整数个信号周期 → **均值恒定、纹波恒为 0**（构造保证，非近似）。窗口满即结算（停机后一个窗口内衰减归零，自愈）；偏好周期变化（增减堆叠）在下一窗口生效。

**共振口径切换**：平衡度 s、增益 R²、活跃锈级数 N 从快 EMA 切到窗口均值——v18 设计语义「增益由平滑值算出、作用在本 tick 基础出力上」的落实。三条铁律结构不变：

1. 窗口只吃基础出力（单遍前馈，增益不回灌）；
2. 窗口与记账 EMA 同输入（`updateOxidationEma` 的 baseReByOx）、同推进点；
3. **结算即精确归零**——窗口清零比 EMA_EPSILON 截断更干净，停发锈级 32 tick 内自愈。

**建立期语义**：开始发电后首个窗口（32 tick ≈ 1.6s）内共振增益为 ×1（窗口未建立），之后进入正常共振；停发锈级同样 32 tick 内淡出。

### 4.3 毫 FE（mFE）定点显示

K=1/16 换算下，**整数 FE 粒度会吞掉小功率发电**（< 8 RE/t 即 < 0.5 FE/t 的读数 round 后为 0，整行消失；0.5~1 FE/t 的也常被取整吞掉）。显示链统一改**毫 FE（mFE）定点**（与铜灯电量同款方案）：

- 遥测字段：`emaPowerMilliFe` / `levelEmaPowerMilliFe` / `levelPowerMilliFe`（long，codec 同步改名）；
- 格式化：`formatMilliFe(v)` —— ≥1000 显示整数 FE，否则两位小数（如 `0.94 FE/t`）；
- 窗口均值以 double 结算（`windowAcc / windowTicks`），不再 long 整除——**窗口均值 < 1 RE/t 也保留精度**。

---

## 5. 相位圆盘与展开条（v19.2）

仪器面板左半区在 v19.2 从**波形示波器**换成**相位圆盘**，右上区的锈级柱状图改为**逆时针旋转 90° 的横向条形图**，下半区新增**相位展开条**。

### 5.1 为什么换掉波形

旧示波器画的是 n 条「均匀错开、等幅、固定画 2 个周期」的正弦——相位取 `2π·ph/n`、
振幅取面板半高、周期取常量 `SCOPE_CYCLES`。三个自由度**没有一个是真实数据**，
整张图唯一真实的信息只有 `n`（波有几条）。它连「错位」都不代表真实相位差，
而且相数一多所有波叠在同一条基线上，糊成一团。→ 纯装饰，替换为真实相位图。

### 5.2 相位圆盘（左半区）

一周 = 一个周期 `P`，12 点为 `φ=0`，顺时针增大：

| 视觉通道 | 编码的量 |
|---|---|
| 辐条**角度** | 相位偏移 `2π·φᵢ/P` |
| 辐条**长度** | `√\|Δᵢ\|`（正是公式里参与求和的那个量） |
| 辐条**条数** | 相数 `n` |
| 外圈琥珀弧 | 解锁度 `u`（0..1 圈） |
| 12 点小方点 | `φ=0` 参考点 |

两条设计约束：

1. **辐条不是箭头。** 合因子用的是**标量求和** `Σ√|Δᵢ|`，相位只通过 `n`（去重计数）
   与调谐效率参与，**不做矢量相加**。画成箭头会暗示不存在的矢量加法。
2. **相位分布不影响收益。** `n` 路均匀分布与 `n` 路挤成一坨，`u = eff×n/pref` 完全相同，
   每周期总跳变数也相同。所以这张图的价值是**诊断**（我搭了 4 路为什么 n 只有 3）
   而不是优化目标——别让玩家误以为该去把相位摆均匀。

**拥挤行为**：波形是 n 条线叠在同一条基线上，圆盘是 n 条线从同一圆心发散，
每条辐条占用独立的角度扇区。半径 28px 下 n=16 时相邻辐条弧长间隔约 11px（可分辨），
n 再大就退化为「看分布形态」，精确计数交给数字读数 `Δφ`（最小相位间隔）兜底。
两路相位极近时辐条会真实贴合——**这不是缺陷，正是它要告诉你的同相风险**。

### 5.3 相位展开条（下半区）

把圆盘剪开拉直：横轴 `[0, P)`，每路相位一根柱子，柱高 `∝ √|Δᵢ|`。

- **柱高之和 = `Σ√|Δᵢ|`** = 合因子的底数 → 「多加一根柱子（凑相位）」与
  「把柱子长高（加大 Δ）」两个优化动作在图上直接可见；
- 柱子的横坐标 = 该路在周期里哪一步跳变，对应跳变门控的记账口径；
- 与圆盘是同一份数据的极坐标 / 直角坐标两种表示（卷起来 / 剪开）。

### 5.4 其它读数

- 锈级横向条形图（右上区）数据源 = `getDisplayEmaByOxidationMilliFe()`（窗口均值 mFE）——条长相对值，单位不敏感，但切 mFE 后小功率锈级不再消失；右侧为基线，条形向左增长，等级 3→0 从上到下（氧化→风化→暴露→新鲜），使锈蚀程度从上到下递减；
- 面板尺寸 172×112（旧 172×62，加高以容纳圆盘 + 展开条）。

---

## 6. 量化降脏

EMA 类 double 读数（共振增益 / 平衡度 / 域快照 effDeltaSum）写回前经 `PowerMath.quantize(v, 3)`（3 位有效数字）：稳态下读数被「钉」在固定值 → `equals` 成立 → 跳过脏写，复用 `TickContext.dirtySlots` 批处理，不另造轮子。服务器场景（多发电容器同时被打开 × 多玩家）下降低每 tick 同步量。

---

## 7. 设计原则与坑清单

本节是实战踩坑的沉淀，**新增 tooltip 读数前必读**：

1. **遥测永不写 DataComponent**——运行时展示数据走 `ContainerRuntimeCache` + 网络包；写入组件会引发 NBT 膨胀与逐 tick 同步风暴。同时意味着：**任何 tooltip 代码读组件拿到的都是旧值/全 0**，必须走运行时缓存；
2. **两个 tooltip 事件的数据获取方式不同**——文本层用 ThreadLocal（桥接器已备好），图形层必须悬停槽位定位（ThreadLocal 在 GatherComponents 前已被 finally 清空）；
3. **大箱子 = CompoundContainer**——原版双箱菜单容器是合成包装而非 BE 本体，任何「玩家菜单 ↔ 容器实例」匹配必须兼容 `CompoundContainer.contains(be)`；
4. **显示与记账分离**——跳变门控记账是脉冲的，任何面向玩家的功率读数一律用窗口均值，禁止直接读快 EMA；
5. **显示口径用 mFE 定点**——K=1/16 下整数 FE 会吞掉 <0.5 FE/t 的读数；新增功率类字段一律毫 FE 定点（long），格式化统一走 `formatMilliFe`；
6. **窗口对齐偏好周期**——显示窗口取周期整倍数，否则均值纹波不可消除；窗口内均值以 double 结算，禁止 long 整除；
7. **量化降脏是稳态优化**——高频场景下量化 3 位仍会跨档（窗口均值已消除主纹波，残余可接受）；不要为了钉死值加大量化损失；
8. **`getContainerSlot()` 才是容器绝对索引**——大箱 54 槽全箱索引，与功能层的 `SlotEntry.slotIndex` 同语义；不要用菜单显示行号；
9. **玩家背包与 BE 容器的遥测缓存必须分离**——背包包直发本人、存独立 player 缓存，否则玩家开箱子时自己的背包包会覆盖箱子的 tooltip 数据（串台）；
10. **创造模式 GUI（CreativeModeInventoryScreen）槽位索引与 Inventory 不对齐**——悬停定位失败时按**物品引用**在 `player.getInventory()` 中兜底匹配（GUI 显示的就是真实背包物品，引用必然相等）；
11. **相位顺序必须走 `PhaseDomain.phasesSorted()`**——`deltaByOffset()` 返回的是 HashMap 值视图：迭代顺序是哈希序而非 offset 序，且**不含 offset 本身**。曾因此把 offset 整个丢掉、只传 δ 值列表给客户端，导致相位圆盘根本没有数据可画。凡是要求「顺序」或「offset」的消费者（`collectDomains`、F3+H 文本、圆盘渲染）一律走 `phasesSorted()`；
12. **DomainSnapshot 的 `offsets` 是 v19.2 必需字段**——不做旧 NBT 兼容。Codec 使用无默认值的 `fieldOf("offsets")`；缺失时直接暴露格式错误，避免把缺失的真实相位数据静默伪装成无辐条。

---

## 关键文件

```
src/main/java/com/qiqi/li/
├── LivingItemClient.java                    # 客户端入口：GatherComponents 挂载（图形层）
├── client/render/LivingItemTooltip.java     # 文本层桥接：ItemTooltipEvent + ThreadLocal
├── client/render/LivingWaxedCopperTooltipRenderer.java  # 仪器面板绘制（相位圆盘 + 展开条 + 锈级柱状图）
├── living/domain/runtime/
│   ├── ContainerRuntimeCache.java           # 服务端运行时缓存 + 脏标记 + flushToClients + 大箱匹配
│   ├── LivingItemClientCache.java           # 客户端单键缓存 + 悬停 ThreadLocal
│   └── LivingItemRuntimeData.java           # per-slot 运行时快照 record
├── network/LivingItemSyncPacket.java        # 遥测同步包（STREAM_CODEC）
└── living/domain/power/
    ├── LivingWaxedCopperFunction.java       # buildTelemetry（telemetry 构建，纯函数）+ addToTooltip（文本）
    ├── LivingWaxedGeneratorData.java        # 仪表盘 record（DataComponent 兼容 + 网络编码）
    ├── LivingWaxedCopperTooltipComponent.java # 图形层组件 record
    ├── GeneratorState.java                  # per-generator 显示窗口均值
    └── ContainerPowerData.java              # per-oxidation 显示窗口均值 + 共振读数口径
```

## 测试覆盖

| 测试 | 覆盖 |
|---|---|
| `ContainerPowerDataTest.ledger_emaAndFeConversion` | 显示窗口稳态零纹波 + 停机自愈归零 |
| `NetworkResonanceTest` | 共振读数口径（恒定输入下 s/gain 收敛）、回代安全、EMA 截断 |
| `NetworkTraversalTest.quantizedTelemetry_doesNotDirtyInSteadyState` | 量化降脏（稳态不脏写） |
| `WaxedGeneratorFeedChainTest` | 遥测数据源端到端（发电 → telemetry 非零） |

> 无 UI 层自动化：`isViewingContainer` 的大箱匹配、悬停定位等客户端路径依赖真实菜单，由游戏内验证覆盖（诊断开关 `-Dlivingitem.debug.sensing=true` 可辅助）。
