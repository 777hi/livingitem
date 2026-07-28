# Living TNT (活TNT) 技术文档

> **文档版本**: 2026.07 v3  
> **最后更新**: 2026-07-28  
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
│  ┌──────────────┐    点火     ┌──────────────────┐              │
│  │ 活打火石      │ ────────→  │ 活TNT (引信点燃)  │              │
│  │ (source)      │   (活漏斗) │ fuseTimer = 80    │              │
│  └──────────────┘            └────────┬─────────┘              │
│                                       │ 每 tick                  │
│                                       ▼ fuseTimer--             │
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
│                          ┌────────────┴────────────┬────────────┐       │
│                          ▼                         ▼             ▼       │
│                    ┌──────────┐             ┌──────────┐  ┌──────────┐ │
│                    │ ≤64 TNT  │             │65~3456 TNT│  │>3456 TNT │ │
│                    │ 普通模式  │             │ 大当量模式 │  │超级爆炸  │ │
│                    │ 有掉落物  │             │ 无掉落物  │  │逐区块消除│ │
│                    └──────────┘             └──────────┘  └──────────┘ │
│                          │                         │           │       │
│                          └────────────┬────────────┴───────────┘       │
│                                       ▼                         │
│                              ┌──────────────────┐              │
│                              │ 实体伤害 + 击退   │              │
│                              └──────────────────┘              │
└──────────────────────────────────────────────────────────────────┘
```

### 1.3 关键类和职责

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `LivingTntFunction` | `function/LivingTntFunction.java` | 活TNT功能入口，管理引信倒计时和爆炸触发 |
| `ExplosionData` | `data/ExplosionData.java` | 引信状态 record：ignited + fuseTimer |
| `LivingTntData` | `data/LivingTntData.java` | 活TNT数据容器：包含 ExplosionData |
| `ExplosionComponent` | `core/components/ExplosionComponent.java` | 爆炸执行引擎：破坏方块、伤害实体、粒子音效 |
| `LivingFlintAndSteelFunction` | `function/LivingFlintAndSteelFunction.java` | 活打火石，提供点火触发标记 |

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

### 3.1 点火触发

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

### 3.2 Tick 倒计时

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

### 3.3 引信参数

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

### 4.3 三种爆炸模式

爆炸模式根据 TNT 总量自动选择：

| 模式 | TNT 数量 | 破坏方式 | 掉落物 | 性能 |
|------|---------|---------|--------|------|
| **普通模式** | ≤ 64 | 原版 `setBlock()` 逐格替换 | 可选（原版衰减 / 100%掉落） | 慢但精确 |
| **大当量模式** | 65 ~ 3456 | 直接修改区块数据 `LevelChunkSection` | 无掉落物 | 快，适合大量 TNT |
| **超级爆炸模式** | > 3456 | 每 tick 消除一个整区块，从中心向外扩散 | 无掉落物 | 渐进式，不卡顿 |

#### 普通模式（≤64 TNT）

```
遍历球体范围内的每个坐标：
  ├─ 检查方块是否在爆炸范围内
  ├─ 检查方块抗爆性（≥100 跳过）
  ├─ 检查距离衰减（随机因子 0.7~1.3）
  └─ 破坏方块
      ├─ vanillaDrops=true  → 原版掉落（战利品表 + 1/radius 存活概率）
      └─ vanillaDrops=false → 100% 掉落（所有方块完整掉落）
```

#### 大当量模式（>64 TNT）

```
遍历爆炸范围内的所有区块：
  ├─ 遍历区块中每个 Section（16×16×16 区域）
  ├─ 遍历 Section 中每个方块
  ├─ 检查方块是否在爆炸半径内
  └─ 在范围内 → 直接设为空气（section.setBlockState()）
```

**性能对比**：
- 普通模式：65536 次 `setBlock()` 调用（32格半径），每次触发方块更新、光照更新
- 大当量模式：直接操作 NBT 数据，跳过所有更新回调，速度提升约 100x

#### 超级爆炸模式（>3456 TNT）

```
爆炸触发时：
  ├─ 计算爆炸半径 = 4.0 × √(TNT总数)
  ├─ 立即应用实体伤害（同其他模式）
  ├─ 播放爆炸音效和粒子
  ├─ 收集爆炸半径内的所有区块
  ├─ 按区块中心到爆炸中心的距离升序排列
  └─ 加入调度队列

每 tick（由 ExplosionComponent.tickAll() 驱动）：
  ├─ 从队列取下一个区块
  ├─ 移除区块内所有方块实体
  ├─ 遍历所有 Section，将每个方块设为空气
  ├─ 标记区块为已修改
  ├─ 通知客户端该区块所有方块位置已变更
  └─ 全部区块处理完毕 → 日志记录完成，清理任务
```

**为什么是 54×64？**
- 54 是大箱子的最大堆叠数（双箱子 54 槽）
- 64 是物品的最大堆叠数
- 54×64 = 3456 是一个大箱子能装下的最大活TNT数量
- 超过这个阈值，意味着玩家使用了活箱子或其他方式堆叠了更多 TNT
- 此时半径 = 4.0 × √3456 ≈ 235 格，涉及约 700 个区块
- 一次性清除会卡死服务器，必须渐进处理

**调度机制**：
- 使用 `LinkedHashMap<UUID, SuperExplosionTask>` 存储待处理任务
- 支持多个超级爆炸同时进行（极罕见但设计上支持）
- 任务完成后自动清理，不残留内存

**客户端同步**：
- 通过 `ChunkMap.getVisibleChunkIfPresent()` + `ChunkHolder.blockChanged(sectionY)` 批量标记 Section
- 每区块仅 ~24 次调用（Section 数量），而非 98,304 次（16×16×384 个方块位置）
- 单区块同步耗时 < 1ms，分布在每次 tick 中不会造成卡顿

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