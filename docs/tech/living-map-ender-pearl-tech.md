# Living Map & Living Ender Pearl (活地图 & 活末影珍珠) 技术文档

> **文档版本**: 2026.08 v42  
> **最后更新**: 2026-08-14  
> **适用版本**: Minecraft 1.21.1

## 目录
1. [架构概览](#1-架构概览)
2. [活末影珍珠：数据与冷却](#2-活末影珍珠数据与冷却)
3. [活地图：视角→目标映射算法](#3-活地图视角目标映射算法)
4. [活地图：传送执行](#4-活地图传送执行)
5. [活地图：客户端标记渲染](#5-活地图客户端标记渲染)
6. [元数据同步：LivingMapMetadataPacket](#6-元数据同步livingmapmetadatapacket)
7. [客户端数据不可靠问题与解决方案](#7-客户端数据不可靠问题与解决方案)
8. [展示框地图传送](#8-展示框地图传送)
9. [展示框地图：客户端光标渲染](#9-展示框地图客户端光标渲染)
10. [活空地图：远程开图](#10-活空地图远程开图)
11. [骑乘传送与 Sable 飞艇兼容](#11-骑乘传送与-sable-飞艇兼容)
12. [关键类和职责](#12-关键类和职责)
13. [已知问题与修复记录](#13-已知问题与修复记录)

---

## 1. 架构概览

### 1.1 什么是活地图传送？

活地图传送是一种**跨维度传送机制**：玩家手持活地图（Living Map）时，根据视角方向在地图上标记目标位置，右键消耗活末影珍珠（Living Ender Pearl）传送到该位置。

两个活物品协同工作：
- **活地图**（`minecraft:filled_map` + 活物品标记）：提供地图坐标系和目标计算
- **活末影珍珠**（`minecraft:ender_pearl` + 活物品标记）：提供传送能力和冷却机制

### 1.2 数据流总览图

```
┌─────────────────────────────────────────────────────────────────────┐
│                     活地图传送 完整数据流                             │
│                                                                     │
│  服务器端（权威数据源）                                               │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  MapItemSavedData（存档NBT）                                 │   │
│  │    centerX = 1024          ← 地图中心X（世界坐标）            │   │
│  │    centerZ = 512           ← 地图中心Z（世界坐标）            │   │
│  │    dimension = overworld   ← 地图维度                        │   │
│  │    scale = 0               ← 缩放等级                        │   │
│  │    colors[16384]           ← 地形颜色数据                    │   │
│  └─────────────────────────────────────────────────────────────┘   │
│          │                              │                           │
│          │ 元数据同步包                  │ 右键传送                  │
│          ▼                              ▼                           │
│  ┌──────────────────┐         ┌──────────────────────────┐        │
│  │ LivingMapMetadata│         │ LivingMapEventHandler    │        │
│  │ Packet (每秒1次)  │         │ .onRightClickItem        │        │
│  │ mapId, centerX,  │         │                          │        │
│  │ centerZ, dimKey  │         │ 1. 检查活地图+活珍珠      │        │
│  └──────────────────┘         │ 2. getTargetFromYawPitch │        │
│          │                    │ 3. MapTeleportExecutor    │        │
│          │                    │    → 旗帜/宝藏/已探索     │        │
│          ▼                    │    → TeleportHelper传送   │        │
│  客户端                        └──────────────────────────┘        │
│  ┌──────────────────┐                                             │
│  │ LivingMapClient  │                                             │
│  │ Cache            │                                             │
│  │ mapId → {centerX,│                                             │
│  │  centerZ, dim}   │                                             │
│  └──────────────────┘                                             │
│          │                                                         │
│          ▼                                                         │
│  ┌──────────────────────────────────────────────────────────┐     │
│  │ ItemInHandRendererMixin（每帧渲染）                        │     │
│  │                                                          │     │
│  │ 1. 从缓存获取 centerX/centerZ/dimension                  │     │
│  │ 2. 用本地 yaw/pitch 计算目标像素坐标                      │     │
│  │ 3. 在3D空间中渲染十字准星标记                              │     │
│  └──────────────────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.3 核心设计原则

**服务器提供"地图信息"，客户端提供"视角信息"，两者在客户端合在一起算出标记位置。**

- 服务器端持有地图的真实元数据（centerX/centerZ/dimension），通过自定义网络包同步给客户端
- 客户端持有玩家的实时视角（yaw/pitch），每帧本地计算标记位置，零延迟
- 传送逻辑完全在服务器端执行，客户端无法伪造传送目标

---

## 2. 活末影珍珠：冷却机制

### 2.1 使用原版冷却系统

活末影珍珠使用 Minecraft 原版的 `player.getCooldowns()` 冷却系统，而非自定义 DataComponent。

```java
// 检查冷却
player.getCooldowns().isOnCooldown(Items.ENDER_PEARL)

// 设置冷却
player.getCooldowns().addCooldown(Items.ENDER_PEARL, 40)
```

**为什么用原版而非自定义？**

| 对比项 | 自定义 DataComponent | 原版 ItemCooldowns |
|--------|---------------------|-------------------|
| 冷却存储 | 每个物品栈独立 | 按物品类型共享 |
| tick 逻辑 | 需手动 tick + syncSlotToClients | `Player.tick()` 自动调用 |
| 客户端显示 | 需自定义 tooltip | 原版扫光动画（sweep）自动显示 |
| 跨栈冷却 | ❌ 每个珍珠独立冷却 | ✅ 所有末影珍珠共享冷却 |
| 代码量 | DataComponent + tick + tooltip + sync | 2 行调用 |

原版冷却按 `Items.ENDER_PEARL` 类型，活珍珠和普通珍珠共享冷却，防止绕过冷却。

### 2.2 传送消耗

| 模式 | 已探索区域 | 未探索区域 |
|------|-----------|-----------|
| 生存模式 | 消耗 1 个活末影珍珠（`shrink(1)`） | 消耗 16 个活末影珍珠（从整个背包凑齐，`consumeFromInventory`） |
| 创造模式 | 不消耗 | 不消耗 |

---

## 3. 活地图：视角→目标映射算法

### 3.1 核心公式

将玩家的 yaw（水平朝向）和 pitch（俯仰角度）映射到地图上的目标像素坐标：

```
方向向量：
  dx = -sin(yaw)
  dz = cos(yaw)

原点选择：
  玩家在地图内 + 同维度 → 原点 = 玩家位置
  否则                  → 原点 = 地图中心

最大距离：
  maxDist = calcMaxDistToMapEdge(原点, 方向, 地图边界)

实际距离：
  distance = ((90° - pitch) / 90°) × maxDist
  distance = clamp(distance, 0, maxDist)

目标世界坐标：
  targetX = originX + dx × distance
  targetZ = originZ + dz × distance

目标像素坐标：
  mapX = (targetX - centerX) / scale + 64
  mapY = (targetZ - centerZ) / scale + 64
```

### 3.2 Pitch 映射关系

| pitch | 含义 | 距离 |
|-------|------|------|
| 90° | 低头看脚下 | 0（标记在原点） |
| 45° | 斜看 | maxDist / 2 |
| 0° | 平视 | maxDist（标记在地图边缘） |

低头→标记近，平视→标记远，符合直觉。

### 3.3 原点选择逻辑

```
玩家在地图内？
  ┌─ 是（playerMapX ∈ [0,128) 且 playerMapY ∈ [0,128)）
  │   且 同维度？
  │   ┌─ 是 → 原点 = 玩家位置（以玩家箭头为零点）
  │   └─ 否 → 原点 = 地图中心
  └─ 否 → 原点 = 地图中心
```

**为什么区分？**

- 玩家在地图内时，标记以玩家箭头为起点，转动视角时标记自然跟随
- 玩家在地图外时（距离太远或跨维度），玩家箭头不在地图上，以地图中心为起点更合理

### 3.4 射线-矩形相交算法

`calcMaxDistToMapEdge` 计算从原点沿方向到地图矩形边界的最大距离：

```
地图边界：
  minX = centerX - 64 × scale
  maxX = centerX + 64 × scale
  minZ = centerZ - 64 × scale
  maxZ = centerZ + 64 × scale

对每条边界：
  如果 dx > 0 → 到右边界的距离 = (maxX - originX) / dx
  如果 dx < 0 → 到左边界的距离 = (minX - originX) / dx
  同理处理 dz

maxDist = 四条边界距离中的最小正值
```

这确保标记不会超出地图范围，且当原点不在地图中心时仍能覆盖全图。

---

## 4. 活地图：传送执行

### 4.1 传送流程

```
右键活地图
  │
  ├─ 检查：是否手持活地图？是否有活末影珍珠？
  │
  ├─ 计算：getTargetFromYawPitch → 目标像素坐标 + 世界坐标
  │
  ├─ 检查：目标是否在地图范围内 [0, 128)？
  │
  ├─ 委托：MapTeleportExecutor.execute()
  │   ├─ 旗帜命中？→ TeleportHelper.teleportToBanner（精确旗帜坐标，不走地表高度）
  │   ├─ 红色大叉叉命中？→ resolveTargetPointWorldPos → TeleportHelper.teleportToMapPosition
  │   ├─ 未探索？
  │   │   ├─ 创造模式 → TeleportHelper.teleportToMapPosition（免费传送）
  │   │   ├─ 生存模式 + 背包珍珠 ≥ 16 → TeleportHelper.teleportToMapPosition（消耗16个）
  │   │   └─ 生存模式 + 背包珍珠 < 16 → 提示"需要16个活末影珍珠"，返回失败
  │   └─ 已探索？→ TeleportHelper.teleportToMapPosition（消耗1个）
  │
  └─ TeleportHelper.executeTeleport（实际传送）
      ├─ 玩家在 Sable 飞艇上？
      │   ├─ 跨维度 → 拒绝，提示"权能不足"
      │   └─ 同维度 → ensureChunkLoaded + teleportSubLevel 瞬移飞艇
      ├─ 普通骑乘？
      │   ├─ 跨维度 → vehicle.changeDimension()（原版内部处理乘客传送和重新骑乘）
      │   └─ 同维度 → 乘客下车 → 传送坐骑 → 传送玩家+其他乘客 → 重新骑乘
      ├─ 无骑乘 → ensureChunkLoaded + player.changeDimension()
      ├─ 重置坠落距离
      ├─ 传送粒子效果（PORTAL）+ 音效（ENDERMAN_TELEPORT）
      ├─ 5点坠落伤害
      └─ 消耗珍珠 + 设置冷却（40 tick）
```

### 4.2 地面Y坐标

```java
private static int findSafeY(LevelChunk chunk, BlockPos pos) {
    return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX() & 15, pos.getZ() & 15) + 1;
}
```

直接从 `LevelChunk` 读取 `MOTION_BLOCKING` 高度图，+1 后即为玩家脚底应站的 Y 坐标。

**为什么直接从 LevelChunk 读取高度图**：`Level.getHeightmapPos()` 内部先调用 `hasChunk()` 检查区块是否已加载，如果 `hasChunk()` 返回 `false`，直接返回 `getMinBuildHeight()`（主世界 -64，即基岩层）。而 `ensureChunkLoaded` 通过 `level.getChunk()` 加载区块时，内部添加的是 `TicketType.UNKNOWN` 类型的 ticket，其超时时间仅 1 tick。在 `ServerChunkCache.tick()` 的 `purgeStaleTickets()` 中，这个 ticket 会被立即清理。如果区块没有其他 ticket 保持加载（如玩家附近的 `PLAYER` ticket），`hasChunk()` 就会返回 `false`，导致高度图返回 -64。直接从 `LevelChunk` 对象读取高度图可以绕过 `hasChunk()` 检查，因为 `ensureChunkLoaded` 返回的 `LevelChunk` 已经包含了正确的高度图数据。

**为什么不需要扫描逻辑**：高度图数据本身是正确的——主世界返回地表 Y，地狱返回基岩天花板 Y（传送到天花板上方是可接受的，与原版末影珍珠行为一致）。之前出现传送到基岩层的问题，根因不是高度图数据错误，而是 `hasChunk()` 前置检查失败导致返回了兜底值 -64。

### 4.3 传送优先级

传送时按以下优先级依次检测：

```
1. 旗帜命中（5像素半径内）
   → 传送到旗帜精确位置（bannerPos.getY() + 1.0），不走地表高度
   → 旗帜本身需要两格空间，位置安全
   → 消耗1个活末影珍珠（生存模式）

2. 可传送图标命中（5像素半径内）
   → 支持33种图标类型（详见下方"可传送图标类型"）
   → 优先从 MAP_DECORATIONS 组件读取精确世界坐标
   → 组件缺失时回退到像素→世界坐标转换
   → 传送到目标位置的地表高度
   → 消耗1个活末影珍珠（生存模式）

3. 已探索区域
   → 传送到目标位置的地表高度
   → 消耗1个活末影珍珠（生存模式）

4. 未探索区域
   → 创造模式：免费传送
   → 生存模式 + 背包珍珠总数 ≥ 16：消耗16个活末影珍珠（从整个背包凑齐），传送到目标位置
   → 生存模式 + 背包珍珠总数 < 16：拒绝传送，提示"此区域尚未探索，需要一组（16个）活末影珍珠才能传送"
```

**可传送图标类型（33种）**：

| 类别 | 图标类型 | 传送目标 | 获取方式 |
|------|---------|---------|---------|
| 玩家 | `player` | 该玩家实时位置 | 地图范围内其他玩家自动出现 |
| 物品帧 | `frame` | 物品帧方块位置 | 地图放入物品帧自动出现 |
| 自定义标记 | `red_marker`, `blue_marker` | 命令设置的固定坐标 | `/give` + 数据包 |
| 目标标记 | `target_x`, `target_point`, `red_x` | 命令/DataComponent设置的坐标 | 沉船宝箱、命令、数据包 |
| 结构 | `mansion`, `monument`, `jungle_temple`, `swamp_hut`, `trial_chambers` | 结构生成位置 | 制图师村民交易 |
| 村庄 | `village_desert`, `village_plains`, `village_savanna`, `village_snowy`, `village_taiga` | 村庄生成位置 | 制图师村民交易 |
| 旗帜 | `banner_white` ~ `banner_black`（16色） | 旗帜方块精确 BlockPos | 地图范围内放置旗帜自动出现 |

**不可传送图标（2种）**：`player_off_map`、`player_off_limits`——坐标被截断到地图边缘（±128/127），不是真实位置。

**未探索区域传送的设计意图**：活空地图远程开图创建的地图，其目标区域在地图上是未探索的。消耗一组珍珠作为"强行撕裂空间"的代价，既保留了传送能力，又设置了合理的门槛。消耗时从整个背包凑齐16个（`LivingEnderPearlFunction.consumeFromInventory`），不要求单个栈满16。

### 4.4 三个传送场景

三个场景的传送优先级和消耗逻辑共享（最终都调用 `MapTeleportExecutor.execute()`），但**珍珠查找方式不同**：

| 场景 | 触发方式 | 目标计算 | 珍珠查找 | 入口类 |
|------|---------|---------|---------|--------|
| 手持传送 | 右键活地图 | 视角（yaw/pitch）→射线→像素坐标 | 整个背包 `findInInventory` | `LivingMapEventHandler` |
| 展示框传送 | 右键展示框上的活地图 | hitVec→UV→像素坐标 | 主/副手 `resolvePearlStack` | `ItemFrameMapTeleportHandler` |
| GUI传送 | 右键扩展地图/单个活地图 | 鼠标坐标→UV→像素坐标 | 主/副手 `resolvePearlStack` | `AbstractContainerScreenMixin` → `LivingMapGuiTeleportPacket` |

**为什么查找方式不同**：

- **手持传送**：玩家手持活地图，珍珠只能在背包里，所以遍历整个背包查找
- **展示框传送**：玩家手持活末影珍珠右键展示框，珍珠必须在手上
- **GUI传送**：玩家手持活末影珍珠右键地图，珍珠必须在手上

**消耗差异**：

| 消耗场景 | 手持传送 | 展示框/GUI传送 |
|---------|---------|--------------|
| 已探索 | 从找到的第一个栈 `shrink(1)` | 从主/副手栈 `shrink(1)` |
| 未探索 | `consumeFromInventory(player, 16)` 从整个背包凑 | `consumeFromInventory(player, 16)` 从整个背包凑 |
| 未探索判断 | `countInInventory(player) >= 16` | `countInInventory(player) >= 16` |

### 4.5 旗帜传送

旗帜传送使用 `MapBanner.pos()` 获取精确世界坐标，直接传送到旗帜所在位置（`bannerPos.getY() + 1.0`），不走 `findSafeY` 地表高度。这样旗帜在地底等位置时也能精准到达。

### 4.6 可传送图标传送

除旗帜外，所有可传送图标（33种中的非旗帜31种）通过 `MapCoordHelper.findTargetPointHit()` 检测，传送坐标获取分两步：

1. **优先路径**：从物品的 `DataComponents.MAP_DECORATIONS` 组件读取匹配条目的精确世界坐标（`entry.x()`, `entry.z()`）
2. **回退路径**：组件缺失时，从 `MapDecoration` 的像素坐标反推世界坐标（有缩放精度损失）

**图标判定逻辑**：`MapCoordHelper.isTeleportableType(decoration)` 检查 decoration 的注册名路径是否在 `TELEPORTABLE_DECORATION_PATHS` 常量集中。该集合包含 33 个路径字符串，排除了 `player_off_map` 和 `player_off_limits`（坐标被截断到地图边缘，不是真实位置）。

> **注意**：`red_x` 等图标的 `explorationMapElement()` 返回 `false`，不能用此方法过滤。必须使用注册名精确匹配。

### 4.7 传送参数

| 参数 | 值 | 说明 |
|------|-----|------|
| 冷却 | 40 tick (2秒) | 防止频繁传送 |
| 坠落伤害 | 5.0 | 模拟末影珍珠伤害 |
| 珍珠消耗（已探索） | 1个 | 生存模式消耗，创造模式不消耗 |
| 珍珠消耗（未探索） | 16个（从整个背包凑齐） | 生存模式从背包凑齐16个，创造模式不消耗 |
| 粒子效果 | PORTAL | 传送点 + 出发点（跨维度时） |
| 音效 | ENDERMAN_TELEPORT | 传送点 |

---

## 5. 活地图：客户端标记渲染

### 5.1 渲染方式

通过 Mixin 注入到 `ItemInHandRenderer.renderMap()` 方法的 TAIL，在3D空间中渲染标记。

**为什么用 Mixin 而不是 GUI Overlay？**

GUI Overlay 在屏幕2D空间渲染，而地图在3D空间渲染（有透视变换）。两者的坐标系不匹配，导致标记位置偏移。Mixin 注入到 `renderMap` 可以复用相同的 `PoseStack`，在3D空间中精确对齐。

### 5.2 标记样式

使用原版准心纹理（`minecraft:hud/crosshair`）通过 `GuiSpriteManager` 获取 sprite，以 `RenderType.text()` 渲染到地图上。

**颜色三档**：

| 颜色 | 条件 | 说明 |
|------|------|------|
| 🟠 橙色 `0xFFFFAA00` | 准心命中任意可传送图标 | 旗帜/结构/村庄/红X/玩家等 |
| 🟢 绿色 `0xFF00FF00` | 已探索区域 | 可传送 |
| 🔴 红色 `0xFFFF3333` | 未探索区域 | 不可传送 |

命中可传送图标时，准心稍大（halfSize = 5.0 vs 4.0），提供视觉"锁定"反馈。

**渲染参数**：
- 纹理来源：`GuiSpriteManager.getSprite("minecraft:hud/crosshair")`
- 渲染类型：`RenderType.text(sprite.atlasLocation())`
- 顶点格式：`addVertex().setColor(r,g,b,a).setUv(u,v).setLight(packedLight)`
- 渲染层级：z = -0.03（略低于地图纹理，避免 z-fighting）

### 5.3 客户端图标检测

客户端无法使用 `mapData.getBanners()`（数据不传输），改用 `mapData.getDecorations()` 检测：

| 检测方法 | 数据来源 | 过滤条件 | 用途 |
|---------|---------|---------|------|
| `isTeleportableDecorationHit` | `getDecorations()` | 注册名在 `TELEPORTABLE_DECORATION_PATHS` 中 | 客户端准心变色 |
| `findTargetPointHit` | `getDecorations()` | 同上，返回5像素半径内最近的匹配 decoration | 服务端传送 |
| `findBannerHit` | `getBanners()` | 遍历所有 MapBanner | 服务端传送（精确世界坐标，优先级最高） |

**`TELEPORTABLE_DECORATION_PATHS` 常量集**：包含 33 个注册名路径字符串，覆盖所有有真实世界坐标的图标类型。排除了 `player_off_map` 和 `player_off_limits`（坐标被截断到地图边缘）。

**旗帜注册名格式**：`minecraft:banner_<颜色>`（如 `banner_pink`、`banner_white`），注意是 `banner_` 前缀而非 `_banner` 后缀。

**客户端旗帜检测需要传入正确的 centerX/centerZ**：客户端 `mapData.centerX/centerZ` 为 0（不准确），必须使用 `LivingMapClientCache` 中的元数据。`findBannerHit` 提供了重载版本 `findBannerHit(mapData, mapX, mapY, centerX, centerZ)` 供客户端使用。

### 5.4 渲染代码结构

```java
@Inject(method = "renderMap", at = @At("TAIL"))
private void renderLivingMapTargetMarker(PoseStack poseStack, MultiBufferSource buffer, ...) {
    // 1. 检查是否为活地图（LivingItemManager.isLivingMap）
    // 2. 获取 MapItemSavedData
    // 3. 从 LivingMapClientCache 获取元数据
    // 4. MapCoordHelper.calcClientTarget → 目标像素坐标
    // 5. 检查是否在地图范围内
    // 6. MapCoordHelper.isTeleportableDecorationHit → 可传送图标命中检测
    // 7. MapCoordHelper.isExplored → 已探索检测
    // 8. LivingMapTargetRenderer.renderMarker → 原版准心纹理 + 三色着色
}
```

---

## 6. 元数据同步：LivingMapMetadataPacket

### 6.1 为什么需要自定义同步包？

Minecraft 原版的 `ClientboundMapItemDataPacket` 不包含三个关键字段：

| 字段 | 服务器端 | 网络传输 | 客户端 |
|------|---------|---------|--------|
| `dimension` | ✅ 准确 | ❌ 不传输 | ❌ 猜测 |
| `centerX` | ✅ 准确 | ❌ 不传输 | ❌ 硬编码0 |
| `centerZ` | ✅ 准确 | ❌ 不传输 | ❌ 硬编码0 |

客户端的 `MapItemSavedData` 在首次收到地图数据时通过 `createForClient()` 创建，`dimension` 被设为客户端当前维度（而非地图实际维度），`centerX/centerZ` 被硬编码为 0。后续的 `applyToMap()` 只更新颜色和装饰，不更新这三个字段。

### 6.2 数据包结构

```java
// LivingMapMetadataPacket.java
public record LivingMapMetadataPacket(
    int mapId,        // 地图ID
    int centerX,      // 地图中心X（世界坐标）
    int centerZ,      // 地图中心Z（世界坐标）
    String dimensionKey  // 维度Key（如 "minecraft:overworld"）
) implements CustomPacketPayload
```

### 6.3 发送时机

```java
// LivingMapEventHandler.onPlayerTick()
// 每秒1次（player.tickCount % 20 == 0）
// 仅当玩家手持活地图时发送
```

低频同步（每秒1次），流量极小。客户端收到后缓存到 `LivingMapClientCache`，每帧渲染时直接读取缓存，无网络延迟。

### 6.4 客户端缓存

```java
// LivingMapClientCache.java
private static final Map<Integer, MapMetadata> cache = new ConcurrentHashMap<>();

public record MapMetadata(int centerX, int centerZ, ResourceKey<Level> dimension) {}

// 更新（来自网络包）
public static void update(int mapId, int centerX, int centerZ, String dimensionKey)

// 查询（来自渲染器，每帧调用）
public static MapMetadata get(int mapId)
```

按 mapId 索引，支持多张地图同时缓存。使用 `ConcurrentHashMap` 保证线程安全。

---

## 7. 客户端数据不可靠问题与解决方案

### 7.1 问题根源

`ClientPacketListener.handleMapItemData()` 中：

```java
if (mapitemsaveddata == null) {
    // 首次收到地图数据 → 创建客户端对象
    // dimension 用的是客户端当前维度，不是地图真实维度！
    mapitemsaveddata = MapItemSavedData.createForClient(
        packet.scale(), packet.locked(),
        this.minecraft.level.dimension()  // ← 猜的
    );
    // centerX = 0, centerZ = 0  ← 硬编码
}
// 后续只更新颜色和装饰，不更新 dimension/centerX/centerZ
packet.applyToMap(mapitemsaveddata);
```

此外，`ClientboundMapItemDataPacket` 只传输 `decorations`（`MapDecoration` 列表），**不传输 `banners`**（`MapBanner` 列表）。所以客户端的 `mapData.getBanners()` 永远为空，客户端旗帜检测必须使用 `getDecorations()` 并通过注册名过滤。

### 7.2 解决方案演进

| 版本 | 方案 | 问题 |
|------|------|------|
| v1 | 直接用 `mapData.centerX/centerZ` | 值为0，标记位置完全错误 |
| v2 | `calculateMapCenterCoord` 反算中心 | 维度判断不可靠，跨维度时坐标无意义 |
| v3 | 自定义网络包同步元数据 | ✅ 准确可靠 |

### 7.3 维度判断的重要性

玩家和地图可能不在同一维度（如在下界打开主世界地图）。此时：
- 下界坐标与主世界坐标是不同的物理空间，直接相减无意义
- 原版地图只在同维度时显示玩家箭头（`tickCarriedBy` 中 `dimension == this.dimension` 才添加 PLAYER 装饰）
- 我们的标记计算也必须区分：同维度用玩家位置作原点，跨维度用地图中心作原点

---

## 8. 展示框地图传送

### 8.1 功能概述

玩家手持活末影珍珠，右键展示框中的活地图，传送到射线指向的地图区域。与手持地图传送不同，展示框传送通过射线与展示框碰撞箱的交点（hitVec）计算目标位置，而非玩家视角。

### 8.2 事件拦截

使用 NeoForge 的 `PlayerInteractEvent.EntityInteractSpecific` 事件，而非 `PlayerInteractEvent.EntityInteract`：

| 事件 | hitVec | 用途 |
|------|--------|------|
| `EntityInteractSpecific` | ✅ 精确的射线-碰撞箱交点 | 展示框传送（需要精确点击位置） |
| `EntityInteract` | ❌ 不提供 hitVec | 不适用 |

事件提供 `getLocalPos()` 返回相对于实体位置的局部坐标，需加上实体位置转为世界坐标：

```java
Vec3 localPos = event.getLocalPos();
Vec3 worldHit = new Vec3(
    frame.getX() + localPos.x,
    frame.getY() + localPos.y,
    frame.getZ() + localPos.z
);
```

### 8.3 hitVec → 地图像素坐标

核心转换链：`hitVec → UV → 地图像素`

#### 8.3.1 hitVec → UV

`MapCoordHelper.hitVecToMapPixel` 根据展示框朝向（facing）计算 UV：

```java
// U 轴（水平方向）
double u = switch (facing) {
    case NORTH -> hitVec.x - framePos.getX();
    case SOUTH -> 1 - (hitVec.x - framePos.getX());
    case EAST  -> hitVec.z - framePos.getZ();
    case WEST  -> 1 - (hitVec.z - framePos.getZ());
    case DOWN  -> hitVec.x - framePos.getX();
    case UP    -> hitVec.x - framePos.getX();
};

// V 轴（垂直方向）
double v = switch (facing) {
    case NORTH -> 1 - (hitVec.y - framePos.getY());
    case SOUTH -> 1 - (hitVec.y - framePos.getY());
    case EAST  -> 1 - (hitVec.y - framePos.getY());
    case WEST  -> 1 - (hitVec.y - framePos.getY());
    case DOWN  -> hitVec.z - framePos.getZ();
    case UP    -> 1 - (hitVec.z - framePos.getZ());
};
```

#### 8.3.2 朝向修正

展示框的 hitVec 坐标系与地图纹理坐标系存在差异，需要根据朝向翻转：

```java
if (facing == Direction.DOWN || facing == Direction.UP) {
    v = 1 - v;    // 地板/天花板：翻转V轴
} else {
    u = 1 - u;    // 墙壁：翻转U轴
}
```

| 朝向 | U轴修正 | V轴修正 | 原因 |
|------|---------|---------|------|
| 墙壁（N/S/E/W） | 翻转 | 不翻转 | hitVec的X轴方向与地图U轴相反 |
| 地板/天花板（DOWN/UP） | 不翻转 | 翻转 | hitVec的Z轴方向与地图V轴相反 |

#### 8.3.3 旋转修正

`frame.getRotation()` 返回**持续递增**的整数（0, 1, 2, 3, 4, 5...），不是 0-3 循环。必须取模：

```java
int rot = rotation % 4;

double rotatedU = switch (rot) {
    case 1 -> v;
    case 2 -> 1 - u;
    case 3 -> 1 - v;
    default -> u;
};

double rotatedV = switch (rot) {
    case 1 -> 1 - u;
    case 2 -> 1 - v;
    case 3 -> u;
    default -> v;
};
```

| 旋转角度 | 旋转次数 | rotation值 | rot | UV变换 |
|---------|---------|-----------|-----|--------|
| 0° | 0次 | 0 | 0 | (u, v) |
| 90° | 1次 | 1 | 1 | (v, 1-u) |
| 180° | 2次 | 2 | 2 | (1-u, 1-v) |
| 270° | 3次 | 3 | 3 | (1-v, u) |
| 0° | 4次 | 4 | 0 | (u, v) ← 取模后正确 |

#### 8.3.4 UV → 像素坐标

地图纹理铺满整个方块面（1×1），没有边框偏移。UV 范围 [0, 1] 直接映射到像素 [0, 128]：

```java
int mapX = (int) (u * 128);
int mapY = (int) (v * 128);
```

### 8.4 传送流程

```
右键展示框中的活地图（手持活末影珍珠）
  │
  ├─ 检查：展示框内是活地图？手持活末影珍珠？冷却中？
  │
  ├─ 获取：MapId → MapItemSavedData → 目标维度
  │
  ├─ 计算：hitVec → hitVecToMapPixel → UV → 像素坐标
  │
  ├─ 检查：UV 是否在 [0, 1] 范围内？像素是否在 [0, 128) 范围内？
  │
  ├─ 委托：MapTeleportExecutor.execute()
  │   ├─ 旗帜命中？→ TeleportHelper.teleportToBanner
  │   ├─ 红色大叉叉命中？→ TeleportHelper.teleportToMapPosition
  │   ├─ 未探索？→ 提示"未探索区域"，返回失败
  │   └─ 已探索？→ TeleportHelper.teleportToMapPosition
  │
  └─ 始终取消事件（无论传送成功与否，防止展示框交互旋转地图）
```

### 8.5 安全物品返回

传送会导致容器菜单关闭，如果玩家在容器界面中操作（如整理物品），光标上可能持有物品。传送后玩家已离开，光标物品会被丢弃到旧位置导致丢失。

```java
// 传送前执行
private static void safeReturnCarried(ServerPlayer player) {
    ItemStack carried = player.containerMenu.getCarried();
    if (carried.isEmpty()) return;
    player.containerMenu.setCarried(ItemStack.EMPTY);
    if (!player.getInventory().add(carried)) {
        player.drop(carried, false);
    }
}
```

优先放回背包，背包满则掉落至玩家脚下，确保物品不丢失。

### 8.6 framePos 获取方式

使用 `frame.blockPosition()` 而非 `frame.getOnPos()`：

| 方法 | 返回值 | 用途 |
|------|--------|------|
| `blockPosition()` | 展示框所在的方块坐标 | ✅ UV计算需要 |
| `getOnPos()` | 展示框"站立"的方块坐标 | ❌ 对墙壁展示框返回脚下方块 |

---

## 9. 展示框地图：客户端光标渲染

### 9.1 渲染方式

通过 Mixin 注入到 `MapRenderer.render()` 方法的 TAIL，在地图纹理渲染完成后叠加光标标记。

**为什么注入 MapRenderer 而非 ItemFrameRenderer？**

- `MapRenderer.render()` 的 PoseStack 处于 128×128 像素坐标系中，和手持地图渲染完全一致
- 不需要处理展示框朝向/旋转的3D反算
- 渲染代码可完全复用 `ItemInHandRendererMixin` 的逻辑

### 9.2 上下文区分

`MapRenderer.render()` 被手持地图和展示框共用。Mixin 通过 `Minecraft.hitResult` 区分：

```java
// 只在注视展示框时渲染光标
if (!(mc.hitResult instanceof EntityHitResult hitResult)) return;
if (!(hitResult.getEntity() instanceof ItemFrame frame)) return;
if (!isLivingMap(frame.getItem())) return;

// 确认是同一张地图
MapId frameMapId = frame.getItem().get(DataComponents.MAP_ID);
if (frameMapId == null || frameMapId.id() != mapId.id()) return;
```

当玩家手持地图时，`hitResult` 通常是 `BlockHitResult`，Mixin 自动跳过，不会误渲染。

### 9.3 光标位置计算

与手持地图不同，展示框光标位置来自射线命中点而非玩家视角：

```java
Vec3 hitVec = hitResult.getLocation();
MapCoordHelper.HitResult uv = MapCoordHelper.hitVecToMapPixel(
    hitVec, frame.blockPosition(), frame.getDirection(), frame.getRotation());
int mapX = MapCoordHelper.uvToMapX(uv.u());
int mapY = MapCoordHelper.uvToMapY(uv.v());
```

### 9.4 光标样式

与手持地图标记完全一致：原版准心纹理 + 三色着色 + z=-0.03 偏移。

| 颜色 | 条件 | 说明 |
|------|------|------|
| 🟠 橙色 | 命中任意可传送图标 | 旗帜/结构/村庄/红X/玩家等 |
| 🟢 绿色 | 已探索区域 | 可传送 |
| 🔴 红色 | 未探索区域 | 不可传送 |

### 9.5 渲染代码结构

```java
@Mixin(MapRenderer.class)
public class MapRendererMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void renderItemFrameCursor(PoseStack poseStack, MultiBufferSource buffer,
            MapId mapId, MapItemSavedData mapData, boolean active, int packedLight, CallbackInfo ci) {
        // 1. 检查：玩家手持活末影珍珠？
        // 2. 检查：注视的是活地图展示框？
        // 3. 确认：展示框地图ID == 当前渲染地图ID？
        // 4. hitVec → hitVecToMapPixel → 像素坐标
        // 5. 像素范围检查 [0, 128)
        // 6. 可传送图标/已探索 命中检测
        // 7. renderMarker → 原版准心纹理 + 三色着色
    }
}
```

---

## 10. 活空地图：远程开图

### 10.1 功能概述

空地图（`minecraft:map`，即 `EmptyMapItem`）活化后变活空地图。右键使用时，不是在玩家当前位置开图，而是在玩家**朝向方向的远程位置**创建活地图。距离由堆叠数量决定。

### 10.2 距离公式

```
distance = 128 × n²  （格/blocks）

其中 n = 堆叠数量
```

| 堆叠数量 | 距离（格） | 约等于 |
|---------|-----------|--------|
| 1 | 128 | 1张地图 |
| 2 | 512 | 4张地图 |
| 4 | 2,048 | 16张地图 |
| 8 | 8,192 | 64张地图 |
| 16 | 32,768 | 256张地图 |
| 32 | 131,072 | 约130km |
| 64 | 524,288 | 约524km |

少量堆叠就近处探索，大量堆叠可远距离开图。

**斜向距离说明**：`128 × n²` 是直线距离（欧几里得距离），与朝向角度无关。斜向时距离分摊到两个轴上，单轴偏移量会缩短：

```
X方向偏移 = 直线距离 × |sin(yaw)|
Z方向偏移 = 直线距离 × |cos(yaw)|
```

以 n=32（直线距离 ≈ 131km）为例：

| 朝向 | yaw | X偏移 | Z偏移 | 直线距离 |
|------|-----|-------|-------|---------|
| 北 | 0° | 0 | 131km | 131km |
| 东北 | 45° | 93km | 93km | 131km |
| 东 | 90° | 131km | 0 | 131km |

斜向时单轴偏移约为直线距离的 0.707 倍（`1/√2`），但直线距离始终等于 `128 × n²`。`calculateMapCenterCoord` 的网格对齐误差最多 ±64 格，对远距离开图可忽略。

**pitch（俯仰角）不影响远程开图距离**，距离只由堆叠数量决定。这与活地图传送不同——传送中 pitch 控制标记远近（低头近、平视远）。

### 10.3 目标位置计算

```java
// 方向向量（基于玩家 yaw）
double dx = -Math.sin(Math.toRadians(yaw));
double dz =  Math.cos(Math.toRadians(yaw));

// 目标世界坐标
double targetX = player.getX() + dx * distance;
double targetZ = player.getZ() + dz * distance;

// 对齐到地图网格（scale = 0 时网格间距 128 格）
int centerX = calculateMapCenterCoord(targetX, 0);
int centerZ = calculateMapCenterCoord(targetZ, 0);
```

`calculateMapCenterCoord` 确保地图中心对齐到 128 格的整数倍网格，与原版 `MapItemSavedData.createFresh` 的行为一致。

### 10.4 Mixin 实现

通过 Mixin 注入 `EmptyMapItem.use()` 方法，在 HEAD 处拦截：

```java
@Mixin(EmptyMapItem.class)
public class MapItemMixin {
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void onUse(Level level, Player player, InteractionHand hand,
                       CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        // 1. 仅拦截活空地图
        if (!LivingItemManager.isLivingItem(stack)) return;

        // 2. 计算远程目标位置
        // 3. 对齐到地图网格
        // 4. consume(1) 消耗1个空地图
        // 5. MapItem.create() 创建活地图
        // 6. LivingItemManager.setLiving(newMap, true) 标记为活地图
        // 7. 返回新地图
    }
}
```

### 10.5 与原版行为的差异

| 行为 | 原版空地图 | 活空地图 |
|------|-----------|---------|
| 开图位置 | 玩家当前位置 | 玩家朝向 × 距离 |
| 缩放等级 | 固定 scale=0 | 固定 scale=0 |
| 创建的地图 | 普通地图 | **活地图**（带 IS_LIVING 标记） |
| 消耗 | consume(1) | consume(1) |
| 不跨维度 | — | 仅当前维度 |

### 10.6 设计决策

**为什么用 n² 而非 n？**

线性增长（128n）最远仅 8km（64个堆叠），不够远距离探索。平方增长（128n²）在少量堆叠时仍可近处开图（1个=128格），大量堆叠时可达数百公里（64个=524km），兼顾实用性和探索感。

**为什么不支持缩放等级？**

原版空地图的缩放等级硬编码为 `(byte)0`（见 `EmptyMapItem.use()` 源码），没有 DataComponent 存储 scale。缩放升级需要制图台，升级后地图变为 `FILLED_MAP`，不再是空地图。因此活空地图固定 scale=0。

---

## 11. 骑乘传送与 Sable 飞艇兼容

### 11.1 骑乘传送

传送时根据玩家是否骑乘以及是否跨维度，采用不同策略：

| 场景 | 处理方式 |
|------|---------|
| 同维度 + 有坐骑 | `ensureChunkLoaded` + 下马 → `vehicle.teleportTo()` → `player.changeDimension()` → 其他 `ServerPlayer` 乘客 `connection.teleport()` → 重新骑乘 |
| 同维度 + 无坐骑 | `ensureChunkLoaded` + `player.changeDimension(transition)`（同维度分支调用 `connection.teleport()` 同步客户端位置） |
| 跨维度 + 有坐骑 | `vehicle.changeDimension(transition)`，原版内部自动处理乘客传送和重新骑乘 |
| 跨维度 + 无坐骑 | `player.changeDimension(transition)` 跨维度传送 |
| 同维度 + 骑乘飞艇坐垫 | `ensureChunkLoaded` + `ModSable.teleportSubLevel()` 传送整个飞艇，骑行系统自动同步位置 |
| 同维度 + 站在飞艇上 | 只传送玩家，清除 tracking 后走普通路径 |

**跨维度带坐骑的关键**：`Entity.changeDimension()` 内部已经处理了乘客的跨维度传送和重新骑乘（`unRide()` → 乘客递归 `changeDimension()` → 乘客 `startRiding(新载具)`），不需要外部手动管理。手动管理反而会导致乘客被 `changeDimension` 两次，产生重复实体。

**同维度远距离传送的关键**：`vehicle.teleportTo()` 的 `teleportPassengers()` 不会为 `ServerPlayer` 乘客发送 `ClientboundPlayerPositionPacket`，导致客户端位置不同步。因此同维度传送必须使用 `player.changeDimension()`（同维度分支内部调用 `connection.teleport()` 发送位置同步包），而非 `player.teleportTo()`。载具传送流程：先下马 → `vehicle.teleportTo()` 传送载具 → `player.changeDimension()` 传送玩家 → 其他 `ServerPlayer` 乘客 `connection.teleport()` 同步位置 → 重新骑乘。

### 11.2 Sable 飞艇传送

Sable（机械动力：航空学）的飞艇使用 SubLevel 投影机制：飞艇方块真实存储在世界极远处的 Plotyard，玩家看到的是通过 `logicalPose()` 投影的位置。

#### 11.2.1 同维度飞艇传送

**核心原则：移动飞艇投影 + 用 `setPos()` 同步玩家位置。**

Sable 有三个关键的 Mixin 机制：

1. **`teleport_players/ServerPlayerMixin`**：Wrap 了 `player.teleportTo(DDD)`，自动调用 `projectOutOfSubLevel()` 把投影坐标反算为 Plotyard 真实坐标。传入世界坐标会被当作投影坐标反算，玩家被传到几千万格外。
2. **`entities_stick_sublevels/ServerPlayerMixin`**：每 tick 根据 `logicalPose` 和 `lastPose` 的差值自动移动玩家，让玩家"粘"在 SubLevel 上。
3. **`plot/ClientChunkCacheMixin`**：拦截客户端区块卸载，Plot 区块不允许被卸载（抛出 `UnsupportedOperationException: Cannot drop chunks in plot`）。

因此，**绝对不能调用 `player.teleportTo()`**。必须用 `player.setPos()` 直接设置坐标（绕过 Mixin），否则会触发以下崩溃链：

```
只移动 logicalPose，不更新玩家位置
  → SubLevelTrackingSystem.shouldLoad() 判定玩家远离飞艇（距离超过追踪范围）
  → 发送 ClientboundForgetLevelChunkPacket 卸载区块
  → 客户端 ClientChunkCacheMixin 拒绝卸载 Plot 区块
  → UnsupportedOperationException: Cannot drop chunks in plot → 断开连接
```

**传送流程**：

```
1. 获取玩家所在的 SubLevel
2. 用世界坐标计算玩家相对飞艇原点的偏移 offset = playerPos - airshipPos
3. 计算新飞艇位置 newAirshipPos = destPos - offset
4. 保存玩家在飞艇局部坐标系中的位置 playerLocal = oldPose.transformPositionInverse(playerPos)
5. 直接设置 logicalPose（双保险，某些物理管线可能不会更新）
6. handle.teleport() 通知物理管线
7. 用新 logicalPose 把 playerLocal 投影回世界坐标 newPlayerPos = newPose.transformPosition(playerLocal)
8. player.setPos(newPlayerPos) 同步玩家位置（绕过 Sable Mixin）
9. updateLastPose() 同步上一 tick 姿态
```

**偏移计算**（世界坐标，适用于无旋转飞艇）：

```java
double offsetX = player.getX() - airshipPos.x();
double offsetY = player.getY() - airshipPos.y();
double offsetZ = player.getZ() - airshipPos.z();

double newAirshipX = destX - offsetX;
double newAirshipY = destY - offsetY;
double newAirshipZ = destZ - offsetZ;
```

**玩家位置同步**（局部坐标投影，适用于有旋转飞艇）：

```java
// 传送前：玩家在旧飞艇局部坐标系中的位置
Vector3d playerLocal = oldPose.transformPositionInverse(
    new Vector3d(player.getX(), player.getY(), player.getZ()));

// 传送后：用新飞艇的 logicalPose 把局部坐标投影回世界坐标
Vector3d newPlayerPos = newPose.transformPosition(playerLocal);
player.setPos(newPlayerPos.x(), newPlayerPos.y(), newPlayerPos.z());
```

**logicalPose 双保险**：参照 `SubLevelSerializer` 的模式，在调用 `handle.teleport()` 之前先直接设置 `logicalPose`。`StaticPhysicsPipeline.teleport()` 会更新 logicalPose，但其他物理管线实现可能不会。直接设置确保 `SubLevelTrackingSystem.shouldLoad()` 始终能正确判断玩家与飞艇的距离。

**为什么不能调用 `player.teleportTo()`**：

| 方法 | Sable Mixin 行为 | 后果 |
|------|-----------------|------|
| `player.teleportTo(ServerLevel, x, y, z, ...)` | 触发跨维度逻辑，stopRiding + 移动坐骑实体 | 把 SubLevel 的坐骑实体从 Plotyard 拉到目标坐标，破坏投影关系 |
| `player.teleportTo(double, double, double)` | WrapMethod 调用 `projectOutOfSubLevel()` 反投影 | 把世界坐标当投影坐标反算，玩家被传到几千万格外 |
| `player.setPos(double, double, double)` | 无 Mixin 拦截，直接设置坐标 | ✅ 正确：绕过所有 Sable Mixin |

**超远距离传送（>320格）"飞快移动"问题**：Sable 的 `entities_stick_sublevels` 系统根据 `logicalPose` 和 `lastPose` 的差值每 tick 平滑移动玩家。超远距离传送时，`logicalPose` 瞬间变化巨大，但 `lastPose` 仍是旧值，差值导致系统在接下来的 tick 中持续移动玩家，产生"缓动"效果。

**修复**：传送后同步网络姿态和速度，使网络系统认为飞艇已停止移动：

```java
serverSubLevel.lastNetworkedPose().set(serverSubLevel.logicalPose());
serverSubLevel.latestLinearVelocity.zero();
serverSubLevel.latestAngularVelocity.zero();
serverSubLevel.setLastNetworkedStopped(true);
```

**站在飞艇上虚空掉落问题**：站在飞艇上（非骑乘坐垫）传送后，客户端仍发送传送前旧坐标的移动包。`ServerboundMovePlayerPacketMixin` 中 `Sable.HELPER.getContaining(level, oldX, oldZ)` 返回 null（飞艇已传送），Mixin 清除 `trackingSubLevel`，玩家脱离飞艇掉入虚空。

**修复**：传送后发送 `ClientboundPlayerPositionPacket` 同步客户端位置，确保客户端后续移动包使用新坐标。

**关键**：必须发送**世界坐标**（`newPlayerPos`），而非 SubLevel 局部坐标（`localPos`）。因为 Sable 的 `LevelPlot`（地块）`plotPos` 固定，传送后 `getContaining(newPlayerPos)` 返回 null（玩家不在 SubLevel 的 plot 范围内），发送局部坐标会导致客户端渲染玩家在 0,0,0 附近，而非飞艇旁。`SubLevelEntityCollision` 基于 `logicalPose` 处理碰撞，玩家可正常站在飞艇方块上：

```java
player.connection.send(new ClientboundPlayerPositionPacket(
    newPlayerPos.x, newPlayerPos.y, newPlayerPos.z,
    player.getYRot(), player.getXRot(),
    Set.of(), -1
));
```

**站立和坐垫均支持**：`Sable.HELPER.getTrackingOrVehicleSubLevel(player)` 覆盖两种情况：

| 状态 | Sable 内部机制 | 返回值 |
|------|---------------|--------|
| 站在飞艇上 | `entities_stick_sublevels` tick 系统粘住玩家 | `getTrackingSubLevel()` |
| 坐在坐垫上 | 玩家骑乘的实体在 SubLevel 内 | `getVehicleSubLevel()` |

两种情况下 `player.getX/Y/Z()` 都是世界投影坐标，偏移计算和 `setPos()` 逻辑完全一致。

#### 11.2.2 跨维度飞艇传送（保守方案：拒绝）

由于以下技术限制，跨维度飞艇传送暂不实现：

1. **SubLevel 绑定 Level**：`logicalPose()` 的坐标是相对于其所属 `ServerLevel` 的，跨维度后坐标无意义
2. **追踪系统卸载**：`SubLevelTrackingSystem` 检测到玩家不在同维度时，会主动发送移除包，客户端飞艇渲染消失
3. **客户端渲染**：飞艇的投影渲染依赖客户端 SubLevel 的插值系统，维度切换时无法平滑过渡

当玩家骑乘飞艇尝试跨维度传送时，显示提示：

| 语言 | 文案 |
|------|------|
| 中文 | 跃迁权能不足，无法撕裂位面壁垒 |
| 英文 | Insufficient jump authority, cannot pierce the planar barrier |

翻译键：`chat.livingitem.ender_pearl.insufficient_authority`

### 11.3 Sable 软依赖架构

Sable 是可选依赖，未安装时所有飞艇传送逻辑自动跳过，不影响普通传送功能。采用三层架构：

```
TeleportHelper
    │
    └─ ModSable（安全调用入口）
        │
        ├─ SableCompat（依赖检测：ModList.isLoaded("sable")）
        │
        └─ SableIntegration（核心逻辑：直接引用 Sable 类）
```

| 层 | 类 | 职责 | 加载时机 |
|----|-----|------|---------|
| 1 | `SableCompat` | 检测 Sable 是否加载 | 始终加载 |
| 2 | `ModSable` | 安全调用入口，捕获 `NoClassDefFoundError` | 始终加载 |
| 3 | `SableIntegration` | 直接引用 Sable API 的核心逻辑 | 仅 Sable 已加载时 |

**类加载保护**：`ModSable` 通过 `Class.forName()` 检查 `SableIntegration` 是否可用，并 try-catch `NoClassDefFoundError`。即使 Sable 版本不兼容也不会崩溃，只是自动禁用飞艇传送功能。

### 11.4 构建配置

```groovy
// Sable 兼容（compileOnly 编译，玩家可选安装）
compileOnly files("libs/sable-neoforge-1.21.1-2.0.3.jar")
compileOnly files("libs/sable-companion-common-1.21.1-1.6.0.jar")  // JarJar 嵌套依赖
```

- `compileOnly`：编译时可用，不打包进发布 jar
- 不设 `runtimeOnly`：Sable 要求 Flywheel 1.0.6+，但 Create 内置 Flywheel 1.0.4，开发环境会冲突
- `sable-companion-common`：Sable 使用 JarJar 打包，编译时需单独提取嵌套的 companion jar

---

## 12. 关键类和职责

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `MapCoordHelper` | `domain/map/MapCoordHelper.java` | 坐标计算核心：射线-矩形相交、视角映射、像素↔世界坐标转换、旗帜命中检测、hitVec→UV转换、客户端目标计算（`calcClientTarget`） |
| `MapTeleportExecutor` | `domain/map/MapTeleportExecutor.java` | 传送决策链：统一处理"旗帜→宝藏→已探索区域→未探索区域"的传送优先级和消息发送，未探索区域需消耗一组（16个）珍珠，消除手持/展示框/容器三处重复逻辑 |
| `LivingMapEventHandler` | `domain/map/LivingMapEventHandler.java` | 事件处理入口：右键传送事件拦截、元数据同步包发送，传送逻辑委托给 `MapTeleportExecutor` |
| `ItemFrameMapTeleportHandler` | `domain/map/ItemFrameMapTeleportHandler.java` | 展示框传送：EntityInteractSpecific事件拦截、hitVec→像素坐标，传送逻辑委托给 `MapTeleportExecutor` |
| `TeleportHelper` | `domain/map/TeleportHelper.java` | 传送执行：安全Y坐标、骑乘传送、跨维度传送、Sable飞艇传送、粒子/音效、伤害、冷却、珍珠消耗（已探索1个/未探索16个） |
| `LivingItemManager` | `api/LivingItemManager.java` | 活物品管理：`isLivingItem()`、`isLivingMap()` 等通用判断 |
| `LivingEnderPearlFunction` | `function/LivingEnderPearlFunction.java` | 活末影珍珠功能：`isLivingEnderPearl()`、`isOnCooldown(player)`、`setCooldown(player)`、`findInInventory(player)`、`countInInventory(player)`、`consumeFromInventory(player, amount)`，冷却委托给原版 `player.getCooldowns()` |
| `LivingMapMetadataPacket` | `network/LivingMapMetadataPacket.java` | 服务器→客户端网络包：同步地图元数据 |
| `LivingMapClientCache` | `domain/map/LivingMapClientCache.java` | 客户端缓存：按 mapId 存储 centerX/centerZ/dimension |
| `LivingMapTargetRenderer` | `client/render/LivingMapTargetRenderer.java` | 客户端渲染工具：3D准心标记渲染（`renderMarker`，原版准心纹理+四色着色）、GUI十字形光标渲染（`renderMarkerGui`，5像素十字形），供 `ItemInHandRendererMixin`、`MapRendererMixin`、`AbstractContainerScreenMixin` 共享 |
| `ItemInHandRendererMixin` | `client/mixin/ItemInHandRendererMixin.java` | 客户端渲染：注入 renderMap 方法，3D空间中渲染目标标记 |
| `MapRendererMixin` | `client/mixin/MapRendererMixin.java` | 客户端渲染：注入 MapRenderer.render 方法，展示框地图光标渲染 |
| `MapItemMixin` | `living/mixin/MapItemMixin.java` | 活空地图：注入 EmptyMapItem.use 方法，根据堆叠数量和朝向在远程位置创建活地图 |
| `ModSable` | `compat/sable/ModSable.java` | Sable 安全调用入口：类加载保护、NoClassDefFoundError 捕获 |
| `SableCompat` | `compat/sable/SableCompat.java` | Sable 依赖检测：ModList.isLoaded("sable") |
| `SableIntegration` | `compat/sable/SableIntegration.java` | Sable 核心逻辑：SubLevel 检测、飞艇瞬移、偏移计算 |
| `ExpandedMapTexture` | `client/render/ExpandedMapTexture.java` | 扩展地图动态纹理：128×128 DynamicTexture 管理，颜色数据更新和哈希检测，资源注册/释放 |
| `LivingMapLayout` | `client/render/LivingMapLayout.java` | 扩展地图布局：槽位扫描（`scan`）、MapGroup 数据结构、UV 坐标计算（`computeUV`/`computeSingleSlotUV`）、区域命中检测（`findGroupAt`）、单个活地图槽位判断（`isSingleLivingMapSlot`） |
| `LivingMapGuiTeleportPacket` | `network/LivingMapGuiTeleportPacket.java` | GUI传送网络包：客户端→服务端传送请求，区分创造/生存模式处理光标物品同步 |
| `CarriedUpdatePacket` | `network/CarriedUpdatePacket.java` | 光标同步网络包：服务端→客户端强制同步光标物品状态，绕过创造模式原版同步限制 |
| `AbstractContainerScreenMixin` | `client/mixin/AbstractContainerScreenMixin.java` | 容器界面核心Mixin：扩展地图渲染（纹理+装饰）、光标物品隐藏（扩展地图+单个活地图）、交互拦截（右键+活末影珍珠→UV传送）、传送包发送、`findGroupBySlot` 备用查找 |

---

## 13. 已知问题与修复记录

### v1 → v2：标记不能覆盖全图

**问题**：使用固定地图半径 `scale * 64` 作为最大距离，当玩家不在地图中心时，标记无法到达某些边缘。

**修复**：实现射线-矩形相交算法 `calcMaxDistToMapEdge`，动态计算从原点沿方向到地图边界的最大距离。

### v2 → v3：视角联动别扭

**问题**：pitch 映射范围不合理，需要仰头才能将标记移到远处。

**修复**：将 pitch [90°, 0°] 映射到 [0, maxDist]，低头看自己、平视看边缘。

### v3 → v4：标记以地图中心为零点

**问题**：客户端 `mapData.centerX = 0`，导致 `isPlayerOnMap` 判断失败，始终走地图中心零点分支。

**修复**：添加 `calculateMapCenterCoord` 反算中心坐标。

### v4 → v5：传送传到地底

**问题**：`findSafeY` 逻辑判断反了，找到的是非实心方块而非地面。

**修复**：改用 `level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY()`，直接使用原版高度图。

### v5 → v6：维度判断不可靠

**问题**：客户端 `mapData.dimension` 是猜测的，跨维度时可能错误。

**修复**：创建 `LivingMapMetadataPacket` 自定义网络包，从服务器端同步真实的 centerX/centerZ/dimension 到客户端 `LivingMapClientCache`。

### v6 → v7：远距离传送掉入虚空

**问题**：目标区块未加载时，`getHeightmapPos` 返回最低高度，导致传送到基岩层以下。

**修复**：在 `findSafeY` 中添加 `level.getChunk(chunkX, chunkZ)` 强制加载目标区块。

### v7 → v8：旗帜传送位置错误

**问题**：旗帜在地底时，`teleportToBanner` 使用地表高度而非旗帜实际位置。

**修复**：重写 `teleportToBanner` 方法，直接使用 `bannerPos.getY() + 1.0` 作为目标 Y 坐标。

### v8 → v9：标记样式改为原版准心

**问题**：自定义线条标记与原版风格不一致。

**修复**：改用 `GuiSpriteManager.getSprite("minecraft:hud/crosshair")` 获取原版准心 sprite，通过 `RenderType.text(sprite.atlasLocation())` 渲染。顶点颜色着色实现四色区分。

### v9 → v10：客户端旗帜/宝藏检测不工作

**问题1**：客户端 `mapData.getBanners()` 为空（网络包不传输 banners），导致 `findBannerHit` 永远返回 null。

**修复1**：新增 `isBannerDecorationHit` 方法，使用 `getDecorations()` + `startsWith("banner_")` 过滤。

**问题2**：旗帜注册名格式为 `banner_<颜色>`（如 `banner_pink`），不是 `<颜色>_banner`。`endsWith("_banner")` 永远匹配不到。

**修复2**：改为 `startsWith("banner_")`。

**问题3**：红色大叉叉注册名为 `red_x`，且 `explorationMapElement()` 返回 `false`，导致 `findTargetPointHit` 过滤掉了所有红色大叉叉。

**修复3**：新增 `isRedXType` 方法，直接用 `equals("red_x")` 判断。

**问题4**：客户端 `mapData.centerX/centerZ` 为 0，旗帜像素坐标计算偏移巨大，准心永远命中不了旗帜。

**修复4**：`findBannerHit` 新增重载版本 `findBannerHit(mapData, mapX, mapY, centerX, centerZ)`，客户端调用时传入 `metadata.centerX()` 和 `metadata.centerZ()`。

### v10 → v11：传送后光标物品消失

**问题**：传送导致容器菜单关闭，光标上持有的物品被丢弃到旧位置，玩家已传送导致物品丢失。

**修复**：传送前调用 `safeReturnCarried`，将光标物品放回背包，背包满则掉落至玩家脚下。

### v11 → v12：展示框传送位置偏离（边框偏移错误）

**问题**：假设地图纹理在展示框中有边框偏移（UV 范围 0.0625~0.9375），导致传送位置偏向地图中心。

**修复**：地图纹理铺满整个方块面，没有边框偏移。UV 范围 [0, 1] 直接映射到像素 [0, 128]。

### v12 → v13：展示框传送位置偏离（framePos 获取错误）

**问题**：使用 `frame.getOnPos()` 获取展示框位置，对墙壁展示框返回脚下方块而非展示框所在方块。

**修复**：改用 `frame.blockPosition()` 获取展示框所在的方块坐标。

### v13 → v14：展示框光标左右方向反转

**问题**：hitVec 的 U 轴方向与地图纹理 U 轴方向相反，导致光标左右反转。

**修复**：墙壁朝向翻转 U 轴（`u = 1 - u`），地板/天花板朝向翻转 V 轴（`v = 1 - v`）。

### v14 → v15：展示框旋转后光标方向错误

**问题**：`frame.getRotation()` 返回持续递增的整数（0,1,2,3,4,5...），switch 的 default 分支将 4+ 当成 0 处理，导致第二圈旋转光标位置错误。

**修复**：旋转变换前取模 `rotation % 4`。

### v15 → v16：地板/天花板展示框光标方向错误

**问题**：地板/天花板展示框的 V 轴方向与地图纹理 V 轴也相反，但之前只翻转了 U 轴。

**修复**：区分朝向——墙壁翻转 U，地板/天花板翻转 V。

### v16 → v17：骑乘传送与 Sable 飞艇兼容

**问题1**：跨维度传送时 `vehicle.dismountTo()` 把坐骑移到源维度的目标坐标，坐骑和玩家分离。

**修复1**：跨维度时分别传送坐骑和玩家到目标维度，再 `player.startRiding(vehicle)` 重新骑乘。

**问题2**：Sable 飞艇传送后载具消失。`handle.teleport()` 在某些物理管线中不更新 `logicalPose()`，追踪系统判断玩家不在飞艇范围内，发送移除包导致客户端飞艇消失。

**修复2**：参照 `SubLevelSerializer` 模式，在 `handle.teleport()` 前直接设置 `logicalPose`（双保险），传送后使用 `player.teleportTo(ServerLevel, ...)` 完整传送确保区块加载和位置同步。

**问题3**：Sable 飞艇跨维度传送技术限制（SubLevel 绑定 Level、追踪系统卸载、客户端渲染）。

**修复3**：保守方案——跨维度拒绝传送，显示提示「跃迁权能不足，无法撕裂位面壁垒」。

### v17 → v18：代码重构 + 事件取消修复

**问题1**：`calcTargetMapPixel` 重复实现 3 次（服务端 `MapCoordHelper`、客户端 `ItemInHandRendererMixin`、客户端 `LivingMapTargetRenderer`），`renderMarker` 重复实现 2 次，`findPearlInInventory` 重复 3 次，`isLivingMap` 重复 5 次，`MAP_SIZE = 128` 重复 4 次。

**修复1**：提取公共方法——`MapCoordHelper.calcClientTarget()`、`LivingMapTargetRenderer.renderMarker()`、`LivingEnderPearlFunction.findInInventory()`、`LivingItemManager.isLivingMap()`、`MapCoordHelper.MAP_SIZE`。新增 `MapTeleportExecutor` 统一传送决策链，消除手持/展示框/容器三处重复逻辑。总计减少约 160 行重复代码。

**问题2**：手持活地图右键传送失败时（如 Sable 跨维度拒绝），事件未取消，原版地图缩放行为仍然触发。

**修复2**：`LivingMapEventHandler.onRightClickItem` 改为始终取消事件（与展示框传送一致），无论传送成功与否。

**问题3**：展示框传送失败时右键交互传递给展示框，活地图被旋转。

**修复3**：`ItemFrameMapTeleportHandler` 改为检测到活地图+活珍珠组合后始终取消事件，无论传送成功与否。

**问题4**：`LivingMapTargetRenderer` 包含死代码（空 `register()` 方法、未使用的 `getHeldLivingMap()` 和 `calcClientTarget()` 委托）。

**修复4**：清理为纯渲染工具类，仅保留 `renderMarker()` 方法。`ItemInHandRendererMixin` 直接调用 `MapCoordHelper.calcClientTarget()`。

### v18 → v19：冷却系统改用原版 ItemCooldowns

**问题**：自定义 `LivingEnderPearlData` DataComponent 重复实现了原版已有的冷却功能，且每个珍珠独立冷却，玩家可通过切换珍珠栈绕过冷却。

**修复**：删除 `LivingEnderPearlData` 及其注册（`LIVING_ENDER_PEARL_DATA`），改用原版 `player.getCooldowns()` 系统。

| 变更 | 旧实现 | 新实现 |
|------|--------|--------|
| 冷却检查 | `isOnCooldown(ItemStack)` | `isOnCooldown(ServerPlayer)` → `player.getCooldowns().isOnCooldown(Items.ENDER_PEARL)` |
| 设置冷却 | `setCooldown(ItemStack, ticks)` | `setCooldown(ServerPlayer, ticks)` → `player.getCooldowns().addCooldown(Items.ENDER_PEARL, ticks)` |
| tick 逻辑 | 每 tick 修改 DataComponent + syncSlotToClients | 不需要，`Player.tick()` 自动调用 |
| 客户端显示 | 自定义 tooltip（灰色倒计时/绿色就绪） | 原版扫光动画（sweep） |
| 冷却范围 | 每个珍珠独立 | 所有末影珍珠共享（含普通珍珠） |

删除文件：`LivingEnderPearlData.java`。

### v19 → v20：Sable 飞艇超远距离传送"飞快移动" + 站在飞艇上虚空掉落

**问题1**：飞艇超远距离跨地图传送（>320格）时，玩家不是直接传送到目标位置，而是以极快速度飞过去。原因是 `entities_stick_sublevels` Mixin 根据 `logicalPose` 和 `lastPose` 的差值每 tick 平滑移动玩家，超远距离时产生"缓动"效果。

**修复1**：传送后同步 `lastNetworkedPose` 与 `logicalPose`，清零线速度/角速度，标记为已停止。使网络同步系统认为飞艇没有移动，不发送移动包。

```java
serverSubLevel.lastNetworkedPose().set(serverSubLevel.logicalPose());
serverSubLevel.latestLinearVelocity.zero();
serverSubLevel.latestAngularVelocity.zero();
serverSubLevel.setLastNetworkedStopped(true);
```

**问题2**：站在飞艇上（非骑乘坐垫）跨地图传送时，玩家掉入虚空不断下落。原因是传送后客户端仍发送旧坐标的移动包，`ServerboundMovePlayerPacketMixin` 中 `Sable.HELPER.getContaining(level, oldX, oldZ)` 返回 null（飞艇已传送），Mixin 清除 `trackingSubLevel`，玩家脱离飞艇。

**修复2**：传送后发送 `ClientboundPlayerPositionPacket` 同步客户端位置，确保客户端后续移动包使用新坐标。参考 Sable 在 `ServerPlayerMixin.sable$adjustTeleportPacket` 中处理骑乘时的做法。

### v20 → v21：站在飞艇上仅传送飞艇不传送玩家

**问题**：站在飞艇上跨地图传送后，玩家留在原地，只有飞艇被传送。v20 使用 SubLevel 局部坐标（`localPos`）发送 `ClientboundPlayerPositionPacket`，但 Sable 的 `LevelPlot`（地块）`plotPos` 是固定的 ChunkPos，不会随 `logicalPose` 移动。传送后 `getContaining(newPlayerPos)` 返回 null（plot 仍在旧位置），客户端收到局部坐标后渲染玩家在 0,0,0 附近，而非飞艇旁。

**修复**：改为发送世界坐标（`newPlayerPos`）而非 SubLevel 局部坐标（`localPos`）。`SubLevelEntityCollision` 基于 `logicalPose` 而非 `plotPos` 处理碰撞，玩家可正常站在飞艇方块上。

```diff
- Vector3d localPos = serverSubLevel.logicalPose()
-     .transformPositionInverse(newPlayerPos, new Vector3d());
- player.connection.send(new ClientboundPlayerPositionPacket(
-     localPos.x, localPos.y, localPos.z, ...));
+ player.connection.send(new ClientboundPlayerPositionPacket(
+     newPlayerPos.x, newPlayerPos.y, newPlayerPos.z, ...));
```

**根因分析**：Sable 的 `LevelPlot` 与 `logicalPose` 是两个独立的概念：

| 概念 | 作用 | 传送后是否变化 |
|------|------|---------------|
| `logicalPose` | 飞艇在世界中的投影位置 | ✅ 变化（手动设置） |
| `LevelPlot.plotPos` | 地块的固定 ChunkPos | ❌ 不变（始终在 Plotyard） |
| `getContaining()` | 基于 `plotPos` 判断玩家是否在 SubLevel 内 | ❌ 返回 null（plot 在旧位置） |

因此，传送后不能依赖 `getContaining()` 来判断位置同步方式，必须直接发送世界坐标。

### v21 → v22：跨维度坐骑传送仅传送坐骑不传送玩家

**问题**：跨维度传送时，原代码只调用 `vehicle.changeDimension(transition)`，未同时传送玩家。`Entity.changeDimension()` 内部会调用 `unRide()` 卸载所有乘客，导致玩家被留在旧维度，只有坐骑被传送到目标维度。

**修复**：在调用 `vehicle.changeDimension()` 之前先 `player.stopRiding()` 干净解除骑乘，然后分别传送坐骑和玩家到目标维度，最后 `player.startRiding(vehicle)` 重新建立骑乘关系。

```java
if (vehicle != null) {
    player.stopRiding();
    vehicle.changeDimension(transition);
    player.changeDimension(transition);
    player.startRiding(vehicle);
}
```

**为什么用 `changeDimension()` 而非 `teleportTo()`**：1.21.1 引入了 `DimensionTransition` 类作为跨维度传送的标准方式。`changeDimension()` 会正确处理实体在维度间的注册/注销、数据保存/加载等逻辑，比手动 `teleportTo(ServerLevel, ...)` 更可靠。`DimensionTransition.DO_NOTHING` 跳过传送门搜索逻辑，直接传送到精确坐标。

### v22 → v23：多乘客载具传送处理

**问题**：当载具上有多个乘客（如船上有两个玩家），传送时只处理了传送者：
- 跨维度：`vehicle.changeDimension()` 内部 `unRide()` 会把其他乘客踢下船留在旧维度
- 同维度：`player.teleportTo()` 内部 `stopRiding()` 只把传送者踢下船，其他乘客也被带到目标位置

**修复**：

**跨维度** — 保存所有乘客，清空后逐个传送并重新骑乘：

```java
List<Entity> passengers = new ArrayList<>(vehicle.getPassengers());
for (Entity passenger : passengers) {
    passenger.stopRiding();
}
vehicle.changeDimension(transition);
for (Entity passenger : passengers) {
    passenger.changeDimension(transition);
    passenger.startRiding(vehicle);
}
```

**同维度** — `dismountTo()` 本身就能带所有乘客移动，只需用 `ClientboundPlayerPositionPacket` 同步客户端位置，替代会踢人下船的 `teleportTo()`：

```java
vehicle.dismountTo(destX, destY, destZ);
player.connection.send(new ClientboundPlayerPositionPacket(
    destX, destY, destZ, player.getYRot(), player.getXRot(),
    Set.of(), -1));
```

### v23 → v24：飞艇上多玩家传送同步

**问题**：飞艇上有多个玩家时，传送只同步了传送者的位置：
- 坐在坐垫上的玩家：坐垫实体在 SubLevel 内，`logicalPose` 变化后坐垫位置跟着变，骑乘者自动跟随 ✅
- 站在飞艇上的玩家：依赖 `entities_stick_sublevels` 每 tick 移动，但传送后 `lastPose` 与 `logicalPose` 差值为零，系统不做任何移动，留在原地掉入虚空 ❌

**修复**：在 `SableIntegration.teleportSubLevel()` 中，传送前遍历所有其他玩家，找到同 SubLevel 的，保存其局部坐标；传送后将局部坐标转回世界坐标，同步位置。

```java
// 传送前：保存飞艇上其他玩家的局部坐标
Map<ServerPlayer, Vector3d> otherPlayersLocal = new HashMap<>();
for (ServerPlayer otherPlayer : player.serverLevel().players()) {
    if (otherPlayer == player) continue;
    if (Sable.HELPER.getTrackingOrVehicleSubLevel(otherPlayer) == serverSubLevel) {
        Vector3d local = oldPose.transformPositionInverse(
            new Vector3d(otherPlayer.getX(), otherPlayer.getY(), otherPlayer.getZ()), new Vector3d());
        otherPlayersLocal.put(otherPlayer, local);
    }
}

// 传送后：同步所有其他玩家的位置
for (Map.Entry<ServerPlayer, Vector3d> entry : otherPlayersLocal.entrySet()) {
    Vector3d otherNewPos = serverSubLevel.logicalPose()
        .transformPosition(entry.getValue(), new Vector3d());
    otherPlayer.setPos(otherNewPos.x, otherNewPos.y, otherNewPos.z);
    otherPlayer.connection.send(new ClientboundPlayerPositionPacket(...));
}
```

### v24 → v25：站在飞艇上远距离传送"虚空无限下坠"

**问题**：站在飞艇上（不骑乘）远距离跨地图传送时，玩家会经历长时间的虚空下坠，然后才传送成功。坐在飞艇上则瞬间完成。

**根因**：`ServerboundMovePlayerPacketMixin` 的位置变换循环。

传送后的时序：
1. `teleportSubLevel()` 将 `logicalPose` 移到新位置，`player.setPos()` 设置新坐标，发送 `ClientboundPlayerPositionPacket`
2. 但 `trackingSubLevel` 未清除
3. 网络延迟期间，客户端发送移动包（旧坐标）
4. Mixin 处理：`getContaining(旧x, 旧z)` 返回 SubLevel（plot 在旧位置）
5. `logicalPose.transformPosition(旧坐标)` → 旧坐标穿过新 logicalPose → 完全错误的位置
6. `ClientboundPlayerPositionPacket` 到达 → 纠正
7. 客户端又发移动包 → 又弹飞 → 无限循环

**为什么坐着不触发**：原版骑乘系统不发送 `ServerboundMovePlayerPacket`，Mixin 完全不参与。

**修复**：传送后清除 `trackingSubLevel`，使 Mixin 不再做坐标变换：

```java
((EntityMovementExtension) player).sable$setTrackingSubLevel(null);
```

对所有被传送的玩家（传送者 + 飞艇上其他玩家）都需要清除。

### v25 → v26：站在飞艇上只传送玩家，不传送飞艇

**决策**：站在飞艇上传送涉及太多边缘情况（plot 位置固定、tracking 清除时机、`ServerboundMovePlayerPacketMixin` 坐标变换循环等），且站在飞艇上"带着飞艇一起传送"的语义不够直观。简化为：只有骑乘飞艇坐垫时才传送飞艇，站在飞艇上只传送玩家自己。

**修改**：`SableIntegration.isPlayerOnSubLevel()` 从"是否在 SubLevel 上"改为"是否骑乘在 SubLevel 上"：

```java
static boolean isPlayerOnSubLevel(ServerPlayer player) {
    SubLevel subLevel = Sable.HELPER.getTrackingOrVehicleSubLevel(player);
    if (!(subLevel instanceof ServerSubLevel)) return false;
    if (player.getVehicle() != null) return true;          // 骑乘 → 传送飞艇
    ((EntityMovementExtension) player).sable$setTrackingSubLevel(null); // 站着 → 清除tracking
    return false;                                           // 走普通传送
}
```

站在飞艇上时清除 `trackingSubLevel` 是必要的，否则 `ServerboundMovePlayerPacketMixin` 会在下一个移动包中把玩家弹回飞艇位置。

### v26 → v27：只有骑乘者随飞艇传送

**决策**：`teleportSubLevel` 中的 `otherPlayersLocal` 逻辑会把飞艇上所有玩家（包括站着的）一起传送。这与 v26 的"站着只传送玩家自己"的决策不一致。简化：只有骑乘坐垫的实体随飞艇传送，站着的留在原地。

**修改**：删除 `SableIntegration.teleportSubLevel()` 中的 `otherPlayersLocal` 收集和同步逻辑（约 30 行），只保留传送者自身的同步。

**骑乘者（坐垫上）怎么传送**：Sable 的坐垫实体在 SubLevel 内，`logicalPose` 变化后坐垫的世界位置自动更新，骑乘者通过原版骑行系统跟随，不需要额外代码。

### v27 → v28：传送逻辑全面修复

**问题1**：跨维度带坐骑传送时，外部手动保存乘客→下车→逐个传送→重新骑乘，但 `Entity.changeDimension()` 内部已经做了同样的事，导致乘客被 `changeDimension` 两次（产生重复实体），且 `vehicle` 变量指向旧维度的已移除实体。

**修复1**：移除外部手动乘客管理，直接调用 `vehicle.changeDimension(transition)`，让原版内部处理乘客传送和重新骑乘。

**问题2**：同维度远距离传送时，`vehicle.dismountTo()` 不加载目标区块，实体可能被移到未加载区块导致"消失"。同时手动发送 `ClientboundPlayerPositionPacket` 同步客户端位置，但 `dismountTo` + 手动发包的顺序可能导致客户端短暂看到玩家在旧位置。

**修复2**：改用 `ensureChunkLoaded` + `vehicle.teleportTo()`。`teleportTo` 内部调用 `moveTo()` + `teleportPassengers()`，`teleportPassengers` 自动遍历所有乘客调用 `moveTo` 同步位置，不需要手动发包。

**问题3**：`teleportToBanner` 手动调用 `targetLevel.getChunk()` 加载区块，但 `executeTeleport` 同维度路径已调用 `ensureChunkLoaded`，重复加载。

**修复3**：移除 `teleportToBanner` 中的冗余区块加载。

**问题4**：Sable 飞艇传送路径没有调用 `ensureChunkLoaded`，虽然 `SableIntegration.teleportSubLevel` 内部加载了区块，但作为防御性编程添加。

**修复4**：在 Sable 路径调用 `teleportSubLevel` 前添加 `ensureChunkLoaded`。

**问题5**：`MapTeleportHandler` 和 `MapTeleportCarriedHandler` 在传送前清空光标物品（`setCarried(EMPTY)`），然后 `safeReturnCarried` 放回背包或掉落。如果传送失败，光标物品已被清空且可能掉落在旧位置。

**修复5**：不再清空光标，直接将光标/槽位中的珍珠引用传给 `TeleportHelper.teleportToMapPosition`。`executeTeleport` 内部的 `consumePearl` 只在传送成功后调用 `pearlStack.shrink(1)`，传送失败时物品不受影响。移除 `safeReturnCarried` 方法。

**问题6**：`MapCoordHelper.isRedXEntry` 没有使用已提取的 `matchEntryPath` 通用方法。

**修复6**：改为 `return matchEntryPath(entry, path -> path.equals("red_x"))`，与 `isBannerType`/`isRedXType` 风格统一。

### v28 → v29：安全Y坐标区分天花板维度

**问题**：`findSafeY` 只使用 `MOTION_BLOCKING + 1`，在地狱中 `MOTION_BLOCKING` 返回基岩天花板（Y≈127），`+1` 后玩家被传送到天花板上方（Y≈128），卡在地狱顶部。

**根因**：`MOTION_BLOCKING` 高度图返回该列最高阻挡运动的方块。在地狱中，基岩天花板（Y≈127）是最高的阻挡方块，不是地板。旧代码不区分天花板维度和无天花板维度，统一向上扫描，导致地狱传送位置在天花板上方。

**修复**：重写 `findSafeY`，根据 `level.dimensionType().hasCeiling()` 区分两种策略：
- **有天花板维度（地狱）**：从天花板向下扫描，找到第一个脚部安全（非实心、非岩浆）、下方有地面（实心或岩浆）、头部安全（非实心）的位置
- **无天花板维度（主世界）**：从地表向上扫描，跳过实心方块和岩浆，找到第一个安全站立位置

返回值直接是玩家站立Y坐标，调用方不再 `+1.0`。

### v29 → v30：GUI传送堆叠珍珠全部消耗 + 光标遮挡 + 扩展地图装饰

**问题1**：手持堆叠活末影珍珠在GUI中传送时，整组珍珠全部消失。

**根因**：`LivingMapGuiTeleportPacket.handle()` 中，生存模式下也执行了 `carriedRestored = true`（因为客户端 `resolveCarriedTag()` 对非创造模式也发送了 carriedTag），导致：
1. 服务端从 carriedTag 恢复光标 → `menu.setCarried(carried)` 设置为N个珍珠
2. `consumePearl()` → `pearlStack.shrink(1)` → 光标变为N-1个
3. `carriedRestored = true` → `menu.setCarried(ItemStack.EMPTY)` 清空服务端光标
4. 发送 `CarriedUpdatePacket` 携带N-1个珍珠
5. `broadcastChanges()` → `synchronizeCarriedToRemote()` 检测到光标从N-1变为EMPTY，发送原版包携带EMPTY
6. 客户端先收到 `CarriedUpdatePacket`（N-1），再收到原版包（EMPTY），最终光标为空

**修复**：区分创造/非创造模式处理光标状态：

| 模式 | carriedTag | carriedRestored | 光标同步方式 |
|------|-----------|----------------|-------------|
| 创造 | 发送（解决创造模式服务端无光标问题） | 可为true | `CarriedUpdatePacket` 强制同步 |
| 生存 | 不发送（服务端已有正确光标） | 始终false | `broadcastChanges()` 自动同步 |

```java
// 客户端：只在创造模式下发送 carriedTag
private CompoundTag living_item$resolveCarriedTag() {
    if (!(mc.screen instanceof CreativeModeInventoryScreen)) return null;
    ItemStack carried = menu.getCarried();
    if (carried.isEmpty()) return null;
    return (CompoundTag) carried.saveOptional(mc.player.registryAccess());
}

// 服务端：只在创造模式下恢复光标和发送 CarriedUpdatePacket
boolean carriedRestored = false;
if (player.isCreative() && packet.carriedTag() != null) {
    menu.setCarried(carried);
    carriedRestored = true;
}
// ...传送逻辑...
if (player.isCreative() && (carriedRestored || pearlFromCursor)) {
    ItemStack modifiedCarried = menu.getCarried().copy();
    menu.setCarried(ItemStack.EMPTY);
    PacketDistributor.sendToPlayer(player, new CarriedUpdatePacket(carriedSyncTag));
}
menu.broadcastChanges(); // 生存模式依赖此调用同步消耗后的物品
```

**问题2**：手持活末影珍珠悬浮在扩展地图区域上时，光标物品图标遮挡地图内容。

**修复**：注入 `AbstractContainerScreen.renderFloatingItem` 方法，当光标物品为活末影珍珠且鼠标位于扩展地图区域时，取消渲染光标物品。

```java
@Inject(method = "renderFloatingItem", at = @At("HEAD"), cancellable = true)
private void living_item$hideFloatingPearl(GuiGraphics guiGraphics, ItemStack stack,
                                            int x, int y, String text, CallbackInfo ci) {
    if (LivingEnderPearlFunction.isLivingEnderPearl(stack)) {
        living_item$updateMapGroups();
        float cursorX = x + 8;  // 光标中心点（已减去 leftPos/topPos 偏移）
        float cursorY = y + 8;
        for (LivingMapLayout.MapGroup group : living_item$mapGroups) {
            float relAreaX = group.x() - LivingMapLayout.SLOT_BORDER_OFFSET;
            float relAreaY = group.y() - LivingMapLayout.SLOT_BORDER_OFFSET;
            float areaSize = group.n() * LivingMapLayout.SLOT_SIZE;
            if (cursorX >= relAreaX && cursorX < relAreaX + areaSize
                && cursorY >= relAreaY && cursorY < relAreaY + areaSize) {
                ci.cancel();
                return;
            }
        }
    }
}
```

**坐标系统说明**：`renderFloatingItem` 的 `x`/`y` 参数已在 `render()` 的 `guiGraphics.pose().translate(leftPos, topPos, 0)` 变换之后，是相对于GUI左上角的坐标。`group.x()`/`group.y()` 是槽位相对于容器原点的偏移，也是相对于 `(leftPos, topPos)` 的偏移，因此两者在同一坐标系中可以直接比较。

**问题3**：扩展地图不渲染旗帜、红色大叉等装饰图案。`ExpandedMapTexture` 仅处理了颜色数据（`mapData.colors`），未渲染 `MapDecoration`。

**修复**：新增 `living_item$renderExpandedMapDecorations` 方法，参考原版 `MapRenderer.render()` 的装饰渲染逻辑：

```java
private void living_item$renderExpandedMapDecorations(GuiGraphics guiGraphics,
        MapItemSavedData mapData, int areaX, int areaY, int areaSize) {
    MapDecorationTextureManager decoTextures = Minecraft.getInstance().getMapDecorationTextures();
    float scale = (float) areaSize / 128;

    guiGraphics.pose().pushPose();
    guiGraphics.pose().translate(areaX, areaY, 1); // z偏移1，在地图纹理之上

    for (MapDecoration deco : mapData.getDecorations()) {
        // 地图像素坐标 → 屏幕坐标
        float decoX = ((float) deco.x() / 2.0F + 64.0F) * scale;
        float decoY = ((float) deco.y() / 2.0F + 64.0F) * scale;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(decoX, decoY, index * -0.01F);
        guiGraphics.pose().mulPose(Axis.ZP.rotationDegrees(deco.rot() * 360 / 16.0F));
        float decoSize = 4.0F * scale;
        guiGraphics.pose().scale(decoSize, decoSize, 1.0F);
        guiGraphics.pose().translate(-0.5F, 0.5F, 0.0F);

        // 使用 VertexConsumer 渲染装饰纹理
        TextureAtlasSprite sprite = decoTextures.get(deco);
        VertexConsumer vc = guiGraphics.bufferSource()
            .getBuffer(RenderType.text(sprite.atlasLocation()));
        Matrix4f matrix = guiGraphics.pose().last().pose();
        vc.addVertex(matrix, -1, 1, 0).setColor(-1).setUv(u0, v0).setLight(0xF000F0);
        // ... 四个顶点 ...

        guiGraphics.pose().popPose();
    }
    guiGraphics.pose().popPose();
}
```

**渲染要点**：

| 参数 | 原版 MapRenderer | 扩展地图 | 说明 |
|------|-----------------|---------|------|
| 坐标变换 | `translate(mapX, mapY, z)` | `translate(areaX, areaY, 1)` | 扩展地图在 PoseStack z=300 基础上偏移+1 |
| 缩放 | 原版固定大小 | `4.0F * scale` | scale = areaSize / 128，随地图尺寸缩放 |
| 旋转 | `deco.rot() * 360 / 16` | 同左 | 装饰旋转角度，16级离散 |
| 纹理 | `MapDecorationTextureManager.get(deco)` | 同左 | 返回 `TextureAtlasSprite`，从图集获取 |
| 渲染方式 | `VertexConsumer` + `RenderType.text()` | 同左 | 使用文字渲染类型确保透明度正确 |
| 光照 | `0xF000F0`（全亮） | 同左 | GUI渲染不需要光照计算 |

**装饰像素坐标计算**：`MapDecoration.x()`/`y()` 是字节值（-128~127），原版通过 `x / 2.0 + 64.0` 映射到 [0, 128] 像素坐标范围。扩展地图乘以 `scale` 将像素坐标映射到实际屏幕尺寸。

### v30 → v31：扩展地图左上角传送位置错误 + 光标隐藏坐标bug + 光标形状改为十字形

**问题1**：在扩展地图的左上角（原活地图槽位所在区域），右键传送时传送到地图中心，而非鼠标点击位置对应的实际显示区域位置。

**根因**：`LivingMapLayout.findGroupAt()` 基于鼠标坐标检测扩展地图区域时，在某些边界条件下对左上角区域返回 null。此时点击事件落入 `GuiInteractionHelper.tryInteract()`，匹配 `map_teleport_carried` 交互规则（trigger=ENDER_PEARL, target=FILLED_MAP），触发 `MapTeleportCarriedHandler` 传送到地图中心（`mapData.centerX, mapData.centerZ`），而非使用 UV 坐标精确传送。

**修复**：在 `mouseClicked` 和 `mouseReleased` 中，当 `findGroupAt()` 返回 null 时，添加基于 `hoveredSlot` 的备用查找——通过 `MapGroup.containsSlot(hoveredSlot.index)` 检查当前悬浮槽位是否属于某个扩展地图组：

```java
// mouseClicked 中
if (living_item$hasLivingEnderPearl()) {
    LivingMapLayout.MapGroup group = LivingMapLayout.findGroupAt(
        living_item$mapGroups, mouseX, mouseY, leftPos, topPos);

    // 备用检查：findGroupAt 返回 null 时，通过 hoveredSlot 查找所属组
    if (group == null && this.hoveredSlot != null) {
        group = living_item$findGroupBySlot(this.hoveredSlot.index);
    }

    if (group != null) {
        // 精确 UV 传送...
    }
}

// mouseReleased 中同样添加备用检查
if (living_item$hasLivingEnderPearl()) {
    boolean inExpandedArea = LivingMapLayout.findGroupAt(...) != null;
    if (!inExpandedArea && this.hoveredSlot != null) {
        inExpandedArea = living_item$findGroupBySlot(this.hoveredSlot.index) != null;
    }
    if (inExpandedArea) { cir.setReturnValue(true); return; }
}
```

新增辅助方法 `living_item$findGroupBySlot`：

```java
@Unique
@Nullable
private LivingMapLayout.MapGroup living_item$findGroupBySlot(int slotIndex) {
    for (LivingMapLayout.MapGroup group : living_item$mapGroups) {
        if (group.containsSlot(slotIndex)) {
            return group;
        }
    }
    return null;
}
```

**问题2**：`renderFloatingItem` 中光标物品隐藏的坐标比较使用了错误的坐标系——`cursorX/cursorY`（`x + 8`, `y + 8`）是屏幕绝对坐标，而 `relAreaX/relAreaY`（`group.x() - SLOT_BORDER_OFFSET`）是容器相对坐标，两者不在同一坐标系中，导致光标隐藏判断失效。

**修复**：将容器相对坐标转换为屏幕绝对坐标，加上 `leftPos`/`topPos` 偏移：

```diff
- float relAreaX = group.x() - LivingMapLayout.SLOT_BORDER_OFFSET;
- float relAreaY = group.y() - LivingMapLayout.SLOT_BORDER_OFFSET;
+ float areaLeft = leftPos + group.x() - LivingMapLayout.SLOT_BORDER_OFFSET;
+ float areaTop = topPos + group.y() - LivingMapLayout.SLOT_BORDER_OFFSET;
```

> **注意**：v30 文档中关于 `renderFloatingItem` 坐标系的说明有误——`x`/`y` 参数并非"相对于GUI左上角"，而是屏幕绝对坐标。`renderFloatingItem` 在 `render()` 方法中被调用时，`guiGraphics.pose()` 的变换已在 `blitOffset` 处理中恢复，`x`/`y` 直接对应屏幕像素位置。

**问题3**：GUI 扩展地图上的传送光标为 4 像素正方形，视觉上不够直观。

**修复**：将 `LivingMapTargetRenderer.renderMarkerGui` 的光标形状从带边框的实心正方形改为 5 像素组成的十字形：

```
  ■
■ ■ ■
  ■
```

5 个 `guiGraphics.fill()` 调用分别绘制上、左、中心、右、下，每个方块大小为 `pixelSize × pixelSize`，以鼠标所在的地图像素为中心向四个方向延伸一格。

### v31 → v32：交互拦截精简 + 单个活地图UV传送 + 光标隐藏 + 左上角传送修复

**问题1**：扩展地图的交互拦截逻辑分散在5个注入点中（`mouseClicked`、`mouseReleased`、`renderSlot`、`renderFloatingItem`、`isSlotInExpandedMap`），其中 `isSlotInExpandedMap` 无条件拦截扩展地图区域内的所有点击，导致普通左键移动物品操作也被拦截。

**修复**：精简交互拦截逻辑为3个核心注入点：

| 注入点 | 功能 | 条件 |
|--------|------|------|
| `mouseClicked` | 右键+活末影珍珠时拦截扩展地图/单个活地图区域点击，发送UV传送包 | `button == 1 && hasLivingEnderPearl()` |
| `mouseClicked` | `GuiInteractionHelper.tryInteract` 处理活物品交互 | 始终检查 |
| `renderFloatingItem` | 活末影珍珠悬浮在扩展地图/单个活地图上时隐藏光标物品 | `isLivingEnderPearl(stack)` |

删除 `isSlotInExpandedMap` 方法和 `renderSlot` 中的无条件拦截，删除 `mouseReleased` 中的扩展地图拦截。左键移动物品操作不再被任何逻辑拦截。

**问题2**：单个活地图（非扩展地图）槽位不支持UV精确传送，右键传送时走 `GuiInteractionHelper` → `MapTeleportCarriedHandler` 传送到地图中心。

**修复**：在 `mouseClicked` 中添加单个活地图槽位的检测和UV传送逻辑：

```java
if (this.hoveredSlot != null && LivingMapLayout.isSingleLivingMapSlot(this.hoveredSlot)) {
    float[] uv = LivingMapLayout.computeSingleSlotUV(this.hoveredSlot, mouseX, mouseY, leftPos, topPos);
    CompoundTag carriedTag = living_item$resolveCarriedTag();
    PacketDistributor.sendToServer(new LivingMapGuiTeleportPacket(
        this.hoveredSlot.index, uv[0], uv[1], carriedTag));
    cir.setReturnValue(true);
    return;
}
```

新增 `LivingMapLayout.computeSingleSlotUV` 方法，将单个槽位内的鼠标坐标转换为 [0,1] 范围的UV坐标：

```java
public static float[] computeSingleSlotUV(Slot slot, double mouseX, double mouseY, int leftPos, int topPos) {
    double areaLeft = leftPos + slot.x - SLOT_BORDER_OFFSET;
    double areaTop = topPos + slot.y - SLOT_BORDER_OFFSET;
    double areaSize = SLOT_SIZE;
    float u = (float) ((mouseX - areaLeft) / areaSize);
    float v = (float) ((mouseY - areaTop) / areaSize);
    u = Math.max(0f, Math.min(1f, u));
    v = Math.max(0f, Math.min(1f, v));
    return new float[]{u, v};
}
```

新增 `LivingMapLayout.isSingleLivingMapSlot` 方法，判断槽位是否为单个活地图（已打开的活地图，非空地图）。

**问题3**：手持活末影珍珠悬浮在单个活地图槽位上时，光标物品图标不会隐藏（仅对扩展地图区域做了隐藏）。

**修复**：在 `renderFloatingItem` 注入中添加单个活地图槽位的遍历检查：

```java
for (Slot slot : this.menu.slots) {
    if (LivingMapLayout.isSingleLivingMapSlot(slot)) {
        float relSlotX = slot.x - LivingMapLayout.SLOT_BORDER_OFFSET;
        float relSlotY = slot.y - LivingMapLayout.SLOT_BORDER_OFFSET;
        float slotSize = LivingMapLayout.SLOT_SIZE;
        if (cursorX >= relSlotX && cursorX < relSlotX + slotSize
            && cursorY >= relSlotY && cursorY < relSlotY + slotSize) {
            ci.cancel();
            return;
        }
    }
}
```

**问题4**：扩展地图左上角（原活地图槽位所在区域）右键传送时，传送到地图中心而非鼠标点击位置。`findGroupAt()` 在某些边界条件下对左上角区域返回 null，导致点击落入 `GuiInteractionHelper.tryInteract()` → `MapTeleportCarriedHandler` 传送到地图中心。

**修复**：在 `mouseClicked` 中，当 `findGroupAt()` 返回 null 时，通过 `hoveredSlot` 备用查找所属扩展地图组：

```java
LivingMapLayout.MapGroup group = LivingMapLayout.findGroupAt(
    living_item$mapGroups, mouseX, mouseY, leftPos, topPos);
if (group == null && this.hoveredSlot != null) {
    group = living_item$findGroupBySlot(this.hoveredSlot);
}
```

新增辅助方法 `living_item$findGroupBySlot`，遍历所有 MapGroup 检查 hoveredSlot 是否属于某个组：

```java
@Unique
@Nullable
private LivingMapLayout.MapGroup living_item$findGroupBySlot(Slot slot) {
    for (LivingMapLayout.MapGroup group : living_item$mapGroups) {
        for (int idx : group.slotIndices()) {
            if (menu.slots.get(idx) == slot) {
                return group;
            }
        }
    }
    return null;
}
```

**问题5**：删除了 `MapTeleportHandler` 和 `MapTeleportCarriedHandler` 的注册，统一走 `LivingMapGuiTeleportPacket` UV传送逻辑。`GuiInteractionHelper.tryInteract` 仅处理非地图传送的活物品交互。

**交互拦截优先级总结**：

```
mouseClicked:
  1. 右键 + 活末影珍珠？
     ├─ findGroupAt 命中扩展地图？→ UV传送（computeUV）
     ├─ findGroupBySlot 备用命中？→ UV传送（computeUV）
     └─ hoveredSlot 是单个活地图？→ UV传送（computeSingleSlotUV）
  2. GuiInteractionHelper.tryInteract → 活物品交互（非地图传送）
  3. 其他逻辑（中键活箱子、Shift+左键活箱子快捷存放）

renderFloatingItem:
  活末影珍珠 + 鼠标在扩展地图/单个活地图区域？→ 取消渲染光标物品
```

### v32 → v33：未探索区域传送——消耗一组活末影珍珠

**问题**：活空地图远程开图创建的地图，其目标区域在地图上是未探索的。旧机制下GUI单活地图传送可以传送到地图中心（绕过未探索限制），但v32统一了4个场景的传送逻辑后，未探索区域被完全拒绝传送，导致远程开图的地图无法使用传送功能。

**修复**：修改 `MapTeleportExecutor.execute()` 中未探索区域的处理逻辑，允许消耗一组（16个）活末影珍珠传送到未探索区域：

| 模式 | 已探索区域 | 未探索区域 |
|------|-----------|-----------|
| 生存模式 | 消耗1个珍珠 | 消耗16个珍珠（一组） |
| 创造模式 | 不消耗 | 不消耗 |

```java
// MapTeleportExecutor.execute() 中
boolean unexplored = !MapCoordHelper.isExplored(mapData, mapX, mapY);
if (unexplored) {
    if (player.isCreative()) {
        // 创造模式免费传送
        success = TeleportHelper.teleportToMapPosition(..., pearlStack, true);
    } else if (pearlStack.getCount() >= UNEXPLORED_PEARL_COST) {
        // 生存模式：消耗一组珍珠传送
        success = TeleportHelper.teleportToMapPosition(..., pearlStack, true);
    } else {
        // 珍珠不足，拒绝传送
        TeleportHelper.sendUnexploredMessage(player);
        return new Result(false, false);
    }
} else {
    success = TeleportHelper.teleportToMapPosition(..., pearlStack, false);
}
```

**消耗逻辑**：`TeleportHelper.consumePearl` 根据 `unexplored` 参数决定消耗数量：

```java
private static void consumePearl(ServerPlayer player, ItemStack pearlStack, boolean unexplored) {
    if (player.isCreative()) return;
    if (unexplored) {
        pearlStack.shrink(MapTeleportExecutor.UNEXPLORED_PEARL_COST); // 16
    } else {
        pearlStack.shrink(1);
    }
}
```

**提示消息更新**：珍珠不足时提示"此区域尚未探索，需要一组（16个）活末影珍珠才能传送"，告知玩家所需数量。

**四个场景统一**：手持传送、展示框传送、GUI扩展地图传送、GUI单个活地图传送均通过 `MapTeleportExecutor.execute()` 共享此逻辑，行为一致。

**常量**：`MapTeleportExecutor.UNEXPLORED_PEARL_COST = 16`（末影珍珠最大堆叠数）。

### v33 → v34：同维度载具传送客户端位置不同步导致抽搐

**问题**：同维度骑乘坐骑远距离传送后，玩家处于不断抽搐状态，皮肤发红（受击效果），视角抖动，无法移动和正常操作。

**根因**：`vehicle.teleportTo()` 内部的 `teleportPassengers()` 只调用 `Entity::moveTo` 设置乘客坐标，不会为 `ServerPlayer` 发送 `ClientboundPlayerPositionPacket`。客户端仍持有旧位置，继续基于旧位置发送移动包，服务端不断纠正 → 客户端回弹 → 抽搐。加上后续 `player.hurt()` 的受击效果（红屏+视角抖动），抽搐更加明显。

**为什么跨维度不会出现此问题**：跨维度走 `changeDimension()`，客户端收到 `ClientboundRespawnPacket`（维度切换），整个世界状态重置，不存在"旧位置"冲突。

**原版末影珍珠的做法**：`ThrownEnderpearl.onHit()` 中，即使是同维度也走 `player.changeDimension()`，其 `ServerPlayer` 重写版本在同维度分支调用 `connection.teleport()` + `connection.resetPosition()`，正确发送位置同步包。

**修复**：同维度传送改用 `player.changeDimension()`，载具传送流程改为：

```java
// 同维度 + 有载具
List<Entity> passengers = new ArrayList<>(vehicle.getPassengers());
for (Entity passenger : passengers) {
    passenger.stopRiding();                    // 1. 所有乘客下马
}
vehicle.teleportTo(destX, destY, destZ);       // 2. 传送载具
DimensionTransition transition = new DimensionTransition(
    targetLevel, new Vec3(destX, destY, destZ), Vec3.ZERO,
    player.getYRot(), player.getXRot(), DimensionTransition.DO_NOTHING);
player.changeDimension(transition);            // 3. 传送玩家（发送 ClientboundPlayerPositionPacket）
for (Entity passenger : passengers) {
    if (passenger != player && passenger instanceof ServerPlayer serverPassenger) {
        serverPassenger.connection.teleport(destX, destY, destZ, ...);  // 4. 其他玩家乘客同步位置
        serverPassenger.connection.resetPosition();
    }
}
for (Entity passenger : passengers) {
    passenger.startRiding(vehicle, true);      // 5. 重新骑乘
}

// 同维度 + 无载具
player.changeDimension(transition);            // 替代原来的 player.teleportTo()
```

**关键**：`ServerPlayer.changeDimension()` 同维度分支只做 `connection.teleport()` + `connection.resetPosition()`，不会触发维度切换逻辑（不发送 `ClientboundRespawnPacket`），开销极小。

### v34 → v35：光标逻辑清理 + tooltip拦截 + 十字光标修复 + 未探索消耗从背包凑

**问题1**：GUI传送中光标持有活末影珍珠时的同步逻辑复杂且存在bug（`carriedTag` 序列化、`CarriedUpdatePacket` 强制同步等），维护成本高。

**修复**：完全移除光标相关逻辑，简化交互流程：

- `LivingMapGuiTeleportPacket`：删除 `carriedTag` 字段及编解码逻辑，删除 `CarriedUpdatePacket` 同步，`resolvePearlStack` 仅从主/副手查找珍珠
- `AbstractContainerScreenMixin`：`hasLivingEnderPearl()` 移除 `menu.getCarried()` 检查，删除 `living_item$resolveCarriedTag` 方法，删除 `living_item$hideFloatingPearl` 注入
- 删除中键+活箱子拦截逻辑（不再需要）
- GUI传送现在只认主/副手的活末影珍珠，不检查光标

**问题2**：手持活末影珍珠悬浮在扩展地图上时会显示物品tooltip，影响视觉。

**修复**：注入 `renderTooltip` 方法，当手持活末影珍珠且鼠标在扩展地图区域或单个活地图槽位上时，取消tooltip渲染。

**问题3**：多个扩展地图时，每个地图上都会渲染十字光标，而非只在鼠标所在的地图上渲染。

**修复**：在十字光标渲染条件中增加 `findGroupAt(...) == group` 判断，确保只在鼠标实际所在的扩展地图上渲染光标。

**问题4**：未探索区域传送消耗只检查单个栈数量是否≥16，无法从多个栈凑齐16个。

**修复**：
- `LivingEnderPearlFunction` 新增 `countInInventory(player)` 统计整个背包活末影珍珠总数
- `LivingEnderPearlFunction` 新增 `consumeFromInventory(player, amount)` 从多个栈依次扣除凑齐指定数量
- `MapTeleportExecutor.execute()` 未探索判断改为 `countInInventory(player) >= UNEXPLORED_PEARL_COST`
- `TeleportHelper.consumePearl()` 未探索消耗改为 `consumeFromInventory(player, UNEXPLORED_PEARL_COST)`

### v35 → v36：容器区块缓存遍历 ConcurrentModification 导致服务器崩溃

**问题**：频繁传送到未探索区域后，游戏进程崩溃，服务器线程抛出 `NullPointerException`：

```
java.lang.NullPointerException: Cannot invoke "it.unimi.dsi.fastutil.objects.ObjectArrayList.get(int)" 
  because "this.wrapped" is null
    at it.unimi.dsi.fastutil.objects.ObjectOpenHashSet$SetIterator.next(ObjectOpenHashSet.java:575)
    at java.util.Collections$UnmodifiableCollection$1.next(Collections.java:1080)
    at com.qiqi.li.LivingItem.processLevelContainers(LivingItem.java:198)
```

**根因**：`processLevelContainers` 在遍历 `ContainerChunkCache` 的区块集合时，调用了 `cache.removeChunk()` 修改底层 `ObjectOpenHashSet`，触发 fastutil 迭代器的 fail-fast 机制。

具体流程：
1. `getCachedChunks()` 返回 `Collections.unmodifiableSet(raw)` — 只是底层 `ObjectOpenHashSet` 的只读视图，不是副本
2. `for (var chunkPos : chunkSet)` 遍历时，迭代器直接操作底层集合的内部数组
3. 遍历中调用 `cache.removeChunk()` → 修改底层 `ObjectOpenHashSet` → 迭代器检测到结构修改，将内部 `wrapped` 数组置为 null → 下一次 `next()` 调用抛出 NPE

**为什么频繁传送更容易触发**：频繁传送到未探索区域时，区块加载/卸载频繁，`processLevelContainers` 中"区块已卸载"或"区块不再包含容器"的判断更频繁命中，`removeChunk` 调用次数大增。

**修复**：两层防御：

**修复1**：`processLevelContainers` 中延迟移除 — 遍历时收集需要移除的区块到 `toRemove` 列表，遍历结束后统一调用 `cache.removeChunk()`：

```java
var toRemove = new ArrayList<ChunkPos>();

for (var chunkPos : chunkSet) {
    if (!level.hasChunk(chunkPos.x, chunkPos.z)) {
        toRemove.add(chunkPos);  // 不再立即移除
        continue;
    }
    // ... 处理容器 ...
    if (!hasContainer) {
        toRemove.add(chunkPos);  // 不再立即移除
    }
}

// 遍历结束后统一移除
for (var chunkPos : toRemove) {
    cache.removeChunk(level.dimension(), chunkPos);
}
```

**修复2**：`ContainerChunkCache.getCachedChunks()` 返回快照副本 — 从 `Collections.unmodifiableSet(raw)` 改为 `new HashSet<>(raw)`，确保即使区块加载/卸载事件在遍历期间修改了底层集合，也不会影响正在进行的遍历：

```java
public Set<ChunkPos> getCachedChunks(ResourceKey<Level> dim) {
    Set<ChunkPos> raw = chunkCache.get(dim);
    if (raw == null || raw.isEmpty()) return Collections.emptySet();
    return new HashSet<>(raw);  // 快照副本，而非原始集合的视图
}
```

**为什么 `Collections.unmodifiableSet()` 不够安全**：它只阻止通过该视图修改集合，但底层集合被其他途径（如事件回调 `onChunkLoad`/`onChunkUnload`）修改时，正在遍历该视图的迭代器仍会崩溃。返回快照副本则完全隔离了遍历与修改。

### v36 → v37：活末影珍珠查找遗漏副手槽位

**问题**：`LivingEnderPearlFunction.findInInventory()` 只遍历 `player.getInventory().items`（主背包 36 格），不检查副手槽位。当活末影珍珠只在副手时，手持传送会静默失败。

**影响范围**：

| 场景 | 珍珠查找方法 | 修复前 | 修复后 |
|------|------------|--------|--------|
| 手持传送 | `findInInventory(player)` | ❌ 不检查副手 | ✅ 检查副手 |
| GUI传送（创造模式） | `findInInventory(player)` | ❌ 不检查副手 | ✅ 检查副手 |
| GUI传送（生存模式） | `resolvePearlStack(player)` | ✅ 已检查主/副手 | 不变 |
| 展示框传送 | `resolvePearlStack(player, heldItem)` | ✅ 传入的 heldItem 已检查主/副手 | 不变 |

**修复**：在 `findInInventory` 方法末尾添加副手检查：

```java
@Nullable
public static ItemStack findInInventory(ServerPlayer player) {
    if (isOnCooldown(player)) return null;
    for (ItemStack stack : player.getInventory().items) {
        if (isLivingEnderPearl(stack)) {
            return stack;
        }
    }
    // 修复：主背包未找到时，检查副手
    ItemStack offhand = player.getOffhandItem();
    if (isLivingEnderPearl(offhand)) {
        return offhand;
    }
    return null;
}
```

**注意**：`countInInventory()` 和 `consumeFromInventory()` 原本已正确检查副手，无需修改。

### v37 → v38：展示框活地图传送时客户端未取消事件导致地图旋转

**问题**：`ItemFrameMapTeleportHandler.onEntityInteractSpecific()` 第一行 `if (!(event.getEntity() instanceof ServerPlayer player)) return;` 导致客户端事件直接返回，不取消事件。客户端的原版展示框交互逻辑正常执行，将活地图旋转 45°。服务端虽然取消了事件，但不会发送纠正包（因为服务端认为交互从未发生），导致客户端-服务端展示框旋转状态不同步。

**触发条件**：玩家手持活末影珍珠右键展示框上的活地图时，传送成功但地图被旋转。在机械动力载具上更容易观察到（载具传送后展示框状态变化更明显）。

**根因**：

```java
// 修复前：客户端事件直接 return，不取消
if (!(event.getEntity() instanceof ServerPlayer player)) return;  // ← 客户端直接跳过
// ... 后续取消事件的代码在客户端不会执行
event.setCanceled(true);  // ← 只在服务端执行
```

**修复**：将 `ServerPlayer` 检查移到事件取消之后，确保客户端和服务端都取消事件，防止原版展示框旋转逻辑执行。传送逻辑仅在服务端执行：

```java
// 修复后：先取消事件（客户端+服务端），再判断服务端执行传送
if (!(event.getTarget() instanceof ItemFrame frame)) return;
if (!LivingItemManager.isLivingMap(frame.getItem())) return;

ItemStack heldItem = event.getEntity().getMainHandItem();
if (!LivingEnderPearlFunction.isLivingEnderPearl(heldItem)) {
    heldItem = event.getEntity().getOffhandItem();
    if (!LivingEnderPearlFunction.isLivingEnderPearl(heldItem)) return;
}

// 客户端和服务端都取消事件，防止原版展示框旋转逻辑执行
event.setCanceled(true);
event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide()));

// 传送逻辑仅在服务端执行
if (!(event.getEntity() instanceof ServerPlayer player)) return;
```

### v38 → v39：创造模式 GUI 传送单个活地图失败（SlotWrapper 索引不匹配）

**问题**：创造模式下右键单个活地图无法传送，扩展地图传送正常。服务端日志显示 `resolveSlot returned null for slotIndex=0`。

**根因**：创造模式下客户端使用 `CreativeModeInventoryScreen.ItemPickerMenu`，`hoveredSlot.index` 是 `ItemPickerMenu` 的槽位索引。但服务端的 `player.containerMenu` 是 `InventoryMenu`，两者的槽位索引不对应。例如客户端 slot 0 指向背包第一格，而服务端 `InventoryMenu` 的 slot 0 是合成结果槽。

**为什么扩展地图不受影响**：扩展地图的 `MapGroup.topLeftSlotIndex()` 也是 `ItemPickerMenu` 的索引，同样存在不匹配问题。但扩展地图传送在之前的版本中可能未在创造模式下测试过，或恰好索引对齐。

**SlotWrapper 机制**：`CreativeModeInventoryScreen` 的 `ItemPickerMenu` 中，背包槽位被 `SlotWrapper` 包装。`SlotWrapper` 持有底层 `InventoryMenu` 的 `Slot` 引用（通过 `target` 字段），其 `index` 才是服务端能识别的索引。

**修复**：在 `AbstractContainerScreenMixin` 中添加 `living_item$resolveServerSlotIndex` 方法，发送传送包前解包 `SlotWrapper`：

```java
@Unique
private int living_item$resolveServerSlotIndex(Slot slot) {
    if (slot instanceof SlotWrapperAccessor accessor) {
        return accessor.getTarget().index;
    }
    return slot.index;
}

@Unique
private int living_item$resolveServerSlotIndex(int clientSlotIndex) {
    if (clientSlotIndex >= 0 && clientSlotIndex < menu.slots.size()) {
        Slot slot = menu.slots.get(clientSlotIndex);
        if (slot instanceof SlotWrapperAccessor accessor) {
            return accessor.getTarget().index;
        }
    }
    return clientSlotIndex;
}
```

`SlotWrapperAccessor` 是通过 Mixin `@Accessor` 暴露 `SlotWrapper.target` 字段的接口：

```java
@Accessor(target = "net.minecraft.world.inventory.SlotWrapper", value = "target")
public interface SlotWrapperAccessor {
    Slot getTarget();
}
```

**调用点**：`mouseClicked` 中发送 `LivingMapGuiTeleportPacket` 时，对 `group.topLeftSlotIndex()` 和 `hoveredSlot` 都调用 `resolveServerSlotIndex`，确保客户端发送的槽位索引与服务端 `InventoryMenu` 一致。

### v39 → v40：跨地图传送首次传送到基岩层

**问题**：跨地图传送到一个很久没有传送过的地图时，玩家会被传送到基岩层（Y=-64），卡在方块里面。后续再传送到同一地图则正常。

**根因**：`Level.getHeightmapPos()` 内部先调用 `hasChunk()` 检查区块是否已加载，如果 `hasChunk()` 返回 `false`，直接返回 `getMinBuildHeight()`（主世界 -64，即基岩层高度）。

```java
// Level.java
public int getHeight(Heightmap.Types heightmapType, int x, int z) {
    if (this.hasChunk(...)) {                          // ← 先检查区块是否"已加载"
        return this.getChunk(...).getHeight(...) + 1;  // ← 已加载：从区块读取高度图
    } else {
        return this.getMinBuildHeight();               // ← 未加载：返回 -64（基岩层！）
    }
}
```

而 `ensureChunkLoaded` 通过 `level.getChunk()` 加载区块时，内部添加的是 `TicketType.UNKNOWN` 类型的 ticket，其超时时间仅 **1 tick**：

```java
// TicketType.java
public static final TicketType<ChunkPos> UNKNOWN = create("unknown", Comparator.comparingLong(ChunkPos::toLong), 1);
//                                                                                                            ↑ timeout = 1 tick
```

在 `ServerChunkCache.tick()` 的 `purgeStaleTickets()` 中，超时的 ticket 会被立即清理。如果区块没有其他 ticket 保持加载（如玩家附近的 `PLAYER` ticket），`hasChunk()` 就会返回 `false`。

**为什么"第一次传送到基岩层，后续正常"**：

| 次数 | 区块状态 | hasChunk() | getHeightmapPos() 返回 |
|------|---------|-----------|----------------------|
| 第一次 | 区块从未加载，`ensureChunkLoaded` 添加 `UNKNOWN` ticket（1 tick 超时） | `false`（ticket 可能已被清理） | `getMinBuildHeight()` = -64 |
| 后续 | 玩家已传送到目标位置附近，`PLAYER` ticket 保持区块加载 | `true` | 正确的地表高度 |

**修复**：`findSafeY` 直接从 `ensureChunkLoaded` 返回的 `LevelChunk` 对象读取高度图，绕过 `hasChunk()` 检查。高度图数据本身是正确的，不需要扫描逻辑：

```java
private static int findSafeY(LevelChunk chunk, BlockPos pos) {
    return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX() & 15, pos.getZ() & 15) + 1;
}
```

`ensureChunkLoaded` 改为返回 `ChunkLoadResult` record，包含 `LevelChunk` 和加载耗时：

```java
private record ChunkLoadResult(LevelChunk chunk, long elapsedMs) {}

private static ChunkLoadResult ensureChunkLoaded(ServerLevel level, double x, double z) {
    int chunkX = (int) x >> 4;
    int chunkZ = (int) z >> 4;
    LevelChunk chunk = level.getChunk(chunkX, chunkZ);
    return new ChunkLoadResult(chunk, elapsed);
}
```

同时修复了 `destY = safeY + 1.0` 的 bug：`findSafeY` 返回的已经是玩家脚底 Y 坐标（`chunk.getHeight() + 1`），不需要再加 1。

### v40 → v41：可传送图标扩展至33种 + 准心颜色统一

**问题1**：传送仅支持旗帜（`banner_*`）和红色大叉叉（`red_x`）两种图标，原版地图的33种图标（结构、村庄、玩家、物品帧等）均不可传送。

**修复1**：`MapCoordHelper` 新增 `TELEPORTABLE_DECORATION_PATHS` 常量集（33个注册名路径），新增 `isTeleportableType(decoration)` 和 `isTeleportableEntry(entry)` 判定方法，替换原来的 `isRedXType`/`isRedXEntry`/`isBannerDecorationHit`。`findTargetPointHit` 现在匹配所有33种可传送图标。

**可传送图标（33种）**：

| 类别 | 图标 | 数量 |
|------|------|------|
| 通用 | player, frame, red_marker, blue_marker, target_x, target_point, red_x | 7 |
| 结构 | mansion, monument, jungle_temple, swamp_hut, trial_chambers | 5 |
| 村庄 | village_desert, village_plains, village_savanna, village_snowy, village_taiga | 5 |
| 旗帜 | banner_white ~ banner_black | 16 |

**不可传送图标（2种）**：`player_off_map`、`player_off_limits`——坐标被 `MapItemSavedData` 截断到地图边缘（±128/127），不是真实世界坐标。

**问题2**：准心颜色有4档（金色=旗帜、青色=红X、绿色=已探索、红色=未探索），旗帜和红X用不同颜色区分，但扩展到33种图标后区分颜色意义不大。

**修复2**：合并为3档——橙色（`0xFFFFAA00`，命中任意可传送图标）、绿色（已探索）、红色（未探索）。`LivingMapTargetRenderer` 的 `bannerHit`+`targetPointHit` 参数合并为单个 `decoHit` 参数，`COLOR_BANNER`+`COLOR_TARGET` 合并为 `COLOR_DECO_HIT`。

**修改文件**：

| 文件 | 改动 |
|------|------|
| `MapCoordHelper` | 新增 `TELEPORTABLE_DECORATION_PATHS`、`isTeleportableType`、`isTeleportableEntry`、`isTeleportableDecorationHit`，替换 `isRedXType`/`isRedXEntry`/`isBannerDecorationHit` |
| `LivingMapTargetRenderer` | 合并 `bannerHit`+`targetPointHit` → `decoHit`，合并 `COLOR_BANNER`+`COLOR_TARGET` → `COLOR_DECO_HIT` |
| `MapRendererMixin` | 适配新 API，移除 `MapDecoration` import |
| `ItemInHandRendererMixin` | 同上 |
| `AbstractContainerScreenMixin` | 同上 |

### v41 → v42：修复远距离传送后服务端卡死

**问题**：远距离传送后，服务端完全卡死（无响应约30秒+）。日志显示 `processLevelContainers` 每 tick 耗时 200-600ms，区块数持续增长（46→106），且 `removed=0`（无区块被移除）。

**根因**：`processLevelContainers` 中使用 `level.getChunk(chunkPos.x, chunkPos.z)` 获取区块。`getChunk` 是同步加载调用，会给区块添加 `TicketType.UNKNOWN`（1 tick 超时），阻止区块卸载。这导致：

1. 传送后，旧区块应被卸载
2. 但 `processLevelContainers` 每 tick 遍历缓存，对每个缓存区块调用 `getChunk`
3. `getChunk` 重新加载旧区块（添加 1 tick ticket）
4. 下一个 tick，ticket 过期，区块准备卸载
5. 但 `processLevelContainers` 又调用 `getChunk` 重新加载
6. **死循环**：旧区块永远无法卸载，缓存只增不减

远距离传送后新区块大量加载加入缓存，旧区块又无法卸载移除，缓存暴涨，每 tick 处理时间超过 tick 预算（50ms），服务端越来越慢直至卡死。

**修复**：将 `level.getChunk()` 替换为 `level.getChunkSource().getChunkNow()`。`getChunkNow` 只返回已加载的区块，不触发加载、不添加 ticket。区块未加载时返回 null，直接加入 `toRemove` 移除缓存。

**修改文件**：

| 文件 | 改动 |
|------|------|
| `LivingItem.processLevelContainers` | `level.getChunk()` → `level.getChunkSource().getChunkNow()`，`null` 时加入 `toRemove` |
| `ContainerChunkCache.rescanChunk` | 同上，`level.hasChunk()` + `level.getChunk()` → `getChunkNow()` |