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

> **实施顺序调整**：先做展示框传送（最快出可玩功能），再做 GUI 渲染（最难）。
> 活地图是纯被动型活物品——不 tick，状态不变，所有数据从原版 `MapItemSavedData` 读取。
> 因此活地图**不需要自己的 DataComponent**，只需 `IS_LIVING: true` 标记。

| 阶段 | 内容 | 难度 | 新增文件 |
|------|------|------|---------|
| **Phase 0** | 基础设施：活末影珍珠 DataComponent + 活按钮配置 | 🟢 低 | 2 |
| **Phase 1** | 展示框传送（最小可玩版本） | 🟡 中 | 3 |
| **Phase 2** | GUI 叠加层渲染（方案 B） | 🔴 高 | 3~4 |
| **Phase 3** | GUI 右键传送 | 🟡 中 | 1~2 |
| **Phase 4** | 3×3 扩展渲染（纯视觉放大） | 🟡 中 | 0~1 |
| **Phase 5** | 5×5+ 扩展、自动生成相邻地图数据 | 🔴 高 | 0~1 |

### Phase 0：基础设施

| 步骤 | 内容 | 产出 |
|------|------|------|
| 0.1 | 活按钮配置 | 地图物品 + 末影珍珠可被活按钮激活（只需 `IS_LIVING` 标记） |
| 0.2 | `LivingEnderPearlData` DataComponent | record，存 `cooldown: int`（唯一需要状态的活物品） |
| 0.3 | 在 `LivingItemManager` 注册 | `LIVING_ENDER_PEARL_DATA` 组件注册 + 便捷方法 |
| 0.4 | `LivingEnderPearlFunction` | 活末影珍珠功能类，tick 中递减冷却 |

> **为什么活地图不需要 DataComponent？**
> 原版地图物品已有 `minecraft:map_id` 组件，活地图只需 `IS_LIVING: true` 标记。
> 所有地图数据（地形、旗帜、玩家标记）从 `Level.getMapData(mapId)` 读取，
> 不需要额外存储。活地图是纯被动型活物品——不 tick，状态不变。

### Phase 1：展示框传送（最小可玩版本）

墙上挂活地图，手持活末影珍珠右键传送。

| 步骤 | 内容 | 技术细节 |
|------|------|---------|
| 1.1 | 坐标换算工具类 | `MapCoordHelper.java` — hitVec → 地图 UV → 地图像素 → 世界坐标，纯静态方法 |
| 1.2 | 旗帜命中检测 | `MapCoordHelper.findBannerHit()` — 遍历 `MapItemSavedData.banners`，3 像素半径命中 |
| 1.3 | 传送执行工具类 | `TeleportHelper.java` — 传送 + 粒子 + 音效 + 摔落伤害 + 消耗珍珠 + 冷却 |
| 1.4 | ItemFrame Mixin | `ItemFrameMixin.java` — 注入 `interactAt()`，拦截活末影珍珠 + 活地图展示框交互 |

### Phase 2：GUI 叠加层渲染

| 步骤 | 内容 | 技术细节 |
|------|------|---------|
| 2.1 | 扫描活地图布局 | `LivingMapLayout.java` — 遍历容器槽位，找到所有活地图的网格位置 |
| 2.2 | 地图纹理渲染 | `LivingMapRenderer.java` — 从 `MapRenderer.getTextureId(mapId)` 获取动态纹理，画到 GUI |
| 2.3 | Mixin 渲染入口 | `AbstractContainerScreenMixin` — 在 `render()` 尾部注入叠加层渲染 |

> **选择方案 B（叠加层）而非方案 A（劫持槽位）**：
> 叠加层和原版槽位渲染完全独立，只需一个注入点，天然支持跨槽位渲染。

### Phase 3：GUI 右键传送

| 步骤 | 内容 | 技术细节 |
|------|------|---------|
| 3.1 | 屏幕像素 → 世界坐标 | `MapCoordHelper.screenToWorld()` — 复用像素→世界坐标逻辑 |
| 3.2 | GUI 交互规则 | 复用 `GuiInteractionPacket` 体系，注册 `MapTeleportInteraction` |

### Phase 4：3×3 扩展渲染

| 步骤 | 内容 | 技术细节 |
|------|------|---------|
| 4.1 | BFS 扫描相邻活地图 | `LivingMapLayout.scanExpansion()` — 从中心 BFS，每完整一圈扩展 1 层 |
| 4.2 | 纯视觉放大 | 修改 `LivingMapRenderer`，根据扩展层数放大中心地图的渲染视野 |

### Phase 5：5×5+ 扩展 + 真实地图拼接（可选）

| 步骤 | 内容 | 技术细节 |
|------|------|---------|
| 5.1 | 自动生成相邻地图数据 | 为未打开的活地图分配新 map ID，计算相邻区域 centerX/centerZ |
| 5.2 | 多地图拼接渲染 | 多个 MapItemSavedData 的 colors 数组拼接成大地图 |
| 5.3 | 地图数据生命周期 | 拿走活地图后数据保留（原版行为），但不再更新 |

> Phase 5 涉及地图数据生成和生命周期管理，建议等 Phase 1-4 稳定后再考虑。

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

---

## 十、补充设计细节

### 展示框碰撞箱扩展

原版展示框碰撞箱比方块面小一圈，边缘点击会命中背后方块。
活地图展示框需要碰撞箱覆盖整个方块面，确保边缘点击也能触发 `interactAt()`。

**实现**：Mixin `ItemFrame`，当展示框内是活地图时，返回覆盖整个方块面的碰撞箱（厚度 1/16，宽高 1×1）。

### 展示框朝向与地图旋转

展示框可挂在 6 个方向（NORTH/SOUTH/EAST/WEST/UP/DOWN），地图可在展示框内旋转（0~3，每次顺时针 90°）。

**朝向**：6 个方向的 UV 换算见第四章坐标换算核心逻辑。

**旋转**：`ItemFrame.getRotation()` 返回 0~3，UV 需要对应变换：

```
rotation 0 (0°):   (u, v) → (u, v)           不变
rotation 1 (90°):  (u, v) → (v, 1-u)         顺时针 90°
rotation 2 (180°): (u, v) → (1-u, 1-v)       180°
rotation 3 (270°): (u, v) → (1-v, u)         顺时针 270°
```

**坐标换算完整流程**：
```
hitVec → 根据朝向计算 UV (u, v) → 根据旋转变换 UV → × 128 → 地图像素
  → 检查 colors[index] != 0（未探索不传送）
  → 旗帜命中检测
  → 像素→世界坐标换算
```

### 未探索区域不传送

`MapItemSavedData.colors` 是 `byte[128×128]`，未探索的像素值为 `0`。

```java
boolean isExplored = mapData.colors[mapY * 128 + mapX] != 0;
```

传送逻辑：点击位置换算为地图像素后，先检查 `colors[index] != 0`，未探索则不传送（可提示"此处未探索"）。

### MapBanner 数据结构

```java
public record MapBanner(BlockPos pos, DyeColor color, Component name) {}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `pos` | BlockPos | 旗帜方块的精确世界坐标 |
| `color` | DyeColor | 旗帜颜色（16 种） |
| `name` | Component | 旗帜自定义名称（铁砧命名），未命名时为空 |

**关键**：`pos` 直接是世界坐标，旗帜命中后可直接 `banner.pos() + 0.5` 作为传送目标，不需要像素→世界坐标换算。