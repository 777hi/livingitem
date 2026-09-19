# Living Tool (活工具) 技术文档

> **文档版本**: v0.1
> **最后更新**: 2026-09-19
> **适用版本**: Minecraft 1.21.1 + NeoForge 21.1.249
> **状态**: **挖掘记忆回放 + 交互记忆回放 + 清除记忆已实现**（挖掘已通过实测）；掉落物形态 / 实体攻击 / 可视化待实现
> **设计探讨记录**: `docs/idea.md` §3.12（L 组，问题池与定案索引，本文档只写结论）

## 目录

1. [架构概览](#1-架构概览)
2. [核心概念](#2-核心概念)
3. [数据模型](#3-数据模型)
4. [记忆录制](#4-记忆录制)
5. [记忆回放](#5-记忆回放)
6. [三宿主](#6-三宿主)
7. [设计定案索引](#7-设计定案索引)
8. [实现文件与注册点](#8-实现文件与注册点)
9. [踩坑记录](#9-踩坑记录)
10. [验证清单](#10-验证清单)
11. [待实现](#11-待实现)

---

## 1. 架构概览

### 1.1 什么是活工具？

活工具（活镐子 / 活斧子 / 活铲子 / 活锄头）活化后放在**容器 / 背包 / 掉落物**里，
会**记住玩家的操作行为**，之后自动重放这个操作。

```
【录制】玩家手持活斧子 → 左键挖方块 / 右键去皮
            ↓ 记住「这条射线」+（蹲下时）方块类型
【回放】活斧子待在容器里 → 以自己所在位置为起点，沿同一条射线重做这个操作
```

### 1.2 关键设计取向

| 取向 | 说明 |
|---|---|
| **记忆的是「操作行为」而非「玩法语义」** | 记的是**左键 / 右键**这两个动作，不是"挖掘"/"去皮"。回放走完整玩家操作链路，模组工具的自定义效果也能触发（`L36`） |
| **服务端管逻辑，客户端只管表现** | 客户端不参与任何判定，只是"照着画"（`K30`） |
| **两端独立算同一条射线** | 记忆同步到客户端后，客户端本地 `clip` 即可得出同样结果，**服务端无需同步"命中了什么"** |
| **不替玩家兜底** | 射线落空、位置不对 —— 用可视化暴露给玩家，玩家重录即可（`L15` 设计原则） |

### 1.3 回放链路

```
容器 tick（现有管线，无需新增扫描通道）
   ↓
LivingToolFunction.tick(entries, ...)
   ↓ 每个活工具
LivingToolReplay.replayDig(...)
   ├─ 沿记忆射线【逐格扫描】→ 跳过黑名单 → 命中目标（或 null 就停）
   ├─ 推进进度（原版重算式）
   └─ progress ≥ 1 → FakePlayer.gameMode.destroyBlock()
                      └─ 内部自动：BreakEvent → canHarvestBlock → mineBlock → 掉落
```

---

## 2. 核心概念

### 2.1 记忆（RayMemory）

一条记忆 = **一根射线** + 可选的类型约束。

```java
public record RayMemory(Vec3 offset, @Nullable Block block) { }
```

- **`offset`**：录制时「玩家眼睛 → 射线命中点（方块表面）」的**完整偏移向量**
- **`block`**：仅**蹲下**时记录；`null` = 不限类型

两条记忆互相独立，各自只保留**最新一条**（新覆盖旧）：

- **挖掘记忆**（`dig`）= 左键操作
- **交互记忆**（`use`）= 右键操作

### 2.2 纯净射线（`L15`）

`offset` **不做任何加工**：

- ❌ 不归一化
- ❌ 不加命中余量
- ❌ 不改用方块中心

理由：玩家按 **F3** 能看到真实射线，记忆必须与之**逐位一致**，否则玩家会困惑。

> 实现提示：`BreakEvent` 只给 `BlockPos`、拿不到表面命中点，
> 故录制时补一次 `level.clip()` 取 `getLocation()`（见 §4.2）。
> `BlockToolModificationEvent` 则可直接用 `getContext().getClickLocation()`。

### 2.3 宿主（Host）

活工具当前所在的载体，决定**射线起点**：

| 宿主 | 射线起点 |
|---|---|
| 方块容器 | **容器方块中心**（`L14=a`） |
| 玩家背包 | **玩家眼睛**（`L3=a`，与录制端一致） |
| 掉落物 | 实体位置（`L14=d`） |

记忆与宿主解耦 —— 存的是"这条线本身"，宿主换了就换个起点重放。

### 2.4 黑名单（Blacklist）

沿射线扫描时**直接无视**的方块集合。目前 = 宿主自身方块（大箱子两格都算）。

它是个通用接口 `Set<BlockPos>`，将来可扩：玩家配置保护方块、模组标记方块等（`L41`）。

⚠️ **例外**：若整条射线都在宿主体内（极短记忆），**允许挖宿主**（`L42`，见 §5.3）。

---

## 3. 数据模型

三个 `DataComponent`，均注册在 `LivingItemManager`：

| 组件 | 类型 | 持久化 | 网络同步 | 用途 |
|---|---|---|---|---|
| `LIVING_TOOL_MEMORY` | `LivingToolMemory` | ✅ | ✅ | 挖掘记忆 + 交互记忆 |
| `LIVING_TOOL_PROGRESS` | `LivingToolProgress` | ✅ | ✅ | 当前在挖的目标 + 起始 tick |
| `LIVING_TOOL_OWNER` | `UUID` | ✅ | ❌ | 主人 UUID（服务端专用） |

### 3.1 LivingToolMemory

```java
public record LivingToolMemory(@Nullable RayMemory dig, @Nullable RayMemory use) {
    public record RayMemory(Vec3 offset, @Nullable Block block) {
        public static RayMemory record(Vec3 eyePosition, Vec3 hitLocation, @Nullable Block block);
        public Vec3 endpointFrom(Vec3 origin);      // 回放：宿主 + offset
        public boolean matches(Block target);       // null block = 不限制
    }
}
```

`block` 记 `Block` 而非 `BlockState` 的好处：**去皮 / 耕地后 Block 改变 → 匹配不上 → 天然停止**（正好实现 `L8`）。

### 3.2 LivingToolProgress

```java
public record LivingToolProgress(BlockPos target, long startTick) { }
```

组件缺失 = 没在挖。**只在开始挖时写一次**，之后每 tick 重算，不写组件（写入量极小）。

### 3.3 LivingToolOwner（UUID）

`FakePlayer` 用它伪装成真实玩家，以通过领地 / 保护插件（`L25`，见 §5.4）。
玩家手动活化时由 `LivingTagPacket` 写入。

---

## 4. 记忆录制

**两条通道，都无需 mixin。**

### 4.1 判定：什么是活工具

```java
public static boolean isLivingTool(ItemStack stack) {
    if (stack.isEmpty() || !LivingItemManager.isLivingItem(stack)) return false;
    return stack.canPerformAction(ItemAbilities.PICKAXE_DIG)
        || stack.canPerformAction(ItemAbilities.AXE_DIG)
        || stack.canPerformAction(ItemAbilities.SHOVEL_DIG)
        || stack.canPerformAction(ItemAbilities.HOE_DIG);
}
```

沿用项目语义判定惯例（`Tillables#isHoeLike`）：**不枚举物品清单**，模组工具自动兼容。

### 4.2 通道一：挖掘记忆 → `BlockEvent.BreakEvent`

| 项 | 说明 |
|---|---|
| 触发时机 | 方块**真正被破坏**时（一次），不是按住左键的每一 tick |
| 工具来源 | **只认主手**（原版 `destroyBlock` 也用 `getMainHandItem()`） |
| 命中点 | 事件只给 `pos`，故补一次 `level.clip()` 取**表面命中点** |
| 类型记录 | `player.isShiftKeyDown()` 时记 `Block` |

### 4.3 通道二：交互记忆 → `BlockEvent.BlockToolModificationEvent`

覆盖 `AXE_STRIP`（去皮）/ `HOE_TILL`（耕地）/ `SHOVEL_FLATTEN`（铺路）等全部 `ItemAbility`，
模组自定义的也会走这里 —— **自动兼容**。

| 项 | 说明 |
|---|---|
| ⚠️ **`isSimulated()` 必须过滤** | 原版会先跑一趟 simulate，不过滤会**重复录制** |
| 命中点 | `event.getContext().getClickLocation()`（直接给精确位置） |
| 工具来源 | `event.getHeldItemStack()` |

### 4.4 ⚠️ 必须排除假玩家（`L39`）

```java
private static boolean isRecordablePlayer(@Nullable Player player) {
    return player instanceof ServerPlayer && !player.isFakePlayer();
}
```

**`FakePlayer` 是 `ServerPlayer` 的子类** —— `instanceof ServerPlayer` 挡不住它（详见 §9.1）。

---

## 5. 记忆回放

### 5.1 找目标：逐格扫描 + 黑名单（`L40`）

```java
private static BlockPos scanForTarget(Vec3 origin, Vec3 end, Set<BlockPos> blacklist, ServerLevel level) {
    Vec3 dir = end.subtract(origin).normalize();
    BlockPos previous = null;
    for (double t = 0.0; t <= length; t += 0.1) {
        BlockPos current = BlockPos.containing(origin.add(dir.scale(t)));
        if (current.equals(previous)) continue;
        previous = current;
        if (blacklist.contains(current)) continue;   // 黑名单：直接无视
        if (!level.isEmptyBlock(current)) return current;
    }
    return null;   // L7：没方块就停
}
```

**为什么不用 `level.clip()`**：clip 不支持"跳过某些方块"，而容器形态下起点在方块内部，
clip 必然先命中宿主自己，于是要"移出宿主"等补丁 —— 且补丁容易失效（§9.2）。
改成逐格扫描后天然支持黑名单、不受起点位置影响，且**仍然沿真实路径，不会隔空挖**。

> 代价：0.1 格步进，精度略低于 clip（对"挖哪一格"完全够用）。

### 5.2 推进进度：原版**重算式**

```java
float perTick = state.getDestroyProgress(fake, level, target);
float total   = perTick * (now - progress.startTick() + 1);
```

源码依据 `ServerPlayerGameMode#incrementDestroyProgress`：

```java
int i = this.gameTicks - startTick;
float f = state.getDestroyProgress(this.player, this.player.level(), pos) * (float)(i + 1);
```

⚠️ **时间源必须用 `level.getGameTime()`（世界轴）**，不能用 `gameMode.gameTicks` ——
后者靠 `gameMode.tick()` 自增，而 FakePlayer 的 `tick()` 是空实现（`L27`）。

**对比 Create 的累加式**（`DeployerHandler:260-265` 用 `progress += perTick * 16`）：
×16 是因为部署器**有冷却**、不是每 tick 动作。我们每 tick 推进，不需要补偿。

### 5.3 极短记忆：允许挖宿主（`L42`）

若扫描没找到外部目标，**且射线终点落在宿主方块内**，则挖宿主。

📌 **真实可触发**：玩家挖**头顶**方块时，眼睛到方块仅约 **0.4 格**，
回放时终点仍在宿主方块内 → 活工具会把容器挖掉。玩家主动为之，视为玩法而非异常。

### 5.4 为什么用 FakePlayer（`L24`）

破坏速度 `BlockState#getDestroyProgress(Player, ...)` **依赖 Player**。
把活工具放进 FakePlayer 主手后，**原版自己算**：

- ✅ 效率附魔（`getDigSpeed` 里 `f += i²+1`）
- ✅ 材质门槛（`canHarvestBlock` → 决定掉不掉；活铁镐挖黑曜石不掉）
- ✅ 时运 / 精准（loot table 用 stack 判）
- ✅ 耐久（`mineBlock`）
- ❌ 经验修补（需捡经验球，FakePlayer 不捡）

**过领地的关键技巧**（Create / Mekanism 同款）：

```java
// LivingToolFakePlayer —— 覆写 getId() 与 getUUID() 返回主人 UUID
private static class OwnerGameProfile extends GameProfile {
    public UUID getId() { return owner == null ? super.getId() : owner; }
}
```

**实例管理**：按 **(维度, 主人 UUID)** 缓存共享（`L26`）。
FakePlayer 在这是**无状态执行器** —— 进度在组件里、tick 单线程、每次只需三项配置：

```java
fake.setPos(origin.x, origin.y, origin.z);
fake.setOnGround(true);          // L28：不设会被判"离地"→ 速度 /5
fake.setItemInHand(InteractionHand.MAIN_HAND, held);
```

### 5.5 模拟完整操作（`L36`）

直接调 `gameMode.destroyBlock()` 会**跳过**挥击前置，故首次命中时补上：

```java
if (freshStart) {
    CommonHooks.onLeftClickBlock(fake, target, face, Action.START_DESTROY_BLOCK);
    state.attack(level, target, fake);
    EnchantmentHelper.onHitBlock(level, held, fake, fake, EquipmentSlot.MAIN_HAND, ...);
}
```

⚠️ 只在**首次命中**触发一次 —— 每 tick 触发会让音效 / 粒子 / 模组回调疯狂调用（`L38`）。

### 5.6 工具副本与写回

破坏会扣耐久，甚至损坏为空（`F3`）。为了让容器触发**脏槽位同步**：

1. 给 FakePlayer 一份 `tool.copy()`
2. 破坏后由 `LivingToolFunction` 比对，变化才 `context.setItem()` + `syncSlotToClients()`

---

## 6. 三宿主

| 宿主 | 射线起点 | 客户端怎么知道 | 现状 |
|---|---|---|---|
| **方块容器** | 容器方块中心 | ❗ 需 S2C 包（`K2`，不开 GUI 客户端不知道内容） | ✅ 已实现 |
| **玩家背包** | 玩家眼睛 | ✅ 本人已知 | ✅ 已实现 |
| **掉落物** | 实体位置 | ✅ **免费**（`IS_LIVING` 已 `networkSynchronized`） | ⬜ 需新增 tick 通道（`L-f`） |

方块容器与玩家背包走**现有** `ContainerLivingItemHandler` 管线，**无需新增扫描通道**。
掉落物形态需另开通道 —— 现有 `processLevelContainers` 只遍历 `getBlockEntities()`。

---

## 7. 设计定案索引

完整问题池与讨论过程见 `docs/idea.md` §3.12。核心定案：

| 编号 | 定案 |
|---|---|
| `L1/L2/L15` | 存**完整偏移向量**（不归一化、不加余量、不改中心），忠于原版射线 |
| `L3/L14` | 射线起点：玩家眼睛 / 容器方块中心 / 掉落物位置 |
| `L4` | 蹲下记 **`Block`**（不是 `BlockState`）—— 去皮后天然停止 |
| `L5` | 工具 `maxStackSize=1`，记忆整堆叠共享，不做按堆叠倍增 |
| `L6` | `FakePlayer` + `gameMode.destroyBlock()` |
| `L7` | 有方块就挖，没方块就停 |
| `L8` | 交互记忆同 `L7`；类型不匹配即停 |
| `L13/L17/L18` | 左键/右键 **`MISS`** 清对应记忆；取消活化清全部组件 |
| `L21` | 进度存 DataComponent `{targetPos, startTick}`（重算式，写入量小） |
| `L23` | 目标变了 → 进度重置 |
| `L24-L26` | FakePlayer；主人 UUID；按（维度,主人）缓存共享 |
| `L25` | 有主人用主人 UUID，无主人（未来自动活化）用自定义 UUID |
| `L27` | `gameMode.tick()` 是空的 → **必须自己推进进度**；时间源用世界轴 |
| `L28` | 每次使用前 `setOnGround(true)`，否则速度 `/5` |
| `L36` | 模拟**完整操作**，补上挥击前置事件 |
| `L37` | 左键命中实体 → 攻击（为将来活剑铺路） |
| `L39` | 录制必须排除假玩家 |
| `L40` | 逐格扫描 + 黑名单（不用 clip） |
| `L42` | 极短记忆允许挖宿主 |
| `F3` | 损坏即消失（最原版） |
| `K30` | 悬浮渲染**不用实体**，纯客户端 `RenderLevelStageEvent` |

---

## 8. 实现文件与注册点

### 8.1 文件清单

```
src/main/java/com/qiqi/li/living/domain/tools/
├── LivingToolMemory.java            记忆组件（含 RayMemory）
├── LivingToolProgress.java          进度组件
├── LivingToolRecorder.java          录制器（两条事件通道）
├── LivingToolFakePlayer.java        假玩家（主人 UUID 技巧）
├── LivingToolFakePlayerCache.java   按（维度,主人）缓存
├── LivingToolReplay.java            回放核心（扫描 / 进度 / 破坏）
└── LivingToolFunction.java          挂进容器 tick 管线

src/main/java/com/qiqi/li/client/render/
└── LivingToolRayRenderer.java       记忆射线可视化（L19/L20，纯客户端）
```

### 8.2 注册点

| 位置 | 内容 |
|---|---|
| `LivingItemManager` | 3 个 DataComponent + `getToolMemory/setToolMemory`、`getToolProgress/setToolProgress`、`getToolOwner/setToolOwner`、`setLiving(stack, living, owner)` 重载 |
| `LivingItemManager.clearLivingData` | 移除 `LIVING_TOOL_MEMORY` / `LIVING_TOOL_PROGRESS` / `LIVING_TOOL_OWNER` |
| `LivingItem` 构造函数 | `NeoForge.EVENT_BUS.register(LivingToolRecorder.class)` |
| `LivingItem.commonSetup` | `LivingItemManager.registerFunction(new LivingToolFunction())` |
| `LivingItem.onServerStopped` | `LivingToolFakePlayerCache.clear()` |
| `LivingTagPacket.handle` | `setLiving(carriedItem, newLiving, player.getUUID())` 记录主人 |
| `LivingItem.onRegisterPayloadHandler` | `playToServer(ToolMemoryClearPacket...)` |
| `LivingItemInputHandler`（客户端） | `onLeftClickEmpty` → 发 `ToolMemoryClearPacket` |
| `ServerPacketHandler.handleToolMemoryClear` | 清记忆 + `broadcastChanges()` |
| `network/ToolMemoryClearPacket.java` | 新增 C2S 包（`LeftClickEmpty` 单端，必须客户端发起） |
| `LivingItemClient.onRenderLevelStage`（客户端） | `LivingToolRayRenderer.render(event)` —— 记忆射线可视化（`L19`，仅 F3+B） |

---

## 9. 踩坑记录

### 9.1 活工具自己录自己（2026-09-19 实测）

**症状**：回放一次后方向越飘越偏，最终变成**垂直向下一直挖**。

**根因**：`FakePlayer` **是 `ServerPlayer` 的子类**，录制器的 `instanceof ServerPlayer`
检查挡不住它 → 活工具把「自己挖的这一次」录成了新记忆，覆盖掉玩家录的。

**修法**：

```java
player instanceof ServerPlayer && !player.isFakePlayer()
```

### 9.2 clip 起点在方块内 → 挖掉容器（2026-09-19 实测）

**症状**：活工具放进箱子后把箱子自己挖掉。

**根因**：容器形态射线起点在容器方块内部，`clip` 第一个命中的就是容器。

**失败的修法**："命中后小步前进" —— 起点在方块内时**命中点也在方块内**，
0.05 步长 × 4 次 = 只走 0.2 格，**没走出容器那一格**（半格就 0.5）。

**最终修法**：改为**沿射线逐格扫描 + 黑名单**（§5.1），顺带删掉
`clip` / `clipSkipping` / `exitHost` 三个补丁方法。

### 9.3 效率附魔不生效 —— FakePlayer 不 tick（`L46`，2026-09-19 实测）

**症状**：挖掘回放里**效率附魔完全不起作用**，但**材质门槛、精准采集正常**。

这个"一半好一半坏"的现象本身就是线索 —— 说明**走 `getMainHandItem()` 的路径都对，
只有走属性表的失效了**。

**根因**：原版挖掘速度的取值链有一段隐藏依赖：

```java
// Player#getDigSpeed
float f = this.inventory.getDestroySpeed(state);              // ① 背包选中槽 → 工具基础速度
if (f > 1.0F) {
    f += (float) this.getAttributeValue(Attributes.MINING_EFFICIENCY);   // ② 效率附魔走这里
}
```

效率附魔在 1.21 注册为一个**属性效果**（`Enchantments.java` 已核对）：

```java
.withEffect(EnchantmentEffectComponents.ATTRIBUTES,
    new EnchantmentAttributeEffect(
        ResourceLocation.withDefaultNamespace("enchantment.efficiency"),
        Attributes.MINING_EFFICIENCY,
        new LevelBasedValue.LevelsSquared(1.0F),      // 值 = 等级²（效率 III = +9）
        AttributeModifier.Operation.ADD_VALUE))
```

而 `MINING_EFFICIENCY` 属性由 `LivingEntity#collectEquipmentChanges()` 刷新，
它只在 **`LivingEntity#tick()`**（源码 line 2482 `this.detectEquipmentUpdates()`）里被调用。
**`FakePlayer#tick()` 是空实现** → 属性永远不更新 → 加成恒为 0。

**为什么其它都正常**：

| 能力 | 取值路径 | 结果 |
|---|---|---|
| 材质门槛 | `hasCorrectToolForDrops` → `getMainHandItem()` | ✅ 正常 |
| 精准采集 / 时运 | `EnchantmentHelper` 直接读 ItemStack 的 `enchantments` 组件 | ✅ 正常 |
| **效率附魔** | `getAttributeValue(MINING_EFFICIENCY)` ← **属性表** | ❌ **失效** |

**修法**：原版 `detectEquipmentUpdates()` 是 `private`，故在 `LivingToolFakePlayer#equipTool()`
里**复刻它内部的摘除 / 装配两段逻辑**（仅限主手槽，用公开的 `ItemStack#forEachModifier`）：

```java
public void equipTool(ItemStack stack) {
    removeEnchantmentModifiers(this.lastEquipped);      // 摘掉上一把留下的（实例跨工具共享）
    this.setItemInHand(InteractionHand.MAIN_HAND, stack);
    addEnchantmentModifiers(stack);                     // 加上这把附魔提供的
    this.lastEquipped = stack;
}
```

⚠️ **必须成对摘除**：`LivingToolFakePlayer` 实例按「维度 + 主人 UUID」缓存并**跨工具共享**
（`L26`），只加不摘会让多把工具的修饰符**叠加**。

### 9.4 其他易错点

| 坑 | 说明 |
|---|---|
| `isSimulated()` | 不过滤会重复录制交互记忆 |
| `setOnGround(true)` | 漏了挖掘速度变 1/5，且不易察觉 |
| 时间源 | 用 `gameMode.gameTicks` 会因 tick 空实现而永远是 0 |
| 写回容器 | 不写回则客户端看到的还是旧耐久 |
| 装备属性 | 物品附魔给的属性修饰符**不会**自动生效（见 §9.3） |

### 9.5 为什么 `FakePlayer#tick()` 是空的 —— 以及我们为它付的代价

**两层空实现，都不是偶然**：

| 层 | 位置 | 作用 |
|---|---|---|
| ① | `FakePlayer.FakePlayerNetHandler#tick()` | 服务端驱动玩家 tick 的入口是 `ServerGamePacketListenerImpl#tick()` → `this.player.doTick()`。它空了 → **FakePlayer 根本不在服务器的 tick 循环里** |
| ② | `FakePlayer#tick()` | 第二道保险：即使有人手工调 `tick()`，也是空转 |

**为什么这么设计** —— NeoForge javadoc 原文：
*"A basic fake server player implementation that can be used to **simulate player actions**."*

> FakePlayer 是 **「`Player` 参数」的载体**，不是世界里的实体。
> 它的用途是被当作参数传给原版方法（`getDestroyProgress` / `useOn` / loot context / 权限判定），
> **用完即弃**。

**真 tick 了，对它是负担甚至有害**：

| tick 里的动作 | 后果 |
|---|---|
| `ServerPlayer#doTick` 遍历背包对 `ComplexItem` 发 `getUpdatePacket` | 空转（`send()` 是空实现），白跑 |
| `containerMenu.broadcastChanges()` | 同样空转，且它的菜单是构造出来的假菜单 |
| `LivingEntity#collectEquipmentChanges()` → `getChunkSource().broadcast(...)` | ⚠️ **会给附近玩家广播"假玩家"的装备变化** |
| `aiStep()` 物理 / AI | 没有客户端输入 → 可能乱动 |
| 血量 / 饥饿 / 经验 / 成就 / 药水效果 | 全是白跑 |

**⭐ 与我们架构的冲突**：上述设计前提是「**用完即弃**」。
而 `L26` 让 FakePlayer **按「维度 + 主人 UUID」缓存、跨工具共享**，
变成了**长生命周期**对象 —— 于是"不 tick"从"无所谓"升级成**坑源**：
它身上那些本应由 tick 维护的状态，**永远不会刷新**。

**已经踩到的三个坑，其实是同一件事的三种表现**：

| 需要 tick 维护的状态 | 症状 | 编号 |
|---|---|---|
| 装备属性（`MINING_EFFICIENCY`） | 效率附魔失效 | `L46` |
| `onGround` | 挖掘慢 5 倍 | `L28` |
| 破坏进度（`gameMode.tick()`） | 进度不推进 | `L27` |

**结论**：**我们只要 FakePlayer 的「身份」，不要它的「行为」** ——
保持不 tick、按需手工补状态，是正确取舍（让它真 tick 会引入上表那些副作用）。

**实践准则**：今后凡是从 `Player` / `LivingEntity` 读到的、**依赖 tick 刷新**的状态，
都必须先确认"它会不会自己更新"；不会的就要在统一入口里手工补。
建议把「定位 + `setPos` + `setOnGround` + `equipTool`」收拢成**一个装配方法**，
避免以后再需要补第四项时漏掉某处 —— 散落的 setter 是这类 bug 的温床。

---

## 10. 验证清单

**挖掘回放**（2026-09-19 全部实测通过）：

- [x] 手持活工具挖方块 → 记忆录下
- [x] 放入箱子 → 自动重放，方向稳定
- [x] 不会挖掉宿主容器
- [x] 方块上有原版破坏裂纹
- [x] 通过控制录制距离可控制回放距离
- [x] 蹲下录制的类型约束生效（去皮/耕地后自动停止）
- [x] 材质门槛（活铁镐挖黑曜石**不掉**东西）
- [x] 精准采集 / 时运生效
- [x] 耐久消耗与损坏消失（`F3`）
- [x] 极短记忆（挖头顶）会挖掉宿主（`L42`）
- [ ] **效率附魔影响速度** —— 曾失效，`L46`（§9.3）修复后**待复测**

**交互回放 / 清除**（2026-09-19 实测通过）：

- [x] 手持活斧子右键原木去皮 → 记忆录下
- [x] 放入箱子 → 自动对同位置去皮
- [x] 去皮后 Block 改变 → 自动停止（不反复触发）
- [x] 左键空气 → 清挖掘记忆
- [x] 右键空气 → 清交互记忆
- [x] 取消活化 → 全部组件被清

**记忆「难得易忘」规则**（`L43` / `L44` / `L45`，2026-09-19 实测通过）：

- [x] 挖完一个方块 → 记忆形成
- [x] 挖完后**不松手**、顺势挖下一个一下 → 记忆**保留**（`L44` 保护期）
- [x] 挖完后隔一会儿再轻点别的方块 → 记忆被清除（`L43`）
- [x] 右键没交互成（如对着石头右键）→ 交互记忆被清除
- [x] 狭小空间内右键 → 交互记忆仍能清掉（不再被 `isRayMiss` 卡住）
- [x] 放入箱子后**立即**去皮，无 0.5 秒延迟（`L45` 去掉限流）

---

## 11. 待实现

| 项 | 说明 |
|---|---|
| **`L-f` 掉落物形态** | 需新增掉落物 tick 通道 + 单栈入口 |
| **`L37` 实体攻击** | 左键命中实体时攻击（为活剑铺路） |
| ~~`L19/L20` 射线可视化~~ | ✅ **已实现**（2026-09-19），见 §11.3 |
| **`K` 组悬浮渲染** | 待机位（宿主旁）↔ 工作位（射线目标点），御剑 `FormationGeometry` 可作排布参考 |

### 11.1 已实现：交互记忆回放（2026-09-19）

`LivingToolReplay#replayUse` —— 只调工具自身的 `ItemStack#useOn`（去皮 / 耕地 / 铺路等 `ItemAbility`），
**不调**方块的 `useItemOn`（避免活工具去开门、开箱子）。

**不节流**（`L45`）—— 曾按「每 10 tick 至多尝试一次」限流，但 `now % N == 0` 的**取模对齐**
会让<b>首次交互最多等 0.5 秒</b>，手感很迟钝。改为每 tick 尝试，靠**天然终止**限速：
原版 `ItemAbility`（去皮 / 耕地 / 铺路）都会**改变方块**，下一次扫描到的已不是同一个方块
（记住类型时 `matches` 直接失败，未记类型时 `useOn` 返回 PASS），于是自动停下。

### 11.1.1 记忆保护期（`L44`）—— 防止「惯性误触」清空

**问题**：玩家挖完一个方块后往往**来不及松开左键**，准星顺势落到下一个方块上。
服务端会收到 `LeftClickBlock(START)`，按 `L43` 的规则就把**刚录好的记忆清掉了**。

**解决**：写入记忆后进入 **1 秒（20 tick）保护期**，期间任何清除入口一律跳过。

| 写入记忆的时机 | 开启保护期 |
|---|---|
| `BlockEvent.BreakEvent`（挖完） | ✅ |
| `BlockEvent.BlockToolModificationEvent`（去皮 / 耕地） | ✅ |

| 清除入口 | 是否受保护期约束 |
|---|---|
| `LeftClickBlock(START)`（左键点方块） | ✅ |
| `RightClickItem`（右键没交互成） | ✅ |
| `ServerPacketHandler#handleToolMemoryClear`（左键空气） | ✅ |

实现：静态 `Map<UUID, Long> LAST_RECORD_TICK`（服务端内存态），
在 `PlayerLoggedOutEvent` / `ServerStoppedEvent` 清理（同 `LivingMapEventHandler` 的既有惯例）。

### 11.2 已实现：清除记忆（`L13` / `L43`）

⭐ **核心口径：「记忆难得易忘」—— 只有完整操作才留下记忆，半途而废就忘却。**

| 事件 | 结果 | 通道 |
|---|---|---|
| **左键开始挖方块**（`LeftClickBlock` action=`START`） | ❌ **清除**挖掘记忆 | 服务端事件 |
| **左键挖完**（`BlockEvent.BreakEvent`） | ✅ **记录**挖掘记忆 | 服务端事件 |
| **左键空气**（`LeftClickEmpty`） | ❌ **清除**挖掘记忆 | **需网络包**（见下） |
| **右键交互成功**（`BlockToolModificationEvent`） | ✅ **记录**交互记忆 | 服务端事件 |
| **右键没交互成**（`RightClickItem`） | ❌ **清除**交互记忆 | 服务端事件 |
| **取消活化** | 移除全部活工具组件 | `clearLivingData` |

**左右键规则完全对称**：完整操作 → 记录；不完整操作 → 清除。

于是「轻点一下方块」= 清除、「完整挖完」= 形成记忆，语义天然成立。

#### ⚠️ 两个已踩的坑

**① `RightClickItem` 不要加"是否命中空气"的过滤**

`RightClickItem` 在"命中方块但交互 PASS"时也会触发，而**这是正常情况** ——
在狭小空间里玩家根本无法对着空气右键。
若要求 `hit.getType()==MISS` 才清除，会导致交互记忆**永远清不掉**。
判据就是「这次右键没产生任何有效交互」。

**② 服务端的 `LeftClickBlock` 只在开始/结束时触发，不是每 tick**

`PlayerInteractEvent.LeftClickBlock.Action` 有三个值（源码注释已确认）：

| 值 | 含义 |
|---|---|
| `START` | 首次左键点在方块上 ← **用这个** |
| `STOP` | 方块被**挖完** |
| `ABORT` | 中途松手 / 换了目标（= 没挖完） |

#### 包链路（仅左键空气需要）

`LeftClickEmpty` **只在客户端触发** —— NeoForge 注释原文：
*"The server is not aware of when the client left clicks empty space, you will need to tell the server yourself."*

```
LivingItemInputHandler.onLeftClickEmpty（客户端，且确有记忆时才发）
  → ToolMemoryClearPacket
  → ServerPacketHandler.handleToolMemoryClear
  → 清记忆 + broadcastChanges()
```

---

### 11.3 已实现：记忆射线可视化（`L19` / `L20`，2026-09-19）

`client/render/LivingToolRayRenderer` —— 把"活工具打算挖 / 交互哪里"画给玩家看。

**为什么需要它**：记忆是一条「纯净射线」（§2.2）。若它擦着两格方块之间的缝隙过去，
回放时会 `MISS`。我们**不做浮点余量兜底**，而是把它**画出来** ——
玩家一眼看出偏了，重录一条即可。（设计原则：给玩家「信息与手段」，而不是在代码里兜底。）

| 项 | 结论 |
|---|---|
| 接入点 | `RenderLevelStageEvent` @ **`AFTER_ENTITIES`** —— 地形与实体深度已写入，线会被**正确遮挡** |
| 显示开关 | **`shouldRenderHitBoxes()`**（`L20=f`），即原版 **F3+B**；纯客户端、无需同步 |
| 颜色 | 挖掘记忆（左键）= 橙红；交互记忆（右键）= 青蓝 |
| 命中与否 | 本地 `clip`：**命中 → 画到命中点（不透明）**；**落空 → 画到终点（半透明）** ← 落空变暗正是"擦缝过去了"的信号 |
| 正在挖时 | **不画** —— 原版 `destroyBlockProgress()` 已自动显示破坏裂纹（零渲染代码） |
| 顶点写法 | `RenderType.lines()` + `addVertex(pose,…).setColor(…).setNormal(pose,…)`，与原版 `renderLineBox` 同款 |
| 坐标 | 事件给的 `PoseStack` 已是**相机相对**，只需 `translate(origin - cameraPos)` |
| 裁剪 | 距离 ≤ 32 格 + `Frustum#isVisible(AABB)` |
| flush | 画完**必须** `bufferSource.endBatch(RenderType.lines())`，否则不保证本帧画出来 |

**各宿主的射线起点**（必须与回放侧一致）：

| 宿主 | 起点 | v1 状态 |
|---|---|---|
| 玩家（背包 / 主手 / 副手） | 眼睛（`L3=a`） | ✅ |
| 掉落物 | 实体位置（`L14=d`） | ✅ |
| **方块容器** | 容器方块中心（`L14=a`） | ❌ **待 `K2` 的 S2C 包** —— 不开 GUI 时客户端拿不到箱子内容 |

> 背包形态不需单独处理主手/副手：`Inventory#getContainerSize()`（41）已含快捷栏与副手。

⭐ **顺手为 `K` 组铺好了地基**：本节建立的正是悬浮渲染要用的**全套底层能力** ——
`RenderLevelStageEvent` 接入、相机相对坐标、世界光照与深度处理、距离 / 视锥裁剪。
`K` 组此后只需在这个骨架上加「模型 + 动画」，不必再趟一遍渲染管线的坑。

---

## 附：参考源码位置

本地源码：`libs/src/neoforge-21.1.249-merged/`（解压自 `build/moddev/artifacts/neoforge-21.1.249-sources.jar`）

| 内容 | 路径 |
|---|---|
| 破坏进度机制 | `net/minecraft/server/level/ServerPlayerGameMode.java:98-133, 138-289` |
| 单 tick 进度公式 | `net/minecraft/world/level/block/state/BlockBehaviour.java:393` |
| 工具动作映射 | `net/neoforged/neoforge/common/extensions/IBlockExtension.java:778-824` |
| NeoForge FakePlayer | `net/neoforged/neoforge/common/util/FakePlayer.java` |
| Create 部署器（模拟左键） | `libs/src/Create-mc1.21.1-6.0.10/.../deployer/DeployerHandler.java:242-284, 375+` |
| Create 假玩家（主人 UUID） | `.../deployer/DeployerFakePlayer.java:165-204` |
| 御剑阵型排布（渲染参考） | `libs/src/YujianCraft-main/.../formation/FormationGeometry.java` |
