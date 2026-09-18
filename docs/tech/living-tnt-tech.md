# Living TNT (活TNT) 技术文档

> **文档版本**: 2026.08 v5  
> **最后更新**: 2026-08-22  
> **适用版本**: Minecraft 1.21.1

## 目录
1. [架构概览](#1-架构概览)
2. [数据结构](#2-数据结构)
3. [引信机制](#3-引信机制)
4. [爆炸系统](#4-爆炸系统)
5. [Tooltip 显示](#5-tooltip-显示)

---

## 1. 架构概览

### 1.1 什么是活TNT？

活TNT是一种**爆炸类活物品**，放置在容器中可以被活打火石点燃，引信倒计时归零后在容器所在位置引发爆炸。爆炸威力与容器中活TNT的总堆叠数量成正比。

活TNT的宿主物品是 `minecraft:tnt`（TNT），必须同时具备活物品标记。

### 1.2 数据流总览图

```
┌──────────────────────────────────────────────────────────────────┐
│                    活TNT 工作流程                                 │
│                                                                  │
│  ┌──────────────┐    点火        ┌──────────────────┐           │
│  │ 活打火石      │ ────────→     │ 活TNT (引信点燃)  │           │
│  │ (source)      │   (活漏斗)     │ fuseTimer = 80    │           │
│  └──────────────┘               └────────┬─────────┘           │
│                                          │ 每 tick              │
│  ┌──────────────┐                        │                      │
│  │ 活红石信号    │ ──→ 红石信号检测 ──→   │                      │
│  │ getSlotSignal │   (信号>0则点燃)      │                      │
│  └──────────────┘                        ▼ fuseTimer--          │
│                              ┌──────────────────┐              │
│                              │ fuseTimer == 0?  │              │
│                              └────────┬─────────┘              │
│                                       │ YES                     │
│                                       ▼                         │
│                              ┌──────────────────┐              │
│                              │ ExplosionComponent│              │
│                              │    .ignite()      │              │
│                              └────────┬─────────┘              │
│                                       │                         │
│              ┌────────────────────────┴────────────────────────┐ │
│              ▼ 立即（本 tick 做完）              ▼ 方块破坏（按区块分帧）│
│   ┌────────────────────────┐      ┌──────────────────────────────┐│
│   │ 音效/粒子 + 实体伤害     │      │ ExplosionLedger（待炸账本）    ││
│   │ （不碰方块）             │      │  参数 + 完成位图（SavedData）  ││
│   └────────────────────────┘      └──────────────┬───────────────┘│
│                                                   ▼                │
│                            ┌──────────────────────────────────────┐│
│                            │ 每 tick ≤ 32 区块 applyToChunk()      ││
│                            │  已加载 → 立即炸                      ││
│                            │  未加载 → 等它自然加载时补上           ││
│                            │  NORMAL / HIGH_YIELD / SUPER 共用     ││
│                            └──────────────────────────────────────┘│
└──────────────────────────────────────────────────────────────────┘
```

> ⚠️ **方块破坏为什么不在 `ignite()` 里做完**：半径可达 235 格（961 区块），其中一部分可能未加载。
> 详见 §4.3 与 [living-item-infrastructure.md](../system-design/living-item-infrastructure.md) §3.2.2。

### 1.3 关键类和职责

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `LivingTntFunction` | `domain/tnt/LivingTntFunction.java` | 活TNT功能入口，管理引信倒计时和爆炸触发，实现 `HasContainerData` 以支持红石信号点燃 |
| `ExplosionData` | `domain/tnt/ExplosionData.java` | 引信状态 record：ignited + fuseTimer |
| `LivingTntData` | `domain/tnt/LivingTntData.java` | 活TNT数据容器：包含 ExplosionData |
| `ExplosionComponent` | `domain/tnt/ExplosionComponent.java` | 爆炸执行引擎：逐区块破坏 + 实体伤害 + 音效粒子 |
| `ExplosionParams` | `domain/tnt/ExplosionParams.java` | 一次爆炸的参数 record（中心/半径/模式/掉落模式）+ **位图索引映射**（`bitIndex ↔ chunkAt` 互逆） |
| `ExplosionLedger` | `domain/tnt/ExplosionLedger.java` | **待炸账本**：世界级 `SavedData`，承载"哪些区块还没炸"的位图；已加载的分帧应用，未加载的等自然加载 |
| `LivingFlintAndSteelFunction` | `domain/tnt/LivingFlintAndSteelFunction.java` | 活打火石，提供点火触发标记 |
| `ContainerRedstoneData` | `domain/redstone/ContainerRedstoneData.java` | 容器级红石数据，提供 `getSlotSignal()` 供 TNT 检测红石信号 |

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
| 活打火石 | 活漏斗 source=打火石, target=TNT | 传统点火路径 |
| 红石信号 | 活TNT所在槽位收到红石信号（>0） | 可与活红石系统联动 |

### 3.2 打火石点火

点火由活漏斗的传输系统在检测到 source=活打火石、target=活TNT 时触发：

```java
// LivingTntFunction.startFuse()
public static boolean startFuse(ItemStack tntStack) {
    LivingTntData data = LivingItemManager.getTntData(tntStack);
    if (data.explosion().ignited()) return false;  // 已点燃，不重复点火
    LivingItemManager.setTntData(tntStack, data.withExplosion(data.explosion().ignite(80)));
    return true;
}
```

### 3.3 红石信号点火

活TNT通过实现 `HasContainerData` 接口融入容器级红石计算流程。当槽位收到红石信号时自动点燃：

```java
public class LivingTntFunction implements LivingItemFunction, HasContainerData {

    // 优先级 1：在红石数据计算（优先级 2）之前执行
    @Override
    public int getPriority() {
        return 1;
    }

    // 触发红石数据计算
    @Override
    public void tickContainerData(List<SlotEntry> entries, ContainerContext ctx, TickContext tick) {
        ContainerRedstoneData redstoneData = tick.getOrCreateRedstoneData(ctx);
        redstoneData.calculate(ctx, tick);
    }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        if (level.isClientSide) return;

        ContainerRedstoneData redstoneData = tick.getOrCreateRedstoneData(context);
        int size = context.getSize();
        int width = context.getWidth();

        for (SlotEntry entry : entries) {
            int slot = entry.slotIndex();
            ItemStack stack = entry.stack();
            LivingTntData data = LivingItemManager.getTntData(stack);
            ExplosionData explosion = data.explosion();

            // 红石信号点火（仅对未点燃的 TNT）
            if (!explosion.ignited()) {
                int signal = tick.getSensor(context).maxSensedSignal(slot);
                if (signal > 0) {
                    explosion = explosion.ignite();
                    LivingItemManager.setTntData(stack, data.withExplosion(explosion));
                    context.syncSlotToClients(slot, stack);
                    continue;
                }
                continue;
            }

            // ... 已点燃则继续倒计时逻辑 ...
        }
    }
}
```

**`RedstoneSensor.maxSensedSignal()` 方法（v19.1）**：通过感知端口 `TickContext.getSensor()` 读取槽位**四方向入边**的最大值（= 邻居朝 TNT 发出的出边）。

> **v19.1 语义修正**：v15 出边模型下，TNT 是非红石组件——信号层从不为它写边，旧实现 `maxOfSlot`（读自身出边）恒 0，红石信号点燃永久失效。修正后与电力层涂蜡采样同语义（读邻居出边）。边界（邻居越界）返回 0：TNT 不感应容器外信号。

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
@Override
public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
    if (level.isClientSide) return;

    for (SlotEntry entry : entries) {
        ItemStack stack = entry.stack();
        LivingTntData data = LivingItemManager.getTntData(stack);
        ExplosionData explosion = data.explosion();

        if (!explosion.ignited()) continue;  // 未点燃，跳过

        explosion = explosion.tick();  // fuseTimer - 1

        if (explosion.isExploded()) {
            // 引信归零 → 执行爆炸
            ExplosionComponent.ignite(context, level);
            LivingItemManager.setTntData(stack, LivingTntData.DEFAULT);  // 重置状态
        } else {
            LivingItemManager.setTntData(stack, data.withExplosion(explosion));
        }
    }
}
```

### 3.5 引信参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `fuseDuration` | 80 ticks | 从点燃到爆炸的时间（4秒） |
| 每 tick 递减 | 1 | 每次 tick 引信减 1 |

---

## 4. 爆炸系统

### 4.1 爆炸触发

`ExplosionComponent.ignite()` 在引信归零时被调用：

```java
public static boolean ignite(ContainerContext containerCtx, Level level,
                              float baseRadius, boolean vanillaDrops) {
    // 1. 确定爆炸中心
    BlockPos pos = containerCtx.getBlockPos();
    Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

    // 2. 统计容器中所有活TNT的总堆叠数
    int totalTntCount = 0;
    for (int i = 0; i < containerSize; i++) {
        ItemStack stack = containerCtx.getItem(i);
        if (isLivingTnt(stack)) totalTntCount += stack.getCount();
    }

    // 3. 计算爆炸半径
    double radius = baseRadius * Math.sqrt(totalTntCount);

    // 4. 清空所有活TNT物品
    for (int i = 0; i < containerSize; i++) {
        if (isLivingTnt(stack)) containerCtx.setItem(i, ItemStack.EMPTY);
    }

    // 5. 执行爆炸
    executeExplosion(level, center, radius, totalTntCount, vanillaDrops);
}
```

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

⇒ 采用**原版 TNT 引信模型**：「世界只在被观测的地方演化」。爆炸时只登记参数 + 位图
（不读任何方块）；已加载区块按预算分帧破坏；未加载区块**等它自然加载时补上**。
完整推导、三个候选的取舍与后果清单见
[living-item-infrastructure.md](../system-design/living-item-infrastructure.md) §3.2.2。

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
  └─ 阶段3-同步：整区块打包 ClientboundLevelChunkWithLightPacket 发给跟踪该区块的玩家
```

**性能对比**：普通模式 65536 次 `setBlock()`（32 格半径）每次触发方块/光照更新；
大当量直接操作 Section 数据、跳过所有回调，速度提升约 100x。

#### SUPER（> 3456 TNT）

```
爆炸触发时（立即）：
  ├─ 计算半径 = 4.0 × √(TNT总数)
  ├─ 播放音效/粒子 + 应用实体伤害（三模式共用，都不碰方块）
  └─ 登记进待炸账本（只写参数 + 位图）

每 tick（ExplosionLedger.flushAll ← ServerTickEvent.Pre）：
  ├─ 取待检查区块；**未加载的丢弃**（等它自然加载时由 ChunkEvent.Load 重新登记）
  ├─ 已加载且未完成的 → applyToChunk（清空整区块）→ 位图置位
  ├─ 每 tick 最多 MAX_CHUNKS_PER_TICK = 32 个区块（分帧限速）
  └─ 条目全部完成 → 移除（账本自动收敛）
```

**为什么是 54×64？**
- 54 = 大箱子槽位数、64 = 最大堆叠数 ⇒ 54×64 = 3456 是一个大箱子能装下的最大活TNT数量
- 超过它意味着玩家用了活箱子等手段堆叠更多 TNT
- 此时半径 = 4.0 × √3456 ≈ 235 格，涉及约 700 ~ 961 个区块
- 一次性清除会卡死服务器，必须分帧处理

**调度机制（2026-09-18 起）**：
- 由 **`ExplosionLedger`（世界级 `SavedData`，随存档持久化）** 承载，
  **不再是内存静态 Map** —— 旧的 `LinkedHashMap<UUID, SuperExplosionTask>` 在服务端关闭时
  **没有清理**，单人游戏「退出存档 → 进另一个存档」（不重启 JVM）会持有旧 `ServerLevel`
  ⇒ 内存泄漏 + 在已关闭的 level 上继续操作。已随本次改造消除。
- 每条条目 = 参数（中心 / 半径 / 模式 / 掉落模式）+ **完成位图**（`long[]`；961 区块 = 16 个 long ≈ 128 字节）。
- 支持多场爆炸同时进行（账本里多条条目，上限 `MAX_ENTRIES = 256`）。
- 只有"已加载但没进预算"的区块会 carryOver 到下一 tick；
  **未加载的**一律丢弃（否则每 tick 都要重扫几百个未加载区块）。

**客户端同步**：整区块打包（`notifyChunkClients`），每区块一次，而非逐方块通知。

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
    LivingTntData data = LivingItemManager.getTntData(stack);
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
容器布局：
┌───────┬───────┬───────┐
│       │       │       │
├───────┼───────┼───────┤
│活打火石│活漏斗  │活TNT  │
│(source)│(slot) │(target)│
└───────┴───────┴───────┘

活漏斗方向：source=左, target=右
→ 活漏斗检测到 source=活打火石, target=活TNT
→ 调用 LivingTntFunction.startFuse() 点燃 TNT
→ 80 ticks (4秒) 后爆炸
→ 容器中所有活TNT被清空
→ 爆炸半径 = 4.0 × √(TNT堆叠总数)
```

---

## 附录：v2 变更记录 (2026-07-28)

**变更1：DataComponent 迁移**

存储从 `LIVING_FUNCTION_DATA: CompoundTag` 迁移到独立的 DataComponent（`LivingTntData`），利用 Minecraft 内置的序列化和同步机制。

**变更2：Tooltip 国际化**

Tooltip 状态标题改为使用 `Component.translatable()` 国际化键，支持多语言。

---

## 附录：v3 变更记录 (2026-07-28)

**变更：超级爆炸模式**

新增第三种爆炸模式，当 TNT 数量超过 3456（54×64）时启用：

- 爆炸触发时立即应用实体伤害和音效/粒子
- 区块破坏改为渐进式：每 tick 消除一个整区块，从爆炸中心逐步向外扩散
- 使用 `LinkedHashMap<UUID, SuperExplosionTask>` 调度队列，支持多个爆炸同时进行
- 通过 `ExplosionComponent.tickAll()` 在 `LivingItem.onServerTick()` 中每 tick 驱动
- 清除区块：移除所有方块实体 → 遍历 Section 设空气 → 标记区块 → 通知客户端

---

## 附录：验证清单

> 重构或架构迁移后，必须逐项验证以下用例。

### 基础爆炸

- [ ] 活TNT被活打火石点燃后开始倒计时
- [ ] 倒计时结束后爆炸，清空容器中所有活TNT
- [ ] 爆炸半径 = 4.0 × √(TNT堆叠总数)
- [ ] 未点燃时不爆炸
- [ ] 多个活TNT同时点燃时各自独立倒计时

### 点燃机制

- [ ] 活打火石通过活漏斗点燃活TNT（活漏斗 source=打火石, target=TNT）
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
- [ ] fuseTimer 在物品离开容器后保留（可继续倒计时）
- [ ] ignited 状态在物品离开容器后保留