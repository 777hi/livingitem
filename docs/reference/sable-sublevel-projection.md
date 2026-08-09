# Sable SubLevel 投影原理技术文档

> **文档版本**: 2026.08 v1
> **最后更新**: 2026-08-03
> **适用版本**: Sable 2.0.3 / Minecraft 1.21.1
> **用途**: 记录 Sable 的 SubLevel 投影机制，供后续"活物品大战僵尸"等功能参考（将生物投影进容器）

## 目录
1. [核心概念：投影机制](#1-核心概念投影机制)
2. [地块园（Plotyard）](#2-地块园plotyard)
3. [logicalPose 与真实存储](#3-logicalpose-与真实存储)
4. [坐标变换 API](#4-坐标变换-api)
5. [物理系统架构](#5-物理系统架构)
6. [实体与 SubLevel 的关系](#6-实体与-sublevel-的关系)
7. [对"活物品大战僵尸"的参考价值](#7-对活物品大战僵尸的参考价值)

---

## 1. 核心概念：投影机制

Sable 的 SubLevel 是一种**"投影"机制**：玩家看到的飞艇（移动的方块结构）并非真实存在于该位置，而是远处一个固定地块的投影。

```
┌─────────────────────────────────────────────────────────────┐
│  玩家看到的飞艇（投影）                                       │
│  位置：(100, 70, 200) ← 这是 logicalPose()                  │
│  旋转：朝北                                                  │
│                                                             │
│        ↕ logicalPose 变换（平移+旋转）                       │
│                                                             │
│  真实方块存储（Plotyard 地块园）                              │
│  位置：(160000, ?, 160000) ← 极远处                         │
│  在这里：方块、方块实体、红石、机器都在正常运行                │
└─────────────────────────────────────────────────────────────┘
```

### 为什么这样设计？

Minecraft 的方块系统本质是**静态的**，不支持"移动的方块"。Sable 的解决方案：

1. 把飞艇的方块放在世界极远处的固定位置（地块园）
2. 方块实体（机器、红石）在固定位置**正常工作**
3. 用 `logicalPose` 做刚体物理模拟，把整个地块"投影"到玩家看到的位置
4. 玩家骑乘时，实际站在地块园里的方块上，但渲染时被投影到飞艇位置

这样既保留了方块系统的完整性（机器能运行），又实现了可移动的方块结构。

---

## 2. 地块园（Plotyard）

### 2.1 位置

地块园位于世界极远处，确保不与正常游玩区域冲突：

```java
// SubLevelContainer.java
// The origin of the plotyard in plots.
// We want the plotyard to be over 30 million blocks out.
public static final int DEFAULT_ORIGIN = 10000;
```

注释表明设计意图是"3000 万格外"，实际值为 10000（以地块为单位，约 160 万格）。

### 2.2 地块（Plot）结构

每个 SubLevel 占用一个"地块"（`LevelPlot`），地块大小由 `logPlotSize` 决定（默认 7，即 128 区块边长）。

```java
// LevelPlot.java
public final ChunkPos plotPos;  // 地块在地块园中的区块坐标

// 地块的真实区块范围
public ChunkPos getChunkMin() {
    return new ChunkPos(this.plotPos.x << this.logSize, this.plotPos.z << this.logSize);
}
```

飞艇的方块真实存在于 `plotPos.x << 7` 到 `(plotPos.x+1) << 7` 这个区块范围内。

### 2.3 地块园网格

`SubLevelContainer` 管理一个 per-Level 的地块网格：

```java
// 每个 ServerLevel 独立持有一个 SubLevelContainer
// 通过 SubLevelContainer.getContainer(level) 获取
protected final SubLevel[] subLevels;           // 地块网格
private final BitSet occupancy;                  // 地块占用情况
private final Map<UUID, SubLevel> subLevelsByUUID;  // 按 UUID 索引
```

---

## 3. logicalPose 与真实存储

### 3.1 两个位置概念

| 概念 | 含义 | 是否变化 |
|------|------|---------|
| **真实方块位置** | 方块在地块园中的固定坐标 | ❌ 不变（除非拆卸重建） |
| **logicalPose** | 飞艇在玩家视野中的投影位置+旋转 | ✅ 随物理模拟变化 |

### 3.2 Pose3d

`logicalPose()` 返回 `Pose3d`，包含平移、旋转、缩放：

```java
// SubLevel.java
public Pose3d logicalPose() { return this.pose; }       // 当前投影位姿
public Pose3dc lastPose() { return this.lastPose; }     // 上一 tick 的位姿（用于插值）
```

### 3.3 传送只改投影

`RigidBodyHandle.teleport()` 修改的是 `logicalPose`（投影位置），**不是方块真实存储位置**：

```java
// RigidBodyHandle.java
public void teleport(final Vector3dc position, final Quaterniondc orientation) {
    this.physicsSystem.getPipeline().teleport(this.body, position, orientation);
}
```

```
teleport 前：
  真实方块在 (160000, 64, 160000)  ← 不变
  投影在 (100, 70, 200)            ← 玩家看到飞艇在这里

teleport 后：
  真实方块在 (160000, 64, 160000)  ← 还是不变！
  投影在 (500, 70, 800)            ← 玩家看到飞艇瞬移到这里
```

**结论**：同维度传送飞艇非常轻量，只改投影，方块不动，机器照常运行。

---

## 4. 坐标变换 API

### 4.1 核心变换方法

`Pose3d` 提供两个方向的坐标变换：

| 方法 | 方向 | 用途 |
|------|------|------|
| `transformPosition(pos)` | 地块园内 → 投影位置 | 渲染、玩家可见坐标 |
| `transformPositionInverse(pos)` | 投影位置 → 地块园内 | 射线命中、交互计算 |
| `transformNormal(vec)` | 方向向量正向 | 速度、朝向 |
| `transformNormalInverse(vec)` | 方向向量反向 | 输入方向转换 |

### 4.2 Sable.HELPER 提供的便捷方法

```java
// 将 SubLevel 内的坐标投影到世界坐标
Vec3 projectOutOfSubLevel(Level level, Vec3 pos);

// 计算 SubLevel 内某点的全局速度（含刚体运动）
Vector3d getVelocity(Level level, Vector3dc pos, Vector3d dest);
```

### 4.3 实体坐标投影

`SubLevelHelper` 提供实体的局部/全局坐标转换：

```java
// 将实体投影进 SubLevel 的局部空间（位置+旋转）
SubLevelHelper.pushEntityLocal(subLevel, entity);
// ... 在局部空间操作 ...
SubLevelHelper.popEntityLocal(subLevel, entity);  // 必须配对调用
```

---

## 5. 物理系统架构

### 5.1 per-Level 物理系统

每个 `ServerLevel` 拥有独立的物理系统：

```java
// Sable.java
public static void defaultSubLevelContainerInitializer(Level level, SubLevelContainer container) {
    if (container instanceof ServerSubLevelContainer serverContainer) {
        SubLevelPhysicsSystem physicsSystem = new SubLevelPhysicsSystem(serverLevel);
        physicsSystem.initialize();
        serverContainer.takePhysicsSystem(physicsSystem);
        // ...
    }
}
```

### 5.2 RigidBodyHandle

通过物理系统获取刚体句柄，进行物理操作：

```java
// 获取句柄
ServerSubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
RigidBodyHandle handle = container.physicsSystem().getPhysicsHandle(serverSubLevel);

// 物理操作
handle.applyImpulseAtPoint(position, force);    // 施加冲量
handle.applyLinearImpulse(impulse);             // 线性冲量
handle.applyAngularImpulse(torque);             // 角冲量
handle.teleport(position, orientation);         // 瞬移
handle.getLinearVelocity(dest);                 // 读取线速度
handle.getAngularVelocity(dest);                // 读取角速度
```

### 5.3 跨维度限制

SubLevel 强绑定到某个 ServerLevel：

| 组件 | 绑定关系 |
|------|---------|
| `ServerSubLevel` | 构造时绑定 `ServerLevel` |
| `SubLevelPhysicsSystem` | per-Level，存在 container 内 |
| `RigidBodyHandle.teleport()` | 只改物理刚体位置，不换 Level |

**Sable 没有提供"把 SubLevel 从维度 A 迁移到维度 B"的 API。** 跨维度带飞艇不可行。

---

## 6. 实体与 SubLevel 的关系

### 6.1 查找实体所在的 SubLevel

```java
// ActiveSableCompanion 提供多种查找方式
SubLevel getContaining(Entity entity);                    // 通过 chunk 坐标
SubLevel getTrackingSubLevel(Entity entity);              // 通过 Mixin 注入的追踪字段（最可靠）
SubLevel getVehicleSubLevel(Entity entity);               // 通过载具链
SubLevel getTrackingOrVehicleSubLevel(Entity entity);     // 综合以上（推荐）
```

### 6.2 实体在 SubLevel 内的存在形式

玩家骑乘飞艇时：
- 玩家的真实坐标在**地块园内**（飞艇方块所在位置）
- 渲染时通过 `logicalPose` 投影到飞艇可见位置
- `player.getVehicle()` 可能返回 null（直接站在方块上）或返回 ContraptionEntity

### 6.3 实体踢出 SubLevel

```java
// EntitySubLevelUtil.java
// 将实体从 SubLevel 踢出到世界，转换坐标和速度
public static void kickEntity(SubLevel subLevel, Entity entity) {
    // 位置转换：local → global
    entity.moveTo(subLevel.logicalPose().transformPosition(pos));
    // 速度转换：local → global
    entity.setDeltaMovement(subLevel.logicalPose().transformNormal(entity.getDeltaMovement()));
    // 朝向转换
    entity.lookAt(EntityAnchorArgument.Anchor.FEET, ...);
}
```

---

## 7. 对"活物品大战僵尸"的参考价值

### 7.1 场景设想

"活物品大战僵尸"可能需要将生物（僵尸、村民等）放入一个容器/竞技场中，让它们在其中活动，同时玩家可以从外部观察。

### 7.2 可借鉴的设计

#### 设计A：地块园作为"竞技场"

借鉴 SubLevel 的地块园设计：
1. 在世界极远处开辟固定区域作为"竞技场"
2. 生物真实存在于竞技场中，AI、路径查找正常工作
3. 用投影机制把竞技场"显示"在容器/物品图标上

**优点**：
- 生物 AI 在真实方块环境中运行，无需重写
- 红石、机器等机制可正常使用（设计陷阱等）
- 与 Sable 架构一致，兼容性好

#### 设计B：投影渲染到容器 GUI

借鉴 `logicalPose` 的投影变换：
1. 生物在远处竞技场活动
2. 容器 GUI 渲染时，把竞技场内容投影到 GUI 空间
3. 玩家通过 GUI 观察/交互

**关键技术点**：
- `transformPosition` / `transformPositionInverse` 用于坐标转换
- `pushEntityLocal` / `popEntityLocal` 用于实体局部空间操作
- `getVelocity` 用于获取生物在投影中的运动速度

### 7.3 实体投影的注意事项

参考 `EntitySubLevelUtil.kickEntity()` 的实现，实体在投影空间和真实空间之间转换时需要处理：

| 属性 | 转换方式 |
|------|---------|
| 位置 | `logicalPose().transformPosition()` |
| 速度 | `logicalPose().transformNormal()` |
| 朝向 | `lookAt()` + 方向向量转换 |
| 旧位置（插值） | `setOldPosNoMovement()` 考虑 SubLevel 位姿 |

### 7.4 跨维度/跨容器问题

与飞艇传送类似：
- 生物真实存在于某个维度的地块园中
- 跨维度投影需要处理维度上下文
- 容器关闭/移动时，生物是否跟随？

建议参考 Sable 的 `SubLevelLoadingTicket` 机制，管理竞技场的加载/卸载：

```java
// 通过 ticket 强制加载 SubLevel
container.addForceLoadTicket(subLevel, SubLevelLoadingTicketType.PLUGIN, key);
// 移除 ticket
container.removeForceLoadTicket(subLevel, SubLevelLoadingTicketType.PLUGIN, key);
```

### 7.5 推荐实现路径

1. **阶段1**：在远处固定位置创建竞技场（普通方块结构，不用 SubLevel）
2. **阶段2**：用渲染器把竞技场内容投影到容器 GUI（类似地图缩略图渲染）
3. **阶段3**：如果需要"移动的竞技场"，再考虑集成 Sable 的 SubLevel

阶段1和2不依赖 Sable，可独立实现；阶段3是可选的高级功能。

---

## 附录：关键类索引

| 类 | 路径 | 职责 |
|----|------|------|
| `SubLevel` | `dev.ryanhcode.sable.sublevel` | SubLevel 抽象基类，持有 logicalPose |
| `ServerSubLevel` | `dev.ryanhcode.sable.sublevel` | 服务端 SubLevel 实现 |
| `SubLevelContainer` | `dev.ryanhcode.sable.api.sublevel` | per-Level 的地块网格管理 |
| `ServerSubLevelContainer` | `dev.ryanhcode.sable.api.sublevel` | 服务端地块网格，含物理系统 |
| `LevelPlot` | `dev.ryanhcode.sable.sublevel.plot` | 地块，管理区块范围 |
| `RigidBodyHandle` | `dev.ryanhcode.sable.api.physics.handle` | 刚体物理操作句柄 |
| `SubLevelPhysicsSystem` | `dev.ryanhcode.sable.sublevel.system` | per-Level 物理系统 |
| `ActiveSableCompanion` | `dev.ryanhcode.sable` | Sable.HELPER 的实现，提供查询 API |
| `EntitySubLevelUtil` | `dev.ryanhcode.sable.api.entity` | 实体与 SubLevel 的交互工具 |
| `SubLevelHelper` | `dev.ryanhcode.sable.api` | SubLevel 间/实体坐标转换工具 |
| `Pose3d` | `dev.ryanhcode.sable.companion.math` | 3D 位姿（平移+旋转+缩放） |