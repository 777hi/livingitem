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
| **方块容器** | 容器方块中心 | ✅ `K2` 的 S2C 包（2026-09-19 完成，见 §12） | ✅ 已实现 |
| **玩家背包** | 玩家眼睛 | ✅ 本人已知 | ✅ 已实现 |
| **掉落物** | **碰撞箱中心** | ✅ **免费**（`itemEntity.getItem()` 走实体同步） | ✅ **已实现**（2026-09-19，见 §11.4） |

**三者共用同一条回放逻辑**：都走 `ContainerLivingItemHandler#processContext`，
差异只在"射线起点"（`LivingToolFunction#resolveOrigin`）与"写回同步"。

| 宿主 | 扫描通道 | 上下文实现 | 写回同步 |
|---|---|---|---|
| 方块容器 | `processLevelContainers`（区块 → 方块实体） | `SimpleContainerContext` | `ClientboundContainerSetSlotPacket` |
| 玩家背包 | `processContainer`（在线玩家） | `SimpleContainerContext` | 同上（`inventoryMenu`） |
| 掉落物 | `processItemEntityContainers`（遍历实体） | `ItemEntityContainerContext` | `ClientboundSetEntityDataPacket` |
| —— | **客户端可见性** | —— | —— |
| 方块容器 | ❌ 瞎（不开 GUI 拿不到）→ **`K2` 的 `LivingToolHostPacket`** | `LivingToolHostSync` → `LivingToolHostClientCache` | 见 §12 |

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

src/main/java/com/qiqi/li/living/container/
└── ItemEntityContainerContext.java  掉落物包装成单栈容器（L-f）

src/main/java/com/qiqi/li/living/domain/tools/
├── LivingToolHostSync.java          服务端：收集活跃容器 + 定向广播（K2）
└── LivingToolHostClientCache.java   客户端缓存（K2）

src/main/java/com/qiqi/li/network/
└── LivingToolHostPacket.java        S2C：近处容器里的活工具清单（K2）
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
| 破坏裂纹 | 停止挖掘时**必须自己清**（原版靠 `gameMode.tick()` 收尾，见 §9.5 的 `L47`） |
| 实体坐标 | `Entity#position()` 是包围盒**底部**，不是中心；起点取错会让"只挖底面"（见 §9.6） |
| 扫描判据 | `isEmptyBlock()` 判 `isAir()`，**水不是空气**；非满高方块（半砖）会让起点落在方块内（见 §9.7） |

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

**已经踩到的四个坑，其实是同一件事的四种表现**：

| 需要 tick 维护的状态 | 症状 | 编号 |
|---|---|---|
| 装备属性（`MINING_EFFICIENCY`） | 效率附魔失效 | `L46` |
| `onGround` | 挖掘慢 5 倍 | `L28` |
| 破坏进度（`gameMode.tick()`） | 进度不推进 | `L27` |
| **破坏裂纹的清除** | 停止挖掘时无人清 → **裂纹永久残留** | `L47` |

#### ⚠️ 破坏裂纹为什么必须自己清（`L47`）

原版 `ServerPlayerGameMode#tick()` 里有一段收尾：

```java
} else if (this.isDestroyingBlock) {
    BlockState s = this.level.getBlockState(this.destroyPos);
    if (s.isAir()) {
        this.level.destroyBlockProgress(this.player.getId(), this.destroyPos, -1);  // ← 清裂纹
        ...
    }
}
```

**FakePlayer 不 tick ⇒ 这段永远不跑。**

裂纹的客户端渲染（`LevelRenderer`）有两点值得注意：

1. 它是 **10 张固定纹理**（`destroy_stage_*`），渲染时**不检查那个位置现在还是不是方块** ——
   所以方块没了，裂纹照样画。
2. 它按 **`breakerId`（实体 id）** 索引（`destroyingBlocks` 是 `Map<Integer, BlockDestructionProgress>`）。

⭐ **第 2 点让问题被放大**：`LivingToolFakePlayer` 是按「维度 + 主人」**共享**的，
同一个主人的所有活工具**共用一个 `breakerId`** —— 一旦某个工具停止挖掘却不清裂纹，
就**再也没人会覆盖它**，那片裂纹永久留在世界上。

**修法**：`replayDig` 的**每一条「停」的路径**都走 `stopDigging()`（清裂纹 + 复位进度）：

| 停止原因 | 位置 |
|---|---|
| 无挖掘记忆 | `ray == null` |
| 路径上没有可挖方块（`L7`） | `target == null` |
| 类型不匹配（`L8`，如去皮后） | `!ray.matches(...)` |
| 换成新目标（`L23`） | `freshStart && previous != null` |
| 被模组 / 保护插件取消 | `onLeftClickBlock(...).isCanceled()` |
| 挖不动（基岩 / 硬度 -1） | `perTick <= 0` |

另有一处**写回侧的坑**：`held` 是「写进度之后」才 `copy()` 的副本，
完成破坏时只清了 `tool` 的进度 ⇒ 写回槽位的那份**带着残留进度**，
下一 tick 会误判为「已经挖了很久」而**瞬间破坏**（无裂纹动画）。
故完成破坏时必须 `setToolProgress(held, null)` 一并清掉。

**结论**：**我们只要 FakePlayer 的「身份」，不要它的「行为」** ——
保持不 tick、按需手工补状态，是正确取舍（让它真 tick 会引入上表那些副作用）。

**实践准则**：今后凡是从 `Player` / `LivingEntity` 读到的、**依赖 tick 刷新**的状态，
都必须先确认"它会不会自己更新"；不会的就要在统一入口里手工补。
建议把「定位 + `setPos` + `setOnGround` + `equipTool`」收拢成**一个装配方法**，
避免以后再需要补第四项时漏掉某处 —— 散落的 setter 是这类 bug 的温床。

---

### 9.6 掉落物射线起点取错 → 只挖底面（`L14=d` 修订，2026-09-19 实测）

**症状**：活工具丢在地上后，射线永远指向**所站的那块方块**（底面）。

**根因**：`Entity#position()` 返回的是碰撞箱的**底部** ——
`EntityDimensions#makeBoundingBox` 构造的是 `new AABB(x-f, y, z-f, x+f, y+height, z+f)`，
`y` 就是**底边**。掉落物落地后这个点**正好贴着脚下方块的上表面**，
而回放的逐格扫描从 `t=0` 开始 ⇒ 第一个格子就命中自己站的那块方块。

**修法**：改用 `getBoundingBox().getCenter()`（抬高 0.125 格），
并提取为 `ItemEntityContainerContext#rayOrigin(ItemEntity)` 作为**单一真源** ——
服务端回放与客户端渲染**共用**，保证"画出来的线"和"实际挖的地方"永远一致。

**教训**：**`Entity#position()` 是包围盒底部，不是中心。**
凡是要"从实体出发"的位置，都得先想清楚该取哪个点（眼睛 / 中心 / 底部），
而且**两端必须共用同一个取法** —— 否则会出现"可视化是对的、挖掘是错的"这种最难查的不一致。

### 9.7 站在半砖上 / 泡在水里 → 不挖（2026-09-19 实测，含一次诊断修正）

两个现象都表现为"**射线看起来没问题，但就是不挖**"，但**根因不同**。

#### ① 半砖 —— 服务端用「格子级」判据，客户端用「形状级」

| | 判据 | 结果 |
|---|---|---|
| **客户端渲染**（`level.clip`） | 射线 × **方块形状**求交 | 半砖形状只占下半 ⇒ 射线从它**上半的空气部分**穿过 ⇒ **穿过** ✅ |
| **服务端回放**（`scanForTarget`） | 格子是否 `isEmptyBlock` | 半砖那格**不是空气** ⇒ **命中** ❌ |

⇒ 分歧就在这里：**"画出来的线是对的，挖的时候却卡在半砖上"**。
用户反馈"我看射线没有在半砖内部呀"，指的正是这个 ——
**视觉上起点确实在半砖上方的空气里**（半砖模型只占下半），
但它在**格子层面**属于半砖那一格。

**修法**：`scanForTarget` 补上**形状求交**，与客户端 `clip` **同源**：

```java
// 只有射线【真的穿过该格的形状】才算命中
if (level.getBlockState(current).getShape(level, current).clip(from, to, current) == null) {
    continue;
}
```

> 📌 **一次诊断修正**：初版把原因归为"起点落在半砖方块内 ⇒ 掉落物缺少黑名单"，
> 并加了"把起点所在格当宿主跳过"的补丁。用户指出"射线没有在半砖内部"之后重新分析，
> 才定位到真正的分歧是**判据粒度不同**（格子 vs 形状）。
> **补丁已撤** —— 直接跳过整格会漏掉那一格里真正的目标；
> 形状求交既精确、又与客户端一致。
>
> ⭐ **教训**：**"可视化"与"判定"必须共用同一套判据。**
> 客户端用 `level.clip`（形状级），服务端用逐格 `isEmptyBlock`（格子级），
> 两者天然会分叉 —— 这类 bug 的表现就是"看的和做的不一样"。

#### ② 泡在水里 —— `isEmptyBlock()` 判的是 `isAir()`

**根因**：`Level#isEmptyBlock(pos)` 等价于 `state.isAir()`，而 **水方块不是空气**。
掉落物浮在水面 / 沉在水下时，射线立刻命中水；穿水时也会被水体挡住。

**修法**：扫描的"可穿过"判据从「非空气」升级为「**非空气、且非纯流体**」：

```java
private static boolean isOpenSpace(ServerLevel level, BlockPos pos) {
    BlockState state = level.getBlockState(pos);
    if (state.isAir()) return true;
    // 判据用「有流体【且】无碰撞箱」，而不是「有流体」——
    // 否则会误伤充水方块（waterlogged 台阶 / 楼梯），那些应当是可挖目标
    return !state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty();
}
```

`replayDig` / `replayUse` **共用** `scanForTarget`，一处改动两边生效；
`L42`（允许挖宿主自己）的判据也一并换成 `isOpenSpace`（否则会去挖水）。

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

**掉落物形态**（`L-f`，2026-09-19 实测通过）：

- [x] 把活工具丢在地上 → 它会继续按记忆回放
- [x] 掉落物移动 → 射线起点跟随移动（`L10=a`）
- [x] 破坏成功后耐久正确扣减并同步到客户端
- [x] 工具挖到耐久耗尽 → 掉落物消失

**容器形态同步**（`K2`，待实测）：

- [ ] 把活工具放进箱子 → 不开 GUI、只按 F3+B → 应看到射线从**箱子中心**射出
- [ ] 走远超过 32 格 → 射线消失（半径定向）
- [ ] 走回来 → 射线重新出现
- [ ] 把活工具取出放进背包 → 容器那条射线消失（整体替换机制）
- [ ] 换维度 → 不残留上一个维度的射线
- [ ] 退出存档再进另一个 → 不残留（登录时清缓存）
- [ ] 大箱子（两格）→ 射线从第一格中心射出，且不会挖掉箱子自己

**射线扫描的边界场景**（2026-09-19 实测通过）：

- [x] 掉在**半砖 / 台阶**上 → 正常挖（`L14=d` 起点取碰撞箱中心 + §9.7 形状求交）
- [x] 掉进**水里** → 正常挖（§9.7 的 `isOpenSpace`）
- [x] 掉在普通满高方块上 → 回归正常
- [x] 站在半砖上、录"挖脚下"的短记忆 → 能挖到半砖
- [x] 活工具挖到一半、方块被人挖走 → **破坏裂纹立刻消失**（`L47`）

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
| ~~`L-f` 掉落物形态~~ | ✅ **已实现**（2026-09-19），见 §11.4 |
| **`L37` 实体攻击** | 左键命中实体时攻击（为活剑铺路） |
| ~~`L19/L20` 射线可视化~~ | ✅ **已实现**（2026-09-19），见 §11.3 |
| **`K` 组悬浮渲染** | 待机位（宿主旁）↔ 工作位（射线目标点），御剑 `FormationGeometry` 可作排布参考。<br>**前置 `K2` 已完成** ✅ —— 数据同步与渲染管线（§11.3）都已就位，只剩「模型 + 动画」 |

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
| 显示时机 | **手持 → 始终显示**；其余宿主（背包非手持槽 / 掉落物）→ 仅在 **F3+B** 时显示。<br>⚠️ `L20` 于 2026-09-19 **修订**：手持正是"玩家正在操作、最需要确认记忆方向"的时刻，不该被开关挡住。<br>开关读 `shouldRenderHitBoxes()`，纯客户端、无需同步 |
| 手持判定 | 用 **引用相等**：`Player#getMainHandItem()` 最终就是 `Inventory#getItem(selected)`，同一个 `ItemStack` 对象 —— 比反查槽位索引可靠 |
| 颜色 | 挖掘记忆（左键）= 橙红；交互记忆（右键）= 青蓝 |
| 命中与否 | 本地 `clip`：**命中 → 画到命中点（alpha 1.0）**；**落空 → 画到终点（alpha **0.7**）** ← 稍淡即"这条线落空了"的信号 |
| ⚠️ "落空"是常态 | 记忆录的是「眼睛 → 方块表面」的偏移。玩家录完只要挪动一步，眼睛位置就变 ⇒ 射线终点偏移 ⇒ **本来命中的记忆也会落空**。故落空的线仍须清晰（初版 alpha 0.25 实测太淡）。<br>📌 也解释了为什么"擦缝 MISS"不必靠代码兜底：**可视化本来就把这件事摆给玩家看了** |
| 正在挖时 | **不画** —— 原版 `destroyBlockProgress()` 已自动显示破坏裂纹（零渲染代码） |
| 画法 | **垂直于视线的四边形「光带」**：`RenderType.debugQuads()`（纯色 / 无纹理 / 支持透明 / 双面可见），顶点 `addVertex(pose,…).setColor(…,…,…,…)` |
| 粗细 | 半宽 = `clamp(相机到线段中点距离 × 0.0025, 0.015, 0.15)` ⇒ **屏幕上的粗细大致恒定**（≈4px @1080p/FOV70） |
| ⚠️ 为什么**不**用 `RenderType.lines()` | **OpenGL core profile 下很多驱动只保证 1.0 像素线宽**，调 `LineStateShard` 也可能没效果（实测偏细）。画光带则宽度完全自控、**跨驱动一致** |
| 横向轴 | `dir × (camera - mid)` ⇒ 光带平面始终"侧对"玩家，任何角度看都是条粗线；退化时（视线与线平行）回退到世界 Y / X 轴 |
| 坐标 | 事件给的 `PoseStack` 已是**相机相对**，只需 `translate(origin - cameraPos)` |
| 裁剪 | 距离 ≤ 32 格 + `Frustum#isVisible(AABB)` |
| flush | 画完**必须** `bufferSource.endBatch(RenderType.lines())`，否则不保证本帧画出来 |

**各宿主的射线起点**（必须与回放侧一致）：

| 宿主 | 起点 | v1 状态 |
|---|---|---|
| 玩家（背包 / 主手 / 副手） | 眼睛（`L3=a`） | ✅ |
| 掉落物 | **碰撞箱中心**（`L14=d`） | ✅ |
| **方块容器** | 容器方块中心（`L14=a`） | ✅ 由 `K2` 的 S2C 包提供（不开 GUI 时客户端拿不到箱子内容） |

> 背包形态不需单独处理主手/副手：`Inventory#getContainerSize()`（41）已含快捷栏与副手。

#### ⚠️ `L47` → `L48`：客户端不自算命中，改由服务端给 target（2026-09-19）

**`L47` 的坑**：容器形态起点是**方块中心**（`L14=a`），`level.clip` **一出发就命中宿主自己的
OUTLINE**，`tip ≈ origin` ⇒ **光带长度为 0**。
症状：挖掘功能完全正常，但 F3+B 下什么都看不到 —— 只有切旁观模式**穿进箱子内部**才看得到一个小点。

⭐ **为什么服务端没这个问题**：`scanForTarget` 用「黑名单 + 逐格扫描」，天然跳过宿主。
所以**只有渲染被自己的家卡住了**。

**第一版修法（走了弯路）**：在客户端**复刻**服务端的判据（`scanOutOfHost` 步进扫描）。
表面修好了，却立刻引出三处新分歧 —— 宿主范围（大箱子是两格）/ 流体 / 形状求交，
而且**永远追不齐**（判据要维护两份，必然逐步走偏）。

**⭐ `L48` 定案：客户端根本不自算，而且要的东西越少越好。**

服务端 `resolveDigTarget` / `resolveUseTarget` 判定后，**只同步一个布尔**
（`LivingToolHostPacket.ToolRay` 的 `digLanded` / `useLanded`）。
客户端画的是**记忆射线本身** `origin → origin + offset`（**恒定**），布尔只用来选透明度。

| | 客户端重算 | 服务端给结论（采用） |
|---|---|---|
| 判据份数 | 2 份，必然走偏 | **1 份** |
| 起点 | 被迫推到方块外（**从边缘发射**） | 就是**中心**（`L14=a`，诚实） |
| 终点 | 客户端推演 | `origin + offset`（**恒定**） |
| 命中判定 | 客户端猜 | **权威布尔** |
| `L47` 类 bug | 会发生 | 不可能 |

⚠️ **中途走过的弯路（值得记住）**：定案后我又把终点画到了目标方块中心
（同步 `BlockPos` 而非布尔），结果**射线跟着目标跳** —— 挖完一格就跳下一格。
表面看是"更精确"，实际背离了 `L19`「把记忆画出来、让玩家看出偏没偏」的初衷：
**一条会跳的线根本看不出偏没偏**。用户连追两次（"根本不需要呀" → "客户端渲染根线要算什么交点"）才拆干净。

**射进方块的那一段不需要我们处理** —— `AFTER_ENTITIES` 阶段地形深度已写入，
**深度测试免费把它挡掉**，视觉上照样"停在表面"。

**为什么只改容器形态**（用户拍板）：容器是**静止**的，同步结果零成本；
玩家 / 掉落物会**高速运动**，本地重算才能零延迟跟上手 —— 且它们起点在空气中，
`clip` 本来就是对的，不必动。

**残留**：`L42`（挖自己的家）时 `target` = 宿主自己 → 画出来是极短的线。
这是**真实情况**（它确实在挖自己），属于诚实呈现；若要更易读，可改成高亮宿主方块轮廓。

⭐ **顺手为 `K` 组铺好了地基**：本节建立的正是悬浮渲染要用的**全套底层能力** ——
`RenderLevelStageEvent` 接入、相机相对坐标、**满亮光照**与深度处理、距离 / 视锥裁剪。
`K` 组此后只需在这个骨架上加「模型 + 动画」，不必再趟一遍渲染管线的坑。

---

### 11.4 已实现：掉落物形态（`L-f`，2026-09-19）

**做法：把掉落物包装成「单栈容器」**，复用现成的 `processContext` 管线 ——
**没有第二套平行回放实现**。

```
LivingItem.onServerTick
  ├── processContainer(玩家背包)           → SimpleContainerContext
  ├── processLevelContainers(区块方块实体)  → SimpleContainerContext
  └── processItemEntityContainers(实体)     → ItemEntityContainerContext（新）
                    ↓
        ContainerLivingItemHandler.processContext（同一条）
                    ↓
        LivingToolFunction.tick → LivingToolReplay.replayDig / replayUse（同一套）
```

**为什么这条路能走通**：`processContext` 完全不依赖"方块" ——
凡是 `instanceof SimpleContainerContext` 的分支（红石 / 流体 / 应力 / 相位快照 / tooltip 同步）
对掉落物**自动跳过**，正是我们要的结果。

| 关注点 | 做法 |
|---|---|
| 槽位数 | `getSize() = 1`（`SINGLE_SLOT`） |
| `getBlockPos()` | 默认 `null` ⇒ 回放侧走"掉落物作起点"分支（`L14=d`） |
| 射线起点 | `rayOrigin(entity)` = **碰撞箱中心**（⚠️ **不是** `position()`，见 §9.6）；服务端与客户端**共用**此方法 |
| `getLevel()` | 实体所在世界（FakePlayer 的 `setPos` 与权限判定要用） |
| 写回同步 | `ClientboundSetEntityDataPacket`（掉落物没有容器菜单） |
| 扫描方式 | 遍历 `ServerLevel#getAllEntities()`，`instanceof ItemEntity` + `isLivingTool` |

#### ⚠️ 同步的坑：必须"先置空再写回"

`ItemEntity` 的物品走 `SynchedEntityData#set`，内部按 `equals` 判重；
自定义 DataComponent 的变更不一定能被检出（与容器侧 `ContainerSync` 同一原因）。
所以 `syncSlotToClients` 里先 `setItem(EMPTY)` 再 `setItem(stack)` ——
**保证一定被标脏**，再用 `packDirty()` 打包广播。
两次赋值发生在同一 tick、客户端只会收到最终值，无副作用。

#### ⚠️ 只处理活工具，不处理全部活物品

其它活物品的功能类都假定自己有方块坐标（从 `getBlockPos()` 取），
扔进掉落物上下文会拿到 `null`。要让更多活物品支持掉落物形态，
得先给 `LivingItemFunction` 加**宿主能力声明** —— 不在 `L-f` 范围内。

#### ⚠️ 为什么直接遍历实体、不建索引

`ContainerChunkCache` 那套区块级缓存依赖"方块容器位置稳定"；
掉落物会移动、会被合并、会被卸载，**维护索引失效的成本高于收益**。
`getAllEntities()` 是 O(实体数) 的浅遍历，绝大多数在 `instanceof` 处短路。

---

### 11.5 已实现：悬浮模型 + 动画（`K` 组，2026-09-19）

`client/render/LivingToolModelRenderer` —— 让活工具在世界里「活起来」。

**模型**：直接用活工具**物品本身的 3D 模型**（`ItemDisplayContext.FIXED`；⚠️ 不能用 `GUI`，那是 2D 图标）。

**位置：一切挂在记忆射线上**

| 位置 | 公式 | 说明 |
|---|---|---|
| 待机位 | `origin + dir × d`，`d = 1.5·L/(L+3)` | **双曲饱和**：射线越长待机位越远，但**增长越来越慢且严格有上界 1.5 格** |
| 交互位 | 射线命中方块**表面**的点 | 用方块自身 `VoxelShape` 求交（只做几何细化，不算判定） |

**动画**

| 状态 | 位置 | 动作 |
|---|---|---|
| 待机 | 待机位 | **不动**，朝向射线方向 |
| 挖掘中 | **瞬现**到交互位 | **风车式转圈**，挖越快转越快 |
| 交互 | **瞬现**到交互位 | **缩放脉冲**，不转圈 |
| 干完 | 飞回待机位 | 缓出，**恒定 8 tick** ⇒ 距离越远自然飞得越快 |

⭐ **为什么「去」是瞬现**：交互是瞬时动作，飞过去必然"方块都挖完了模型还在半路上"。回程恒定 8 tick 就自动满足"距离越远越快"，不需要速度函数。

⭐ **为什么自转绕【薄板法线】**：镐子本质上是一块**平面**（T 形薄板）。绕什么轴转，决定了看不看得出在转：

| 自转轴 | 效果 |
|---|---|
| 柄轴 | 对称图形绕对称轴 ⇒ **从侧面几乎看不出来** ❌ |
| T 平面**内**的横轴 | 工具"**横着翻滚**"，姿势别扭 ❌（2026-09-20 实测踩过） |
| **垂直于 T 平面**（薄板法线） | 薄板在自己平面里旋转 ⇒ **任何视角都一眼看出在转** ✅ |

模型空间里柄是 `+Y`、薄板躺在 `XY` 平面 ⇒ **法线恰好是 `Z`**，所以自转就是一个 `Axis.ZP.rotation(spin)`。

⭐ **位置：写在 `rollRad` 的【内侧】、立正的【紧外侧】**：

```java
poseStack.mulPose(Axis.YP.rotation(rollRad));            // 只调"脸朝哪"（相对射线）
poseStack.mulPose(Axis.ZP.rotation(-spinRad));           // 自转：轴 = 立正后的 Z = 板面法线
poseStack.mulPose(Axis.ZP.rotation(MODEL_UPRIGHT_FIX));  // 立正
```

这样自转轴就是「**立正后的 `Z`**」= 板面法线，而且 **相对模型自身恒定** ——
不随 `rollRad`、也不随射线方向改变。

> 📌 **用户澄清（2026-09-20）**：*"这个自转是相对于模型本身的，不是相对于射线的。射线不是模型自转的参考系。"*
>
> ⚠️ **曾写错**：一开始把自转放在 `rollRad` 的**外侧**，还特意注释"这样自转轴会跟着 rollRad 滚"。
> 那是把「自转轴」和「朝向修正」绑在了一起 —— 结果自转轴被带到**模型 X**（T 平面内的横轴）上，
> 变成"横着翻滚"。`rollRad` 是**相对射线**的调整，不该当自转的参考系。
>
> 📌 **教训**：**"朝向修正"和"自转轴"是两个独立的东西**，不要图省事让后者的参考系跟着前者跑。
> 判据很简单：**自转轴应当在模型自身的坐标系里恒定**。

⚠️ **转圈最快只能是 4 tick/圈**（不是 1）：60fps 下 1 tick 只有 3 帧，1 tick/圈 = 每帧 120°，远超 Nyquist 极限 ⇒ 会看成**倒转或抖动**（车轮效应）。

**同步：只加 2 个 network-only 组件**

| 组件 | 何时写 | 用途 |
|---|---|---|
| `LIVING_TOOL_DIG_TICKS` (int) | 开始挖新目标时**一次** | 转圈速度（挖掘期间速度恒定） |
| `LIVING_TOOL_LAST_ACTION` (tick + BlockPos) | 交互成功时 | 触发脉冲 + 定位瞬现点 |

挖掘的"是否在挖 / 挖哪一格"**不用新增** —— `LIVING_TOOL_PROGRESS` 本来就是 `networkSynchronized` 且含目标，客户端直接读即可。

⭐ **动画是纯表现层，零逻辑耦合**：所有"要不要干、挖哪、多快"都由服务端定（`L48`），本类只读结果。动画播错/漏播**不影响核心功能**。

**覆盖范围**

| 形态 | 是否画 | 说明 |
|---|---|---|
| 玩家背包（非手持） | ✅ | — |
| 掉落物 | ✅ **额外**渲染 | ⚠️ 原版 `ItemEntityRenderer` 仍会画一个（位置更低、带旋转浮动），**两者视觉重叠**（用户 2026-09-20 明确要求）。若嫌重叠，可用 mixin 对活工具掉落物隐藏原版那个 |
| 方块容器 | ✅ | — |
| **手持** | ❌ | 玩家手里已经拿着了，再飘一个是重复 |

#### ⭐ 两种模式，两套位置逻辑（2026-09-20 补）

「有没有记忆」不只决定功能，也决定**模型长什么样** —— 位置本身就是模式指示器：

| 模式 | 待机位置 | 挖掘时 | 传达的含义 |
|---|---|---|---|
| **有记忆**（自主） | 挂在**记忆射线**上（`d = 1.5·L/(L+3)`），朝向射线 | 瞬现到命中面 + 风车自转 | 它是**工人** —— 一直在这儿干活 |
| **无记忆**（辅助） | **玩家背部**围成环，放射状朝外 | 瞬现到**目标方块**围成环 + 风车自转 | 它是**帮手** —— 用完就归位 |

**环（辅助模式）的关键设计**

- **自适应半径**：`r = clamp(0.28 + 0.055 × 工具数, …, 1.10)` ——
  工具越多环越大，弧长 `2πr/n` 大致恒定，不至于挤成一坨。
- **环心高度 `RING_HEIGHT = 1.7`（头部高度）**。
  ⚠️ 别调低：环半径最大 `RING_RADIUS_MAX = 1.10`，放在胸口（`1.0`）时最低点是 `−0.10`，
  **下半环会埋进地底**（2026-09-21 用户实测）。取头部高度则最低点为 `0.6`，安全。
- **环离背部 `RING_BACK_OFFSET = 0.75` 格**。⚠️ 别调太小：`0.45` 时某些视角下
  活工具会**穿透玩家脑袋、挡住视线**（2026-09-20 用户实测）。
- **环平面竖直**，法线取**玩家朝向的水平分量**（不含俯仰）⇒ <b>抬头低头环不晃</b>。
  挖掘环的法线则指向玩家（环面朝着玩家）。

  > ⚠️ **这条是刻意选的，别再改**（2026-09-20 试过"跟随俯仰"，观感不好被打回）。
  > 而且它还是下面两个实现细节的**前提**：
  > 1. 环平面内的正交基可以直接借**世界 `up`** 当第二根轴 —— 正因为法线**恒为水平**，`normal × up` 不会退化。
  > 2. 环心按水平背向偏移，抬头低头时环不会跑到脚底/头顶。
  >
  > ⚠️ 另外：视线接近**正上 / 正下**时，水平分量退化为零向量 ——
  > 此时必须**沿用上一次的有效方向**（`lastBackNormal`），
  > **不能**退回写死的方向，否则环会"啪"地钉到固定一侧（2026-09-20 用户实测）。
  >
  > 一旦让法线跟随俯仰，这两条**同时失效**：正交基要重新构造，且正对上下时叉积为零（归一化出 NaN ⇒ 工具整圈消失），
  > 得额外兜底。改动成本远大于"能晃"那点观感收益。
- **长轴沿半径朝外** ⇒ 工具**头朝外、柄朝圆心**，呈放射状（2026-09-20 用户定）。
  ⚠️ 前提是模型已**立正**（见下文 `MODEL_UPRIGHT_FIX`）—— 否则贴图那 45° 的倾斜会盖过
  这里设的朝向，看起来"怎么摆都不对"。
- **起始角从正上方起**（`RING_START_ANGLE = π/2`）⇒ 单工具**竖直立于头顶**、双工具一上一下。
  ⚠️ 从 `0`（玩家右手边）起步时，只有一个工具会**横躺在右侧、头朝右**，不像"待命"（用户实测指正）。
- ⭐ **朝向用「正交基」直接钉死**（而不是 `yaw`/`pitch`）：

  | 局部轴 | 指向 | 含义 |
  |---|---|---|
  | `+X` | **环法线** | 镐头横线 ⇒ **垂直于环面** |
  | `+Y` | **径向** | 柄 ⇒ **头朝外、柄朝圆心** |
  | `+Z` | `normal × 径向`（**切向**） | 板面法线 ⇒ 左右两半**同规则** |

  ⚠️ **为什么不能用 `yaw`/`pitch`**：那套解只保证「把 `+Y` 转到目标方向」，
  **剩下的滚转自由度是任意的** —— 于是板面法线会随工具在环上的位置翻来翻去：
  **右半边朝切向、左半边朝切向的反方向**，看着"右半朝前、左半朝后"（2026-09-20 用户实测发现）。

  > 📌 **教训**：**"对齐一根轴"不等于"定死朝向"**。
  > 一条射线只有一个方向自由度，所以射线上 `yaw`/`pitch` 看着没问题；
  > 但环上 `radial` 会绕一圈，那个"多余的自由度"就会暴露出来 —— 必须钉死一整组**正交基**。

- ⭐ **漂移锚点 `RING_PSI_DRIFT`**（2026-09-21 用户设计）—— 让整个 T 平面绕**柄轴**缓慢旋转。

  **它解决的问题**：`ax`（横线）被约束为「⊥ 环面」，而"⊥ 环面"的方向**只能是环法线 = 玩家朝向**。
  于是图案完全由朝向决定 ⇒ 你会形成"朝北长这样、朝东长那样"的**固定印象**，感觉四方向"不对称"。

  **但这是个感知问题，不是几何问题** —— 几何上四方向本来就只是**整体绕竖直轴转了 90°**，
  从相机看完全一样。真正的来源是「图案是**静止**的，所以会被记住、被比较」。

  **解法**：给 T 平面绕柄轴加一个**随时间往复的相位**：

  ```java
  ψ = RING_PSI_SWEEP · (1 − cos(ω·t)) / 2      // 0 → 一圈 → 0
  ```

  ⭐ **函数形式的坑（我们连撞两次）**：

  | 形式 | 行为 | 结论 |
  |---|---|---|
  | `ψ = ω·t`（匀速） | 一直朝一个方向绕 | ❌ 不会回头 |
  | `ψ = A·sin(ωt)` | `0→+A→0→−A→0`，**每个四分之一周期就换向** | ❌ 单个工具一直在"左右晃"，**永远转不满一圈** |
  | `ψ = A·(1−cos(ωt))/2` | 从 0 **单调**升到 A、再**单调**降回 0 | ✅ 全程只在两端各换一次向 ⇒ **转完一整圈再倒回** |

  ⚠️ 注意 `sin` 的"往返"是**相对零点**的，看着像在摆，但**扫过的角度不到一整圈**；
  要"真的转一圈"，必须用 `(1−cos)` 这种**非负、单调上去再单调下来**的形式。

  | 常量 | 现值 | 说明 |
  |---|---|---|
  | `RING_PSI_SWEEP` | `360°` | **单向行程** —— 转满一整圈再倒回；调到 `0` 即回到"横线 ⊥ 环面"的静止姿态 |
  | `RING_PSI_OMEGA` | `0.0025` rad/tick | `(1−cos)` 的完整周期 = `2π/本值` ⇒ 正转 ≈ 63 秒、反转 ≈ 63 秒；`0` = 关闭 |

  **待机环与挖掘环共用同一相位**（形状语言一致，2026-09-21 用户要求挖掘环也启用）。

- ⭐ **「立体程度」呼吸 `RING_ALPHA_OMEGA`**（2026-09-21 用户要求）

  环的姿态其实有**两层**可调，之前只用了第二层：

  | 层 | 现在 | 说明 |
  |---|---|---|
  | **① 基准方向** | `α` 插值 ⇒ **时间函数** | `α = 0.5 + 0.5·sin(ωt)` ∈ `[0,1]` |
  | **② 平面内相位** | `ψ` ⇒ 时间函数 | 见上面的 `RING_PSI_*` |

  ```
  脸 = normalize( (1−α)·切向 + α·环法线 )     // 两者都已 ⊥柄，插值天然安全
  ```

  | `α` | 脸指向 | 观感 |
  |---|---|---|
  | `0` | **切向**（沿圆走） | 工具**"插"在环上**，最立体；但直径两端脸相反 |
  | `1` | **环法线** | **所有工具脸同向**；但板面 ∥ 环面，工具"贴"在环上 |

  ⭐ **两条路相差 90°，不是 180°**（2026-09-21 用户质疑后修正）：
  绕柄转 180° 只在切向里翻个面（切向 → 反切向），**出不了环平面**。
  所以"把朝向不对的那个转 180°"解决不了两端相反 —— 必须转 **90°** 才跳得过去。

  > 📌 **曾犯的错（值得记）**：一开始把 `α` 参数化成「环法线 ↔ **世界方向**」的插值，
  > 但那样插值出来的脸**全都还在环平面内**，怎么调都到不了"环法线"那条路 ——
  > **参数化本身就是错的**。正确做法是让它在「**切向 ↔ 环法线**」之间插值
  > （这两者才是"⊥柄平面"内的那组正交基）。

  📌 **取舍是躲不掉的**：立体感正来自"横线 ⊥ 环面"，所以 `α` 越小环越平。
  现在让它缓慢呼吸（≈78 秒一次），在"很立体"和"很平"之间往返。

  > 退化处理：世界锚定方向取世界 `up`；柄接近竖直（`|dir.y| > 0.99`）时改用 `Z` 轴，
  > 否则投影到 ⊥柄 平面会退化。基准与柄几乎平行时本帧跳过绘制。

- ⭐ **以玩家朝向为锚点：朝向补偿 `RING_PSI_VIEW_WEIGHT`**（2026-09-21 用户提出）

  让漂移量带上朝向的反向补偿：

  ```java
  ψ = f(t) − w · φ          // φ = 朝向方位角 = atan2(normal.x, normal.z)
  ```

  这样工具姿态变成**相对朝向**的量 ⇒ 它的**世界姿态不再随你转头而变**
  （环跟着你转，工具却"钉"在世界里），四个朝向看起来就一致了。

  ⚠️ **它只对一部分工具完全成立**：

  | 工具位置 | ⊥柄平面 | 补偿效果 |
  |---|---|---|
  | **顶部 / 底部**（柄竖直） | **水平面** | 绕柄轴转 ≡ 绕竖直轴转 ⇒ 方位角恰好抵消为 `f(t)`，**完全与朝向无关** ✅ |
  | **侧面**（柄水平） | 竖直的 `span{朝向, 下}` | 不同朝向下这两个平面**正交**，无法完全抵消 —— 只能大幅缩小偏差 ⚠️ |

  📌 **根因**：`⊥柄的平面`由柄唯一决定（**没有自由度**），而不同朝向下
  侧面工具的该平面互相正交 —— 这是几何决定的，换任何函数都绕不过去。

  ⚠️ **代价**：转头时工具会**反向自转**一下（因为它的柄必须沿径向，而径向在转）。
  `w` 调小可以减弱；设 `0` 即完全不补偿。

  📌 **思路价值**：当"几何上已经对称、但主观上仍觉得不对称"时，**解法可能在时间维度上** ——
  让那个被记住的"固定态"本身动起来。
- **只有出力的去挖掘环**，其余留在背部环 —— "环上少了几把"本身就是"它们去帮忙了"的表达。
- 顺序按**槽位号升序** ⇒ 环上的角度稳定、不跳。

**"正在挖哪一格"从哪来**：`LivingToolAssistState` —— 由 `LivingToolAssist` 在**客户端**侧
写入（挂在 `BreakSpeed` 事件上），**不需要任何网络同步**。静默超过 3 tick 即视为停止。

> ⚠️ **修正（2026-09-20，实测 bug）**：原设计认为"`BreakSpeed` 每 tick 触发 ⇒ 最近有没有事件
> 就表达了在不在挖"。**这是错的** —— `BreakSpeed` **只要准心对着可破坏方块就每 tick 触发**，
> 根本不需要按住左键。
> 于是渲染出的症状是：**玩家没挖，准心指向哪，模型就在那格转圈**。
>
> **修法**：渲染侧额外要求 `MultiPlayerGameMode#isDestroying()` —— 它才是"玩家确实按着左键
> 在挖"的权威判据。
>
> 📌 **教训**：给"状态"找代理信号时，要确认那个信号的**触发条件**与真实意图**等价**，
> 而不是"经常一起发生"。`BreakSpeed`（"对着"）≠ 挖掘（"按着"）。

**⭐ 朝向：模型天生是【斜 45°】的 —— 必须先"立正"再对齐**

⚠️ 这是本项目踩过的一个**认知坑**（2026-09-20 由用户定位）：

MC 的 `item/handheld` 贴图是**斜 45° 对角**画的（镐头在右上、柄在左下），
为的是在**物品栏图标**里好看。而 `ItemDisplayContext.FIXED` 的 display 变换**只有"绕 Y 转 180°"**
（模型 json 的 `"fixed"` 块），不含任何"立正"旋转 ⇒ 渲染出来仍是**贴图原生朝向** = **斜的**。
（`GROUND` 掉落物同理 —— 所以掉落物也是斜的。）

📌 **推论：模型"哪边是上"这个信息，MC 里没有元数据可查** —— 它**画在贴图里**。
`ItemTransforms` 只给各 context 的变换，给不出"模型自身的上下"。
唯一的办法是**按贴图约定校正 + 实测**。

**完整链路**（注意第 2 步，FIXED 自己也会转一下 —— 漏了它符号就会反）：

| # | 变换 | 贴图长轴方向 |
|---|---|---|
| 1 | 模型原始空间：贴图 `(0,16) → 模型 (0,0)`、`(16,0) → 模型 (1,1)`（Y 翻转） | `(+1,+1)` |
| 2 | **`FIXED` 自带：绕 Y 转 180°**（`(x,y,z) → (-x,y,-z)`） | `(-1,+1)` |
| 3 | **`MODEL_UPRIGHT_FIX`：绕 Z 转 `-45°`** | `(0, √2)` ⇒ 立成 `+Y` |
| 4 | `drawModel`：把 `+Y` 对齐目标方向（`yaw` + `pitch`） | 指向目标 |

验算第 3 步：`x' = -cos(-45) − sin(-45) = 0`、`y' = -sin(-45) + cos(-45) = √2` ⇒ 头朝上、柄朝下。

> 📌 **跨项目印证**：同工作区的「御剑」模组（`libs/src/YujianCraft-main`）用**完全相同的方案**
> —— `ItemRenderer#renderStatic(stack, ItemDisplayContext.FIXED, …)` + 绕 Z 轴校正，
> 并在 `ClientModEvents.java` L345 留了结论：
> *"FIXED rotates the vanilla item 180 degrees around Y. -45 aligns the real blade axis."*
> 那句话就是第 2 步的证据。我最初**漏了 FIXED 的 Y180**，于是把校正角写成了 `+45`（符号反）。

都写在 `PoseStack` 最内层（= 最先作用于模型），常量偏差可直接调。

**绕长轴的滚转：两条路径期望不同（`RAY_ROLL_FIX`）**

**立正只固定了"长轴竖直"，还留着一个绕长轴的自由度 —— 即板面朝向（"脸朝哪"）。**
两条路径对这个自由度的期望**不一样**（2026-09-20 用户实测）：

| 路径 | 脸的要求 | 补角 |
|---|---|---|
| **辅助环** | **垂直于环面** | `0`（立正后天然满足 ✅） |
| **射线上** | 还需再转 90° | `RAY_ROLL_FIX = +π/2` |

实现上它是 `drawModel` 的一个参数，写在**立正的外侧**：

```java
poseStack.mulPose(Axis.YP.rotation(rollRad));            // 绕长轴（此时 +Y 才是长轴）
poseStack.mulPose(Axis.ZP.rotation(MODEL_UPRIGHT_FIX));  // 立正（更内层）
```

> ⚠️ 顺序不能反：只有**立正之后** `+Y` 才等于长轴，绕 Y 转才是"绕长轴滚转"。

> 📌 **教训**：调朝向 / 变换前，先确认「**模型的原始坐标系长什么样**」。
> 我在没确认这一点的情况下调了两轮滚转角（还**绕错了轴** —— 用了 Y，而平面内旋转应是 Z），
> 结果"柄 45° 斜朝下"，怎么调都不对。**在错误的前提下调参数，只会越调越乱。**

**⚠️ 一个隐蔽 bug：动画状态永远同步不到「掉落物」形态**

`LivingToolFunction#writeBack` 开头有 `ItemStack.matches(before, after)` 短路，
而 `PatchedDataComponentMap.equals()` **检测不到自定义组件的变化**（项目已知坑）——
于是「只改了 `LIVING_TOOL_PROGRESS` / `LIVING_TOOL_LAST_ACTION`」时短路命中、**直接 return，永不同步**。

各形态表现不同，正是因为**兜底通道的有无**：

| 形态 | 兜底通道 | 结果 |
|---|---|---|
| 方块容器 | `K2` 每 tick 全量同步 | 看得到（**侥幸**） |
| 玩家背包 | 原版 `broadcastChanges()` | 看得到（**侥幸**） |
| **掉落物** | **无** | ❌ 永远看不到"开始挖 / 停挖 / 刚交互" ⇒ **模型完全没动画** |

**修法**：`syncStateFlip` —— 在回放之后比较 progress / action 是否翻转，翻转则显式
`context.syncSlotToClients(...)`。两个组件都是翻转语义，故不会每 tick 重发。

📌 教训：**"侥幸能工作"的路径最容易藏 bug** —— 三种形态里两种靠各自的兜底通道掩盖了问题，
只有第三种暴露出来。定位时应先问"这三种的差异在哪"，而不是"是不是渲染写错了"。

---

### 11.6 已实现：辅助玩家挖掘（`A3` 支线，2026-09-20）

`domain/tools/LivingToolAssist` —— 背包里的活工具给玩家的挖掘「搭把手」。

**边界：记忆是「分工开关」（用户定）**
- **有记忆** → 它**自己**干活（`L` 组回放），不帮忙
- **没记忆** → 它**帮玩家**挖

**⭐ 零 mixin —— NeoForge 官方钩子全覆盖**

原设计里标着"唯一技术难点"的 `B1`（Mixin `ServerPlayerGameMode` 拿破坏进度）**整个不需要**：
原版每 tick 调 `getDigSpeed` / `hasCorrectToolForDrops`，这两个位置正好都有钩子 ——
**"玩家正在挖哪一格"它自己会告诉我们**，中断 / 换目标天然被处理 ⇒ `B2`、`B3` 也一并消失。

| 维度 | 钩子 | 做法 |
|---|---|---|
| **加速** | `PlayerEvent.BreakSpeed` | `setNewSpeed(原速 + Σ 各活工具速度)` |
| **材质门槛** | `PlayerEvent.HarvestCheck` | 任意一把挖得动 → `setCanHarvest(true)` |
| **附魔归属** | `BlockDropsEvent` | 用**槽位最靠前**那把重算掉落与经验（`E6`） |
| **耐久** | 同上 | 每把出过力的扣 1 点（`F`） |

**⭐ 速度用 `LivingToolFakePlayer` 精算，而不是自己读 `ItemStack#getDestroySpeed`**

后者拿不到**效率附魔** —— 那是 `MINING_EFFICIENCY` 属性效果（`L46` 踩过），需要走属性表。
用 FakePlayer 则天然正确，且**复用了 `L46` 的 `equipTool` 装备属性同步**。

> ⚠️ FakePlayer 是 `ServerPlayer` 子类，它的 `getDigSpeed` 会**再次触发 `BreakSpeed` 事件** ——
> 所有回调的第一行都过滤 `isFakePlayer()`，故无递归（`L39` 同一坑，已有先例）。

**定案（用户拍板）**

| 项 | 结论 |
|---|---|
| 生效范围 | 仅**玩家背包**；手持普通工具时**也帮**；创造 / 旁观**跳过** |
| 距离 | **不需要**（只作用于玩家当前目标，天然受限） |
| 叠加 | 速度**直接相加、不设上限** —— 用户口径：*"玩家背包槽位数量就已经是上限了"* |
| 材质门槛 | 任意一把活工具挖得动即可 |
| 排序 | 槽位号升序（与 `SlotEntry.slotIndex` 一致） |

**⚠️ 两处「不与玩家抢手」**

1. **主手那一格被跳过** —— 原版 `getDigSpeed` 已经把手持工具算进去了，再算一次会**翻倍**。
2. **掉落重算只在玩家自己挖不动时才接管** —— 玩家能挖就完全走原版，不干预。

#### ⭐⭐ 关键坑：破坏的节奏由【客户端】控制（2026-09-20 实测踩到）

原版 `ServerPlayerGameMode#tick`：

```java
private float incrementDestroyProgress(...) {
    float f = state.getDestroyProgress(...) * (float)(i + 1);
    ... this.level.destroyBlockProgress(...)   // ← 只用来发破坏裂纹（客户端显示）
    return f;                                   // ← 返回值【被丢弃】，服务端不破坏
}
```

真正触发破坏的是客户端算完进度后发来的 `STOP_DESTROY_BLOCK`：

```java
} else if (action == STOP_DESTROY_BLOCK) {
    float f1 = blockstate1.getDestroyProgress(...) * (float)(j + 1);
    if (f1 >= 0.7F) { 破坏 }
```

⇒ **只加速服务端 = 完全没用**：客户端仍按原速等满时长才松手，服务端只能干等。

**症状极具迷惑性**（正是它让我查错方向）：

| 维度 | 是否生效 | 为什么 |
|---|---|---|
| 材质门槛 | ✅ | 服务端在 STOP 时判定 |
| 时运 / 精准采集 | ✅ | 同上 |
| **速度** | ❌ | **客户端才是节奏控制者**，它没被加速 |

⭐ 用户的实测描述一句话点破：*"空手挖完石头后石头掉落了，还吃到了精准采集附魔效果，
就是没有加速"* —— **门槛和附魔是服务端判的，速度是客户端控的**，于是"一半好一半坏"。

**修法**：`isAssistable` 放宽到两端都要放行（原先 `player instanceof ServerPlayer` 把客户端
`LocalPlayer` 直接挡在门外了），且速度计算分两端：

| 端 | 算法 | 理由 |
|---|---|---|
| 服务端 | `LivingToolFakePlayer` 精算 | 效率附魔是属性效果，需要走属性表（`L46`） |
| **客户端** | 本地算：`getDestroySpeed + 效率等级²` | 客户端没有 `ServerLevel`，也就没有 FakePlayer |

最终仍由服务端二次校验（`f1 >= 0.7F`）兜底，所以客户端算得略快是**安全**的。

📌 教训：**"一半生效一半不生效"时，先问"这两半各由哪一端判定"** ——
比逐行读代码快得多。

---

### 11.7 已实现：活工具 tooltip（2026-09-20）

`LivingToolFunction#addToTooltip`（框架按 `canApply` 过滤，故只有活工具会显示）。

**⭐ 为什么这个 tooltip 重要**：记忆是**隐形**的 —— 手持才画射线，在容器里还要 F3+B。
而 `A3` 之后**「有没有记忆」直接决定这把工具是哪一种模式**：

```
无记忆 → 辅助模式（在背包里帮玩家挖）
有记忆 → 自主模式（自己按记忆干活）
```

不说清楚的话，玩家根本不知道手上这把是「帮手」还是「工人」。

**显示内容**

```
--- 活物品 ---

  模式: 辅助玩家挖掘              ← 或 "自主挖掘"

  模式: 自主挖掘
  挖掘记忆: 前方 3.2 格
  交互记忆: 前方 2.8 格 (限定: 橡木原木)   ← 只有蹲下录的才带「限定」
```

距离 = 记忆偏移向量的长度；「限定」= `RayMemory.block()` 非空（蹲下录制时的类型约束）。

词条收在 `lang/zh_cn.json` / `en_us.json` 的 `tooltip.livingitem.tool.*`。

---

## 12. 已实现：`K2` 容器内容 S2C 同步（2026-09-19）

> ✅ 已按本节方案落地。三宿主现在**全部**能在客户端画射线。
>
> **最终定案**（相比草案有 3 处简化，均为用户拍板）：
> ① 同步 `ItemStack`（一次满足射线 / 模型 / 动画三个需求）
> ② **去掉节流** —— 内容去重后本就是按需发送
> ③ **状态字段先不加** —— 挖掘靠原版破坏裂纹推断，交互是瞬时动作
> ④ R = **32**，理由是**必须与原版裂纹广播半径一致**（不是随便取的值）

### 12.1 为什么必须新增一个包

现有 `LivingItemSyncPacket` **不能用**，有两个硬伤：

| 硬伤 | 说明 |
|---|---|
| **只发给正在看 GUI 的玩家** | `isViewingContainer(player, containers)` 要求玩家菜单里含该容器 —— 世界渲染需要的是"**路过就能看见**"，不开 GUI 也要看得见 |
| **不含 ItemStack** | 它同步的是 `LivingItemRuntimeData`（遥测/瞬态），没有物品本体 —— 客户端既画不出记忆、也渲染不了模型 |

### 12.2 三个设计决策

#### ① 同步什么 —— 推荐：位置 + **活工具的 ItemStack 副本**

| 方案 | 内容 | 评价 |
|---|---|---|
| a | `BlockPos` + **每个活工具的 `ItemStack`** | ⭐ **推荐** |
| b | `BlockPos` + 只提取记忆（offset/block）+ 物品 id | 包更小，但 `K` 组渲染模型时还得再补一次 |

**为什么推荐 a**：同步 `ItemStack` **一次满足三个需求**：

| 需求 | 靠什么 |
|---|---|
| 射线可视化 | `LIVING_TOOL_MEMORY`（已 `networkSynchronized`）随 ItemStack 一起到 |
| 悬浮模型渲染 | ItemStack 本体 → `ItemRenderer` 直接渲染 |
| 动画驱动（`K31=a`） | 未来的 `LIVING_TOOL_ACTION` 做成 network-only 组件，同样随 ItemStack 到 |

**安全**：只同步**活工具**（`isLivingTool` 筛过），**不泄露容器内的其他物品**。

**体积**：活工具 `maxStackSize = 1`（不堆叠），且只在近处 → 条目数是个位数。

#### ② 多久同步一次 —— 推荐：节流 **N tick** + 内容去重

| 方案 | 评价 |
|---|---|
| a | 每 tick 全量 —— 太重 |
| b | **节流 N tick**（建议 **10**，=0.5 秒），且与"上次发给该玩家的内容"比对，**无变化就不发** ⭐ |
| c | 纯增量（只发变化的那几条） —— 最省，但要处理"移除"，复杂度高 |

**为什么 0.5 秒可接受**：破坏裂纹由原版 `destroyBlockProgress` 广播（不依赖本包），
记忆录制时工具还在玩家手上（背包形态客户端本来就已知）**⇒ 没有需要零延迟的场景**。

**去重方式**：服务端为每个玩家缓存上次发出的条目列表，逐项比较（条目是个位数，开销可忽略）。

> ⚠️ **2026-09-19 修正**：原方案用 `ItemStack.matches` **全量比较组件**，但容器里的活工具
> 每 tick 都在被写入 `LIVING_TOOL_PROGRESS`（挖掘进度）与耐久 ⇒ **"内容永远在变"、去重彻底失效**。
> 实测日志确认退化为**每 tick 发一个包**。
>
> 改为只比较**渲染真正用到的**：命中目标 + 物品种类 + 记忆（`sameRay` / `sameTool`）。
> 📌 教训：**同步包的去重口径，应当只覆盖"消费方真正依赖的字段"** ——
> 用"全量相等"当去重条件，很容易被无关的易变字段击穿。

#### ③ 发给谁 —— 推荐：**按距离定向**

| 方案 | 评价 |
|---|---|
| a | 全服广播 —— ❌ 明确禁止（项目已有教训：`EnderChannelSyncPacket` 全服广播） |
| b | **只发半径内的在线玩家** ⭐（建议 **R = 32**，与 `LivingToolRayRenderer.MAX_DISTANCE` 对齐） |

**客户端清理**：每次收到包就**整体替换**缓存 —— 不需要处理"移除"逻辑，
玩家走远 / 换维度 / 世界里已无活工具时收到空（或不发但下包覆盖）即自然清空。

### 12.3 客户端如何「自己推断」动画（`K31` 修订）

> 📌 用户口径：*"客户端只需要知道两点：1. 活工具正在回放记忆 2. 活工具闲着。
> 其它的动画都从客户端自己推断播放。"*

于是状态从"细分动作（DIG / STRIP / IDLE）"**收缩为 1 bit「忙 / 闲」**，
`K31=a` 那个 `LIVING_TOOL_ACTION` 组件**直接省掉**。

客户端拿到的只有「忙 / 闲」，剩下的**全部本地推断**：

| 客户端观测 | 推断 | 播放 |
|---|---|---|
| 忙 + **该位置有破坏裂纹** | 在**挖掘**（裂纹由原版 `destroyBlockProgress` 广播，**零同步**） | 挖掘挥动，节奏跟**裂纹档位**（`K31=c`） |
| 忙 + 无裂纹 | 在**交互**（去皮 / 耕地这类一次性动作） | 一次性交互动作 |
| 闲 | 待机 | 转圈 / 摇晃 / 浮动 |

**朝向**也不需要同步：客户端自己读记忆的 `offset` 向量即可（它本来就同步着）。

⭐ **结论**：动画这一层**只剩 1 bit 需要同步**，其余全是客户端本地计算 + 原版已有广播。

### 12.4 包格式（草案）

```
varint count
for each:
    BlockPos pos            (BlockPos.STREAM_CODEC)
    varint toolCount
    for each:
        ItemStack stack     (ItemStack.STREAM_CODEC, 需 RegistryFriendlyByteBuf)
        byte state          // 0 = 空闲, 1 = 工作中（见 §12.3）
```

复用 `LivingItemSyncPacket` 已有的写法：`StreamCodec<FriendlyByteBuf, ...>` 里
cast 到 `RegistryFriendlyByteBuf`（`ItemStack.STREAM_CODEC` 需要注册表访问）。

> ⚠️ 注意 `ItemStack` **仍然要同步** —— 它不是为了动画，而是为了
> ① **画记忆射线**（需要 `LIVING_TOOL_MEMORY`）② **渲染悬浮模型**（需要物品本体）。
> 用户这次简化的是**动画状态**，不是这两项。

### 12.4 文件清单（草案）

| 文件 | 职责 |
|---|---|
| `network/LivingToolHostPacket.java` | 新 S2C 包 |
| `domain/tools/LivingToolHostSync.java` | 服务端：收集活跃容器 + 节流 + 定向广播 |
| `domain/tools/LivingToolHostClientCache.java` | 客户端缓存（整体替换） |
| `client/render/LivingToolRayRenderer.java` | 新增容器形态的读取分支 |
| `LivingItem.java` | 注册包 + 每 tick 调用广播 |

> 命名待定：`Host` / `Presence` / `Float` 均可，先不定死。

### 12.5 边界情况

| 情况 | 处理 |
|---|---|
| **大箱子两格** | 同步 `getBlockPos()`（第一格）即可；渲染若需两格可后补 `getAssociatedBlockPositions()` |
| **跨维度** | 每玩家用他**所在 level** 的列表，包里不必带维度 |
| **容器被破坏** | 下一轮收集时条目消失 → 该玩家签名变化 → 发新包 → 客户端整体替换 ✅ |
| **容器内容变了**（耐久 / 记忆） | 每轮收集的都是**当前** ItemStack → 签名变化 → 自动发 ✅ |
| **玩家刚进维度** | 首轮必发（签名从"无"变成有）✅ |

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
| **世界空间渲染物品模型**（`K` 组直接用） | `libs/src/StarMeowCraft-master/.../client/renderer/ThrownSwordRenderer.java` |
| **「吞噬 + 召唤」跨模组武器兼容** | `libs/src/StarMeowCraft-master/.../items/DevourSword.java`、`helper/EntityHelper.java` |
