# Living Farmland (活耕地) 技术文档

> **文档版本**: v1.1
> **最后更新**: 2026-09-13
> **适用版本**: Minecraft 1.21.1
> **状态**: 已实现，待游戏实测验证（骨粉催熟 + 双槽渲染修复后）

## 目录

1. [架构概览](#1-架构概览)
2. [数据结构](#2-数据结构)
3. [获取与种植交互](#3-获取与种植交互)
4. [作物分类与准入](#4-作物分类与准入)
5. [生长逻辑](#5-生长逻辑)
6. [产出机制（round-robin）](#6-产出机制round-robin)
7. [骨粉催熟](#7-骨粉催熟)
8. [客户端渲染（双槽）](#8-客户端渲染双槽)
9. [特殊作物类型](#9-特殊作物类型)
10. [实现文件与注册点](#10-实现文件与注册点)
11. [踩坑记录](#11-踩坑记录)
12. [验证清单](#12-验证清单)

---

## 1. 架构概览

### 1.1 什么是活耕地？

活耕地将原版的「耕地 + 作物」二维种植系统**虚拟化到容器网格**。玩家在容器 GUI 内完成
获取（活锄头耕活泥土）、种植（活种子右键）、催熟（活骨粉）三个动作，之后含活耕地的
被加载容器每 tick 自动推进作物生长，成熟后逐项产出作物到上方的「生长槽位」。

```
活耕地生存链路：

  活泥土 + 活锄头 ──右键──→ 活耕地（GUI 交互转换）
  活耕地 + 活种子 ──右键──→ 种植（写入作物类型标记，不消耗种子）
  活耕地 + 活骨粉 ──右键──→ 催熟 +2~5 级
                                │
  容器 tick ──每 200t──→ 概率生长（湿润×3 / 干燥×1）
                                │
  age = maxAge ──→ 冻结实³战利品表 → 每冷却周期产出一项 ×堆叠数 → 生长槽
                                │
  全部产完 ──→ 标准模式回 GROWING / 浆果模式保留成熟继续产出
```

### 1.2 核心设计决策（定案记录）

| # | 决策 | 理由 |
|---|------|------|
| 1 | **种子不消耗**：种植 = 写入作物类型标记 | 种子数与耕地堆叠数无法对应（5 个种子种 16 个耕地？），部分种植需记录比例、堆叠合并时比例不同又不能合并——整条链都是复杂度。一个种子标记一组耕地，产出按堆叠数放大，经济自洽 |
| 2 | **round-robin 逐项产出**：战利品表首轮成熟评估一次冻结进组件，每冷却周期产出一项 | 原版战利品表同时产出多种物品（小麦+种子）；「随机抽一种」期望减半且种子库归零（已否决）；「一次全放」一个槽放不下。冻结保证 round-robin 取项稳定（每轮一次掷骰） |
| 3 | **双槽渲染**：耕地槽叠加**种子图标**（类型区分）+ 上方空生长槽绘制作物阶段大图（阶段反馈） | 玩家实测需求「叠加种子图标方便区分作物」+「上方槽位显示作物」；湿润时耕地图标切原版 farmland_moist 深色纹理（零新 PNG） |
| 4 | **BLOCKED 语义删除**：生长槽被占只阻塞输出，生长照常 | 惩罚性 age 归 0 过重；生长与运输解耦更简单 |
| 5 | **浆果丛采后回退 age=1**（原版采摘语义） | 查证原版 SweetBerryBushBlock：右键采摘后 age 重置 1（非清零/非保留 maxAge）；产出由概率 tick 驱动模拟自动采摘 |
| 6 | **留种**：冻结时 cropSeed 产出项数量 -1 | 种植不消耗种子（标记制），产出中的种子 -1 作为变相自动补种 |
| 5 | **世界轴时间戳节拍**：`level.getGameTime()` 而非每 tick 计数器 | 稳态（冷却未到）零组件写入零同步（熔炉燃烧标志同铁律）；跨会话连续，与红电相位时钟 `resolvePhaseClock` 同源 |

### 1.3 数据流总览

```
服务端                                    客户端
─────────────────────────────────────────────────────────
LivingFarmlandFunction.tick（每容器 tick）
  ├─ 分组扫描由框架完成（scanAndGroupLivingItems）
  ├─ 冷却到期判定（getGameTime - lastGrowthAttemptTick ≥ 200）
  ├─ GROWING：湿润概率 → age++               ─┐
  ├─ MATURE：冻结/产出/推进/重置              ├─ 写组件 + syncSlotToClients
  └─ BLOCKED：age = 0                       ─┘   （脏槽批量同步）
                                                    │
                                                    ↓
                                    AbstractContainerScreenMixin
                                      render @TAIL 读组件 → blit
                                    （耕地小图 + 上方空槽大图）
```

---

## 2. 数据结构

### 2.1 FarmlandPlantComponent（DataComponent: `living_item:farmland_plant`）

`domain/farmland/FarmlandPlantComponent.java`，record + `Codec`/`StreamCodec` 双序列化，
`networkSynchronized` 随物品同步，跨容器迁移：

| 字段 | 类型 | 说明 |
|------|------|------|
| `cropSeed` | `@Nullable Item` | 作物种子（类型标记，null = 未种植；**不消耗**） |
| `age` | `int` | 当前生长阶段（0 ~ maxAge） |
| `maxAge` | `int` | 总生长阶段（种植时从作物方块读取并**冻结**——防模组更新后错位） |
| `lastGrowthAttemptTick` | `long` | 上次生长/产出尝试的世界轴时间戳（冷却节拍基准） |
| `outputIndex` | `int` | round-robin 当前产出项索引（-1 = 未开始/已完成） |
| `pendingDrops` | `List<ItemStack>` | 首次成熟时评估冻结的战利品列表（round-robin 数据源，Codec 用 `ItemStack.OPTIONAL_CODEC.listOf()`） |

**不进 `getIgnoredComponentTypes()`**——age/pendingDrops 是客户端渲染数据源，进忽略
集合会复现「大箱快照坑」（tooltip-system.md 坑清单第 3 条）。代价：仅同作物同 age 同
冷却戳才可堆叠（一堆 = 一片同步生长的田，语义合理）。

**`setData` 移除语义安全**：`LivingItemManager.setData` 在组件等于 DEFAULT 时会 remove；
已种植态 `cropSeed != null` 永不等 DEFAULT（`null,0,0,0L,-1,List.of()`），不受影响。

**网络编码细节**：`@Nullable Item` 用 `ByteBufCodecs.optional(ByteBufCodecs.registry(Registries.ITEM))`
+ `map` 桥接 Optional↔null（注意 StreamCodec.map 方向：factory 是 decode 侧
`Optional→Item`，getter 是 encode 侧 `Item→Optional`，写反会编译错——见踩坑 §11.4）。

### 2.2 状态机（第四轮精简：BLOCKED 已删除）

```
┌──────────┐  种植种子  ┌──────────┐
│  EMPTY    │─────────→│  GROWING │  age 0→max（概率 tick，200t 冷却门）
│ 未种植     │          └─────┬────┘
└──────────┘                 │ age 达到 maxAge
                             ↓
                     ┌───────────────────┐  概率成功（湿润 1/9 / 干燥 1/26）
                     │      MATURE       │  且待输出为空 → 冻结战利品表
                     └──────┬────────────┘
                            │ 【输出阶段·每 tick 不限速】
                            │ pendingDrops[outputIndex] → 生长槽
                            │ （空放/同种合并；放不下等位，生长不受影响）
                            ↓
                     全部产完 → 按回退点重置：
                       浆果丛 age = 1（原版采摘语义，重新长到 3 再产）
                       其余作物 age = 0（回 GROWING 重新长）
                     顶行耕地：冻结照常、输出挂起（搬到有生长槽位置自动续）
```

**核心规则表**：

| # | 规则 | 说明 |
|---|------|------|
| ① | 生长与生长槽状态完全解耦 | 生长槽被占只阻塞输出；生长照常推进（BLOCKED 语义已删） |
| ② | 成熟不着急获取战利品表 | 概率 tick 成功（原本 age++ 的时机）才评估+冻结 |
| ③ | 输出不限速 | 待输出冻结后每 tick 推进（每项一 tick），放不下等位 |
| ④ | 数量 × 堆叠数 | 每项产出 = `min(战利品数量 × 耕地堆叠数, 该物品堆叠上限)` |
| ⑤ | 留种 | 冻结时与 cropSeed 相同的产出项数量 -1（最多到 0）——变相自动补种 |
| ⑥ | 采后回退 | 浆果丛回 age=1（原版采摘语义）；其余回 age=0 重新长 |
| ⑦ | 无生长槽（顶行） | 生长/冻结照常，输出挂起，搬运后自动续 |
| ⑧ | 骨粉双语义 | 未成熟 +2~5（到 maxAge 即时冻结）；已成熟直接触发产出（确保冻结）；无变化不消耗 |


## 3. 获取与种植交互

三个交互全部走 GUI 交互系统（声明式规则 + 客户端统一拦截 + 服务端处理），注册点在
`LivingItem.commonSetup`。

### 3.1 活锄头耕活泥土 → 活耕地（物品转换型交互）

```java
// 6 种锄头各注册一条精确规则（target=DIRT, trigger=各锄头）
InteractionRegistry.register(new InteractionEntry(Items.DIRT, Items.WOODEN_HOE, 1, "till_to_farmland"));
// ... STONE/GOLDEN/IRON/DIAMOND/NETHERITE_HOE 同理
```

`TillToFarmlandHandler`：验证目标 = 活泥土、光标 = 活锄头（生存消耗 1 耐久，创造信任
客户端）→ 目标槽位**原地替换**为 `Items.FARMLAND`（新 ItemStack，保留堆叠数 +
`IS_LIVING`）→ broadcastChanges。

### 3.2 种植（通配条目 + triggerFilter 组合过滤 + 种子消耗）

```java
InteractionRegistry.register(new InteractionEntry(Items.FARMLAND, null, 1, "plant_crop",
    false, PlantCropHandler::canPlantWith));   // ← triggerFilter：BiPredicate(trigger, target)
```

`trigger=null` 是「任意光标」的通配条目，裸通配会拦截**所有**右键（含原版拿起/
放置/分堆）——`triggerFilter` 把拦截面收窄到「种得成」的组合（canPlantWith 双端
共用，客户端拦截门槛 + 服务端校验同一段逻辑）：

```java
canPlantWith(trigger, target):
  活种子（IS_LIVING）+ 可种植 + **种子数 ≥ 耕地堆叠数**
```

**种子消耗（第五轮定稿）**：消耗 = 活耕地堆叠数量的活种子（一堆 16 块耕地消耗
16 个活种子）；数量不足时 triggerFilter 不命中 → 不拦截 → 原版交换照常（种子与
耕地换位），服务端 handler 双重校验同样拒绝。

### 3.3 骨粉催熟（精确触发器）

```java
InteractionRegistry.register(new InteractionEntry(Items.FARMLAND, Items.BONE_MEAL, 1, "bonemeal"));
```

精确条目与种植通配共用 target/button，靠 findInteraction **两趟优先级匹配**分流
（详见 §11.1）。`BonemealHandler` 见 §7。

### 3.4 点击拦截与释放阶段（通用坑，活耕地三交互全部受益）

手持物品右键 = 「按下拦截 + `skipNextRelease` 置位」——原版对光标非空的放置/交换
发生在**释放阶段**，按下取消拦不住它。详见 gui-click-interception.md 坑 10。

---

## 4. 作物分类与准入

`CropClassifier`（domain/farmland）是种植准入、类型判定、maxAge 读取的唯一定义点。

### 4.1 三层种植准入

```java
isSeedPlantableOnFarmland(stack):
  🥇 Block 白名单：BlockItem 的方块 instanceof CropBlock | StemBlock | NetherWartBlock | SweetBerryBushBlock
     （或手动映射表命中——甜浆果 SWEET_BERRIES → Blocks.SWEET_BERRY_BUSH）
  🥈 模组标签：stack.is(Tags.Items.SEEDS)   // c:seeds
  🥉 手动注册 API：CropClassifier.registerManualSeed(item, block)（动态扩展点）
```

**为什么第 2 层不用 `instanceof BushBlock` 兜底**：1.21.1 源码验证 `FlowerBlock`、
`SaplingBlock`、`DeadBushBlock`、`TallGrassBlock` 全部继承 `BushBlock`——直接
instanceof 会把「种一朵花/一棵树苗/一丛草」全判成作物。

**为什么不用 `c:crops` 标签**：核对标签内容混有仙人掌、可可豆、甘蔗等非耕地作物
（种植后无耕地语义、产出走异常路径）。`c:seeds` 内容全是真种子（小麦/甜菜/西瓜/
南瓜/火把花），安全。

**甜浆果为什么需要手动映射**：`Items.SWEET_BERRIES` 不是 BlockItem（甜浆果丛在
原版无物品形态），不在任何种子标签——三层全漏。

### 4.2 maxAge 读取（种植时冻结）

```java
CropBlock        → crop.getMaxAge()     // 唯一有方法的原版作物类
StemBlock        → StemBlock.MAX_AGE        // 7（公开常量，无 getMaxAge 方法）
NetherWartBlock  → NetherWartBlock.MAX_AGE  // 3
SweetBerryBushBlock → ...MAX_AGE            // 3
其他（标签进来的模组作物）→ CropBlock.MAX_AGE 兜底（渲染/产出按实际 blockstate 钳制）
```

**勘误记录**：设计稿的 `StemBlock.getMaxAge()` 方法不存在——只有 `CropBlock` 有。

### 4.3 采后回退点

```java
getHarvestResetAge(block) = block instanceof SweetBerryBushBlock ? 1 : 0
```

产完重置到的 age：浆果丛对齐**原版采摘语义**（SweetBerryBushBlock 右键采后回
age=1，保留 2/3 进度，概率 tick 从 1 长回 3 再产）；其余作物回 0 重新长。
不存组件——作物方块的固有属性，产出时从 cropBlock 推断。

---

## 5. 生长逻辑

### 5.1 双阶段节拍（2026-09-13 第四轮定稿）

tick 内每块活耕地按固定顺序走两阶段：

```java
// 【输出阶段】每 tick 不限速：有冻结的待输出内容就往生长槽推（放得下就出）。
// 生长槽被占 → 放不进自然等待，生长不受影响；顶行无生长槽 → 输出挂起。
if (isMature && growthSlot >= 0 && !plant.pendingDrops().isEmpty()) {
    plant = tryOutputOnce(...);   // 放当前项/推进/产完重置
}

// 【概率生长 tick】冷却门 200t 世界轴——只节流概率判定，不节流输出：
if (now - plant.lastGrowthAttemptTick() < GROWTH_INTERVAL_TICKS) return;
plant = plant.withLastGrowthAttemptTick(now);
// 概率成功（湿润 1/9 / 干燥 1/26）：
//   未成熟 → age+1
//   成熟且待输出为空 → tryFreezeDrops（此刻才获取战利品表——成熟不着急）
```

世界轴与红电相位时钟同源决策：跨会话连续，不受容器本地 tickCounter 重进重置影响。
旧设计「每 tick growthCooldown++」会让一片 27 格耕地每 tick 27 次组件写入+网络包。

**首拍即时**：种植时戳为 0，`now - 0 ≥ 200` 恒真 → 种下后第一次冷却判定立即发生
（然后按 200t 节奏），玩家反馈更快。

### 5.2 湿润传播（原版式水分扩散，2026-09-13 第五轮定稿）

**湿润等级**：与水流相邻（4 向，含水桶源槽位）的活耕地 = **4 级**
（`MAX_MOISTURE_LEVEL`）；湿润沿相邻的活耕地传播、每跳 -1（4→3→2→1）；
**level ≥ 1 即湿润**（f=3.0），level 0 干燥（f=1.0）。对齐原版 4 格湿润半径：
水源起 4 块耕地（第五轮实测修正——源 3 级只够蔓延 3 块）。

```java
int[] moisture = computeMoisture(ctx, tick, size, width, entries);
// 源：与水流相邻的活耕地 = 3（tick.fluidData.getFlows() 键含邻居槽位）
// BFS：湿润沿相邻活耕地传播每跳 -1（参考活红石粉信号传播形态），
//      只有活耕地是传播介质（普通物品/空槽不传）
```

派生态：每 tick 从 fluidData 现算（活水桶 prio 0 容器级数据先行算好），无需跨 tick
存储。概率公式不变：`nextInt((int)(25/f) + 1) == 0`——湿润 1/9 ≈ 11.1%、干燥
1/26 ≈ 3.8%（7 级湿润 ~10.5 分钟 / 干燥 ~30.3 分钟）。「上」方向水源同样算相邻
（水桶源槽位的 FlowEntry 键 = 水桶自身槽位）。

### 5.2.1 湿润标志组件（图标 moist/dry 变体数据源）

`LIVING_FARMLAND_MOIST`（Boolean）——每 tick 检测（未种植耕地也更新，湿润是耕地
属性与种植无关），**翻转才写 + syncSlotToClients 主动同步**（进
`getIgnoredComponentTypes` 后 broadcastChanges 对其失明，必须手动推——熔炉燃烧标志
`LIVING_FURNACE_BURNING` 完全同款先例：稳态零写入零同步 + 翻转主动推）。
湿润/干燥耕地可堆叠。图标注册 moist（`isFarmlandMoist` 谓词，引用原版
`block/farmland_moist` 深色纹理）/ dry（默认）两变体，零新 PNG。

### 5.3 无生长槽（顶行/边缘）语义（2026-09-13 定案）

`resolveNeighbor(E_UP)` 对顶行返回 -1。**生长与产出评估都不受影响**（BLOCKED
语义已在第四轮精简删除——生长槽被占只影响输出，不影响生长）；无生长槽时输出
阶段挂起——搬到有生长槽的位置后自动续传。

---

## 6. 产出机制（round-robin）

### 6.1 战利品表冻结（`LivingFarmlandFunction.tryFreezeDrops`）

公开静态（骨粉即时冻结共用），首次成熟时评估一次：

```java
// 茎作物：直取果实方块物品（stem.fruit AT）——不滚果实战利品表（见下）
// 非茎作物：
BlockState matureState = matureStateFor(cropBlock);   // setValue(AGE, maxAge)
List<ItemStack> drops = Block.getDrops(matureState, level, BlockPos.ZERO, null);
plant = plant.withOutput(0, drops);
```

**茎作物直取果块（第六轮定稿）**：西瓜战利品表是 alternatives 结构——瓜块分支带
`match_tool` **精准采集**条件、空工具（`TOOL=EMPTY`）恒不命中 → 只出 3~7 西瓜片、
永远出不了瓜块；南瓜表虽本就掉南瓜块。统一改为直取 `fruit.asItem()` 输出果块本身
（1 个果块 ×耕地堆叠数），两条茎作物行为一致且不依赖工具参数。

**必须用公开静态 `Block.getDrops(state, level, pos, be)`**：内部自动补齐
`BLOCK_STATE`/`ORIGIN`/`TOOL=EMPTY` 三必填参数并走 `LootContextParamSets.BLOCK`
验证。手搓 `LootParams` 缺 `BLOCK_STATE` 不仅抛 `Missing required parameters`，
且 wheat 战利品表靠 `block_state_property(age=7)` 条件区分掉麦还是掉种子——缺参
条件全 false → 空产出（设计稿原始错误，见踩坑 §11.2）。

**为什么冻结进组件**：战利品表每次评估重新掷随机数（西瓜 3~7 片、种子 fortune 加成），
不冻结则 round-robin 的「每周期取下一项」取不稳——第 0 项和第 1 项来自两次掷骰。
冻结后产出期间列表稳定；浆果模式产完归零、下轮成熟重新评估（每轮一次掷骰）。

**留种（2026-09-13 定稿）**：冻结时遍历 drops，与 cropSeed 相同的产出项数量
**-1（最多到 0，0 则该项跳过）**——变相自动补种，种子不再全额掉落（小麦种子
2 → 1）。茎作物果实战利品表不含种子 → 不减。减后全空的极端情况（种子是唯一
产出且只掉 1）按回退点重置。

**AGE 属性读取**：`CropBlock.getAgeProperty()` 是 protected——统一从
`defaultBlockState().getProperties()` 按名字 `"age"` 取 `IntegerProperty`，
一个方法覆盖全部作物类型（含模组自定义 AGE 属性作物）。

### 6.2 逐项产出（每 tick 不限速，2026-09-13 第四轮定稿）

```java
// 【输出阶段】每 tick（在概率冷却门之前，不受 200t 节流）：
ItemStack drop = pendingDrops.get(outputIndex);
int count = Math.min(farmlandCount * drop.getCount(), drop.getMaxStackSize());
growthSlot 空 → 放入；同种未满 → 合并（min 到上限）；同种已满/不同种 → 本 tick 放不进
放入/合并成功 → outputIndex++（同 tick，不等玩家取走）
outputIndex ≥ pendingDrops.size() → 按回退点重置：
  浆果丛 age = 1（原版采摘语义，保留 2/3 进度）
  其余作物 age = 0（回 GROWING 重新长）
```

待输出冻结后**连续几个 tick 内全部送进生长槽**（每项一 tick），不再 10 秒一项；
放不下（不同种占据/同种已满）时该 tick 无操作下个 tick 重试——生长槽被占只阻塞
输出，不影响生长。

---

## 7. 骨粉催熟（强制触发一次生长 tick）

`BonemealHandler`（交互线程内执行，非容器 tick）：

**语义（第五轮定稿：作物一切行为由生长 tick 决定，骨粉只是触发器）**——立即执行
一次必定成功的生长 tick（`LivingFarmlandFunction.forceGrowthTick`，与容器 tick
概率成功后走的是同一段判定体）：

- 未成熟 → age+1
- 成熟且待输出为空 → 冻结实³战利品表（输出阶段每 tick 自动运输，下个 tick 送达生长槽）
- 组件无实际变化（如已冻结的成熟耕地重复右键）→ **不消耗骨粉**

验证：目标 = 已种植活耕地；光标 = 活骨粉（生存验证，创造免费）。

**历史勘误**：+2~5 用 `Mth.nextInt` 双闭区间（`RandomSource.nextInt(2,5)` 上界
排除只有 +2~4）；双语义版本（未成熟催熟/成熟直触）已被本简化取代。

## 8. 客户端渲染（双槽）

注入点：`AbstractContainerScreenMixin.render @TAIL`（`living_item$renderFarmlandCrops`），
纯客户端读组件（`networkSynchronized` 随物品同步），无服务端参与。

### 8.1 纹理解析：CropTextureResolver

**主路径 blockstate → 模型 → 粒子图标查表**（原版 GUI 同款链路）：

```java
BlockState state = stateForAge(block, age);        // setValue(AGE, age)，值域越界 null
BakedModel model = mc.getBlockRenderer().getBlockModelShaper().getBlockModel(state);
TextureAtlasSprite sprite = model.getParticleIcon();
```

不硬推导 `block/<name>_stage<age>` 纹理路径——1.21.1 原版数据核对：胡萝卜 blockstate
是 age 0~7 映射 **4 个模型**（`stage7` 路径不存在）、下界疣 age1/2 共用 stage1。
查 blockstate 的「状态 → 模型」权威映射天然覆盖，且自动带染色（crop 模型 tint）。

**⚠️ 染色必须显式套用（第六轮实测踩坑）**：粒子图标 blit 不会自动带 crop 模型的
tintindex 染色——南瓜/西瓜的藤蔓纹理是**灰度图**，原版在世界渲染时靠 BlockColors
按 age 染色（`ARGB32.color(age*32, 255-age*8, age*4)`：age 0 纯绿 → age 7 橙黄，
纯 age 驱动、忽略 level/pos，null 传入即可正确取色）。GUI 直绘精灵图不染色 →
藤蔓发白。修复：`CropTextureResolver.getCropTint(block, age)`——茎方块按 age 取
`getBlockColors().getColor(state, null, null, 0)`（4 参重载，无注册返回 -1），
生长槽大图按 -1 分流：染色走 `blit(..., sprite, r, g, b, a)`（positionTexColorShader
逐顶点色），预着色纹理（小麦/胡萝卜/果实图标）走无色 blit。

**预着色 vs 灰度对照**（1.21.1 BlockColors 核对）：小麦/胡萝卜/马铃薯/甜菜/下界疣/
甜浆果 = 预着色纹理（无注册）；**瓜茎/attached 茎** = 灰度 + age 染色；草/蕨/树叶
= 灰度 + 生物群系染色（null level 回退 GrassColor.getDefaultColor）。

- 茎作物成熟态：切**果实方块**的粒子图标（`stem.fruit` AT，与服务端产出来源一致）
- 兜底：成熟形态 BlockItem 的物品模型图标（非标准作物/缺 age 变体模型）
- 缺失判定：`TextureAtlasSprite` 无 `isMissing()` 方法——用
  `contents().name().equals(MissingTextureAtlasSprite.getLocation())` 比较

**渲染调用**：`guiGraphics.blit(x, y, 100, 16, 16, sprite)`（带精灵图对象的重载）。
探索代理已核实 `innerBlit` 自动 `RenderSystem.setShaderTexture(0, sprite.atlasLocation())`
+ `setShader(positionTexShader)`——**无需手动绑定图集纹理**（与水桶渲染的外部设置
差异是水桶需要 blend/着色，作物贴图不透明用默认即可）。z=100：物品模型 z=150 之下、
但物品模型是在 depth test 关闭窗口画的（无深度写入），TAIL 注入时 z=100/水桶 z=0
均实测可画。

### 8.2 双槽渲染策略（职责划分：耕地槽=种什么，生长槽=长到哪）

```
耕地槽：叠加【种子物品图标】（guiGraphics.renderItem(plant.cropSeed())，
  原版种子 item 纹理零新资源，物品图标自身大量透明、耕地图标仍可见）
  → 一眼区分种植的作物类型；跟物品走（顶行/搬运途中/背包乱放都有反馈）

生长槽：按【坐标匹配】找正上方一格（slot.y - 18）的同容器菜单槽，
  该槽为空时绘制作物当前阶段贴图（blockstate→模型→粒子图标）
  → 「空槽即画」自动覆盖全部状态：
     生长中空槽显苗 / 成熟产出后被真实物品覆盖 / 取走后显成熟形态
  → 同容器约束（slot.container 相等）防跨容器错位
     （箱子顶行耕地不会把苗画到玩家背包槽上）

湿润图标：LivingIconSpec 双变体（moist=原版 farmland_moist 深色纹理 /
dry=默认 farmland），谓词读 LIVING_FARMLAND_MOIST 组件（tick 翻转写入）。
```

**种子图标的层级修复（第五轮复盘）**：种子叠加最初用 `renderItem`（物品模型层
z=150）被耕地图标覆盖，pose 抬到 z=175 仍不显示、z=300 才可见——根因是 **TAIL
阶段深度测试已开启，绘制与世界深度缓冲竞争**（玩家盯着的箱面很近，z<300 的判定
输给箱面），而非与槽位内物品竞争。修复：立即模式 blit（同步 flush）+
`RenderSystem.disableDepthTest()` 临时窗口 + nominal z=175（高于物品 150、低于
堆叠数 200）+ blend 保透明。槽位叠加层的完整层级表与两个渲染窗口的语义收编
[icon-system.md「槽位叠加层渲染层级」](../system-design/icon-system.md)。

按坐标匹配而非 `resolveNeighbor` 索引算术：箱子 9 列、背包 9 列、模组容器列数不同、
创造模式 SlotWrapper——坐标 `y - 18` 是「正上方一格」的布局通用真理，天然适应
全部容器布局。

---

## 9. 特殊作物类型

### 9.1 作物类型对比（V1 范围）

| 作物类型 | 渲染 | 产出来源 | 产出物 | 模式 |
|---------|------|---------|-------|------|
| 标准作物（小麦/胡萝卜/马铃薯/甜菜/火把花） | 各阶段粒子图标 | 自身战利品表 | 作物+种子 | 标准→重置 |
| 茎作物（西瓜/南瓜） | 未成熟藤蔓（灰度+age 染色），成熟**果实图标** | **果实方块物品本身**（stem.fruit 直取，不滚表） | 西瓜块/南瓜 | 浆果模式 |
| 下界疣 | 3 阶段（AGE_3） | 自身战利品表（age=3 条件） | 下界疣×2~4 | 标准→重置 |
| 甜浆果 | 3 阶段（AGE_3） | 自身战利品表（age=3 条件） | 甜浆果×2~3 | 浆果模式 |

茎作物 `StemBlock.fruit` 是 `private final ResourceKey<Block>`，AT 读取
（`accesstransformer.cfg`：`public net.minecraft.world.level.block.StemBlock fruit`），
`BuiltInRegistries.BLOCK.get(fruitKey)` 解析。产出来源/渲染/成熟图标三处共用
`CropClassifier.getStemFruit`。

### 9.2 未来扩展（V1 不实现）

- **分阶段作物（FD 番茄 BuddingTomato）**：`BuddingBushBlock` 是 FD 类，
  无软依赖直接 instanceof 会 `NoClassDefFoundError`——必须 `compat/farmersdelight/`
  隔离（compat/create 先例）。设计储备：连续 age 映射 + `phaseTransitionAge` 字段
  （届时随需求加回组件）
- **两格高作物（FD 水稻等）**：需要独立的上/下半阶段计数与跨槽位渲染 +
  `CropHeightRegistry` 显式注册表（V1 无高度枚举消费者，单格渲染统一）
- **含水耕地（水田）**：「恒湿润」是该槽位自身是水田的环境结果（与水流检测同属
  「环境决定湿润」），不是无条件白给
- **手动注册 API 扩展**：`CropClassifier.registerManualSeed` 已有雏形，需要时公开

---

## 10. 实现文件与注册点

### 10.1 文件清单

```
src/main/java/com/qiqi/li/
├── living/domain/farmland/
│   ├── FarmlandPlantComponent.java     # 种植数据组件（Codec + StreamCodec）
│   ├── CropClassifier.java             # 作物分类器（准入/maxAge/浆果判定/茎果实）
│   └── LivingFarmlandFunction.java     # tick 功能（生长/产出状态机 + tooltip）
├── living/interaction/
│   ├── TillToFarmlandHandler.java       # 活锄头 → 活耕地（6 锄头规则）
│   ├── PlantCropHandler.java            # 种植（通配 + handler 校验）
│   └── BonemealHandler.java             # 骨粉催熟（精确触发器）
├── client/render/
│   └── CropTextureResolver.java         # blockstate→模型→粒子图标解析
└── client/mixin/AbstractContainerScreenMixin.java  # render @TAIL 双槽渲染
```

### 10.2 注册点

| 注册 | 位置 | 内容 |
|------|------|------|
| 组件 | `LivingItemManager` | `FARMLAND_PLANT` DeferredRegister + `get/setFarmlandPlant` |
| 功能 | `LivingItem.commonSetup` | `registerFunction(new LivingFarmlandFunction())` |
| 交互规则 | 同上 | DIRT×6 锄头（till）、FARMLAND+null（plant）、FARMLAND+BONE_MEAL（bonemeal） |
| 交互处理器 | 同上 | `registerHandler` × 3（actionId 对应） |
| AT | `accesstransformer.cfg` | `public net.minecraft.world.level.block.StemBlock fruit` |
| 图标 | `LivingIconRegistry.registerAll` | FARMLAND → `item/farmland_living`（引用原版 `block/farmland` 顶面纹理，零新 PNG） |
| lang | `assets/living_item/lang/` | `tooltip.livingitem.farmland.*` ×4（中英） |

### 10.3 测试

| 测试 | 覆盖 |
|------|------|
| `InteractionRegistryTest`（5 项） | 两趟优先级：精确不被通配遮蔽（骨粉→bonemeal）、非精确回退通配（种子→plant_crop）、精确要求活触发器、无匹配 null、非活目标不匹配 |

---

## 11. 踩坑记录

### 11.1 通配自交互遮蔽精确交互（2026-09-13 游戏实测，骨粉催熟失效根因）

**现象**：活骨粉右键活耕地无任何效果。**根因**：`plant_crop`（trigger=null 通配）
与 `bonemeal`（当时也是 trigger=null）同 target/button 注册，`findInteraction` 按
注册顺序返回首个匹配 → 骨粉永远命中先注册的 plant_crop，PlantCropHandler 对骨粉
静默返回，BonemealHandler 是**死代码**。**修复**：① findInteraction 两趟匹配
（第一趟精确 `triggerItem != null`，第二趟通配）；② bonemeal 条目改精确触发器
`Items.BONE_MEAL`。**守卫**：InteractionRegistryTest.preciseEntryBeatsWildcardEvenIfRegisteredLater
（故意按最坏顺序注册断言精确优先）。结构性教训：**通配条目的语义是「兜底」而非
「抢先」，匹配优先级必须通配让位于精确**。

### 11.2 战利品表构造缺 BLOCK_STATE 必填参数（设计稿错误，实现时拦截）

设计稿手搓 `LootParams.Builder` 只给 TOOL/ORIGIN：`LootContextParamSets.BLOCK`
三必填（BLOCK_STATE/ORIGIN/TOOL），缺参 `create()` 抛 `Missing required parameters`；
且 wheat 战利品表靠 `block_state_property(age=7)` 条件区分掉麦/种子，缺参条件全
false → **空产出**。修法：公开静态 `Block.getDrops(state, level, pos, be)` 一行式，
内部自动补齐。顺带：1.21.1 `Block.getLootTable()` 返回 `ResourceKey<LootTable>`
非 `ResourceLocation`，查询入口是 `server.reloadableRegistries().getLootTable(key)`。

### 11.3 顶行耕地误判永久 BLOCKED（2026-09-13 游戏实测，作物不生长）

旧实现 `slotBlocked = growthSlot < 0 || !生长槽空`——顶行 `resolveNeighbor(E_UP)`
返回 -1 被算进 BLOCKED → age 恒 0 永不生长，与规则②「只有被放入物品才阻塞」矛盾，
玩家测试时耕地放第一行即「完全没动静」。修复：`slotBlocked = growthSlot >= 0 && 槽被占`，
顶行正常生长、产出挂起（§5.3）。

### 11.4 1.21.1 API 细节勘误集合

| 错误写法 | 正确写法 | 说明 |
|---------|---------|------|
| `StemBlock.getMaxAge()` | `StemBlock.MAX_AGE` | 公开常量，无方法；NetherWart/SweetBerry 同 |
| `getAgeProperty()` 直接调用 | `state.getProperties()` 按名取 | protected 访问限制，且按名取覆盖模组自定义 AGE |
| `BuiltInRegistries.BLOCK.getValue(key)` | `BuiltInRegistries.BLOCK.get(key)` | Registry.get(ResourceKey) |
| `sprite.isMissing()` | `contents().name()` 与 missing 位置比较 | TextureAtlasSprite 无该方法 |
| `RandomSource.nextInt(2,5)` 当 +2~5 | `Mth.nextInt(r,2,5)` | 前者上界排除 = +2~4 |
| `BuiltInRegistries.ITEM.streamCodec()` | `ByteBufCodecs.registry(Registries.ITEM)` | DefaultedRegistry 无 streamCodec() |
| `stage<age>` 路径推导纹理 | blockstate→模型→粒子图标 | 胡萝卜 8age→4stage 映射破坏推导 |
| 手动 `RenderSystem` 设置后 blit 精灵 | 直接 blit（innerBlit 自动绑纹理+shader） | 水桶需要的是 blend/tint 而非绑定 |

### 11.5 按下拦截后释放阶段原版 PICKUP 二次执行（2026-09-12，交互通用坑）

手持物品右键时原版的放置/交换发生在**释放阶段**——按下拦截成功必须置位原版
`skipNextRelease` 让释放自我跳过，否则交互生效的同时光标与槽位被交换。
活耕地三交互全部受益。详见 gui-click-interception.md 坑 10。

### 11.6 创造模式跳过种子校验（顺带修复）

旧 PlantCropHandler 创造模式直接跳过可种植性校验——但创造光标经
`GuiInteractionPacket.carriedTag` 已在服务端恢复，是可校验的。不校验则能把
活泥土等垃圾「种」上形成死循环作物。修复：两种模式统一「IS_LIVING + 可种植」双校验。

### 11.7 裸通配条目吞掉原版右键操作（2026-09-13 第三轮实测反馈）

**现象**：活耕地的**所有**右键都被拦截——空手右键分堆（拿一半）、拿起/放置等原版
操作全部失效。**根因**：plant_crop 是 `trigger=null` 裸通配条目，客户端匹配发生在
原版点击逻辑**之前**，任何光标都命中 → `cir.setReturnValue(true)` 取消原版 →
handler 里空手静默返回——交互没发生，但原版操作也已被吞。对照：活TNT 的条目是精确
触发器（FLINT_AND_STEEL），只有拿活打火石才拦，所以从无此问题。
**修复**：`InteractionEntry` 新增 `triggerFilter` 谓词（`BiPredicate<trigger, target>`，
由 findInteraction 两趟匹配统一执行），通配条目靠它把拦截面收窄到「种得成」的组合
（活种子 + 可种植 + 种子数 ≥ 耕地堆叠数）——其余光标不匹配 → 不发包不取消，原版
操作照常。服务端 handler 校验保留（双重防线）。
**规则收编**：gui-interaction-system.md §5.1——特定物品交互用精确触发器；「一类物品」
交互用 triggerFilter 谓词；只有真·任意光标自交互（按钮/拉杆）才留裸通配。

### 11.8 茎作物藤蔓发白（第六轮实测：灰度纹理未套 age 染色）

南瓜/西瓜藤蔓纹理是灰度图，原版靠 BlockColors 的 age 驱动染色器上色（见 §8.1）。
GUI blit 精灵图不会自动应用模型 tintindex 染色 → 藤蔓白色、无状态变化。修复：
`getCropTint`（BlockColors 4 参重载，null level/pos 对 age 驱动染色器安全）+ 生长槽
大图按染色分流 `blit(..., sprite, r, g, b, a)`。

### 11.9 茎作物成熟后没有产物（第六轮实测：果块无 age 属性）

`tryFreezeDrops` 的产出来源是果实战利品表（`stem.fruit` → 南瓜/西瓜块），但
`matureStateFor(果块)` 在方块无 age 属性时返回 null → 战利品表永远冻结失败 →
成熟无产物。修复：无 age 属性的方块（果实块）`defaultBlockState()` 即成熟态，
直接返回。教训：**「构造成熟态」的语义是「可滚战利品表的方块状态」，果块没有
生长阶段概念，默认态即可用**——不要把 age 属性假设强加到所有产出来源方块上。

后续定稿（同轮）：茎作物**不再滚果实战利品表**——西瓜表的瓜块分支带精准采集
`match_tool` 条件、空工具恒不命中，永远只出西瓜片；改为直取 `fruit.asItem()` 输出
果块本身（南瓜表虽无此问题，统一直取）。matureStateFor 的默认态回退由此只剩
防御意义（茎作物路径不再经过）。

### 11.10 取消活化残留 farmland_plant 组件（第三轮实测反馈）

`clearLivingData`（LivingItemManager）是取消活化时的统一组件清理点，其 javadoc 明确
要求「新增功能必须在此添加 remove，否则旧数据残留」——活耕地实现时漏了。表现：
取消活化的耕地 tooltip 仍显示旧作物（客户端组件还在），再活化时旧种植状态复活。
修复：`stack.remove(FARMLAND_PLANT.value())` 补进清理列表。

---

## 12. 验证清单

### 12.1 交互链路

- [ ] 活锄头右键活泥土 → 泥土变耕地、锄头留手上只掉耐久（6 种锄头逐一）
- [ ] 活种子右键活耕地 → tooltip 显示作物 + 生长 0/N，**消耗与耕地堆叠数等量的种子**
- [ ] 种子数不足（< 耕地堆叠数）→ 不拦截，原版交换照常
- [ ] **活骨粉右键活耕地 → 强制生长 tick：未成熟 +1 级 / 成熟触发产出**（本次验证点）
- [ ] 骨粉催到成熟 → tooltip 显示已成熟，且**立即**开始产出（不等概率周期）
- [ ] 已冻结的成熟耕地重复右键骨粉 → 无变化不消耗
- [ ] 非活种子/非活骨粉右键 → 无交互（原版行为）
- [ ] 已种植耕地再种 → 无反应；取消活化再活化 → 种植数据清空

### 12.2 生长与产出

- [ ] 小麦（湿润：邻格放活水桶）：~10 分钟成熟
- [ ] 小麦（干燥）：~30 分钟成熟（或用骨粉验证概率外的路径）
- [ ] 成熟后**不立即出产物**——等概率 tick 成功（湿润 ~90s 期望）才冻结战利品表，
      随后**连续几 tick 内全部进生长槽**（不再 10 秒一项）
- [ ] 种子产出留种 -1：小麦种子 2 → 1（变相自动补种）
- [ ] 标准模式产完 → 回到生长 0/7 重新长
- [ ] 西瓜（茎作物）：成熟显示西瓜图标、**产出西瓜块**（果块直取，非西瓜片）
- [ ] **南瓜/西瓜藤蔓随 age 变色**（绿 → 橙黄，原版 age 染色；不再发白）
- [ ] **南瓜成熟产出南瓜块、西瓜产出 3~7 西瓜片**（果实战利品表修复验证）
- [ ] 甜浆果：产出后回 stage1 贴图，重新长到 stage3 再产（原版采摘回退语义）
- [ ] **顶行耕地：正常生长**，成熟后 tooltip 已成熟但不产出，
      搬到下方槽位后开始产出
- [ ] 生长槽放杂物 → 生长照常、成熟后产物等位不进槽（BLOCKED 重置已删）
- [ ] **骨粉右键已成熟耕地 → 立即触发产出**；重复右键已冻结的不消耗骨粉
- [ ] **湿润图标**：邻格放活水桶 → 耕地图标变深（farmland_moist）；收走变浅

### 12.3 渲染

- [ ] **耕地槽叠加种子物品图标**（类型一眼区分，随物品搬运跟随）
- [ ] **上方空槽显示作物当前阶段贴图**（「土下苗上」）
- [ ] **湿润图标切换 + 传播**：邻格放活水桶 → 直接相邻耕地变深（3 级），
      相邻耕地的相邻耕地也变深（2→1 级传播）；收走水桶全部恢复浅色
- [ ] 传播只沿活耕地：中间隔普通物品/空槽则不传播
- [ ] 生长槽被产出物占据时显示真实物品图标（作物贴图让位）
- [ ] 胡萝卜/下界疣各阶段贴图正确（查表路径的映射验证）
- [ ] 大箱子中渲染正常（CompoundContainer 同步链路）
