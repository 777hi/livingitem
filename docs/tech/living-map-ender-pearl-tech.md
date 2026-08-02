# Living Map & Living Ender Pearl (活地图 & 活末影珍珠) 技术文档

> **文档版本**: 2026.08 v4  
> **最后更新**: 2026-08-03  
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
11. [关键类和职责](#11-关键类和职责)
12. [已知问题与修复记录](#12-已知问题与修复记录)

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
│          │                    │ 3. 检查是否已探索          │        │
│          │                    │ 4. TeleportHelper传送     │        │
│          ▼                    │ 5. 消耗珍珠+冷却+伤害     │        │
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

## 2. 活末影珍珠：数据与冷却

### 2.1 数据结构

```java
// LivingEnderPearlData.java
public record LivingEnderPearlData(int cooldown) {
    public static final LivingEnderPearlData DEFAULT = new LivingEnderPearlData(0);
}
```

存储在物品的 `LIVING_ENDER_PEARL_DATA` DataComponent 中，仅包含一个字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `cooldown` | int | 冷却倒计时（tick），0 表示可用 |

### 2.2 冷却机制

```java
// LivingEnderPearlFunction.tick() — 每 tick 执行
if (data.cooldown() > 0) {
    LivingItemManager.setEnderPearlData(stack, data.withCooldown(data.cooldown() - 1));
    container.syncSlotToClients(entry.slotIndex(), stack);
}
```

- 传送后冷却 40 tick（2 秒）
- 冷却期间 tooltip 显示灰色倒计时
- 冷却结束后 tooltip 显示绿色"就绪"

### 2.3 传送消耗

| 模式 | 消耗 |
|------|------|
| 生存模式 | 消耗 1 个活末影珍珠（`shrink(1)`） |
| 创造模式 | 不消耗 |

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
  ├─ 检查：目标附近是否有旗帜？（5像素半径内）
  │   ├─ 有 → 传送到旗帜位置
  │   └─ 无 → 检查目标是否已探索
  │       ├─ 未探索 → 提示"未探索区域"，取消
  │       └─ 已探索 → 传送到目标位置
  │
  ├─ 传送：TeleportHelper.teleportToMapPosition
  │   ├─ findSafeY → MOTION_BLOCKING 高度图
  │   ├─ 跨维度传送 / 同维度传送
  │   ├─ 传送粒子效果 + 音效
  │   ├─ 5点坠落伤害
  │   ├─ 消耗珍珠 + 设置冷却
  │   └─ 重置坠落距离
  │
  └─ 跨维度提示（如果目标维度与当前不同）
```

### 4.2 安全Y坐标

```java
private static int findSafeY(ServerLevel level, BlockPos pos) {
    return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY();
}
```

使用 Minecraft 原版的 `MOTION_BLOCKING` 高度图，直接获取该 X/Z 列最高的阻挡运动方块的 Y 坐标。石头、泥土、树叶、木板等都算阻挡运动，确保玩家传送到地表而非地底。

### 4.3 传送优先级

传送时按以下优先级依次检测：

```
1. 旗帜命中（5像素半径内）
   → 传送到旗帜精确位置（bannerPos.getY() + 1.0），不走地表高度
   → 旗帜本身需要两格空间，位置安全

2. 藏宝图红色大叉叉命中（5像素半径内）
   → 优先从 MAP_DECORATIONS 组件读取精确世界坐标
   → 组件缺失时回退到像素→世界坐标转换
   → 传送到目标位置的地表高度

3. 普通区域
   → 检查是否已探索，未探索则取消
   → 传送到目标位置的地表高度
```

### 4.4 旗帜传送

旗帜传送使用 `MapBanner.pos()` 获取精确世界坐标，直接传送到旗帜所在位置（`bannerPos.getY() + 1.0`），不走 `findSafeY` 地表高度。这样旗帜在地底等位置时也能精准到达。

### 4.5 藏宝图红色大叉叉传送

藏宝图（Explorer Map）的红色大叉叉是 `MapDecorationType` 注册名为 `minecraft:red_x` 的装饰。传送坐标获取分两步：

1. **优先路径**：从物品的 `DataComponents.MAP_DECORATIONS` 组件读取 `red_x` 条目的精确世界坐标（`entry.x()`, `entry.z()`）
2. **回退路径**：组件缺失时，从 `MapDecoration` 的像素坐标反推世界坐标（有缩放精度损失）

> **注意**：`red_x` 装饰的 `explorationMapElement()` 返回 `false`，不能用此方法过滤。

### 4.6 传送参数

| 参数 | 值 | 说明 |
|------|-----|------|
| 冷却 | 40 tick (2秒) | 防止频繁传送 |
| 坠落伤害 | 5.0 | 模拟末影珍珠伤害 |
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

**颜色四档**：

| 颜色 | 条件 | 说明 |
|------|------|------|
| 🟡 金色 `0xFFFFAA00` | 准心命中旗帜 | 旗帜可精准传送 |
| 🔵 青色 `0xFF00DDFF` | 准心命中红色大叉叉 | 藏宝图宝箱位置 |
| 🟢 绿色 `0xFF00FF00` | 已探索区域 | 可传送 |
| 🔴 红色 `0xFFFF3333` | 未探索区域 | 不可传送 |

命中旗帜或红色大叉叉时，准心稍大（halfSize = 5.0 vs 4.0），提供视觉"锁定"反馈。

**渲染参数**：
- 纹理来源：`GuiSpriteManager.getSprite("minecraft:hud/crosshair")`
- 渲染类型：`RenderType.text(sprite.atlasLocation())`
- 顶点格式：`addVertex().setColor(r,g,b,a).setUv(u,v).setLight(packedLight)`
- 渲染层级：z = -0.03（略低于地图纹理，避免 z-fighting）

### 5.3 客户端旗帜/宝藏检测

客户端无法使用 `mapData.getBanners()`（数据不传输），改用 `mapData.getDecorations()` 检测：

| 检测方法 | 数据来源 | 过滤条件 | 用途 |
|---------|---------|---------|------|
| `isBannerDecorationHit` | `getDecorations()` | 注册名 `startsWith("banner_")` | 客户端准心变色 |
| `findTargetPointHit` | `getDecorations()` | 注册名 `equals("red_x")` | 客户端准心变色 |
| `findBannerHit` | `getBanners()` | 遍历所有 MapBanner | 服务端传送（精确世界坐标） |

**旗帜注册名格式**：`minecraft:banner_<颜色>`（如 `banner_pink`、`banner_white`），注意是 `banner_` 前缀而非 `_banner` 后缀。

**红色大叉叉注册名**：`minecraft:red_x`，注意 `explorationMapElement()` 返回 `false`，不能用此方法过滤。

**客户端旗帜检测需要传入正确的 centerX/centerZ**：客户端 `mapData.centerX/centerZ` 为 0（不准确），必须使用 `LivingMapClientCache` 中的元数据。`findBannerHit` 提供了重载版本 `findBannerHit(mapData, mapX, mapY, centerX, centerZ)` 供客户端使用。

### 5.4 渲染代码结构

```java
@Inject(method = "renderMap", at = @At("TAIL"))
private void renderLivingMapTargetMarker(PoseStack poseStack, MultiBufferSource buffer, ...) {
    // 1. 检查是否为活地图
    // 2. 获取 MapItemSavedData
    // 3. 从 LivingMapClientCache 获取元数据
    // 4. calcTargetMapPixel → 目标像素坐标
    // 5. 检查是否在地图范围内
    // 6. isBannerDecorationHit → 旗帜命中检测（客户端用 decorations）
    // 7. findTargetPointHit → 红色大叉叉命中检测
    // 8. isExplored → 已探索检测
    // 9. renderMarker → 原版准心纹理 + 四色着色
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
  ├─ 检查：目标附近是否有旗帜？（5像素半径内）
  │   ├─ 有 → 传送到旗帜位置
  │   └─ 无 → 检查是否有红色大叉叉？
  │       ├─ 有 → 传送到宝藏位置
  │       └─ 无 → 检查目标是否已探索
  │           ├─ 未探索 → 提示"未探索区域"，取消
  │           └─ 已探索 → 传送到目标位置
  │
  ├─ 传送：TeleportHelper（复用手持地图传送逻辑）
  │
  └─ 安全物品返回：传送前将光标物品放回背包，背包满则掉落至玩家脚下
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

与手持地图标记完全一致：原版准心纹理 + 四色着色 + z=-0.03 偏移。

| 颜色 | 条件 | 说明 |
|------|------|------|
| 🟡 金色 | 命中旗帜 | 旗帜可精准传送 |
| 🔵 青色 | 命中红色大叉叉 | 藏宝图宝箱位置 |
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
        // 6. 旗帜/宝藏/已探索 命中检测
        // 7. renderMarker → 原版准心纹理 + 四色着色
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

## 11. 关键类和职责

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `MapCoordHelper` | `domain/map/MapCoordHelper.java` | 坐标计算核心：射线-矩形相交、视角映射、像素↔世界坐标转换、旗帜命中检测、hitVec→UV转换 |
| `LivingMapEventHandler` | `domain/map/LivingMapEventHandler.java` | 事件处理入口：右键传送逻辑、元数据同步包发送 |
| `ItemFrameMapTeleportHandler` | `domain/map/ItemFrameMapTeleportHandler.java` | 展示框传送：EntityInteractSpecific事件拦截、hitVec→像素坐标、安全物品返回 |
| `TeleportHelper` | `domain/map/TeleportHelper.java` | 传送执行：安全Y坐标、跨维度传送、粒子/音效、伤害、冷却 |
| `LivingEnderPearlFunction` | `function/LivingEnderPearlFunction.java` | 活末影珍珠功能：冷却 tick、tooltip、消耗判断 |
| `LivingEnderPearlData` | `data/LivingEnderPearlData.java` | 活末影珍珠数据组件：cooldown 字段 |
| `LivingMapMetadataPacket` | `network/LivingMapMetadataPacket.java` | 服务器→客户端网络包：同步地图元数据 |
| `LivingMapClientCache` | `domain/map/LivingMapClientCache.java` | 客户端缓存：按 mapId 存储 centerX/centerZ/dimension |
| `ItemInHandRendererMixin` | `client/mixin/ItemInHandRendererMixin.java` | 客户端渲染：注入 renderMap 方法，3D空间中渲染目标标记 |
| `MapRendererMixin` | `client/mixin/MapRendererMixin.java` | 客户端渲染：注入 MapRenderer.render 方法，展示框地图光标渲染 |
| `MapItemMixin` | `living/mixin/MapItemMixin.java` | 活空地图：注入 EmptyMapItem.use 方法，根据堆叠数量和朝向在远程位置创建活地图 |

---

## 12. 已知问题与修复记录

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