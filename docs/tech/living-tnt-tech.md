# Living TNT (活TNT) 技术文档

> **适用版本**: Minecraft 1.21.1
>
> 爆炸规模化方案已由用户确认并实施（`D-tnt-03`）：大规模 SUPER 使用参数 + epoch，
> NORMAL/HIGH_YIELD 保留位图。§4.6 记录当前实现边界与验证入口；
> 游戏内性能、光照与客户端表现仍按 §4.6 待验；自动化结果以测试快照与当前构建为准。论证原稿暂留
> [living-tnt-explosion-design.md](../buffer/living-tnt-explosion-design.md)，待复核后再按文档规约分流。

## 目录
0. [玩法定义](#-0-玩法定义)
1. [架构概览](#1-架构概览)
2. [数据结构](#2-数据结构)
3. [引信机制](#3-引信机制)
4. [爆炸系统](#4-爆炸系统)
5. [Tooltip 显示](#5-tooltip-显示)

---

## §0 玩法定义

> **实现入口**：`living/domain/tnt/` —— 关键类：`LivingTntFunction`（功能入口）·
> `ExplosionComponent`（逐区块破坏引擎）· `ExplosionLedger`（待炸账本，世界级 SavedData）·
> `ExplosionParams`（参数 record + 位图索引）

> **性质**：本节「规则」是**契约**（违反即 bug），不是实现细节 —— 它是本子系统
> **功能需求的来源**与**行为对错的判据**。⚠️ **只写规则与语义**；数值与操作步骤归实现层。

**语义**：**活TNT = 容器内的爆炸物**。点燃后引信倒计时归零，
有可用锚点时在锚点位置引发爆炸；**威力与容器中活TNT的总堆叠数成正比**。
无锚点时引信走完即**熄灭**（不爆炸、不消耗）。

**获取**：原版 TNT + 活化。

**核心循环**：点燃（活打火石 / 红石信号）→ 引信倒计时 → 爆炸 → 按破坏策略作用于世界。

**规则**（违反即 bug）：

| # | 规则 | 为什么 / 后果 |
|---|---|---|
| 1 | **两种点火方式**：① 活打火石 GUI 右键交互（支持光标与槽位互换）② 红石信号（槽位信号 > 0） | GUI 规则为 `ignite` / `ignite_carried`；后者可与活红石系统联动 |
| 2 | **已点燃不重复点火** | `ignited()` 守卫 |
| 3 | 引信归零且有可用锚点 ⇒ **在锚点位置**爆炸；无锚点则**熄灭**（`ignited=false`、`fuseTimer=0`），不消耗 TNT、不留待爆态 | 待爆态会让 TNT 挪到有锚点处不点自爆，玩家无法判断它是否还点着；锚点只限制爆炸出口，不代表全部停止 tick |
| 4 | **威力 ∝ 容器内活TNT总堆叠数** | 堆叠 = 当量 |
| 5 | 爆炸**唯一破坏入口** = `ExplosionComponent.applyToChunk`；不为补炸主动加载或生成区块 | 未观测处的破坏交给持久账本，受容量降级边界约束（见 §4.3 / §4.6）；不能将 FULL 尚未就绪误当卸载 |

**能力清单**：✅ 两种点火 · ✅ 引信（默认 80 tick）· ✅ 爆炸（三档破坏策略）
· ✅ 光照补偿 · ✅ 待炸账本（按区块分帧推进）· ✅ Tooltip

**验证入口**：`ExplosionParamsTest`（参数）· `ExplosionLedgerTest`（待炸账本）·
`ExplosionComponentLightTest`（光照补偿）· `ExplosionComponentCountTest`（堆叠求和与计数守卫）

---

## 1. 架构概览

### 1.1 什么是活TNT？

活TNT是一种**爆炸类活物品**，放置在容器中可以被活打火石通过 GUI 交互点燃，引信倒计时归零且有可用锚点时在锚点位置引发爆炸。无锚点时引信走完即熄灭（不爆、不消耗、不留引信）。爆炸威力与容器中活TNT的总堆叠数量成正比。

活TNT的宿主物品是 `minecraft:tnt`（TNT），必须同时具备活物品标记。

### 1.2 数据流总览

```
GUI / 红石点火 → DataComponent 引信倒计时
  → 有可用锚点 + 正数计数 + 有效参数 → 消耗容器内活 TNT
  → 立即声光 / 实体伤害（不改方块）
  → ExplosionLedger（每维度 SavedData）
      NORMAL / HIGH_YIELD：参数 + 完成位图，完成即退役
      SUPER：参数 + 正 epoch，无覆盖网格与完成位图
  → 已加载候选 / 自然加载事件入 FIFO
  → 服务端 tick：FULL 就绪坐标整轮处理 → applyToChunk
      位图族置位；SUPER 全轮成功后提交区块 epoch 水印
```

规模化实现与验收状态见 §4.6。分帧预算约束的是完成的区块坐标轮次，
不是条目调用次数，也不是 tick 耗时承诺。世界交互边界见
[living-item-infrastructure.md](../system-design/living-item-infrastructure.md) §3.2.2。

### 1.3 关键类和职责

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `LivingTntFunction` | `domain/tnt/LivingTntFunction.java` | 活TNT功能入口，管理引信倒计时和爆炸触发，经感知端口读取红石信号点燃 |
| `ExplosionData` | `domain/tnt/ExplosionData.java` | 引信状态 record：ignited + fuseTimer |
| `LivingTntData` | `domain/tnt/LivingTntData.java` | 活TNT数据容器：包含 ExplosionData |
| `ExplosionComponent` | `domain/tnt/ExplosionComponent.java` | 爆炸执行引擎：逐区块破坏 + 实体伤害 + 音效粒子 |
| `ExplosionParams` | `domain/tnt/ExplosionParams.java` | 爆炸几何与模式参数；位图索引仅用于 NORMAL/HIGH_YIELD，SUPER 覆盖判定不依赖网格面积 |
| `ExplosionLedger` | `domain/tnt/ExplosionLedger.java` | 每维度 `SavedData`；按位图族 / 参数 + epoch 族保存，候选调度与容量降级见 §4.6 |
| `LivingFlintAndSteelFunction` | `function/LivingFlintAndSteelFunction.java` | 活打火石，提供 GUI 点火触发标记，无自身 tick 逻辑 |
| `ContainerRedstoneData` | `domain/redstone/ContainerRedstoneData.java` | 容器级红石数据；TNT 经 `RedstoneSensor` 端口读 `maxSensedSignal()` 检测信号 |

---

## 2. 数据结构

### 2.1 ExplosionData — 引信状态

```java
public record ExplosionData(boolean ignited, int fuseTimer) {

    public static final ExplosionData DEFAULT = new ExplosionData(false, 0);

    public ExplosionData ignite() { return ignite(80); }
    public ExplosionData ignite(int fuseDuration) { return new ExplosionData(true, fuseDuration); }
    public ExplosionData tick() { return new ExplosionData(ignited, fuseTimer - 1); }
    public boolean isExploded() { return ignited && fuseTimer <= 0; }
    public ExplosionData reset() { return DEFAULT; }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `ignited` | boolean | 是否已点燃 |
| `fuseTimer` | int | 剩余引信时间（ticks），默认 80 ticks（4秒） |

### 2.2 存储结构

活TNT的所有数据存储在 ItemStack 的 DataComponent 中，通过 `LivingTntData` record 管理：

```
ItemStack
├── IS_LIVING: true                        ← 活物品标记
└── LIVING_TNT_DATA: LivingTntData         ← 功能状态（DataComponent）
    └─ explosion: ExplosionData
        ├─ ignited: boolean                ← 是否已点燃
        └─ fuseTimer: int                  ← 剩余引信时间
```

---

## 3. 引信机制

### 3.1 点火方式

活TNT支持两种点火方式：

| 方式 | 触发条件 | 说明 |
|------|---------|------|
| 活打火石 | GUI 中活打火石右键活TNT，或活TNT右键活打火石 | `ignite` / `ignite_carried` 交互，不走活漏斗 |
| 红石信号 | 活TNT所在槽位收到红石信号（>0） | 可与活红石系统联动 |

### 3.2 打火石点火

GUI 点火规则在 `assets/living_item/interaction_rules.json`，处理器由 `TntRegistration` 注册：

- 光标活打火石右键槽位活TNT → `IgniteHandler` 点燃槽位中的 TNT。
- 光标活TNT右键槽位活打火石 → `IgniteCarriedHandler` 点燃光标上的 TNT。
- 两者均调用 `ExplosionComponent.startFuseOnStack()`；已点燃不重置引信。
- 生存模式每次交互扣打火石 1 点耐久，创造模式不扣；不触发活漏斗传输或冷却。

### 3.3 红石信号点火

活 TNT 是红石信号的**借用者**（消费者）—— 它在自己的 `tick()` 里读「本槽位是否被充能」，
**不驱动**红石层重算（2026-10-08 收归后，那件事由 `LivingRedstoneFunction` 单点负责）：

```java
public class LivingTntFunction implements LivingItemFunction {   // ← 不再实现 HasContainerData

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        if (level.isClientSide) return;

        // 感知端口同时确保红石账本存在
        tick.getSensor(context);
        int size = context.getSize();

        for (SlotEntry entry : entries) {
            // ... 红石信号点火（仅对未点燃的 TNT）...
            int signal = tick.getSensor(context).maxSensedSignal(slot);   // 读四方向入边最大值
            if (signal > 0) { /* 点燃 */ }
        }
    }
}
```

> ⚠️ **收归前**：本类曾实现 `HasContainerData` 并写一段与 9 个红石元件**完全相同**的
> `tickContainerData`，连注释一起抄错 —— 原注释称「优先级 1 确保红石数据在 TNT tick 之前计算完毕」，
> 但 `tick()` 在阶段 2、`tickContainerData()` 在阶段 4 ⇒ 这个「之前」对读取时机毫无帮助。
> 那 10 处重复正是「**错误样板的产地**」，详见
> [redstone-driver-consolidation-plan.md](../archive/redstone-driver-consolidation-plan.md) §3。

**`RedstoneSensor.maxSensedSignal()` 方法（v19.1）**：通过感知端口 `TickContext.getSensor()` 读取槽位**四方向入边**的最大值（= 邻居朝 TNT 发出的出边）。

> **v19.1 语义修正**：v15 出边模型下，TNT 是非红石组件——信号层从不为它写边，旧实现 `maxOfSlot`（读自身出边）恒 0，红石信号点燃永久失效。修正后与电力层涂蜡采样同语义（读邻居出边）。
>
> **边界（邻居越界）返回 0 ⇒ TNT 不感应容器外信号** —— 外部信号只对**方向性元件**（火把/中继器/比较器）开放（反馈循环防护，见 `红电系统.md`）。⇒ 只有「红石火把 / 红石块**紧挨着** TNT 所在槽位」才有效，**容器外的红石不算**。

**红石信号链路**：

```
容器内信号源（火把/红石块/红石粉）
  → Phase 1-5 传播 → edgeGrid 边信号
  → RedstoneSensor.maxSensedSignal(slot)  // 四方向入边最大值
  → signal > 0 → explosion.ignite()
  → 同步到客户端
```

### 3.4 Tick 倒计时

```java
for (SlotEntry entry : entries) {
    ItemStack stack = entry.stack();
    ExplosionData explosion = LivingTntData.of(stack).explosion();

    if (!explosion.ignited()) {
        if (tick.getSensor(context).maxSensedSignal(slot) > 0) {
            // 点燃 + 写回 + 同步
        }
        continue;
    }

    explosion = explosion.tick();               // fuseTimer - 1

    if (explosion.isExploded()) {
        if (ExplosionComponent.ignite(context, level)) {
            return;                             // 成功：ignite 已清空容器内全部活 TNT 槽位
        }
        // 无可用锚点 ⇒ 熄灭（清零 ignited / fuseTimer），写回 + 同步
        LivingTntData.set(stack, data.withExplosion(ExplosionData.DEFAULT));
        context.setItem(slot, stack);
        context.syncSlotToClients(slot, stack);
        continue;
    }

    LivingTntData.set(stack, data.withExplosion(explosion));
    context.setItem(slot, stack);               // ⚠️ 必须显式写回（见下方说明）
    context.syncSlotToClients(slot, stack);
}
```

⚠️ 三个容易写错的地方，都是真机踩过的：

- **成功要 `return` 而不是继续循环**：`ignite` 会把容器里**所有**活 TNT 写成 `EMPTY`，
  之后任何写回都会把刚清掉的槽位填回去（「炸了但 TNT 还在」）。多颗时更糟 ——
  `entries` 是 tick 前的快照，其余条目照样会各自写回（把没轮到的复活）。
- **失败要熄灭**：无锚点时 `ignite` 返回 `false`。若直接 `return` 不写回，TNT 会停在
  `ignited=true / fuse=1` 的待爆态，挪到有锚点处**不点自爆**（旧「哑弹」语义，2026-10-28 撤销）。
- **必须显式 `setItem`**：不能依赖「`getItem` 返回的活引用」。NBT 背书的容器
  （精妙背包的 handler）里就地改组件不会触发它自己的保存钩子。

### 3.5 引信参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `fuseDuration` | 80 ticks | 从点燃到爆炸的时间（4秒） |
| 每 tick 递减 | 1 | 每次 tick 引信减 1 |

---

## 4. 爆炸系统

### 4.1 爆炸触发

`ExplosionComponent.ignite()` 在引信归零时被调用。爆炸中心必须先由可用锚点确定；
无锚点返回 `false`，**不消耗 TNT**，调用方随即**熄灭**（`ignited=false`、`fuseTimer=0`），
不留待爆态。熄灭后若槽位仍有红石信号，下一次 tick 会按正常入口重新点火（引信重头烧）；
否则变回普通活 TNT，需要重新点火才会炸。

| 锚点 / 容器形态 | 爆炸位置 / 约束 |
|---|---|
| `AtBlock` | 方块中心 |
| `AtPlayer` | 玩家位置 |
| `AtEntity`（船 / 矿车 / 掉落物） | 实体位置 |
| 物品形态容器 | 自身无锚点；仅 `tntUsesHostAnchor()` 开启且宿主锚点可用时借宿主位置；否则引爆失败 ⇒ 熄灭、不消耗 TNT |
| 原版 `CONTAINER` 内层 | 没有可用锚点时不爆、不消耗 TNT、引信归零时熄灭；不能由此推断其所有功能停止 tick |
| 原版玩家末影箱内容 | 无锚点，不爆、不消耗 TNT、引信归零时熄灭；见活末影箱 §15 |

锚点判定**不等于停掉容器全部 tick**。有可用锚点后，先以 long 统计容器内全部活 TNT；
正数计数、半径与模式参数、位图族布局的准入校验必须在清空槽位**之前**完成。非法输入拒绝且
保留物品，不能等位图分配失败后才发现 TNT 已被消耗。通过后才清空活 TNT、执行声光与实体伤害，
并登记方块破坏账本；容量拒绝属于已触发爆炸的降级，不是参数拒绝，见 §4.6。
无锚点的 TNT 在引信归零时熄灭，不进账本。

### 4.2 爆炸半径计算

```
radius = baseRadius × √(totalTntCount)

baseRadius = 4.0（默认）
```

| TNT 数量 | 半径 | 破坏范围 | 爆炸模式 |
|----------|------|---------|----------|
| 1 | 4.0 | 4格球体 | 普通 |
| 16 | 16.0 | 16格球体 | 普通 |
| 64 | 32.0 | 32格球体 | 普通 |
| 256 | 64.0 | 64格球体 | 大当量 |
| 1024 | 128.0 | 128格球体 | 大当量 |
| 3456 | 235.2 | 235格球体 | 大当量 |
| 10000 | 400.0 | 400格球体 | 超级爆炸 |

### 4.3 三种爆炸模式与逐区块执行

爆炸模式根据 TNT 总量自动选择：

| 模式 | TNT 数量 | 单区块破坏方式 | 掉落物 | 半径（格） |
|------|---------|---------------|--------|-----------|
| **NORMAL** | ≤ 64 | 原版 `setBlock()` 逐格替换 | 可选（原版衰减 / 100% 掉落） | ≤ 32 |
| **HIGH_YIELD** | 65 ~ 3456 | 直接改 `LevelChunkSection` 数据 | 无掉落物 | 32 ~ 235 |
| **SUPER** | > 3456 | 整区块清空 | 无掉落物 | > 235 |

#### ⚠️ 三种模式统一为**按区块**执行（2026-09-18）

旧实现是"一次性遍历整个球体"（普通/大当量在爆炸瞬间跑完；超级爆炸才按区块逐 tick）。
现在三者统一：唯一的破坏入口是
**`ExplosionComponent.applyToChunk(level, params, chunk)`**，立即阶段与延迟阶段都走它
⇒ 「同一场爆炸，无论区块什么时候加载，破坏结果一致」。

**为什么必须这样**：半径 `= 4.0 × √TNT数` —— 64 TNT 就跨 5×5 区块、3456 TNT 达 31×31 = **961 区块**。
其中一部分可能**未加载**：

- 当场去读 → `getChunk(requireChunk = true)` ⇒ 强制加载（单次凑 289 区块足迹、主线程阻塞、
  票据钉住、还会传染其他模组）
- 直接跳过 → 爆炸语义残缺：同一场爆炸，玩家站的位置不同、结果不同

⇒ 采用**原版 TNT 引信模型**：「世界只在被观测的地方演化」。不为爆炸读取未加载方块；
已加载区块按预算分帧破坏，未加载区块由保留的持久条目在自然加载后补上。规模化实现
保留 NORMAL/HIGH_YIELD 位图，SUPER 改存参数 + epoch（§4.6），不再枚举巨大覆盖网格。
完整推导、被否候选与世界交互红线见
[living-item-infrastructure.md](../system-design/living-item-infrastructure.md) §3.2.2。

#### ⚠️ 「哪些区块受影响」的判据 = **区块 AABB ∩ 球体**（2026-09-22 修）

`ExplosionParams.affects(ChunkPos)` 是**覆盖命中**的唯一判据：位图族以它排除网格角落，
SUPER 的已加载候选与自然加载补炸也只用它，不计算位图面积。每模式的实际破坏形状不变。

```
✅ 正确：区块 AABB 上离爆心最近的点  到爆心的距离 ≤ radius
❌ 错误：区块中心                   到爆心的距离 ≤ radius   ← 2026-09-22 前的实现
```

**为什么中心判据是错的**：区块是 **16×16** 的方块。"中心在半径外、边缘却落在球内"的区块
**大量存在**（区块中心最远可比最近角远约 11 格）。用中心判定会把它们**整块跳过** ——
而且是在**建档时**就标记成"已完成"，永不处理 ⇒
**坑不圆，边缘残留一整块区块形状的地形**（玩家可见）。

> ⚠️ 这条判据原本写着"与旧 `scheduleSuperExplosion` 同口径，保持不动以免改变既有行为"，
> 且被 `ExplosionParamsTest.affects_matchesLegacyCriterion` **钉住** —— 于是错误行为被测试保护了起来。
> 修的时候必须**同时改测试**（现为 `affects_isChunkAabbIntersectingSphere` +
> 边界回归 `affects_rimChunkWithCenterOutside_isAffected`）。
> **教训：把"既有行为"当规格钉住之前，先确认既有行为是对的。**

**只判水平方向就够**：区块是贯穿整个世界高度的柱体，只要 (x,z) 与球体的水平投影
（半径 r 的圆）相交，块内就一定有落在球体里的方块。

**旧判据漏掉多少区块**（纯几何可复算 —— 爆心在区块正中偏移 8 格的常见情形）：

| 半径 | 网格 | 旧命中 | 新命中 | **被漏掉** |
|---|---|---|---|---|
| 16 | 3² | 5 | 9 | 4 |
| 32 | 5² | 13 | 21 | 8 |
| 64 | 9² | 49 | 69 | 20 |
| 128 | 17² | 197 | 241 | 44 |
| 235 | 31² | 673 | 741 | **68** |
| 333 | 43² | 1361 | 1447 | **86** |

⇒ 每场大当量爆炸都有**几十个区块**被整块跳过（= 几十块方形地形留在坑边缘）。

**守卫用例**：`ExplosionParamsTest.affects_coversEveryBlockInsideSphere` ——
断言「半径内**每一个方块**所在区块都必须命中」。**已验证它在旧判据下会 FAIL**，
所以这条不是"通过了就完事"的空测试。

#### NORMAL（≤64 TNT）

```
对每个已加载的受影响区块（球体 ∩ 该区块）：
  ├─ 检查方块是否在爆炸范围内
  ├─ 检查方块抗爆性（≥100 跳过）
  ├─ 检查距离衰减（随机因子 0.7~1.3）
  └─ 破坏方块
      ├─ vanillaDrops=true  → 原版掉落（战利品表 + 1/radius 存活概率）
      └─ vanillaDrops=false → 100% 掉落（所有方块完整掉落）
```

#### HIGH_YIELD（65 ~ 3456 TNT）

```
对每个区块：
  ├─ 阶段1-收集：遍历「球体 ∩ 本区块」，把该炸的方块按 Section 分组
  ├─ 阶段2-修改：section.setBlockState() 直接设为空气（跳过所有更新回调）
  ├─ 阶段3-光照刷新：refreshLightAfterBulkEdit() —— 见下方「⚠️ 光照刷新」
  └─ 阶段4-同步：整区块打包 ClientboundLevelChunkWithLightPacket 发给跟踪该区块的玩家
```

**性能对比**：普通模式 65536 次 `setBlock()`（32 格半径）每次触发方块/光照更新；
大当量直接操作 Section 数据、跳过所有回调，速度提升约 100x。

⚠️ **代价**：跳过所有回调**也包括光照更新** —— 必须由阶段3补回来（见下）。

#### SUPER（> 3456 TNT）

```
爆炸触发时：声光 / 实体伤害仍立即执行；方块破坏登记参数 + 正 epoch，不分配位图
服务端 tick：取 FIFO 候选 → FULL 就绪时整轮应用 → 成功后更新该区块的 SUPER 水印
未就绪：留候选等下一 tick；真正卸载：丢候选，保留条目由自然加载重新触发
SUPER 无完成位图：不能凭当前已加载部分完成就退役，也不按时间过期
```

以上是当前表示法与调度边界，非已验证的性能结论；容量逐出与拒绝路径见 §4.6。

### ⚠️ 光照刷新（2026-09-22 修）

**症状**：大当量/超级爆炸后，坑里与坑壁**一片漆黑**（光照没刷新）。

**根因**：`NORMAL` 走 `level.setBlock()`，最终进入 `LevelChunk.setBlockState()` ——
原版把光照更新写在那里（`LevelChunk.java:258~270`）：

| 原版在那里做的事 | 触发条件 |
|---|---|
| `lightEngine.updateSectionStatus(pos, hasOnlyAir)` | section 由「非空」变「空」（或反向） |
| `lightEngine.checkBlock(pos)` | `LightEngine.hasDifferentLightProperties(...)` 为真 |

而 `HIGH_YIELD` / `SUPER` 为了性能**直接调 `LevelChunkSection.setBlockState()`**，
绕过了 `LevelChunk.setBlockState()` ⇒ 光照引擎完全不知道方块没了：
天光柱高图仍认为地下被堵死、光照数据仍是炸之前的值 ⇒
`ClientboundLevelChunkWithLightPacket` 发出去的就是**旧光照**。

**修法**：`ExplosionComponent.refreshLightAfterBulkEdit(level, chunk, removedLightSources)`
（每个受影响区块调一次，与账本的分帧粒度天然对齐）：

```
① chunk.initializeLightSources()        // 按当前方块重建「天光柱高图」
                                        // （原版 INITIALIZE_LIGHT 阶段同款调用）
② lightEngine.updateSectionStatus(SectionPos.of(chunkPos, minSection + i), section.hasOnlyAir())
                                        // 逐 section 上报空态；⚠️ 用 section Y 不是索引
③ lightEngine.propagateLightSources(chunkPos)
                                        // 天光按列重算 + 重新登记剩余方块光源（向四周扩散）
④ lightEngine.checkBlock(pos)           // 逐个「被炸掉的发光方块」显式减光
```

**两条必须记住的约束**：

- **① 必须早于 ③** —— ③ 是**按柱高图**算天光的；高图还是旧的（认为地下被堵死），
  算出来就还是黑的。顺序写反 = 修了等于没修。
- **③ 只负责增光，④ 才负责减光** —— `propagateLightSources` 只是把**现存**光源重新登记；
  光源方块被炸掉后残留的旧光，只能靠 `checkBlock` 降下来。
  `SUPER` 传空表即可（整区块清空 ⇒ 每个 section 都变空，旧光数据随 ② 被丢弃），
  `HIGH_YIELD` 在收集阶段顺手记录发光方块（`state.getLightEmission() > 0`，零额外扫描成本）。

**⚠️ ②③④ 在 `ThreadedLevelLightEngine` 上都是「入队」而非同步执行** ——
本 tick 只登记，真正计算在光照线程跑；算完后原版经
`ChunkHolder.sectionLightChanged` → `broadcastChanges` 自动发 `ClientboundLightUpdatePacket`
同步给客户端。所以紧跟其后的 `notifyChunkClients` 里那份光照**这一瞬间仍是旧的**，
**下一 tick 会被光照包覆盖成正确的**（与原版区块加载同一套机制：`initializeLight` /
`lightChunk` 也都是异步的）。

守卫用例：`ExplosionComponentLightTest`（钉接线与顺序，真实光照计算属游戏内验证）。

### ⚠️ 已知缺口：批量破坏未更新高度图

`HIGH_YIELD` / `SUPER` 绕过 `LevelChunk.setBlockState()` ⇒ 原版在那里对**每个方块**
调的 `heightmap.update(...)`（4 种高度图）同样被跳过了。

**后果**：
- `WORLD_SURFACE` / `MOTION_BLOCKING` 仍反映**炸之前**的地形 ⇒ 刷怪面、
  `Level.getHeight` 类查询、雨雪高度会看到已经不存在的地面。
- 这两张高度图**随区块包发给客户端**（`ClientboundLevelChunkPacketData` 的 `heightmaps`），
  客户端 `LevelChunk.replaceWithPacketData` 会 `setHeightmap` ⇒ **客户端也拿到陈旧值**。

**用户实测**：在炸空区域游历时，生物浮空生成，随后全部落入虚空。该现象与陈旧高度图
吻合，用户已确认值得修复；本次规模化提交只记录缺口，高度图修复另行实施。

**修复约束**：使用原版公开的高度图接口；SUPER 空区块重置与 HIGH_YIELD 受影响列更新
分别处理，不恢复逐方块邻居回调。`Heightmap.primeHeightmaps()` 不是唯一可用接口，且
不能假定它会将找不到有效方块的旧高度清零；修复须验证空列、剩余地面、保存/重载与客户端值，
并测量新增单区块成本。

**为什么是 54×64？**
- 54 = 大箱子槽位数、64 = 最大堆叠数 ⇒ 54×64 = 3456 是一个大箱子能装下的最大活TNT数量
- 超过它意味着玩家用了活箱子等手段堆叠更多 TNT
- 此时半径 = 4.0 × √3456 ≈ 235 格，涉及约 700 ~ 961 个区块
- 一次性清除会卡死服务器，必须分帧处理

**待炸账本的持久 / 运行态边界**：
- 爆炸参数与完成记录随每维度 `SavedData` 保存；SUPER 水印随区块官方附件保存。
- 候选队列与被拒 SUPER 的瞬态降级任务不持久化；服务端停止时必须清理，不持有旧世界。
  这是旧静态任务遗漏清理导致单机换存档泄漏的守卫边界，不另造生命周期体系。
- 只有位图族完成后自动移除；SUPER 的保留、逐出与队列预算统一见 §4.6。

**客户端同步**：整区块打包（`notifyChunkClients`），每区块一次，而非逐方块通知。

### ⚠️ 客户端「还画着旧方块」= 重建排队，不是数据没同步（2026-09-22 定性）

**现象**：爆炸后能看到一块块/方形区域**仍显示旧方块**，**过几秒到十几秒自己消失**。

**机制**（已逐段核实源码，不是推测）：

```
服务端 notifyChunkClients → ClientboundLevelChunkWithLightPacket
  └─ 客户端 handleLevelChunkWithLight
       ├─ updateLevelChunk → replaceWithPacketData   // 方块**数据**立刻正确
       └─ queueLightUpdate → enableChunkLight
            └─ 对**每个 section** 调 level.setSectionDirtyWithNeighbors(x, y, z)
                 // 每调一次 = 标记 3×3×3 = 27 个 section 需要重建
```

⇒ **数据是对的**（所以挖下去是空气、能走进去），**渲染是滞后的** ——
一次大当量爆炸改动**上万个 section**，每个又带 27 邻域标记，
客户端的重建队列要慢慢排；没轮到的 section 就继续画旧几何。
**队列排完就恢复正常**（所以"会自己消失"）。

**为什么必须走整区块包**：逐方块的 `ClientboundSectionBlocksUpdatePacket` 才是"只标记改动的那一个
section"（无 27 倍放大），但半径 235 的爆炸要改**几千万个方块**，逐方块发包体积不可接受
⇒ 只能整区块打包，代价就是这次重建风暴。

**⚠️ 与「坑不圆、整块残留」的区别（别混）**：

| | 表现 | 性质 |
|---|---|---|
| 本节（重建滞后） | 旧方块**过一会自己消失** | 正常，客户端排队 |
| `affects` 判据错（见 §4.3 前一节） | 方形区域**一直在**，有碰撞、挖下去有方块 | **bug**，服务端根本没处理那个区块 |

**判据**：**会自己消失 = 正常；一直都在 = bug。**

> 可选优化（**未做**）：爆炸边缘的区块往往只改动少量方块，
> 对这类区块改发 `ClientboundSectionBlocksUpdatePacket` 可以避开 27 倍邻域放大。
> 需要先量化"边缘区块占比"再决定是否值得 —— 别为了好看引入两套同步路径。

### 4.4 实体伤害

三种模式共用同一套实体伤害逻辑：

```
伤害公式（与原版相同）：
  impact = 1 - (距离 / 半径)
  damage = (impact² + impact) / 2 × 7 × 半径 + 1

击退：
  knockback = impact × 2.0
  dy += 0.5 × impact（额外向上击退）
```

**伤害特性**：
- 距离越近伤害越高
- 爆炸中心伤害 = 7 × 半径 + 1
- 爆炸边缘伤害 = 1
- 不受方块遮挡影响（穿透伤害）
- 物品实体（ItemEntity）不受伤害

### 4.5 音效与粒子

爆炸后播放：
- `SoundEvents.GENERIC_EXPLODE` — 爆炸音效
- `ParticleTypes.EXPLOSION_EMITTER` — 爆炸粒子（中心）
- `ParticleTypes.EXPLOSION` — 爆炸粒子（边缘随机散布）

---

### 4.6 规模化定案与验收边界（D-tnt-03）

**用户确认后已实现的变化**：大规模 SUPER 去掉覆盖网格位图与逐格建档；扩大可处理的
SUPER 当量，是能力扩展而非改玩法。`radius = baseRadius × √N`、三档阈值、点火 / 引信、
锚点与无锚点熄灭、实体伤害 / 击退、掉落、破坏形状及原有光照链均不变。
**以下描述当前实现与验收边界；游戏内性能与表现不由单测代替。**

#### 表示法与参数准入

| 族 | 保存 / 完成判据 | 边界 |
|---|---|---|
| NORMAL / HIGH_YIELD | 参数 + `long[]` 完成位图；全部完成后退役；不读 SUPER 水印 | 默认 `baseRadius=4.0` 与阈值合起来才保证网格 ≤961 bit，即 `ceil(961/64)=16` 个 long（128 B 裸数组载荷） |
| SUPER | 参数 + 正 epoch；无位图、覆盖坐标清单、剩余区块计数或范围级建档循环 | `affects` 直接判断候选区块；构造、日志、保存 / 读取、调度都不得暗算 `side²` |

≤961 **不是全部 API 输入的上界**：现有自定义 `baseRadius` 入口可让位图族网格更大，
本轮不加新半径配置或人为改阈值。位图边长、面积、偏移、索引、区块坐标与数组字数计算
必须用检查过的算术，不能先 int 溢出再转 long；非法中心、非有限 / 非正半径、不可表示布局
在消耗 TNT **之前**拒绝。long 求和守卫沿用，不声称 N 无上界或所有模式内存均有统一固定上限。

#### 候选、预算与水印

- **复用原版已加载视图**：从 `ChunkMap` 现有 protected `getChunks()` 枚举可见候选，
  仅增加该方法的窄 Access Transformer 访问口；不建第二份 loaded 登记表，也不误用仅含容器
  区块的 `ContainerChunkCache`。枚举 holder 不是 FULL 已就绪的证明。
- **FIFO、三态**：自然加载事件只提交坐标；tick 阶段区分「FULL 可用」「可见但 FULL 尚未就绪」
  「已卸载」。前者执行，未就绪保留 / 重试，真正卸载丢候选；保留的持久条目由自然重载再触发。
  禁止因 `getChunkNow == null` 就把尚未完成加载的候选永久丢掉；全程不主动加载或生成区块。
- **预算单位**：每维度每 tick 至多完成 `MAX_CHUNKS_PER_TICK = 32` 个**区块坐标整轮**，
  正常持久账本与 SUPER 瞬态降级共用；一个坐标命中多条仍需整轮处理，不按每条扣预算。
  不新增任意硬 backlog 上限；FIFO 只约束候选先后，不承诺单次检查或 tick 耗时恒定。
- **固定旧水印 + 快照 + 登记顺序**：一轮只读取一次旧 `W`，对原始登记顺序的混合模式
  条目快照逐项应用。位图族用自己的完成位；SUPER 只与本轮旧 `W` 比较。
  **整轮成功后**才写 `W = max(旧 W, 本轮已应用 SUPER epoch)`。不得逐条提前写水印、
  先 SUPER 后 NORMAL 重排、或用“终态都是空气”证明掉落无影响。
- **同 tick 新登记 / 重入**：新条目不能插进正在处理的快照，也不能在出队时把其候选一起
  覆盖丢掉；下轮仍必须能处理。异常退出不得提前提交本轮水印；这不等于撤销已发生的方块修改。
- **epoch**：每维度 `SavedData` 的持久正数单调计数器；条目空了也不重置，不用 gameTime，
  耗尽时拒绝而非回绕。区块水印使用 NeoForge 官方持久附件（long，缺失视为 0），
  只读检查不附着默认值；成功提交才写入，`setData` 负责脏标记，无新 Mixin / 私有哨兵。

#### 容量、退役与降级

`MAX_ENTRIES = 4096` 为两族**共享持久条目**上限；它不限制 TNT 数量或半径，也不保证
调度成本或 JVM 总内存。NORMAL/HIGH_YIELD 满时拒绝，新 SUPER 满时只逐出**最旧 SUPER**，
不得逐出未完成的普通位图。SUPER 不凭当前 loaded 部分完成而退役，不按时间过期。
逐出必须告警：丧失的是旧条目尚未兑现的后续破坏义务，不是“爆炸全部完成”；已炸内容不回滚。

若全是位图条目、没有 SUPER 可逐出，新 SUPER 被拒绝并进入**有预算的瞬态 loaded-only
降级**：仅拒绝瞬间 FULL 已就绪的目标集合，绑定同一 holder / chunk，不读写持久 SUPER 水印；
不能加入未来 Load，目标卸载后也不因重载重新补炸。
不保存该任务，执行完 / 失效后回收，服务端停止时清理候选与任务；不得退回巨大网格枚举或
同步无限量清空。NORMAL/HIGH_YIELD 原有“只炸当场已加载部分”的**同步**满账本降级不变。

#### 成本与持久化诚实边界

拆掉的是 SUPER 覆盖面积级的位图、队列注入与建档循环；几何参数存储不随覆盖区块数增长。
实际成本仍随已加载候选、保留条目、被拒瞬态任务以及实际破坏 / 同步工作增长；实体查询
仍走既有原版链路，也不是与半径无关。**不承诺全链 O(1)、固定 tick 时长或“巨型点火不掉 TPS”。**
本轮不新增整块空预扫描、嵌套 / 包含关系跳过优化，不改实体查询、伤害或破坏形状。

账本、计数器、区块内容与水印一起恢复到**一致旧快照**的完整回档可保持语义；硬杀、
部分回档、I/O 失败会落入不同文件的异步保存窗口，SavedData 与区块附件**不是跨文件事务**。
先 apply 后写水印是必要操作顺序，不证明崩溃原子性或所有模式重放都无副作用。
正常保存 / 读取往返须有测试验证，不将其外推为任意崩溃恢复保证；不加旧数据迁移或自动修复。

#### 验证入口与待验

既有入口：`ExplosionParamsTest`、`ExplosionLedgerTest`、`ExplosionComponentCountTest`、
`ExplosionComponentLightTest`；新增 `ExplosionSuperLedgerTest` 是 SUPER 账本 / 水印 / 降级守卫入口，
本轮需守住阈值 / 自定义半径算术、SUPER 无位图保存读取、
epoch 空账本后仍递增、FIFO 三态 / 32 坐标预算、混合原始登记顺序、旧水印整轮提交、
同 tick 新登记与异常退出、4096 容量 / 最旧 SUPER 逐出、拒绝瞬间 loaded-only 无重载续炸、
停止清理和正常保存往返。自动化结果读取全量测试快照；这里保留规则与验证入口，不维护测试数量。

#### 游戏内验证

**极限规模已通过（用户实测）**：无尽箱子中 `300 × 2,147,483,647 = 644,245,094,100`
个活 TNT，以世界出生点为爆心引爆；点火与当前加载范围的清空流畅，周围地形迅速化为空气。
按 `r = 4 × √N` 计算，理论半径为 `3,210,595.19` 格、区块半径为 `200,663`；连续使用
传送指令取样，在约 320 万格处观察到爆炸边界，与公式及 SUPER 的区块粒度边界一致。

该观测验证的是**建档不随理论覆盖面积展开**，以及自然加载后的区块能迅速兑现破坏；不表示
半径内全部未加载区块已被预先生成、遍历或写盘。未观测区域仍由持久参数在自然加载时求值。

**仍待组合验证**：多场混合档掉落时序、未就绪到 FULL 的补炸、炸后重建再卸载/重载、
下一发新 epoch 再炸、跨维度与正常重启续炸、容量降级与换存档清理，以及独立的光照表现。

## 5. Tooltip 显示

### 5.1 显示内容

| 状态 | 显示内容 |
|------|---------|
| 未点燃 | "TNT"（红色） |
| 已点燃 | "TNT [Fuse: N ticks]"（红色加粗） |

### 5.2 实现

```java
@Override
public void addToTooltip(Item.TooltipContext context,
                         Consumer<Component> tooltipAdder,
                         TooltipFlag flag,
                         ItemStack stack) {
    LivingTntData data = LivingTntData.of(stack);
    ExplosionData explosion = data.explosion();

    if (explosion.ignited()) {
        tooltipAdder.accept(Component.literal("TNT [Fuse: " + explosion.fuseTimer() + " ticks]")
            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
    } else {
        tooltipAdder.accept(Component.literal("TNT")
            .withStyle(ChatFormatting.RED));
    }
}
```

---

## 附录：使用示例

```
光标持有活打火石 → 右键容器槽位中的活TNT
（或光标持有活TNT → 右键槽位中的活打火石）
→ GUI 交互处理器点燃 TNT；生存模式扣打火石 1 点耐久
→ 引信从 80 ticks (4秒) 开始倒计时
→ 引信归零且有可用锚点时爆炸
→ 容器中所有活TNT被清空
→ 爆炸半径 = 4.0 × √(TNT堆叠总数)

无可用锚点 → 不爆、不消耗 TNT，熄灭引信（ignited / fuseTimer 归零）
```

---

## 附录：v2 变更记录 (2026-07-28)

> **历史记录（冻结）**：保留当时表述。

**变更1：DataComponent 迁移**

存储从 `LIVING_FUNCTION_DATA: CompoundTag` 迁移到独立的 DataComponent（`LivingTntData`），利用 Minecraft 内置的序列化和同步机制。

**变更2：Tooltip 国际化**

Tooltip 状态标题改为使用 `Component.translatable()` 国际化键，支持多语言。

---

## 附录：v3 变更记录 (2026-07-28)

> **历史记录（冻结）**：保留当时表述。

**变更：超级爆炸模式**

新增第三种爆炸模式，当 TNT 数量超过 3456（54×64）时启用：

- 爆炸触发时立即应用实体伤害和音效/粒子
- 区块破坏改为渐进式：**由待炸账本按区块分帧推进（每 tick 32 个区块）**，从爆炸中心逐步向外扩散
- 爆炸登记进世界级 `ExplosionLedger`（`ExplosionLedger.schedule`），支持多个爆炸同时进行；
  未加载的区块等自然加载时补炸（`ExplosionLedger.onChunkLoaded`）
- 通过 `ExplosionLedger.flushAll(server)` 在 `LivingItem.onServerTick()` 中每 tick 驱动
  （旧实现为 `ExplosionComponent.tickAll()` + `SuperExplosionTask` 队列，已废弃）
- 清除区块：移除所有方块实体 → 遍历 Section 设空气 → 标记区块 → 通知客户端

---

## 附录：验证清单

> 重构或架构迁移后，必须逐项验证以下用例。

### 基础爆炸

- [ ] 活TNT被活打火石点燃后开始倒计时
- [ ] 倒计时结束且有可用锚点时爆炸，清空容器中所有活TNT
- [ ] 无锚点（含原版 `CONTAINER` 内层无可用宿主锚点、原版玩家末影箱内容）不爆、不消耗 TNT，引信归零时熄灭（`ignited=false` / `fuseTimer=0`）
- [ ] 无锚点不新增待爆 / 储存爆炸状态；**熄灭后移至有锚点处不会自爆**（需重新点火）；熄灭时若红石信号仍在，下一 tick 重新点火
- [ ] 爆炸半径 = 4.0 × √(TNT堆叠总数)
- [ ] 未点燃时不爆炸
- [ ] 多个活TNT同时点燃时各自独立倒计时

### 点燃机制

- [ ] 活打火石通过 GUI 右键交互点燃活TNT（光标 / 槽位两种方向）
- [ ] 生存模式交互扣打火石 1 点耐久，创造模式不扣；不触发活漏斗冷却
- [ ] 点燃后 fuseTimer 从 80 开始倒计时
- [ ] 已点燃的活TNT不能被重复点燃
- [ ] 点燃后活TNT的 ignited 标志为 true

### 爆炸效果

- [ ] 爆炸清空容器中所有活TNT（同容器内连锁）
- [ ] 爆炸不影响容器中非活TNT物品
- [ ] 堆叠数影响爆炸半径：1个=4.0, 4个=8.0, 16个=16.0
- [ ] ≤64 TNT → 普通模式（有掉落物）
- [ ] 65~3456 TNT → 大当量模式（无掉落物，直接操作 Section 数据）
- [ ] >3456 TNT → 超级爆炸模式（渐进式逐区块消除）

### 超级爆炸模式

- [ ] 超过 3456 个 TNT 时自动启用超级爆炸模式
- [ ] 爆炸触发时立即应用实体伤害和音效/粒子
- [ ] 每 tick 消除一个区块，从中心向外扩散
- [ ] 区块消除后客户端正确显示为空气
- [ ] 多个超级爆炸同时进行时互不干扰
- [ ] 所有区块消除完毕后任务自动清理，无内存泄漏

### Tooltip 与同步

- [ ] 未点燃显示 "TNT"（红色）
- [ ] 已点燃显示 "TNT [Fuse: N ticks]"（红色加粗）
- [ ] Tooltip 国际化（Component.translatable）
- [ ] fuseTimer 变化后 Tooltip 实时更新

### 数据持久化

- [ ] LivingTntData 通过 DataComponent 持久化
- [ ] fuseTimer 在物品离开容器后保留；只有实际收到功能 tick 时推进；无锚点不重置引信，但归零引爆失败时熄灭
- [ ] ignited 状态在物品离开容器后保留