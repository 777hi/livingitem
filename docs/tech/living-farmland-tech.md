# Living Farmland (活耕地) 技术文档

> **文档版本**: v1.0
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
| 3 | **双槽渲染**：耕地槽叠加作物小图 + 上方空生长槽绘制作物大图 | 玩家实测预期「上方槽位显示作物」（原始设计）；耕地侧小图保留「跟物品走」的可移植性（顶行/搬运途中也有视觉反馈） |
| 4 | **顶行正常生长、产出挂起**：`growthSlot<0` 不算 BLOCKED | 旧实现把顶行误判为永久 BLOCKED（age 恒 0 不生长）与规则②矛盾；顶行是合法布局，成熟后搬到有生长槽的位置自动开始产出 |
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

### 2.2 状态机

```
┌──────────┐  种植种子  ┌──────────┐  生长槽被放入物品  ┌──────────┐
│  EMPTY    │─────────→│  GROWING │──────────────────→│  BLOCKED │
│ 未种植     │          │ age 0→max │                  │ age = 0 │
└──────────┘          └─────┬─────┘←─ 生长槽变空 ──────└──────────┘
                            │ age 达到 maxAge            （回到 GROWING）
                            ↓
                          ┌─────────────────────┐
                          │       MATURE        │←── 浆果模式产完归零
                          │  冻结 pendingDrops   │   （保留 MATURE 重新评估）
                          │  outputIndex = 0    │
                          │  每冷却周期产出当前项  │
                          └──────┬──────┬───────┘
                                 │      │
              ┌──────────────────┘      └───────────────┐
              ↓                                         ↓
     产出放入生长槽成功                          无法放入
     （空槽/同种合并）                        （不同种占据/已满）
              │                                         │
     ┌────────┴────────┐                    留在 MATURE，outputIndex 不变
     ↓                  ↓                    下次冷却周期重试
  还有更多项       没有更多项了
     │                  │
     ↓                  ↓
  outputIndex++    outputIndex = -1，清空 pendingDrops
  (下个冷却周期)   ┌────────┴────────┐
                标准模式            浆果模式
                age = 0            age = maxAge
                回到 GROWING       下轮冷却周期重新评估战利品表

  顶行耕地（growthSlot<0）：生长不受影响；MATURE 后冻结照常、产出挂起，
  搬到有生长槽的位置后下个冷却周期自动开始产出。
```

**核心规则表**：

| # | 规则 | 说明 |
|---|------|------|
| ① | 生长与槽状态解耦 | 生长（冷却计时+age 推进）不依赖生长槽；生长槽只影响产出与 BLOCKED |
| ② | BLOCKED 仅当「生长槽存在且被物品占用」 | 玩家/管道向生长槽放入物品 → `age = 0`（防把生长槽当储存格）；顶行/边缘不算 |
| ③ | BLOCKED 变空 → 重新生长 | 从 `age = 0` 继续 GROWING |
| ④ | 成熟 → 逐项产出 | 首次成熟冻结完整战利品表到 pendingDrops，每冷却周期产出一项 |
| ⑤ | 数量 × 堆叠数 | 每项产出 = `min(战利品数量 × 耕地堆叠数, 该物品堆叠上限)` |
| ⑥ | 成熟期产出占槽不触发② | 产出是系统行为，age 不变 |
| ⑦ | 产出成功同 tick 推进 | 放入/合并成功 → `outputIndex++`，玩家无需取走动作参与 |
| ⑧ | 浆果模式不重置生长 | 产完保留 `age = maxAge`，归零待产出，下轮重新评估战利品表 |

---

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

### 3.2 种植（通配自交互 + handler 内校验）

```java
InteractionRegistry.register(new InteractionEntry(Items.FARMLAND, null, 1, "plant_crop"));
```

`trigger=null` 是「任意光标可触发」的通配条目，`PlantCropHandler` 内统一校验
（生存/创造同一套——创造光标经 `GuiInteractionPacket.carriedTag` 已在服务端恢复）：
光标非空 + IS_LIVING + `CropClassifier.isSeedPlantableOnFarmland`。通过后
`withCropSeed(carried.getItem(), maxAge)` 写入类型标记，**不消耗种子**。

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

### 4.3 浆果模式判定

```java
isBerryModeCrop(block) = block instanceof SweetBerryBushBlock || block instanceof StemBlock
```

成熟后持续产出不重置生长。**不存组件**——作物方块的固有属性，tick 时从 cropSeed
推断即可（省一个可被写错的冗余字段）。

---

## 5. 生长逻辑

### 5.1 生长节拍：世界轴时间戳

```java
GROWTH_INTERVAL_TICKS = 200;   // ≈ 10 秒一次生长/产出尝试

// tick 内（稳态零写入零同步）：
long now = level.getGameTime();
if (now - plant.lastGrowthAttemptTick() < GROWTH_INTERVAL_TICKS) return;
plant = plant.withLastGrowthAttemptTick(now);
```

世界轴与红电相位时钟同源决策：跨会话连续，不受容器本地 tickCounter 重进重置影响。
旧设计「每 tick growthCooldown++」会让一片 27 格耕地每 tick 27 次组件写入+网络包。

**首拍即时**：种植时戳为 0，`now - 0 ≥ 200` 恒真 → 种下后第一次冷却判定立即发生
（然后按 200t 节奏），玩家反馈更快。

### 5.2 湿润检测与生长概率

```
湿润 = 左/右/下三个方向（resolveNeighbor E_LEFT/E_RIGHT/E_DOWN）任一邻居槽位
      在 tick.fluidData.getFlows() 中有 FlowEntry（活水流，容器级数据先于功能 tick 算好）

f = 湿润 ? 3.0 : 1.0
判定 = level.random.nextInt((int)(25.0F / f) + 1) == 0
     = 湿润 nextInt(9)==0 → 1/9 ≈ 11.1%
     = 干燥 nextInt(26)==0 → 1/26 ≈ 3.8%
```

| 条件 | f | 每周期生长概率 | 7 级约需 | 约需现实时间 |
|------|---|--------------|---------|------------|
| 湿润 | 3.0 | 1/9 ≈ 11.1% | ~63 周期 | ~10.5 分钟 |
| 干燥 | 1.0 | 1/26 ≈ 3.8% | ~182 周期 | ~30.3 分钟 |

「上」方向不算湿润来源（容器网格中上是生长槽方向）；无光照/密度惩罚（容器无光照概念；
同格堆叠互不影响）。

### 5.3 顶行耕地语义（2026-09-13 定案）

`resolveNeighbor(E_UP)` 对顶行返回 -1。**生长不受影响**（BLOCKED 仅当生长槽存在且
被占用）；MATURE 后战利品表照常冻结、产出在 `tryOutputLoot` 的 `growthSlot < 0`
守卫处**挂起**——搬到有生长槽的位置后下个冷却周期自动开始产出。

---

## 6. 产出机制（round-robin）

### 6.1 战利品表冻结（`LivingFarmlandFunction.tryFreezeDrops`）

公开静态（骨粉即时冻结共用），首次成熟时评估一次：

```java
Block dropBlock = cropBlock instanceof StemBlock stem
    ? CropClassifier.getStemFruit(stem)     // 茎作物 → 果实方块（stem.fruit AT 读取）
    : cropBlock;
BlockState matureState = matureStateFor(dropBlock);   // setValue(AGE, maxAge)
List<ItemStack> drops = Block.getDrops(matureState, level, BlockPos.ZERO, null);
plant = plant.withOutput(0, drops);
```

**必须用公开静态 `Block.getDrops(state, level, pos, be)`**：内部自动补齐
`BLOCK_STATE`/`ORIGIN`/`TOOL=EMPTY` 三必填参数并走 `LootContextParamSets.BLOCK`
验证。手搓 `LootParams` 缺 `BLOCK_STATE` 不仅抛 `Missing required parameters`，
且 wheat 战利品表靠 `block_state_property(age=7)` 条件区分掉麦还是掉种子——缺参
条件全 false → 空产出（设计稿原始错误，见踩坑 §11.2）。

**为什么冻结进组件**：战利品表每次评估重新掷随机数（西瓜 3~7 片、种子 fortune 加成），
不冻结则 round-robin 的「每周期取下一项」取不稳——第 0 项和第 1 项来自两次掷骰。
冻结后产出期间列表稳定；浆果模式产完归零、下轮成熟重新评估（每轮一次掷骰）。

**AGE 属性读取**：`CropBlock.getAgeProperty()` 是 protected——统一从
`defaultBlockState().getProperties()` 按名字 `"age"` 取 `IntegerProperty`，
一个方法覆盖全部作物类型（含模组自定义 AGE 属性作物）。

### 6.2 逐项产出（每冷却周期一项）

```java
// 每冷却周期：
ItemStack drop = pendingDrops.get(outputIndex);
int count = Math.min(farmlandCount * drop.getCount(), drop.getMaxStackSize());
growthSlot 空 → 放入；同种未满 → 合并（min 到上限）；同种已满/不同种 → 本轮跳过保留索引
放入/合并成功 → outputIndex++（同 tick，不等玩家取走）
outputIndex ≥ pendingDrops.size() → 归零重置：
  标准模式 age = 0（回 GROWING）
  浆果模式 age = maxAge（下轮重新评估战利品表）
```

等待机制：产出被阻塞时保留 outputIndex，下次冷却周期重试；每 tick 重试纯属浪费
（战利品检查 + 槽位检查空转）。

---

## 7. 骨粉催熟

`BonemealHandler`（交互线程内执行，非容器 tick）：

1. 验证：目标 = 已种植未成熟活耕地；光标 = 活骨粉（生存验证 + shrink 1，创造免费）
2. 推进：`age = min(age + Mth.nextInt(random, 2, 5), maxAge)`
3. **催到成熟 → 立即 `tryFreezeDrops` 冻结战利品表**——不等下个冷却周期
   （否则玩家催熟后空转 ≤10 秒才见产出）
4. 写组件 + broadcastChanges

**数学勘误**：`Mth.nextInt(r, 2, 5)` 是**双闭区间**（+2~5，原版
`StemBlock.performBonemeal` 同公式）；`RandomSource.nextInt(2, 5)` 上界排除只有
+2~4。旧实现用错为后者。

BLOCKED 态可催熟（BLOCKED 只是生长暂停不是死亡），产出仍走等待机制。

---

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

### 8.2 双槽渲染策略

```
耕地槽小图（保留）：耕地图标上叠 16×16 作物贴图，z=100
  → 跟物品走：顶行、搬运途中、背包乱放都有视觉反馈

生长槽大图（新增）：按【坐标匹配】找正上方一格（slot.y - 18）的同容器菜单槽，
  该槽为空时绘制作物当前阶段贴图
  → 「空槽即画」自动覆盖全部状态：
     生长中空槽显苗 / BLOCKED 被占自动隐
     成熟产出后被真实物品覆盖 / 取走后显成熟形态
  → 同容器约束（slot.container 相等）防跨容器错位
     （箱子顶行耕地不会把苗画到玩家背包槽上）
```

按坐标匹配而非 `resolveNeighbor` 索引算术：箱子 9 列、背包 9 列、模组容器列数不同、
创造模式 SlotWrapper——坐标 `y - 18` 是「正上方一格」的布局通用真理，天然适应
全部容器布局。

---

## 9. 特殊作物类型

### 9.1 作物类型对比（V1 范围）

| 作物类型 | 渲染 | 产出来源 | 产出物 | 模式 |
|---------|------|---------|-------|------|
| 标准作物（小麦/胡萝卜/马铃薯/甜菜/火把花） | 各阶段粒子图标 | 自身战利品表 | 作物+种子 | 标准→重置 |
| 茎作物（西瓜/南瓜） | 未成熟藤蔓，成熟**果实图标** | **果实战利品表**（stem.fruit） | 西瓜片/南瓜 | 浆果模式 |
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

---

## 12. 验证清单

### 12.1 交互链路

- [ ] 活锄头右键活泥土 → 泥土变耕地、锄头留手上只掉耐久（6 种锄头逐一）
- [ ] 活种子右键活耕地 → tooltip 显示作物 + 生长 0/N，种子不消耗不交换
- [ ] **活骨粉右键活耕地 → tooltip 生长阶段跳升 +2~5**（本次修复验证点）
- [ ] 骨粉催到成熟 → tooltip 显示已成熟，且**立即**开始产出（不等 10 秒）
- [ ] 非活种子/非活骨粉右键 → 无交互（原版行为）
- [ ] 已种植耕地再种 → 无反应；取消活化再活化 → 种植数据清空

### 12.2 生长与产出

- [ ] 小麦（湿润：邻格放活水桶）：~10 分钟成熟
- [ ] 小麦（干燥）：~30 分钟成熟（或用骨粉验证概率外的路径）
- [ ] 成熟后每 10 秒产出一项：先小麦×堆叠数，后种子×2×堆叠数（受堆叠上限 min）
- [ ] 标准模式产完 → 回到生长 0/7 重新长
- [ ] 西瓜（茎作物）：成熟显示西瓜图标、产出来源是**果实**战利品表（3~7 西瓜片）、
      浆果模式产完继续重新产出
- [ ] 甜浆果（手动映射 + 浆果模式）：可持续产出
- [ ] **顶行耕地：正常生长**（本次修复验证点），成熟后 tooltip 已成熟但不产出，
      搬到下方槽位后开始产出
- [ ] 生长槽放杂物 → BLOCKED（age 归 0），取走后重新生长

### 12.3 渲染

- [ ] **上方空槽显示作物当前阶段贴图**（本次修复验证点，「土下苗上」）
- [ ] 耕地图标上叠加作物小图，随物品搬运跟随
- [ ] 生长槽被产出物占据时显示真实物品图标（作物贴图让位）
- [ ] BLOCKED 态（槽被杂物占）生长槽不画作物贴图
- [ ] 胡萝卜/下界疣各阶段贴图正确（查表路径的映射验证）
- [ ] 大箱子中渲染正常（CompoundContainer 同步链路）
