# Living Farmland (活耕地) 技术文档

> **文档版本**: v1.11
> **最后更新**: 2026-09-16
> **适用版本**: Minecraft 1.21.1
> **状态**: 已实现，九轮实测全过 + 终审修复后；自动施肥已收编为注册式槽位交互（跨容器两方向统一，§11.15），2026-09-15 用户实测确认正常；放置回世界（§3.5）2026-09-16 新增；种子图标迁到装饰器路径（§8.2，快捷栏现在也渲染）2026-09-16

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
  活耕地 + 活种子 ──右键──→ 种植（消耗与耕地堆叠数等量的活种子，数量不足无法种植）
  活耕地 + 活骨粉 ──右键──→ 强制一次必定成功的生长 tick（未成熟 +1 / 成熟触发产出）
                                │
  容器 tick ──每 200t──→ 概率生长（湿润×3 / 干燥×1）
                                │
  age = maxAge ──→ 概率 tick 成功时冻结战利品表 → 每 tick 逐项产出 ×堆叠数 → 生长槽
                                │
  全部产完 ──→ 标准模式回 GROWING / 浆果模式保留成熟继续产出
```

### 1.2 核心设计决策（定案记录）

| # | 决策 | 理由 |
|---|------|------|
| 1 | **种子消耗**（第五轮修订）：种植消耗与耕地堆叠数等量的活种子，数量不足无法种植 | 初版「标记制不消耗」被实测推翻——不消耗则种子无限白嫖产出；等量消耗让「一堆耕地 = 一片田」经济自洽（详见 §3.2） |
| 2 | **round-robin 逐项产出**：战利品表首轮成熟评估一次冻结进组件，每 tick 不限速产出一项 | 原版战利品表同时产出多种物品（小麦+种子）；「随机抽一种」期望减半且种子库归零（已否决）；「一次全放」一个槽放不下。冻结保证 round-robin 取项稳定（每轮一次掷骰）；合并仅当放得下整份（部分合并会丢差额，2026-09-14 终审修复） |
| 3 | **双槽渲染**：耕地槽叠加**种子图标**（类型区分，走 `IItemDecorator`）+ 上方空生长槽 renderSingleBlock 世界管线绘制作物阶段大图（阶段反馈） | 玩家实测需求「叠加种子图标方便区分作物」+「上方槽位显示作物」；湿润时耕地图标切原版 farmland_moist 深色纹理（零新 PNG）。种子图标 2026-09-16 从容器 Mixin 迁到装饰器路径——原实现依赖 `leftPos/topPos`，**HUD 快捷栏画不出来**（§8.2） |
| 4 | **BLOCKED 语义删除**：生长槽被占只阻塞输出，生长照常 | 惩罚性 age 归 0 过重；生长与运输解耦更简单 |
| 5 | **浆果丛采后回退 age=1**（原版采摘语义） | 查证原版 SweetBerryBushBlock：右键采摘后 age 重置 1（非清零/非保留 maxAge）；产出由概率 tick 驱动模拟自动采摘 |
| 6 | **留种**：冻结时 cropSeed 产出项总量 -1 | 种植消耗等量种子（第五轮），产出中的种子 -1 作为变相自动补种（如小麦种子 2→1）；多池掉同种种子也只扣一份 |
| 7 | **世界轴时间戳节拍**：`level.getGameTime()` 而非每 tick 计数器 | 稳态（冷却未到）零组件写入零同步（熔炉燃烧标志同铁律）；跨会话连续，与红电相位时钟 `resolvePhaseClock` 同源 |

### 1.3 数据流总览

```
服务端                                    客户端
─────────────────────────────────────────────────────────
LivingFarmlandFunction.tick（每容器 tick）
  ├─ 分组扫描由框架完成（scanAndGroupLivingItems）
  ├─ 冷却到期判定（getGameTime - lastGrowthAttemptTick ≥ 200）
  ├─ GROWING：湿润概率 → age++               ─┐
  ├─ MATURE：冻结/产出/推进/重置              ├─ 写组件 + syncSlotToClients
  └─ 输出放不下 → 本 tick 等待重试           ─┘   （脏槽批量同步）
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
| ⑧ | 骨粉 = 强制一次必定成功的生长 tick | 未成熟 +1 / 成熟且待输出为空 → 触发产出（冻结）；无变化不消耗（第五轮定稿：作物一切行为由生长 tick 决定，骨粉只是触发器；+2~5 双语义已删） |


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

### 3.5 放置回世界（模拟玩家右键种一次，2026-09-16）

**语义**：把一块**已种植**的活耕地物品**放置到世界中**时，放置出来的耕地上直接长出那株作物。

**做法：模拟玩家右键，而不是自己塞方块。** `LivingFarmlandPlacement.onPlaced` 构造
`UseOnContext`（命中面 = 耕地顶面）后调 `ItemStack.useOn` —— 原版 `BlockPlaceContext`
会自动把落点算到 `farmlandPos.above()`，并完整走**原版与模组自己的**种植校验
（`canSurvive`、模组覆写的 `useOn`）。

> ⚠️ **不要改成 `CropClassifier.getBlockFromSeed` + `level.setBlock`**。硬塞方块会绕过
> 模组校验（例如「水稻只能在水下种」），种出非法状态。**兼容性优先于实现便利** ——
> 用 `useOn` 后连 `CropClassifier`、`canBeReplaced` 检查、`is(Blocks.FARMLAND)` 检查
> 都不需要了（种子自己的 `canSurvive` 会校验下方是耕地），代码反而更短。

**触发条件**：`isPlantable(stack)` = `Items.FARMLAND` + `IS_LIVING` + `cropSeed != null`。
非活耕地 / 未种植的活耕地走原版路径，**零行为变化**。

**调用点**：`mixin/BlockItemMixin`（`@Mixin(BlockItem.class)`），注入 `place` 里
`consume` 调用**之前**（原因见 §11.16）。

**口径与边界**：

| 维度 | 行为 |
|------|------|
| 成熟度 | **一律种成幼苗**（用户定调）——物品里的 `age` 不保留 |
| 失败处理 | **软逻辑**：种不上（条件不满足 / 模组拒绝 / 抛异常）一律静默，只留一条 WARN 日志。整段兜在 try/catch 内——异常冒泡出 `BlockItem.place` 会造成「方块已放、物品未扣」并炸 tick |
| 未排空产出 | `pendingDrops` 掉落到耕地旁，不凭空消失 |
| 多格作物 | 上部件 / 柱段**不**由我们放置，交给原版/模组自己长 |

回归测试 `LivingFarmlandPlacementTest`（5 项）：触发条件真值表 / 落点在耕地之上且一律
age 0 / 客户端不生效 / **异常不冒泡** / **端到端 Mixin 接线**（`BlockItem.place` 全流程，
同时钉住「必须注入在 `consume` 之前」）。

---

## 4. 作物分类与准入

`CropClassifier`（domain/farmland）是种植准入、类型判定、maxAge 读取的唯一定义点。

### 4.1 三层种植准入

```java
isSeedPlantableOnFarmland(stack):
  🥇 Block 白名单：BlockItem 的方块 instanceof CropBlock | StemBlock | NetherWartBlock
     | SweetBerryBushBlock | PitcherCropBlock
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

**瓶子草为什么需要显式列入白名单（第八轮实测踩坑）**：`PitcherCropBlock`
**extends DoublePlantBlock 而非 CropBlock**——白名单四个 instanceof 全不中；
`pitcher_pod` 又不在 c:seeds 标签（标签 JSON 已核对：只有甜菜/西瓜/南瓜/火把花/
小麦五族）→ 三层准入全漏无法种植。只加 `PitcherCropBlock` 精确类，不用
`DoublePlantBlock` 兜底（玫瑰/牡丹/向日葵全是 DoublePlantBlock 会误入）。

### 4.2 maxAge 读取（种植时冻结）

```java
CropBlock        → crop.getMaxAge()     // 唯一有方法的原版作物类
StemBlock        → StemBlock.MAX_AGE        // 7（公开常量，无 getMaxAge 方法）
NetherWartBlock  → NetherWartBlock.MAX_AGE  // 3
SweetBerryBushBlock → ...MAX_AGE            // 3
其他（标签进来的模组作物）→ state 定义里 "age" IntegerProperty 的真实上限
  （FD 水稻 AGE_3 → 3、BuddingTomato 0~4 → 4；完全无 age 属性才兜底 CropBlock.MAX_AGE）
```

**勘误记录**：设计稿的 `StemBlock.getMaxAge()` 方法不存在——只有 `CropBlock` 有。

**⚠️ 兜底 7 的坑（第七轮实测：FD 稻米/番茄苗成熟无产物 + 渲染种子图标）**：
非 CropBlock 模组作物回落到硬编码 7，而 FD 水稻是 AGE_3、番茄苗 0~4——maxAge=7
冻结进组件后：生长 tick 把 age 推过值域 → `stateForAge` 值域外返回 null → 渲染走
兜底（种子物品图标）；`matureStateFor` 同样 null → 战利品表永远冻结失败 → 无产物。
修复：getMaxAge 增加通用回退——`CropClassifier.getAgeProperty(block)`（common 安全
的 age 属性查找）取属性真实最大值。**注意**：修复前种下的 FD 作物组件里 maxAge=7
已冻结，需铲掉重种。

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
// 源：与水流相邻的活耕地 = 源 4 级 MAX_MOISTURE_LEVEL（tick.fluidData.getFlows() 键含邻居槽位）
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
**不落盘**：只有 `networkSynchronized`、无 `persistent`（vanilla `MAP_POST_PROCESSING`
先例，与 LIVING_FURNACE_BURNING / LIVING_HOPPER_FILTER 同口径）——湿润度每 tick
可从流体邻接重算，持久化无正确性价值，徒增存档脏写。

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
// 非茎作物：收获形态解析（见下）→ 滚「收获形态方块」的成熟态战利品表
Block lootSource = cropBlock;
ResourceLocation harvestId = CropClassifier.getHarvestBlockId(cropBlock);
if (harvestId != null) {
    Block harvest = BuiltInRegistries.BLOCK.get(harvestId);
    if (harvest != Blocks.AIR) lootSource = harvest;   // 模组不在 → 回退自身
}
BlockState matureState = matureStateFor(lootSource);   // setValue(AGE, maxAge)
List<ItemStack> drops = Block.getDrops(matureState, level, BlockPos.ZERO, null);
plant = plant.withOutput(0, drops);
```

**茎作物直取果块（第六轮定稿）**：西瓜战利品表是 alternatives 结构——瓜块分支带
`match_tool` **精准采集**条件、空工具（`TOOL=EMPTY`）恒不命中 → 只出 3~7 西瓜片、
永远出不了瓜块；南瓜表虽本就掉南瓜块。统一改为直取 `fruit.asItem()` 输出果块本身
（1 个果块 ×耕地堆叠数），两条茎作物行为一致且不依赖工具参数。

**收获形态解析（第七轮定稿，2026-09-14）**：作物自身战利品表不一定是收获形态——
FD 下部方块表只掉种子本身（`rice.json` → 稻谷×1、`budding_tomatoes.json` →
番茄种子×1），滚它会被留种扣成空产出（零产出循环，见 §11.11）。
`CropClassifier.HARVEST_BLOCKS` 把「作物方块 → 收获形态方块」显式注册，冻结时
滚覆盖方块的成熟态战利品表：

| 作物（种植入口） | 收获形态方块 | 空工具产出 |
|---|---|---|
| `farmersdelight:rice` | `rice_panicles`（上部抽穗 @AGE=3） | 稻穗×1（刀才出稻谷；稻穗≠种子，留种不扣） |
| `farmersdelight:budding_tomatoes` | `tomatoes`（成熟原地转化的结果藤 @age=3） | 番茄×1~2 + 种子×1（留种恰扣种子）+ 5% 烂番茄 |
| `minecraft:torchflower_crop` | `torchflower`（成熟态花方块——作物表任何 age 只掉种子×1） | 火把花×1（无种子，留种 no-op） |

其余作物无覆盖 → 滚自身（小麦/胡萝卜/浆果等原版行为不变）。键值按注册名延迟
解析（UPPER_CROPS 同款软依赖安全），`registerHarvestBlock` 为公开扩展点。
番茄藤 `VINE_AGE` 属性名就是 `"age"`、`ROPELOGGED` 默认 false——`matureStateFor`
现有逻辑直接命中两个产出池的 `block_state_property` 条件，无需特判。

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
2 → 1）。茎作物直取果块不含种子 → 不减。减后全空（种子是唯一产出且只掉 1）
**原样输出未扣减的产出**——耕地组件本身持久化、产完照常按回退点重长，不依赖
留种补种；按回退点重置空转只会零产出循环（2026-09-14 修正，见 §11.11）。

**AGE 属性读取**：`CropBlock.getAgeProperty()` 是 protected——统一从
`defaultBlockState().getProperties()` 按名字 `"age"` 取 `IntegerProperty`，
一个方法覆盖全部作物类型（含模组自定义 AGE 属性作物）。

### 6.2 逐项产出（每 tick 不限速，2026-09-13 第四轮定稿）

```java
// 【输出阶段】每 tick（在概率冷却门之前，不受 200t 节流）：
ItemStack drop = pendingDrops.get(outputIndex);
int count = Math.min(farmlandCount * drop.getCount(), drop.getMaxStackSize());
growthSlot 空 → 放入；同种且剩余空间 ≥ 整份 → 合并；空间不足/不同种 → 本 tick 放不进
放入/合并成功 → outputIndex++（同 tick，不等玩家取走）
outputIndex ≥ pendingDrops.size() → 按回退点重置：
  浆果丛 age = 1（原版采摘语义，保留 2/3 进度）
  其余作物 age = 0（回 GROWING 重新长）
```

待输出冻结后**连续几个 tick 内全部送进生长槽**（每项一 tick），不再 10 秒一项；
放不下（不同种占据/同种剩余空间不足整份）时该 tick 无操作下个 tick 重试——生长槽
被占只阻塞输出，不影响生长。

**合并仅当放得下整份（2026-09-14 终审修复）**：旧实现「塞 min(outputCount, space)
即推进索引」在玩家从生长槽拿走一半产物时，`outputCount - toAdd` 差额物品静默
消失——部分合并 = 丢物品。修复后空间不足就整份等待，与「不同种占据」同一语义，
零损失；回归守卫 LivingFarmlandFunctionTest（5 项）。

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

### 7.1 自动施肥（活漏斗联动，2026-09-15 新增）

**触发**：活漏斗按 WASD 方向传输时，货物是**骨粉**且目标槽位是**活耕地**——
由注册式槽位交互 `FarmlandBonemealInteraction` 接管（`SlotInteractions` 内置条目，
2026-09-15 收编：此前方程硬编码在三处传输分支，拉取方向漏写导致单方向失效，
见 §11.15）。三处调用点统一走 `SlotInteractions` 分发器：
容器内 `TransferPipeline.executeInContainer`、跨容器推送 `tryPushToNeighbor`、
跨容器拉取 `tryInteractFromNeighbor`（流程图 [3.5]，详见
[living-hopper-tech.md §6.2.1](living-hopper-tech.md)）。

**语义**：与 GUI 活骨粉右键共用 `forceGrowthTick` + equals 零空转（无变化不
消耗），但**触发物口径有意区分**：

| 路径 | 触发物 | 定位 |
|------|--------|------|
| GUI 右键（BonemealHandler） | **活**骨粉 | 手动 = 活化能力（右键本就是「活物品使用自己的能力」） |
| 漏斗传输（`FarmlandBonemealInteraction` → tryFertilize） | **只认普通骨粉** | 自动 = 传输语义——施肥是「漏斗用传输能力把骨粉送进活耕地」，因此受漏斗自己的货物规则约束：**活物品不作货物**（隔离规则），活骨粉不是合法货物 |

> **为什么漏斗不吃活骨粉**（2026-09-15 用户定案，别当 bug 改掉）：
> 施肥被定义为**漏斗的传输能力**把骨粉送进活耕地——它是传输语义，不是「交互层新增的
> 消耗通道」。活物品隔离（活物品不被其它活物品当普通物品处理）是漏斗自身的规则，
> 不能因为「反正要消耗掉」而给它开洞。**手动要活化、自动要普通**，两条链路各自口径清晰。
>
> 实现：规则唯一定义点 `SlotInteractions.isEligibleCargo`（活物品不作货物，活箱子/活末影箱
> 除外），传输层与交互层共用；活骨粉在**四个方向都不施肥**，且不会被当普通货物入槽/合并。
> 详见 [living-hopper-tech.md §6.2.1](living-hopper-tech.md)。

**节奏与消耗（一次施肥 = 一次传输，与普通传输完全同节奏）**：

| 维度 | 行为 | 来源 |
|------|------|------|
| 施肥间隔 | 冷却 `max(1, 8 − N/8)`——N = 漏斗堆叠数：1 个 8t/次、8 个 7t、**56 个以上每 tick 一次** | 漏斗 tick 的通用冷却公式（LivingHopperFunction），施肥返回 true 自然设冷却 |
| 单次剂量 | **固定 1 粉/次，不随漏斗堆叠放大** | 普通传输量是 `min(N, 64)`/次，但施肥刻意不放大——一次生长 tick 只值一粉；按堆叠放大则 64 漏斗一次烧 64 粉 |
| 堆叠耕地联动 | **1 粉 = 整叠耕地同步 +1 age**（堆叠耕地共享一个 FARMLAND_PLANT 组件），产出再 ×耕地堆叠数 | 施肥走 forceGrowthTick 改的就是那一个共享组件 |
| 空转守卫 | 整叠耕地已冻结（等待输出）→ equals 零空转，**一滴粉不烧**（对堆叠同样生效） | tryFertilize 的 equals 守卫 |

**堆叠性价比结论**：最优形态 = 大堆耕地 + 堆叠漏斗——漏斗数压节奏（56+ =
每 tick 施一次）、耕地数放大收益（1 粉整叠同涨 + 产出按堆叠数倍出）、
冻结窗口零浪费。1 个漏斗配 1 块耕地是最差配比（8t 一粉只推一格 age）。

**骨粉堆就是续航**：源槽的骨粉堆 = 施肥库存，用完自动停（管道空源检查
自然短路，无报警无残留行为）。

**跨容器（两个方向各有入口，语义同一）**：施肥方程已收编为注册式槽位交互
`FarmlandBonemealInteraction`（`SlotInteractions` 内置条目）——三处调用点
（容器内 / 跨容器推送 / 跨容器拉取）都只调分发器，**方程本身零传输代码**。
推送方向与邻居间直传共用 `tryPushToNeighbor` 的槽位循环；**拉取方向**（源在邻居、
目标活耕地在本容器）走 `tryInteractFromNeighbor`——这条**必须前置**，因为通用拉取
对活耕地目标槽必然失败（§11.15）。邻居槽冻结 → 交互返回 false → 不接管继续找
（多耕地只有需要的吃粉）；无匹配目标槽 → 骨粉作为普通货物照常入箱。
`getStackInSlot` 是 BE 实时引用，组件修改即刻生效；推送方向 GUI 同步由耕地所在容器
自身 tick 兜底，容器内与拉取方向（耕地在本容器）由调用点 `syncSlotToClients` 主动推。

回归测试 `FertilizeTransferTest`（6 项）+ `CrossContainerTransferFertilizeTest`（15 项，
两个分发入口 + 三个方向各覆盖）+ `SlotInteractionCargoGateTest`（5 项，货物准入）：
未成熟 age+1 扣粉 / 已冻结零空转 / 非活耕地拒绝 / 未种植拒绝 / 非骨粉拒绝 /
**拉取方向施肥生效 + 跳空槽找粉 + 邻居无粉不误伤 + 冻结不烧粉** /
**活骨粉（活物品）四方向都不施肥、不入槽、不合并** / 准入真值表。

## 8. 客户端渲染（双槽 + 世界级管线直绘）

注入点：`AbstractContainerScreenMixin.render @TAIL`（`living_item$renderFarmlandCrops`），
纯客户端读组件（`networkSynchronized` 随物品同步），无服务端参与。

### 8.1 通用机制：renderSingleBlock（世界级管线直绘，2026-09-13 定稿）

作物视觉的**正路线**——`BlockRenderDispatcher.renderSingleBlock(state, pose, buffer,
FULL_BRIGHT, NO_OVERLAY)` 把任意 BlockState 用完整世界渲染管线画进 GUI：

- blockstate → 烘焙模型（就是世界里的那个模型，含自定义几何——FD 茎的逐段生长、
  瓶子草上下两段，全是模型本身的内容，**自动呈现中间态**）
- **BlockColors 染色自动套用**（茎的 age 驱动染色、草系生物群系回退——发白问题从根上消失）
- 自动路由正确 RenderType（NeoForge `RenderTypeHelper.getEntityRenderType(rt, false)`
  为 GUI/无 cull 场景适配）
- 支持 ModelData / ENTITYBLOCK_ANIMATED（带 BEWLR 的方块也能画）

mixin 里的封装（`living_item$renderBlockState`）：

```java
pose.pushPose();
pose.translate(x, y + 16, 100);       // 块底锚定槽位底部（角落原点模型，非物品中心原点！）
pose.scale(16.0F, -16.0F, 16.0F);      // 1 方块 = 16px，Y 翻转对齐 GUI
// 强制 cutout RenderType（7 参重载）：默认会转实体渲染变体，其着色器带双光源
// 漫反射（按法线着色）——作物十字模型法线朝水平方向，漫反射吃掉大半亮度 → 发暗
// （2026-09-13 实测踩坑）；cutout 无漫反射，亮度纯由 FULL_BRIGHT 光照图决定
mc.getBlockRenderer().renderSingleBlock(state, pose,
    mc.renderBuffers().bufferSource(), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
    net.neoforged.neoforge.client.model.data.ModelData.EMPTY, RenderType.cutout());
pose.popPose();
mc.renderBuffers().bufferSource().endBatch();   // 立即物化
```

**锚点勘误（第七轮实测：渲染位置错位半格）**：方块模型是**角落原点**（0,0,0~1,1,1），
不是物品模型的中心原点——translate 用物品习惯的 `(x+8, y+8)` 会让方块画到
「槽中心→右下 16px」整体偏移半格。正确配方 `translate(x, y+16, 100)` +
`scale(16, -16, 16)`：模型 (0,1,0) 顶角 → 槽位左上 (x, y)，(1,0,1) 底角 → 槽位
右下，恰好铺满槽位且 Y 翻转后正立。

**边界**：不渲染 BlockEntity 渲染器（箱子开合动画那类 BEWLR 需走
`BlockEntityRenderDispatcher.render` 路径——作物无 BE 不受影响）；光照为 GUI 满亮
（要"真世界光照"可用耕地真实位置的 packedLight）；性能与物品图标渲染同级。

### 8.2 双槽渲染策略（职责划分：耕地槽=种什么，生长槽=长到哪）

```
耕地槽：叠加【种子物品图标】全尺寸 16×16（走 IItemDecorator —— LivingFarmlandSeedDecorator，
  原版种子粒子图标，零新资源）→ 一眼区分种植的作物类型；跟物品走
  ⚠️ 2026-09-16 从容器 Mixin 迁到装饰器路径：原实现依赖 leftPos/topPos ⇒ 快捷栏画不出来
生长槽：按【坐标匹配】找正上方一格（slot.y - 18）的同容器菜单槽，空槽时
  renderSingleBlock 当下 age 的作物状态 → 「土下苗上」原样呈现；
  成熟产出后被真实物品覆盖 / 取走后显成熟形态；同容器约束防跨容器错位
湿润图标：LivingIconSpec 双变体（moist=原版 farmland_moist 深色纹理 /
  dry=默认 farmland），谓词读 LIVING_FARMLAND_MOIST 组件（tick 翻转写入）
```

**种子图标的层级修复（第五~八轮迭代史）**：`renderItem`（物品模型层 z=150）被耕地
图标覆盖 → z=175 仍被吞、z=300 才可见——根因是 **TAIL 阶段深度测试已开启，绘制与
世界深度缓冲竞争**（玩家盯着的箱面很近，z<300 判定输给箱面）。中间试过半尺寸居中
避让、重绘堆叠数文字——最终定稿：**全尺寸 16×16 + disableDepthTest，盖住堆叠数
数字为已知取舍**（类型辨识优先）。槽位叠加层完整层级表与两窗口语义收编
[icon-system.md「槽位叠加层渲染层级」](../system-design/icon-system.md)。

**2026-09-16 迁移：快捷栏不渲染的修复**。上面那套「TAIL + `disableDepthTest`」方案
**只在容器 GUI 生效**——HUD 快捷栏走 `Gui.renderHotbar` → `Gui.renderSlot` →
`GuiGraphics.renderItemDecorations` → `ItemDecoratorHandler`，**从不经过
`AbstractContainerScreen`**，而 TAIL 版硬依赖 `leftPos`/`topPos` ⇒ 快捷栏里永远不画。
已迁到 `IItemDecorator`（`LivingFarmlandSeedDecorator`，自抬 z=200）：装饰器在
**快捷栏 / 容器 GUI / 创造物品栏 / 副手槽**都会被调用，一份代码全覆盖、只画一次。

装饰器的坐标与 z 契约（三处易错点：容器传的是 `slot.x` 而非 `slot.x+leftPos`、
容器内 z 有 +100 基准故净 300、`ItemDecoratorHandler` 会重开深度测试故不能写
`RenderSystem`）详见 [icon-system.md「种子图标改走装饰器路径」](../system-design/icon-system.md)。

### 8.2.1 多格作物的三种渲染模式（2026-09-14 三模式收齐）

生长槽下部件之上，上部件有三种互斥形态，各自独立注册、逐级尝试：

```
① 原版半部件（DOUBLE_BLOCK_HALF，瓶子草）：同方块 HALF=UPPER、同 age 同步生长
   ——getUpperCompanion 自动识别（属性存在即命中），注册表无需条目
② 注册式独立上部件（FD 水稻 = RiceBlock + 上方 RicePaniclesBlock 两个方块）：
   UPPER_CROPS 注册表（下部件注册名 → 上部件注册名），成熟才出现
③ 柱状多段（同方块 + IntegerProperty 分段，如 KC 水稻 location 0=下/1=中/2=上）：
   COLUMN_PARTS 注册表（作物注册名 → 各段属性覆盖列表），各段从生长槽正上方
   逐格向上渲染（findSlotAbove 循环推进，空槽才画、到顶/被占自动截断）
```

模式③的 `getColumnParts`：`stateForAge` 基础上应用各段属性覆盖（属性名 → 值
字符串经 `Property.getValue` 解析），age 与下部件自动同步；属性缺失的段静默跳过。
注册名延迟解析（软依赖安全，模组不在时 BuiltInRegistries 解析为 AIR/空表）。
三种模式各管各的注册集合，同一作物不会同时命中两个（属性结构互斥）。

### 8.3 旧粒子图标路线（已替换，历史背景）

最初的实现走「blockstate → 模型 → 粒子图标查表 + blit」：粒子图标 = 模型 particle
纹理，能拿到单张贴图但**拿不到模型的几何与染色管线**——胡萝卜 8age→4stage 映射、
下界疣共用模型、茎灰度纹理发白（需手动 age 染色）、茎无中间态（stage 模型是同纹理
UV 裁剪）等问题逐个暴露，最终被 renderSingleBlock 整体取代。历史价值：*
「纹理推导不可靠，必须查 blockstate 权威映射」* 的教训仍适用于任何贴图级方案。

---

## 9. 特殊作物类型

### 9.1 作物类型对比（V1 范围）

| 作物类型 | 渲染 | 产出来源 | 产出物 | 模式 |
|---------|------|---------|-------|------|
| 标准作物（小麦/胡萝卜/马铃薯/甜菜/火把花） | 各阶段粒子图标 | 自身战利品表 | 作物+种子 | 标准→重置 |
| 茎作物（西瓜/南瓜） | 未成熟藤蔓（灰度+age 染色），成熟**果实图标** | **果实方块物品本身**（stem.fruit 直取，不滚表） | 西瓜块/南瓜 | 浆果模式 |
| 下界疣 | 3 阶段（AGE_3） | 自身战利品表（age=3 条件） | 下界疣×2~4 | 标准→重置 |
| 甜浆果 | 3 阶段（AGE_3） | 自身战利品表（age=3 条件） | 甜浆果×2~3 | 浆果模式 |
| FD 稻米 | 生长穗各阶段 + 成熟上槽满穗模型（UPPER_CROPS） | **上部抽穗 `rice_panicles` 战利品表**（HARVEST_BLOCKS 覆盖） | 稻穗×1 | 标准→重置 |
| FD 番茄 | 各阶段粒子图标（age 0~4） | **结果藤 `tomatoes` 战利品表**（HARVEST_BLOCKS 覆盖） | 番茄×1~2 + 种子（留种扣）+ 5% 烂番茄 | 标准→重置 |
| 火把花 | 苗（age 0~1）→ 成熟显**花方块模型**（displayStateFor 越界回退） | **花方块 `torchflower` 战利品表**（HARVEST_BLOCKS 覆盖） | 火把花×1 | 标准→重置 |
| 瓶子草 | 单格苗（age 0~2，top 模型原版就是空几何）→ 双格（age 3~4，DOUBLE_BLOCK_HALF 上部件） | 自身战利品表（age=4 + half=lower 条件） | 瓶子草植株×1 | 标准→重置 |
| KC 水稻（kaleidoscope_cookery） | **三格柱**（location 属性分段，COLUMN_PARTS 各段同 age 独立模型；age 0~3 两格、4~7 三格——空纹理段自动不可见） | 自身战利品表（location=down 段滚表，我方默认态恰好命中） | 稻穗×N（模组表） | 标准→重置 |

茎作物 `StemBlock.fruit` 是 `private final ResourceKey<Block>`，AT 读取
（`accesstransformer.cfg`：`public net.minecraft.world.level.block.StemBlock fruit`），
`BuiltInRegistries.BLOCK.get(fruitKey)` 解析。产出来源/渲染/成熟图标三处共用
`CropClassifier.getStemFruit`。

### 9.2 未来扩展（V1 不实现）

- ~~分阶段作物（FD 番茄）~~ / ~~两格高作物（FD 水稻）~~：**均已落地**（2026-09-14）——
  番茄走「自身 age 0~4 直接生长 + 冻结时滚结果藤战利品表」（原地转化语义被
  收获形态覆盖吸收，无需组件过渡字段）；水稻走 `UPPER_CROPS` 上部件注册
  （成熟才渲染抽穗，纯视觉不参与 tick）。全程零 FD 类引用（注册名延迟解析，
  软依赖安全），无需 `compat/farmersdelight/` 隔离；任意模组作物扩展靠
  `registerUpperCrop` / `registerHarvestBlock` 两个注册点
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
│   ├── LivingFarmlandFunction.java     # tick 功能（生长/产出状态机 + tooltip）
│   ├── FarmlandBonemealInteraction.java # 槽位交互：骨粉 → 活耕地 = 施肥（活漏斗自动施肥）
│   └── LivingFarmlandPlacement.java    # 放置回世界：模拟玩家右键种一次（§3.5）
├── living/transfer/
│   ├── SlotInteraction.java            # 槽位交互接口（matches + interact + consumeAmount）
│   └── SlotInteractions.java           # 槽位交互注册表 + 分发器（三处传输分支唯一入口）
├── living/interaction/
│   ├── TillToFarmlandHandler.java       # 活锄头 → 活耕地（6 锄头规则）
│   ├── PlantCropHandler.java            # 种植（通配 + handler 校验）
│   └── BonemealHandler.java             # 骨粉催熟（精确触发器）
├── living/mixin/
│   └── BlockItemMixin.java              # 放置活耕地后自动种下自带作物（consume 前注入，§3.5）
├── client/render/
│   ├── CropTextureResolver.java         # blockstate→模型→粒子图标解析
│   └── LivingFarmlandSeedDecorator.java # 耕地槽种子图标叠加（IItemDecorator，快捷栏也生效）
└── client/mixin/AbstractContainerScreenMixin.java  # render @TAIL 生长槽大图渲染
```

### 10.2 注册点

| 注册 | 位置 | 内容 |
|------|------|------|
| 组件 | `LivingItemManager` | `FARMLAND_PLANT` DeferredRegister + `get/setFarmlandPlant` |
| 功能 | `LivingItem.commonSetup` | `registerFunction(new LivingFarmlandFunction())` |
| 交互规则 | 同上 | DIRT×6 锄头（till）、FARMLAND+null（plant）、FARMLAND+BONE_MEAL（bonemeal） |
| 交互处理器 | 同上 | `registerHandler` × 3（actionId 对应） |
| 槽位交互（活漏斗自动施肥） | `SlotInteractions` 静态块 | `register(new FarmlandBonemealInteraction())`——内置条目，三处传输分支经分发器自动生效 |
| Mixin（放置回世界） | `living_item.mixins.json` | `BlockItemMixin` —— `@Mixin(BlockItem.class)`，注入 `place` 的 `consume` **之前**（§11.16） |
| 装饰器（耕地槽种子图标） | `LivingIconRegistry` FARMLAND spec | `.decorator(new LivingFarmlandSeedDecorator())`——`RegisterItemDecorationsEvent` 注册；走装饰器路径故快捷栏/容器/创造物品栏共用 |
| AT | `accesstransformer.cfg` | `public net.minecraft.world.level.block.StemBlock fruit` |
| 图标 | `LivingIconRegistry.registerAll` | FARMLAND → `item/farmland_living`（引用原版 `block/farmland` 顶面纹理，零新 PNG） |
| lang | `assets/living_item/lang/` | `tooltip.livingitem.farmland.*` ×4（中英） |

### 10.3 测试

| 测试 | 覆盖 |
|------|------|
| `InteractionRegistryTest`（7 项） | 两趟优先级：精确不被通配遮蔽（骨粉→bonemeal）、非精确回退通配（种子→plant_crop）、精确要求活触发器、无匹配 null、非活目标不匹配、triggerFilter 数量门槛（种子不足不拦截原版交换）、triggerFilter 非种子不匹配 |
| `CropClassifierTest`（10 项） | 火把花 maxAge=2 超属性值域 + displayStateFor 成熟回退花/未成熟照常/超熟钳制、普通作物 displayStateFor 直通无回归、瓶子草准入（Block 白名单）+ maxAge=4 + 成熟态 HALF=LOWER、HARVEST_BLOCKS 原版条目解析、柱状段未注册空表/属性覆盖应用（HALF 翻转+同 age）/属性缺失静默跳过 |
| `LivingFarmlandFunctionTest`（5 项） | 输出合并回归（2026-09-14 终审）：部分合并等待不丢物品、整份合并推进、空槽全放、堆叠上限钳制、边界含等号 |
| `FertilizeTransferTest`（6 项） | 自动施肥语义（2026-09-15）：未成熟 age+1 扣粉、已冻结零空转不消耗、非活耕地/未种植/非骨粉拒绝；`tryFertilize` 本身不做货物准入（政策在漏斗侧） |
| `CrossContainerTransferFertilizeTest`（15 项） | 槽位交互分发（2026-09-15）：拉取方向入口（邻居骨粉 → 本容器耕地 age+1 且扣邻居 1 粉、空槽跳过命中后面的粉堆、邻居无粉不误伤、冻结耕地零空转不烧粉、非活/未种植拒绝）+ 已知货物入口（生效真扣 1 粉、冻结不真扣、非骨粉/空目标槽不接管）+ `canInteract` 廉价筛选谓词 + 推送方向（普通骨粉照常插入、活骨粉不入空槽、活骨粉不与同种普通堆合并、活熔炉全拒）+ **活骨粉三个入口全被拒** |
| `SlotInteractionCargoGateTest`（5 项） | 货物准入唯一定义点 `isEligibleCargo`（2026-09-15 用户定案）：真值表（普通物品/活箱子/活末影箱合法，活骨粉/活熔炉非法）、工厂同口径返回 null、**活骨粉 + 活耕地不施肥不扣货**、普通骨粉端到端无回归 |
| `LivingFarmlandPlacementTest`（5 项） | 放置回世界（2026-09-16）：`isPlantable` 真值表（非活耕地/活但未种植/已种植/非耕地活物品）、**落点在耕地之上且一律 age 0**（mock Level 捕获 `setBlock`）、客户端不生效、**软逻辑红线——种植抛异常被吞掉不外泄**、**端到端 Mixin 接线**（`BlockItem.place` 全流程 → 作物被种下，同时钉住「必须注入在 `consume` 之前」） |
| `LivingFarmlandSeedDecoratorTest`（4 项） | 种子图标装饰器守卫（2026-09-16）：普通非活耕地 false / 活耕地未种植 false / 活耕地已种植 true / 非耕地活物品 false。装饰器按 `Item` 注册 ⇒ 会对所有 FARMLAND 调用，守卫必须完整；画面本身交游戏实测 |

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
本坑连同发白问题已由 renderSingleBlock 路线从根上消除（模型自带几何与染色管线）。
GUI blit 精灵图不会自动应用模型 tintindex 染色 → 藤蔓白色、无状态变化。修复：
`getCropTint`（BlockColors 4 参重载，null level/pos 对 age 驱动染色器安全）+ 生长槽
大图按染色分流 `blit(..., sprite, r, g, b, a)`。**后续已被 renderSingleBlock 路线
整体替代**（模型自带几何与染色，无需手动 tint/裁剪）。

### 11.8.1 茎藤蔓无中间态（第六轮实测：stage 模型同纹理 + UV 裁剪；已由 renderSingleBlock 整体替代）

粒子图标法对茎失效——8 个 stage 模型同引用一张 `pumpkin_stem.png`/`melon_stem.png`，
粒子图标恒为同一张全图，只有 age 染色在变（「颜色变化正常、没有中间态」）。
原版生长形态 = 纹理顶部条带逐级展开（stageN UV 高度 `2+2N`，锚定方块底部）。
修复：`renderStemGrowthStage` 手写 POSITION_TEX_COLOR 顶点（getU0/getV0 浮点 UV
裁剪 + 染色，立即模式），非茎作物不受影响。**后续已被 renderSingleBlock 路线整体
替代**（模型几何天然带逐阶段形态）。

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

### 11.11 FD 稻米/番茄成熟后零产出（第七轮实测：下部战利品表只掉种子 + 留种扣空）

稻米/番茄种植后正常生长、正常渲染成熟形态，但成熟后永远没有产出。根因是两段
逻辑叠加成死循环：① `tryFreezeDrops` 非茎路径滚**下部作物方块自身**的战利品表，
而 FD 下部表只掉种子本身（`rice.json` → 稻谷×1、`budding_tomatoes.json` →
番茄种子×1——FD 语义里「挖掉作物」的保底就是种子，真实收获在抽穗/结果藤上）；
② 留种逻辑对与 cropSeed 相同的产出项数量 -1 → 唯一一叠被扣成 0 → 「留种后全空」
按回退点重置 → 重长到成熟 → 再扣空 → **无限循环零产出**。

修复（收获形态解析 + 守卫，2026-09-14）：`CropClassifier.HARVEST_BLOCKS` 显式
注册「作物方块 → 收获形态方块」（稻米 → `rice_panicles`、番茄 → `tomatoes`），
冻结时滚覆盖方块的成熟态战利品表——空工具下稻穗×1 / 番茄×1~2 + 种子（留种恰扣
种子、番茄保留），正是 FD 徒手收获的原版语义；键值注册名延迟解析，FD 不在时
回退自身行为不变。守卫加固：「留种后全空」从重置改为**原样输出未扣减的产出**
（耕地组件持久化、产完照常重长，重置空转毫无收益）。

教训：**「滚作物自身战利品表」隐含了「自身表 = 收获形态」的假设**——多阶段作物
（种植入口方块 ≠ 成熟形态方块）不成立。种植入口、成熟渲染、产出来源是三个独立
关注点，需各自解析（本例分别由 `getBlockFromSeed` / `getUpperCompanion` /
`HARVEST_BLOCKS` 承担）。

### 11.12 火把花零产出 + 瓶子草无法种植（第八轮实测：原版也有同型怪癖）

**火把花零产出**：原版怪癖——`TorchflowerCropBlock` 的 AGE 属性是 `AGE_1`
（值域 0~1），但 `getMaxAge()`=2，成熟态 `getStateForAge(2)` 直接返回
`Blocks.TORCHFLOWER` 花方块（作物方块本身没有 age=2 状态）；且
torchflower_crop 战利品表**任何 age 都只掉种子×1**（与 FD 下部表完全同型：
真实收获在花方块表）。我方双断点：`matureStateFor` 值域 [0,1] 不含 2 →
null → 冻结失败零产出（每概率 tick warn 一次）；渲染 `stateForAge(,2)` null →
成熟后生长槽空白。修复：HARVEST_BLOCKS 补 `torchflower_crop → torchflower`
（滚花方块表，每轮产火把花×1）；渲染新增 `displayStateFor`——age 越界且已
成熟时回退收获形态方块默认态（通用兜底，不限于火把花）。

**瓶子草无法种植**：`PitcherCropBlock` extends `DoublePlantBlock` 而非
CropBlock，白名单四 instanceof 全不中 + `pitcher_pod` 不在 c:seeds 标签 →
三层准入全漏。修复：白名单补 `instanceof PitcherCropBlock` 精确类（不能用
DoublePlantBlock 兜底——玫瑰/牡丹/向日葵全是它的子类）。其余路径零改动：
maxAge 走 age 属性兜底=4、成熟态 HALF 默认 LOWER 恰好命中战利品表全部
half=lower 产出池条件、渲染走既有 DOUBLE_BLOCK_HALF 上部件模式（age 0~2 的
top 模型原版就是空几何，天然不显示；3~4 正常双格）。

教训：**「种植入口 = CropBlock 子类」的假设对双格作物不成立**（原版瓶子草自己
就不走 CropBlock）；白名单按「能种在耕地上的作物」语义收口，逐类显式列出，
不用父类兜底。

### 11.13 KC 水稻三格只渲染一格（第九轮实测：第三种多格形态）

KC（KaleidoscopeCookery）水稻成熟形态是**三格柱**，活耕地里第一格渲染正常、
生长产出全正常，但中/上两格空白。根因：KC 水稻既非原版半部件（DOUBLE_BLOCK_HALF
只有 LOWER/UPPER 两值）也非 FD 的两个独立方块，而是<b>同一方块 + IntegerProperty
分段</b>——`rice_crop` 带 `age`(0~7) + `location`(0=下/1=中/2=上) + `waterlogged`，
种下时三格同时放置（`setPlacedBy`）、各段 age 同步生长（`updateShape` 从下方邻居
拷贝）、每段每 age 各有独立 cross 模型（blockstate 24 个变体全有真实模型）。
我方 `stateForAge` 用默认态 location=DOWN 只出第一格，`getUpperCompanion` 两种
既有模式都不命中 → 中/上段不渲染。

修复：`CropClassifier.COLUMN_PARTS` 注册表（作物注册名 → 各段属性覆盖列表，
`kaleidoscope_cookery:rice_crop → [{location:1}, {location:2}]`）+ 渲染循环从
生长槽正上方逐格向上画各段（空槽才画、到顶/被占自动截断）。产出侧零改动——KC
的 `getDrops` 只在 location=DOWN 段滚战利品表，我方 `matureStateFor` 默认态恰好
就是 DOWN（「产出正常」的实测印证）。

教训：**多格作物的实现方式至少有三种**（半部件翻转 / 独立上部件方块 / 同方块
属性分段），互相之间不可推导——渲染要按「属性结构 + 注册表」双轨解析，缺一种
注册模式就漏一类模组作物。

### 11.14 终审四连修（2026-09-14：三路并行审计——服务端/渲染/文档）

九轮实测全过后的全面代码审查（三路 Explore 并行审计）收尾：

- **部分合并丢物品（唯一确认功能性 bug）**：生长槽同种半堆空间不足整份时旧实现
  塞 `min(outputCount, space)` 即推进索引，差额物品静默消失——玩家从生长槽拿走
  一半产物再等下一轮即触发。修复：合并仅当 `space ≥ outputCount`，放不下整份
  本 tick 等待。教训：**「塞得下多少塞多少」在推进游标语义下 = 丢弃差额**；
  游标推进必须与「整份送达」绑定。
- **生长槽双重认领（渲染）**：同列两块耕地隔一空行 → 两块都把中间空槽当生长槽，
  同槽双画重叠。修复：per-frame 认领表先到先得。
- **多格模式无互斥（渲染防御）**：上部件与柱状段双注册时同槽双画（当前条目不
  触发，API 公开即踩）。修复：命中其一即跳过其余。
- **渲染异常隔离**：第三方作物的 BlockColors 处理器拿 null level/pos 可能 NPE，
  单作物失败只跳过该槽 + warn 一次，不崩整屏渲染循环。
- 留种改**总量 -1**（多池掉同种种子只扣一份）；**存量 maxAge 自愈**（注册表修正
  后旧组件重冻结 + age 钳制，maxAge=7 时代耕地不需铲掉重种）；注释漂移/import
  重复/死代码清洁。

设计取舍（明示不修）：「留种后全空原样输出」= 种子-only 作物每周期净产 1 种子
（防零产出循环的有意行为）；超大堆叠耕地（count>64 抽屉）因等量种子消耗不可种；
瓶子草 age 0~2 GUI 双格渲染（javadoc 已标常驻取舍）；渲染缓存（当前规模无压力，
需求驱动再议）。

### 11.15 跨容器施肥「推送生效、拉取失效」（2026-09-15 实测：方向不对称的隐藏假设）

**现象**：活漏斗跨容器施肥只在一个方向生效——输出槽（target）是跨容器活耕地、
输入槽（source）是同容器骨粉 → 正常施肥；反过来输入槽是跨容器骨粉、输出槽是同容器
活耕地 → **完全不施肥**（把活耕地移走，骨粉又能正常跨容器传输到该槽，证明拉取链路
本身没坏）。

**根因**：施肥分支只内嵌在推送方向（`tryPushToNeighbor`）与容器内管道
（`TransferPipeline`），拉取方向 `pullFromNeighbor` 走的是通用路径
（`tryPullFromNeighbor` + `SlotAccessor.transfer`）——而
`SlotAccessorFactory.create` 开头就把**非箱类活物品**挡掉（`return null`），
`target == null` 直接 `return false`。活耕地正是「活物品 + 非存储容器」，
通用拉取对它**必然失败**：骨粉送不进去，施肥分支也就永远不被考虑。

**为什么推送方向能「自然涌现」而拉取方向不能**：推送方向的邻居槽位由
`createForNeighbor` 包装（**不查活物品**），所以内嵌的施肥 if 有被执行的
机会；拉取方向的目标槽位走 `create`（**查活物品 → null**），连循环都进不去。
同一句「跨容器自动获得施肥能力」在两个方向上不成立——**对称性假设未经代码验证**。

**修复（两步）**：

1. **止血**：`pullFromNeighbor` 增加前置分支（目标槽 = 活耕地 → 遍历邻居找骨粉 →
   `simulateExtract(1)` 试粉 → 生效才 `extract(1)` 扣粉），与推送/容器内两处同语义；
   生效时 `containerCtx.syncSlotToClients(targetSlot, farmland)` 主动推组件
   （耕地在本容器，且其自身 tick 因 equals 守卫不会写回）。
2. **结构性修复（同日）**：方程收编为注册式槽位交互 `FarmlandBonemealInteraction`
   （`SlotInteractions` 内置条目），三处传输分支只调分发器
   （`tryInteract` / `tryInteractFromNeighbor`）——**新增同类交互 = 1 个实现类 + 1 行注册，
   零传输代码改动**，漏调用点的机会从「每个新交互一次」降为「每个调用点一次」。
   扩展方式与协议见 [living-hopper-tech.md §6.2.1](living-hopper-tech.md)。

**守卫**：`CrossContainerTransferFertilizeTest`（15 项）——拉取方向 age+1 扣邻居粉、
空槽跳过命中后面的粉堆、邻居无粉不误伤耕地、冻结耕地零空转不烧粉、非活/未种植拒绝；
已知货物入口（容器内/推送）生效真扣 1 粉、冻结不真扣、非骨粉/空目标槽不接管；
推送方向隔离红线；活骨粉三个入口全被拒。
`SlotInteractionCargoGateTest`（5 项）专测货物准入（活骨粉不施肥 + 真值表）。

**后续修订（同日，用户定案）**：上一版曾把「活骨粉也放行」统一到四个方向，方向错了——
施肥属**传输语义**，必须受漏斗自身的货物规则（活物品隔离）约束。现已改为
**漏斗只认普通骨粉**，规则收在唯一定义点 `SlotInteractions.isEligibleCargo`
（传输层与交互层共用，调用点顺序变更也绕不过），详见 §7.1 与
[living-hopper-tech.md §6.2.1](living-hopper-tech.md)。

**教训**：把「路径 A 实现了 X」写成「X 已覆盖所有路径」是两类不同的断言。
涉及**方向 / 分支枚举**的能力（推送⇄拉取、容器内⇄跨容器、上⇄下）必须逐条
点名验证——本次 `pushToNeighbor` 与 `pullFromNeighbor` 是两份独立实现，
「一处内嵌两路径共用」只对 `pushToNeighbor` + `transferBetweenNeighbors` 成立。
结构性规则与全量覆盖矩阵见 [living-hopper-tech.md §6.2.2](living-hopper-tech.md)：
**特殊槽位识别只在 `containerCtx` 一侧生效，邻居侧没有访问器工厂**——面向邻居侧
特殊槽位的交互已由注册表统一接管（不再逐处硬编码）。

### 11.16 放置回世界的三个坑（2026-09-16，实现期拦截）

**(1) 注入 `@At("RETURN")` 会「只在单块放置时静默失效」**
`BlockItem.place` 在成功分支**末尾**才 `itemstack.consume(1, player)`；而
`ItemStack.isEmpty()` 为真时 `getComponents()` 返回 `DataComponentMap.EMPTY`
（`ItemStack.java:229`）⇒ 单块放置（count 1→0）后组件读不到、`isPlantable` 恒 false；
**堆叠放置（count>1）却正常**。这种「只在单块时坏」的 bug 极难排查。
→ 必须锚在 `consume` **之前**（该点全 `BlockItem` 唯一，无需 `ordinal`）。

**(2) 别用 `level.isClientSide` 字段——它是 `public final`，Mockito 读不到**
`Level.isClientSide` 是**字段**（`Level.java:113`），方法 `isClientSide()` 只是读它。
字段不可被 mock ⇒ 单测里客户端守卫永远不触发，会出现「测试红但代码看着对」。
→ 统一用**方法式** `level.isClientSide()`（语义等价，同 `EnderRouteManager`）。

**(3) 别自己 `setBlock`——会绕过模组校验**
`CropClassifier.getBlockFromSeed` + `stateForAge` + `level.setBlock` 能「种上」，但模组在
`canSurvive` / 覆写的 `useOn` 里的校验（如「水稻只能在水下种」）**一律不执行**，种出非法状态。
→ 改用 `ItemStack.useOn(new UseOnContext(...))` 模拟玩家右键（§3.5）。副作用是连
`CropClassifier`、`canBeReplaced` 检查、`is(Blocks.FARMLAND)` 检查都不需要了，代码更短。

**附带发现**：Create 也 Mixin 了 `BlockItem`
（`BlockItem.handler$zzk000$create$fixDeployerPlacement`），与本模组共存无冲突。

**教训**：**「能跑通」不等于「走对了路径」**。当一个能力的语义是「模拟玩家的某个动作」时，
优先找原版对应的**入口方法**（这里是 `useOn`）而不是复刻它的**结果**（`setBlock`）——
后者会静默绕过所有下游扩展点，兼容性问题要等玩家装了模组才暴露。

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

**放置回世界（§3.5，2026-09-16 新增）**：

- [ ] 已种植活耕地**放置到世界** → 耕地上方出现**幼苗**（age 0，与物品成熟度无关）
- [ ] **单块放置（count=1）与堆叠放置（count>1）行为一致**（§11.16 坑 1 的回归点）
- [ ] 未种植活耕地 / 普通非活耕地放置 → 与原版**完全一致**（零行为变化）
- [ ] 上方被占 / 落点非耕地 → 种不下，**无异常、无崩溃、无日志刷屏**
- [ ] **模组兼容**：对种植位置有要求的模组作物（如水下水稻）→ 不满足时**种不下**
      （证明未绕过模组逻辑，§11.16 坑 3）
- [ ] 带未排空产出的活耕地放置 → 产出掉出，不凭空消失

### 12.2 生长与产出

- [ ] 小麦（湿润：邻格放活水桶）：~10 分钟成熟
- [ ] 小麦（干燥）：~30 分钟成熟（或用骨粉验证概率外的路径）
- [ ] 成熟后**不立即出产物**——等概率 tick 成功（湿润 ~90s 期望）才冻结战利品表，
      随后**连续几 tick 内全部进生长槽**（不再 10 秒一项）
- [ ] 种子产出留种 -1：小麦种子 2 → 1（变相自动补种）
- [ ] 标准模式产完 → 回到生长 0/7 重新长
- [ ] 西瓜（茎作物）：成熟显示西瓜图标、**产出西瓜块**（果块直取，非西瓜片）
- [ ] **南瓜/西瓜藤蔓随 age 变色 + 逐级长高**（renderSingleBlock 渲染 stem_growthN
      真实模型几何：藤从 2px 长到全图 + 绿→橙黄染色）
- [ ] **FD 稻米成熟后生长槽上方出现满穗稻穗模型**（上部件渲染；上槽被占自动让位）
- [ ] **FD 稻米成熟后产出稻穗×1/轮**（HARVEST_BLOCKS 滚抽穗表；稻穗≠种子，留种不扣）
- [ ] **FD 番茄成熟后产出番茄×1~2（偶带烂番茄），种子产出被留种扣除**
      （滚结果藤 tomatoes 表）
- [ ] **火把花可种植**，成熟后生长槽显花方块模型，每轮产火把花×1（花方块表覆盖）
- [ ] **瓶子草（pitcher_pod）可种植**，age 3~4 双格渲染（上槽显上半模型），
      成熟产瓶子草植株×1；age 0~2 单格苗
- [ ] **KC 水稻（kaleidoscope_cookery:rice）三格柱渲染**：各段独立模型与田间形态
      一致（age 0~3 两格、4~7 三格），上方槽被占自动让位截断；产出走 down 段表
      不受影响（第九轮修复验证）
- [ ] **生长槽留半堆产物不再丢物品**（终审修复）：从生长槽拿走一半后，下轮产出
      等待空间足够整份才合并——槽内数量不无故变化
- [ ] 瓶子草（pitcher）上下两段同步生长（DOUBLE_BLOCK_HALF 通用模式）
- [ ] **南瓜/西瓜成熟均产出果块本身**（茎作物直取 fruit.asItem——西瓜战利品表的
      瓜块分支带精准采集 match_tool 条件，空工具永远只出西瓜片，见 §6.1）
- [ ] 甜浆果：产出后回 stage1 贴图，重新长到 stage3 再产（原版采摘回退语义）
- [ ] **顶行耕地：正常生长**，成熟后 tooltip 已成熟但不产出，
      搬到下方槽位后开始产出
- [ ] 生长槽放杂物 → 生长照常、成熟后产物等位不进槽（BLOCKED 重置已删）
- [ ] **骨粉右键已成熟耕地 → 立即触发产出**；重复右键已冻结的不消耗骨粉
- [ ] **自动施肥**：容器里活漏斗方向指向活耕地、源槽放普通骨粉堆 → 耕地 age
      每 8t +1 直至成熟并开始产出，骨粉逐个消耗；已冻结成熟耕地不烧骨粉；
      跨容器（漏斗容器与耕地容器相邻）**两个方向都要试**：
      ① 输入槽在同容器（骨粉）、输出槽在邻居（活耕地）→ 施肥；
      ② 输入槽在邻居（骨粉）、输出槽在同容器（活耕地）→ 施肥（§11.15 修复点）
- [ ] **活骨粉不给漏斗施肥**（准入规则）：源槽放活骨粉 → 四个方向都不施肥、
      不入槽、不与普通骨粉堆合并（活物品不作货物）；活骨粉改到 GUI 右键用 → 正常催熟
- [ ] **跨容器拉取施肥后 GUI 即时刷新**：方向 ② 下耕地 tooltip 的 age 立刻 +1
      （分支内主动 syncSlotToClients，不等 200t 冷却）
- [ ] **湿润图标**：邻格放活水桶 → 耕地图标变深（farmland_moist）；收走变浅

### 12.3 渲染

- [ ] **耕地槽显示全尺寸种子图标**（类型一眼区分，随物品搬运跟随；堆叠数数字被
      覆盖为已知取舍）
- [ ] **种子图标在快捷栏也显示**（2026-09-16 修复点）：把已种植活耕地放进快捷栏
      （**不打开背包**）→ 应显种子图标；未种植活耕地 / 普通耕地 → 不显
- [ ] **容器 GUI 无回归**：打开箱子时耕地槽仍显图标，计数数字角与图标边缘正常
- [ ] 创造物品栏 / 副手槽的活耕地同样显图标；既有装饰器（活漏斗箭头等）不受影响
- [ ] **上方空槽显示作物当前阶段贴图**（「土下苗上」）
- [ ] **湿润图标切换 + 传播**：邻格放活水桶 → 直接相邻耕地变深（3 级），
      相邻耕地的相邻耕地也变深（2→1 级传播）；收走水桶全部恢复浅色
- [ ] 传播只沿活耕地：中间隔普通物品/空槽则不传播
- [ ] 生长槽被产出物占据时显示真实物品图标（作物贴图让位）
- [ ] **同列两块耕地中间隔一个空行**：中间空槽只被其中一块认领为生长槽，
      不出现两份作物重叠双画（终审修复验证）
- [ ] 胡萝卜/下界疣各阶段贴图正确（查表路径的映射验证）
- [ ] 大箱子中渲染正常（CompoundContainer 同步链路）
