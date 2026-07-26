<!-- markdownlint-disable -->
# 活地图 + 活末影珍珠 设计草稿

> 状态：草稿，待实施
> 日期：2026-07-25

---

## 一、概述

### 活地图

原版地图开图后必须拿在手上才能查看，但活地图在物品栏里就能看，会将物品栏格子本身当成展示地图的区域。

- 如果活地图周围围一圈没有打开过的活地图，显示区域会扩展到周围（3×3）
- 再围一圈可扩展到 5×5，以此类推

### 活末影珍珠

- 在容器界面光标拿着活末影珍珠，右键活地图上的位置 → 传送到对应位置
- 右键物品展示框里的活地图 → 传送
- 右键活地图上的旗帜标记 → 传送到旗帜位置

---

## 二、实施路线

| 阶段 | 内容 | 难度 |
|------|------|------|
| **Phase 1** | 单个活地图在 GUI 槽位中渲染（1×1） | 🟡 中 |
| **Phase 2** | 3×3 扩展渲染（纯视觉放大） | 🟡 中 |
| **Phase 3** | 物品展示框路线（地图渲染 + 传送） | 🟡 中 |
| **Phase 4** | 活末影珍珠右键传送（GUI / 展示框 / 旗帜） | 🟢 低 |
| **Phase 5** | 5×5+ 扩展、自动生成相邻地图数据 | 🔴 高 |

> **建议**：Phase 3 物品展示框路线先做，因为原版 `ItemFrameRenderer` 天然支持地图渲染，不需要任何渲染 Mixin。Phase 1-2 的坐标换算逻辑可直接复用。

---

## 三、活地图 — GUI 槽位渲染

### 核心难点

原版物品栏槽位只渲染静态 item sprite（16×16），地图是动态纹理。需要劫持槽位渲染。

### 方案 A：劫持槽位渲染（推荐）

```
物品栏 GUI 渲染槽位时
  → 检测槽位物品是活地图
  → 不走正常 ItemRenderer
  → 从原版 MapItemSavedData 拿地图纹理
  → 直接画到槽位区域（可放大到 32×32 甚至跨槽位）
```

需要 Mixin 的地方：
- `ItemRenderer.renderGuiItem()` — 拦截单个物品渲染
- `AbstractContainerScreen.renderSlot()` — 或在此层拦截更有控制力

### 方案 B：GUI 叠加层（备选，更简单）

```
在容器 GUI 上方叠加一个自定义渲染层
  → 检测背包中活地图的布局
  → 在对应位置绘制大地图
  → 和槽位渲染分离，互不干扰
```

### 扩展机制：3×3 → 5×5 → ...

| 方案 | 描述 | 复杂度 |
|------|------|--------|
| **A：真实地图拼接** | 未打开地图自动生成数据（分配新 map ID），拿走地图后数据保留 | 🔴 高 |
| **B：纯视觉放大** | 仅扩大中心地图的渲染视野，像缩放一样，地图拿走视野缩回 | 🟡 中 |

> 建议 B 先做，因为不涉及地图数据生成，只是渲染层的放大。Phase 5 再考虑 A。

```
扫描逻辑:
  找到已打开的活地图（中心）
  向外 BFS 搜索未打开的活地图
  每完整一圈 → 扩展 1 层
  3×3 = 1 圈，5×5 = 2 圈，以此类推
```

### 地图数据存储

```json
{
  "living_map": {
    "map_id": 123,
    "opened": true
  }
}
```

复用原版 `MapItemSavedData` 系统，不需要自己管理地图数据。

---

## 四、活地图 — 物品展示框路线

### 优势

原版 `ItemFrameRenderer` 自动渲染完整大地图（128×128），**不需要任何渲染 Mixin**。比 GUI 路线简单得多。

### 需要 Mixin 的地方

`ItemFrameEntity.interactAt()` — 唯一需要 Mixin 的地方，因为只有它有精确的 `hitVec` 参数。

```java
@Inject(method = "interactAt", at = @At("HEAD"), cancellable = true)
private void onInteractAt(Player player, Vec3 hitVec, InteractionHand hand,
                          CallbackInfoReturnable<InteractionResult> cir) {
    // 手持活末影珍珠 + 展示框内是活地图 → 计算坐标 → 传送
}
```

### 交互设计

| 场景 | 行为 |
|------|------|
| 空手右键 | 正常（旋转物品/取下） |
| 手持活末影珍珠右键（地图区域内） | 传送 + 消耗珍珠 |
| 手持活末影珍珠右键（地图边框上） | 正常交互（旋转） |
| 手持活末影珍珠右键普通物品展示框 | 正常交互 |
| 手持普通物品右键活地图展示框 | 正常交互 |

---

## 五、坐标换算核心逻辑

### 展示框路线：3D 射线 → 世界坐标

```
3D 点击位置 (hitX, hitY, hitZ)
  ↓ 物品展示框朝向 + 位置
地图 UV (0.0~1.0, 0.0~1.0)
  ↓ × 128
地图像素 (0~127, 0~127)
  ↓ MapItemSavedData 中心 + 缩放
世界坐标 (worldX, worldZ)
```

```java
Vec3 hitPos = hitResult.getLocation();
BlockPos framePos = frameEntity.getBlockPos();
Direction facing = frameEntity.getDirection();

// 计算点击位置在 frame 面上的 UV
double u = 0, v = 0;
switch (facing) {
    case NORTH -> { u = hitPos.x - framePos.getX();     v = 1 - (hitPos.y - framePos.getY()); }
    case SOUTH -> { u = 1 - (hitPos.x - framePos.getX()); v = 1 - (hitPos.y - framePos.getY()); }
    case EAST  -> { u = hitPos.z - framePos.getZ();     v = 1 - (hitPos.y - framePos.getY()); }
    case WEST  -> { u = 1 - (hitPos.z - framePos.getZ()); v = 1 - (hitPos.y - framePos.getY()); }
    case DOWN  -> { u = hitPos.x - framePos.getX();     v = hitPos.z - framePos.getZ(); }
    case UP    -> { u = hitPos.x - framePos.getX();     v = 1 - (hitPos.z - framePos.getZ()); }
}

// frame 内的地图区域: 0.0625~0.9375（即 1/16 ~ 15/16）
double mapU = (u - 0.0625) / 0.875;
double mapV = (v - 0.0625) / 0.875;

if (mapU < 0 || mapU > 1 || mapV < 0 || mapV > 1) {
    return; // 点击在 frame 边框上
}

// 地图像素坐标
int mapX = (int)(mapU * 128);
int mapY = (int)(mapV * 128);

// 世界坐标
MapItemSavedData mapData = MapItemSavedData.getMapData(mapId, level);
int scale = 1 << mapData.scale; // 1, 2, 4, 8, 16
int worldX = mapData.centerX + (mapX - 64) * scale;
int worldZ = mapData.centerZ + (mapY - 64) * scale;
```

### GUI 路线：屏幕像素 → 世界坐标

类似展示框，但输入是 `(screenX, screenY)` 而非 `hitVec`，需要先换算到槽位内的相对坐标。

---

## 六、旗帜标记传送

### 原版机制

原版 `MapItemSavedData` 中已存储旗帜标记：

```java
Map<String, MapBanner> bannerMarkers;  // "颜色@位置" → 旗帜数据

record MapBanner(BlockPos pos, DyeColor color, Component name) {}
```

数据已经在了，只需要做命中检测。

### 旗帜命中检测

```java
// 旗帜的世界坐标 → 地图像素坐标
int bannerPx = (banner.pos().getX() - mapData.centerX) / scale + 64;
int bannerPy = (banner.pos().getZ() - mapData.centerZ) / scale + 64;

// 命中半径 3 像素
int dx = clickPx - bannerPx;
int dy = clickPy - bannerPy;
if (dx * dx + dy * dy <= 3 * 3) {
    // 命中旗帜！传送到旗帜位置
    targetX = banner.pos().getX() + 0.5;
    targetZ = banner.pos().getZ() + 0.5;
}
```

### 优先级

```
点击位置命中检测优先级:
  1. 旗帜标记（最优先）
  2. 普通地图区域（兜底）
```

---

## 七、传送后处理

```
传送成功后:
  1. 播放末影珍珠传送粒子效果
  2. 播放传送音效
  3. 造成少量摔落伤害（还原原版末影珍珠体验）
  4. 消耗活末影珍珠（count - 1 或耐久 - 1）
  5. 设置冷却（防止连续传送）
  6. 命中旗帜时显示 ActionBar: "已传送到 {旗帜名称}"
```

---

## 八、传送三模式总结

| 模式 | 触发方式 | 传送目标 |
|------|---------|---------|
| GUI 右键 | 容器界面中右键活地图 | 点击位置对应的世界坐标 |
| 展示框右键 | 右键墙上活地图展示框 | 点击位置对应的世界坐标 |
| 旗帜标记 | 右键活地图上的旗帜标记 | 旗帜所在的世界坐标 |

三个模式共用同一套坐标换算核心逻辑，只是入口不同。旗帜标记模式是展示框模式的子集，多了旗帜命中检测。

---

## 九、GUI vs 物品展示框 对比

| 维度 | GUI 槽位 | 物品展示框 |
|------|---------|-----------|
| 地图渲染 | 需要 Mixin 劫持 | 原版自动支持 |
| 点击精度 | GUI → 世界坐标换算 | 3D 射线精确命中 |
| 3×3 扩展 | 需要跨槽位渲染 | 放多个展示框即可 |
| 多人可见 | 仅自己 | 所有人都能看到 |
| 实现难度 | 🔴 高 | 🟡 中 |