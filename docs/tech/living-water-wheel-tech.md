# Living Water Wheel (活水车) 技术文档

> **文档版本**: 2026.08 v12  
> **最后更新**: 2026-08-26  
> **适用版本**: Minecraft 1.21.1

## 目录
1. [架构概览](#1-架构概览)
2. [数据结构](#2-数据结构)
3. [应力计算](#3-应力计算)
4. [应力存储与传输](#4-应力存储与传输)
5. [Create 集成（软依赖）](#5-create-集成软依赖)
6. [物品栏 3D 渲染](#6-物品栏-3d-渲染)
7. [Tooltip 显示](#7-tooltip-显示)
8. [实现步骤](#8-实现步骤)
9. [踩坑记录](#9-踩坑记录)

---

## 1. 架构概览

### 1.1 什么是活水车？

活水车是一种**应力产生类活物品**，参照机械动力（Create）的水车设计。放置在容器槽位中后，观察周围水流方向，通过力矩计算产生顺时针（CW）或逆时针（CCW）应力。多个活水车的应力可以叠加或抵消，最终从容器底部输出净应力，驱动机械动力的传动杆、齿轮等方块旋转。

活水车的宿主物品是 `create:water_wheel`（机械动力水车），必须同时具备活物品标记。

### 1.2 设计理念：容器级力矩模型

活水车将机械动力的水车力学**映射到容器网格**中：

```
机械动力世界                  容器网格
┌─────────────────────┐       ┌─────────────────────┐
│ 水车方块             │  ←→  │ 活水车物品           │
│ 水流冲击叶片产生旋转  │  ←→  │ 水流流过周围产生力矩  │
│ 多水车叠加应力       │  ←→  │ 多活水车叠加应力      │
│ 传动杆接收旋转       │  ←→  │ 容器底部输出应力      │
└─────────────────────┘       └─────────────────────┘
```

**核心特性**：
- 活水车阻挡水流（作为活物品），但观察周围 4 方向的水流
- 每个方向的水流对水车产生力矩（二维叉积）
- 力矩 > 0 → 顺时针（CW），力矩 < 0 → 逆时针（CCW）
- 同方向应力叠加，反方向应力抵消
- 堆叠数量放大 SU 容量（64 个 = 64 倍 SU），转速不变
- 净应力从容器底部垂直输出，驱动下方 Create 方块旋转

### 1.3 关键类和职责

#### 内部类（无 Create 依赖）

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `LivingWaterWheelFunction` | `domain/waterwheel/LivingWaterWheelFunction.java` | 活水车功能入口，管理应力同步和 Tooltip |
| `WaterWheelData` | `domain/waterwheel/WaterWheelData.java` | 水车应力 record：cwStress/ccwStress/netStress |
| `LivingWaterWheelData` | `domain/waterwheel/LivingWaterWheelData.java` | 活水车数据容器：包含 WaterWheelData |
| `ContainerStressData` | `container/ContainerStressData.java` | 容器级应力累加器，遍历所有活水车计算力矩 |
| `CONTAINER_STRESS_DATA` | `api/LivingItemManager.java` | NeoForge `AttachmentType`，给 BlockEntity 附加 `ContainerStressData` 应力数据 |

#### Create 兼容类（软依赖，无 Create 时不加载）

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `CreateCompat` | `compat/create/CreateCompat.java` | 检测 Create 是否安装（`ModList.get().isLoaded`） |
| `StressOutputManager` | `compat/create/StressOutputManager.java` | 应力输出统一入口：找到容器下方/玩家脚底 BE 并注入应力，含白名单过滤和方向兼容性检查 |
| `StressStateMachine` | `compat/create/StressStateMachine.java` | 应力输出状态机：管理网络连接、自过期、区块卸载清理、客户端同步，从 Mixin 中提取的纯逻辑类 |
| `LivingItemStressOutput` | `compat/create/LivingItemStressOutput.java` | 接口，定义 Mixin 注入的方法签名（`livingItem$applyStress`） |
| `CreateMixinPlugin` | `compat/create/CreateMixinPlugin.java` | Mixin 条件加载插件，仅 Create 安装时应用 Mixin |
| `KineticBlockEntityMixin` | `mixin/create/KineticBlockEntityMixin.java` | Mixin 到 KineticBlockEntity，委托 `StressStateMachine` 管理应力状态，实现 `LivingItemStressOutput` 接口 |
| `SmartBlockEntityMixin` | `mixin/create/SmartBlockEntityMixin.java` | Mixin 到 SmartBlockEntity，拦截 `onChunkUnloaded` 转发到 `LivingItemStressOutput`，避免 Mixin 目标方法不存在导致整个类失效 |

#### 渲染类（客户端，软依赖 Create）

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `ItemRendererWaterWheelMixin` | `client/mixin/ItemRendererWaterWheelMixin.java` | Mixin 到 ItemRenderer，拦截渲染实现 3D 旋转 + 漫反射光照修正 |
| `WaterWheelRenderState` | `client/icon/WaterWheelRenderState.java` | ThreadLocal 存储 RPM，桥接 resolve() 和 applyTransform() |
| `LivingIconRegistry` | `client/icon/LivingIconRegistry.java` | 条件注册活水车图标，仅 Create 安装时激活 |
| `LivingIconSpec` | `client/icon/LivingIconSpec.java` | 图标规格定义，支持 `rotating` 标记 |

---

## 2. 数据结构

### 2.1 WaterWheelData — 水车应力

```java
public record WaterWheelData(
    int cwStress,    // 顺时针应力
    int ccwStress,   // 逆时针应力
    int netStress    // 净应力 = cwStress - ccwStress
)
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `cwStress` | int | 顺时针应力累加值（正值） |
| `ccwStress` | int | 逆时针应力累加值（正值） |
| `netStress` | int | 净应力 = CW - CCW，>0 顺时针，<0 逆时针 |

### 2.2 ContainerStressData — 容器级应力

```java
public class ContainerStressData {
    private int netCWStress;                          // 容器总顺时针应力
    private int netCCWStress;                         // 容器总逆时针应力
    private int netStress;                            // 容器净应力
    private Map<Integer, int[]> wheelTorques;         // 每个水车的力矩 [cw, ccw]
}
```

### 2.3 存储结构

活水车的应力数据存储在 ItemStack 的 DataComponent 中：

```
ItemStack
├── IS_LIVING: true                              ← 活物品标记
└── LIVING_WATER_WHEEL_DATA: LivingWaterWheelData  ← 功能状态（DataComponent）
    └─ wheel: WaterWheelData
        ├─ cwStress: int    ← 顺时针应力
        ├─ ccwStress: int   ← 逆时针应力
        └─ netStress: int   ← 净应力
```

容器 BlockEntity 也通过 NeoForge `AttachmentType` 持有应力数据：

```
BlockEntity (via AttachmentType)
└── CONTAINER_STRESS_DATA: ContainerStressData   ← 容器级应力
    ├─ netCWStress: int
    ├─ netCCWStress: int
    └─ netStress: int
```

---

## 3. 应力计算

### 3.1 力矩模型

活水车观察周围 4 方向（上下左右）的水流，计算每个方向水流产生的力矩：

```
力矩 = 位置向量 × 水流方向向量（二维叉积）

位置向量 P = (px, py) = (邻居槽位 - 水车槽位) 的坐标差
水流方向 F = (fdx, fdy) = (邻居槽位 - 水流来源槽位) 的坐标差

torque = px * fdy - py * fdx
```

**叉积的几何意义**：力矩衡量水流"绕水车旋转"的分量。

### 3.2 力矩方向判定

```
torque > 0 → 顺时针 (CW)  → 水流从水车左侧流过
torque < 0 → 逆时针 (CCW) → 水流从水车右侧流过
torque = 0 → 无力矩       → 水流正对水车流过（径向）
```

### 3.3 计算示例

```
9列容器，活水车在 (4,2)，水流从左向右流过：

┌───┬───┬───┬───┬───┬───┬───┬───┬───┐
│   │   │   │   │   │   │   │   │   │
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│   │💧│ → │ → │🔄│ → │ → │   │   │
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│   │   │   │   │   │   │   │   │   │
└───┴───┴───┴───┴───┴───┴───┴───┴───┘

水车左侧 (3,1)：水流方向 (1,0)，位置向量 (-1,0)
  torque = (-1)*0 - 0*1 = 0  （径向，无力矩）

水车右侧 (5,1)：水流方向 (1,0)，位置向量 (1,0)
  torque = 1*0 - 0*1 = 0  （径向，无力矩）

→ 单行直线水流不产生力矩！
```

```
9列容器，L形水流绕过水车：

┌───┬───┬───┬───┬───┬───┬───┬───┬───┐
│   │💧│ → │ ↓ │   │   │   │   │   │
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│   │   │   │🔄│   │   │   │   │   │
├───┼───┼───┼───┼───┼───┼───┼───┼───┤
│   │   │   │   │   │   │   │   │   │
└───┴───┴───┴───┴───┴───┴───┴───┴───┘

水车上方 (3,0)：水流方向 (0,1)（向下），位置向量 (-1,-1)
  torque = (-1)*1 - (-1)*0 = -1  → CCW

水车左侧 (2,1)：水流方向 (1,0)（向右），位置向量 (-1,0)
  torque = (-1)*0 - 0*1 = 0  → 无力矩

→ L形水流产生逆时针力矩！
```

### 3.4 水流强度权重

水流越靠近水源越强，使用线性权重：

```java
float strength = (MAX_FLOW_LEVEL - fe.level() + 1.0f) / MAX_FLOW_LEVEL;
int weightedTorque = Math.round(torque * strength * stackSize);
```

| 水流级别 | strength | 说明 |
|---------|----------|------|
| 0（水源） | 8/7 ≈ 1.14 | 最强 |
| 1 | 7/7 = 1.0 | |
| 4 | 4/7 ≈ 0.57 | |
| 7（最远） | 1/7 ≈ 0.14 | 最弱 |

### 3.5 堆叠放大

活水车可堆叠，堆叠数量直接乘以力矩，**增加 SU 容量而非转速**：

```
1 个活水车，力矩 +3 → CW 应力 = 3 → RPM = 8, SU 容量 = 96
64 个活水车堆叠，力矩 +3 → CW 应力 = 192 → RPM = 8, SU 容量 = 6144
```

> RPM 固定为 8（与原版小水车一致），堆叠只增加 SU 容量（能驱动更多设备）。

---

## 4. 应力存储与传输

### 4.1 容器级应力累加

`ContainerStressData.calculate()` 遍历容器中所有活水车，累加应力：

```java
public void calculate(ContainerFluidData fluidData, ContainerContext ctx) {
    netCWStress = 0;
    netCCWStress = 0;

    for (int i = 0; i < containerSize; i++) {
        if (!isLivingWaterWheel(stack)) continue;

        int cwTorque = 0, ccwTorque = 0;
        for (int neighbor : getNeighbors(i, ...)) {
            int torque = px * fdy - py * fdx;
            int weighted = Math.round(torque * strength * stackSize);
            if (weighted > 0) cwTorque += weighted;
            else if (weighted < 0) ccwTorque += -weighted;
        }

        netCWStress += cwTorque;
        netCCWStress += ccwTorque;
    }

    netStress = netCWStress - netCCWStress;
}
```

### 4.2 应力叠加与抵消

```
场景1：双水车同向叠加
  💧→🔄 CW(+3)  💧→🔄 CW(+2)
  净应力 = +5 CW → RPM = 8, SU 容量 = 160（更大功率）

场景2：双水车反向抵消
  💧→🔄 CW(+3)  💧→🔄 CCW(-3)
  净应力 = 0 → 不旋转！

场景3：堆叠放大 SU
  🔄×64 → 应力 × 64 → RPM 不变(8), SU 容量 × 64（驱动更多设备）
```

### 4.3 BlockEntity 应力存储

通过 NeoForge `AttachmentType` 给所有 `BlockEntity` 附加 `ContainerStressData`：

```java
// LivingItemManager.java
public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
    DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, LivingItem.MOD_ID);

public static final DeferredHolder<AttachmentType<?>, AttachmentType<ContainerStressData>> CONTAINER_STRESS_DATA =
    ATTACHMENT_TYPES.register("container_stress_data", () ->
        AttachmentType.builder(() -> ContainerStressData.EMPTY).build());
```

每个容器 tick 结束后，`ContainerLivingItemHandler.processContext()` 将应力数据写入关联的 BlockEntity：

```java
if (stressData != null && context instanceof SimpleContainerContext simpleCtx) {
    for (BlockEntity be : simpleCtx.getAssociatedBlockEntities()) {
        be.setData(LivingItemManager.CONTAINER_STRESS_DATA.value(), stressData);
    }
}
```

### 4.4 垂直传输设计

应力从容器底部垂直输出，参照从容器顶部往下的视角：

```
容器方块
  ┌─────────────┐
  │  💧 → 🔄    │  活水车产生应力
  │  💧 → 🔄    │  应力叠加
  └──────┬──────┘
         │ 净应力输出（底部面）
         ▼
  ┌─────────────┐
  │  传动杆/齿轮  │  Create 方块接收旋转
  └─────────────┘
```

输出方向约定：
- 净应力 > 0 → 顺时针旋转（从上方俯视）
- 净应力 < 0 → 逆时针旋转（从上方俯视）
- 净应力 = 0 → 无旋转

### 4.5 玩家脚底应力传递

当活水车在玩家背包中产生应力时（而非方块容器中），应力从玩家脚底输出：

```
玩家背包
  ┌─────────────┐
  │  💧 → 🔄    │  活水车产生应力
  └──────┬──────┘
         │ 净应力输出（玩家脚底）
         ▼
  ┌─────────────┐
  │  传动杆/齿轮  │  玩家脚下方块接收旋转
  └─────────────┘
```

**实现逻辑**（`ContainerLivingItemHandler.processContext()`）：

```java
if (stressData != null && context instanceof SimpleContainerContext simpleCtx) {
    // 1. 容器场景：应力从容器底部输出
    for (BlockEntity be : simpleCtx.getAssociatedBlockEntities()) {
        be.setData(LivingItemManager.CONTAINER_STRESS_DATA.value(), stressData);
        updateStressOutput(simpleCtx, be, stressData);
    }

    // 2. 玩家背包场景：无关联 BlockEntity，从玩家脚底输出
    if (simpleCtx.getAssociatedBlockEntities().isEmpty() && simpleCtx.getInventory() != null) {
        updatePlayerFeetStressOutput(simpleCtx, stressData);
    }
}
```

**判断条件**：当 `SimpleContainerContext` 的 `associatedBlockEntities` 为空且 `inventory` 不为空时，说明活物品在玩家背包中，使用 `player.blockPosition()` 作为应力输出位置。

**自过期机制**：玩家移开脚后，`KineticBlockEntityMixin` 的 `refreshedThisTick` 标记不再被刷新，下一 tick 自动清理应力并同步客户端。

---

## 5. Create 集成（软依赖）

### 5.1 软依赖设计原则

活水车的 Create 集成采用**软依赖**模式：

- **不安装 Create**：活水车仍可产生应力（内部计算、Tooltip 显示），但不会驱动任何方块旋转
- **安装 Create**：活水车自动激活应力输出功能，容器下方的传动杆/齿轮会旋转

这意味着活物品模组**不强制要求玩家安装 Create**，但安装后自动获得兼容功能。

### 5.2 软依赖实现架构

```
┌─────────────────────────────────────────────────────────────┐
│                    活物品模组（始终加载）                       │
│                                                             │
│  CreateCompat ──→ ModList.get().isLoaded("create")          │
│       │                                                     │
│       ▼                                                     │
│  StressOutputManager.apply(level, containerPos, stressData) │
│       │                                                     │
│       ├── CreateCompat.isLoaded() == false → 直接返回        │
│       │                                                     │
│       └── CreateCompat.isLoaded() == true                   │
│              │                                              │
│              ├── 检查容器下方 BE                              │
│              ├── instanceof LivingItemStressOutput          │
│              ├── isSafe() 白名单检查                         │
│              ├── 方向兼容性检查                              │
│              └── stressOutput.livingItem$applyStress(rpm, cap) │
│                     │                                       │
│                     └── StressStateMachine.applyStress()    │
│                            ├── setSpeed + setNetwork        │
│                            ├── attachKinetics               │
│                            ├── updateNetwork                │
│                            └── self.sendData() 即时同步      │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│              Create Mixin（仅 Create 安装时加载）              │
│                                                             │
│  CreateMixinPlugin ──→ getResource() 检测 Create 类          │
│       │                                                     │
│       ├── Create 不存在 → 跳过 Mixin                         │
│       │                                                     │
│       └── Create 存在 → 应用 KineticBlockEntityMixin         │
│              │                                              │
│              └── KineticBlockEntity 实现 LivingItemStressOutput │
│                    ├─ stressState: StressStateMachine       │
│                    ├─ getGeneratedSpeed() 注入               │
│                    ├─ tick() HEAD 注入（自过期 + 重连）       │
│                    ├─ onChunkUnloaded() 注入（网络清理）       │
│                    ├─ calculateAddedStressCapacity() 注入    │
│                    └─ livingItem$applyStress(rpm, cap) 统一入口 │
└─────────────────────────────────────────────────────────────┘
```

### 5.3 关键类详解

#### CreateCompat — Create 安装检测

```java
public final class CreateCompat {
    private static Boolean loaded;

    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = ModList.get().isLoaded("create");
        }
        return loaded;
    }
}
```

运行时通过 NeoForge 的 `ModList` API 检测 Create 是否安装。结果缓存，只查询一次。

#### StressOutputManager — 应力输出统一入口

```java
public class StressOutputManager {
    public static final float BASE_RPM = 8.0f;            // 基础转速，与原版小水车一致
    public static final float BASE_SU_CAPACITY = 32.0f;   // 基础 SU 容量，与原版小水车一致

    public static void apply(Level level, BlockPos containerPos,
                              ContainerStressData stressData) {
        if (!CreateCompat.isLoaded()) return;               // 无 Create 直接返回
        if (level == null || level.isClientSide) return;

        BlockPos belowPos = containerPos.below();
        BlockEntity be = level.getBlockEntity(belowPos);

        if (!(be instanceof LivingItemStressOutput stressOutput)) return;

        // 白名单过滤
        if (!(be instanceof SimpleKineticBlockEntity)
            && !(be instanceof BracketedKineticBlockEntity)) {
            return;
        }

        float rpm = 0;
        float suCapacity = 0;
        if (stressData != null && !stressData.isEmpty()) {
            int netStress = stressData.getNetStress();
            rpm = -Math.signum(netStress) * BASE_RPM;       // 负号修正旋转方向
            suCapacity = Math.abs(netStress) * BASE_SU_CAPACITY;
        }

        // 方向兼容性检查
        if (rpm != 0 && !isDirectionCompatible(be, rpm)) {
            stressOutput.livingItem$applyStress(0, 0);      // 清零残留应力
            return;
        }

        stressOutput.livingItem$applyStress(rpm, suCapacity);
    }
}
```

**关键改进**（相比旧的 `ModCreate` + `CreateIntegration`）：
- **合并为单次调用**：`livingItem$applyStress(rpm, cap)` 一次调用同时设置 RPM 和 SU 容量，消除了 `setGeneratedRPM` 必须在 `setStressCapacity` 之前调用的隐式依赖
- **职责合并**：将 `ModCreate`（入口 + 常量）和 `CreateIntegration`（逻辑 + 白名单）合并为一个类，减少文件碎片化
- **直接 import Create 类型**：不再需要 `instanceof` 接口检查白名单（`StressOutputManager` 在 `compat/create` 包中，Create 已确认加载）

#### StressStateMachine — 应力输出状态机

```java
public class StressStateMachine {
    private float rpm;
    private float capacity;
    private boolean refreshedThisTick;
    private boolean pendingReattach;

    // 状态：INACTIVE / ACTIVE（由 rpm 值隐式表达）

    public void tick(KineticBlockEntity self) {
        if (self.getLevel() == null || self.getLevel().isClientSide) return;

        if (pendingReattach) {
            pendingReattach = false;
            self.attachKinetics();
            self.setChanged();
            self.sendData();                               // 即时同步
        }

        if (rpm == 0) {
            refreshedThisTick = false;
            return;
        }

        if (!refreshedThisTick) {                          // 自过期：应力源消失
            self.detachKinetics();
            self.setSpeed(0);
            self.setNetwork(null);
            pendingReattach = true;
            self.setChanged();
            self.sendData();                               // 即时同步
            rpm = 0;
            capacity = 0;
        }

        refreshedThisTick = false;
    }

    public void applyStress(KineticBlockEntity self, float newRpm, float newCap) {
        if (!isSafe(self)) { ... return; }

        refreshedThisTick = true;                          // 标记本 tick 有应力源

        // 状态切换逻辑
        if (prev == 0 && newRpm != 0) {
            // 从静止到旋转：创建网络 + 连接
            self.setSpeed(newRpm);
            self.setNetwork(self.getBlockPos().asLong());
            self.attachKinetics();
            updateNetwork(self, newCap);
        } else if (prev != 0 && newRpm == 0) {
            // 从旋转到静止：断开网络 + 重连邻居
            self.detachKinetics();
            self.setSpeed(0);
            self.setNetwork(null);
            pendingReattach = true;
        } else if (prev != 0 && newRpm != 0) {
            // 速度变化：断开 + 重连
            self.detachKinetics();
            self.setSpeed(newRpm);
            self.attachKinetics();
            updateNetwork(self, newCap);
        }

        self.setChanged();
        self.sendData();                                   // 即时同步客户端
    }
}
```

**关键改进**（相比旧的内联 Mixin 逻辑）：
- **即时 `sendData()`**：不再使用 `deferredSync` 延迟到 `tick()` TAIL，避免 TAIL 时状态已被 HEAD 清理导致客户端收到空状态
- **纯逻辑类**：从 Mixin 中提取，可独立测试，不依赖 Mixin 框架
- **统一入口**：`applyStress(rpm, cap)` 同时处理 RPM 和 capacity，消除顺序依赖
- **显式状态机**：虽然状态是隐式的（由 rpm 值表达），但状态转换逻辑集中在 `applyStress` 中

#### LivingItemStressOutput — 接口注入模式

```java
public interface LivingItemStressOutput {
    void livingItem$applyStress(float rpm, float capacity);
    void livingItem$setGeneratedRPM(float rpm);
    float livingItem$getGeneratedRPM();
    void livingItem$setStressCapacity(float capacity);
    float livingItem$getStressCapacity();
}
```

**改进**：新增 `livingItem$applyStress` 统一入口，同时保留旧方法用于向后兼容。

#### CreateMixinPlugin — 条件 Mixin 加载

```java
public class CreateMixinPlugin implements IMixinConfigPlugin {
    private static boolean isCreateLoaded() {
        ClassLoader cl = CreateMixinPlugin.class.getClassLoader();
        var url = cl.getResource("com/simibubi/create/content/kinetics/base/KineticBlockEntity.class");
        return url != null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return isCreateLoaded();
    }
}
```

**重要**：使用 `getResource()` 而非 `Class.forName()` 检测 Create 是否安装。`Class.forName()` 会触发类加载链，导致 `BlockEntity` 在 Mixin 准备阶段被提前加载，与其他模组（如 Fabric API）的 Mixin 冲突。

#### 白名单策略

活水车的应力输出**仅允许简单传动组件**接收，复杂组件一律跳过。这是为了防止注入应力后触发 Create 内部复杂逻辑导致崩溃。

**白名单**：

| 类名 | 对应方块 | 安全原因 |
|------|---------|---------|
| `SimpleKineticBlockEntity` | 传动杆、齿轮等简单传动 | 无 `source` 依赖，无复杂状态 |
| `BracketedKineticBlockEntity` | 支架齿轮 | 继承自 SimpleKineticBE，同样安全 |

**黑名单（典型崩溃案例）**：

| 类名 | 对应方块 | 崩溃原因 |
|------|---------|---------|
| `DirectionalShaftHalvesBlockEntity` | 竖直十字齿轮箱 | 依赖 `source` 字段，NPE |
| `SplitShaftBlockEntity` | 十字齿轮箱 | 多方向旋转逻辑复杂 |
| `GearshiftBlockEntity` | 变速箱 | 状态切换逻辑复杂 |

**实现方式**：双重白名单检查——`StressOutputManager` 和 `StressStateMachine` 各自维护白名单检查。两处检查确保：
1. `StressOutputManager` 不会向非白名单 BE 发送应力
2. 即使绕过第一层检查，`StressStateMachine.applyStress()` 也会拒绝非白名单 BE

#### KineticBlockEntityMixin — 精简后的 Mixin

```java
@Mixin(KineticBlockEntity.class)
public abstract class KineticBlockEntityMixin implements LivingItemStressOutput {

    private final StressStateMachine stressState = new StressStateMachine();

    @Inject(method = "getGeneratedSpeed", at = @At("HEAD"), cancellable = true, remap = false)
    private void livingItem$getGeneratedSpeed(CallbackInfoReturnable<Float> cir) {
        float rpm = stressState.getRPM();
        if (rpm != 0) {
            cir.setReturnValue(rpm);
        }
    }

    @Inject(method = "onChunkUnloaded", at = @At("HEAD"), remap = false)
    private void livingItem$onChunkUnloaded(CallbackInfo ci) {
        stressState.onChunkUnloaded((KineticBlockEntity) (Object) this);
    }

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void livingItem$checkExpiry(CallbackInfo ci) {
        stressState.tick((KineticBlockEntity) (Object) this);
    }

    @Inject(method = "calculateAddedStressCapacity", at = @At("HEAD"), cancellable = true, remap = false)
    private void livingItem$calculateAddedStressCapacity(CallbackInfoReturnable<Float> cir) {
        if (stressState.isActive()) {
            cir.setReturnValue(stressState.getCapacity());
        }
    }

    @Override
    public void livingItem$applyStress(float rpm, float capacity) {
        stressState.applyStress((KineticBlockEntity) (Object) this, rpm, capacity);
    }

    // 向后兼容方法
    @Override
    public void livingItem$setGeneratedRPM(float rpm) {
        stressState.applyStress((KineticBlockEntity) (Object) this, rpm, stressState.getCapacity());
    }

    @Override
    public float livingItem$getGeneratedRPM() {
        return stressState.getRPM();
    }

    @Override
    public void livingItem$setStressCapacity(float capacity) {
        stressState.applyStress((KineticBlockEntity) (Object) this, stressState.getRPM(), capacity);
    }

    @Override
    public float livingItem$getStressCapacity() {
        return stressState.getCapacity();
    }
}
```

**Mixin 注入的方法**：

| 方法 | 注入目标 | 作用 |
|------|---------|------|
| `livingItem$getGeneratedSpeed` | `getGeneratedSpeed()` | 纯 getter：让 Create 认为该 BE 是旋转源，返回固定 RPM（±8） |
| `livingItem$onChunkUnloaded` | `onChunkUnloaded()` | 区块卸载时清理网络连接，防止残留实体导致应力翻倍和变速结构爆炸 |
| `livingItem$checkExpiry` | `tick()` HEAD | 自过期机制：委托 `stressState.tick()`，每 tick 检查 `refreshedThisTick`，未刷新则清理 |
| `livingItem$calculateAddedStressCapacity` | `calculateAddedStressCapacity()` | 纯 getter：让 Create 认为该 BE 提供应力容量 |
| `livingItem$applyStress` | 新增方法 | 统一入口：外部调用设置 RPM + SU 容量，委托 `stressState.applyStress()` |

**关键精简**（相比旧版本）：

| 旧版本 | 新版本 | 改进 |
|--------|--------|------|
| `livingItem$generatedRPM` 等字段直接定义在 Mixin 中 | 委托 `StressStateMachine` 管理 | 纯逻辑类，可独立测试 |
| `getGeneratedSpeed()` 含副作用（清零 RPM） | 纯 getter | 消除副作用 |
| `deferredSync` TAIL 注入 | 移除，`applyStress()` 中直接 `sendData()` | 即时同步，避免客户端空状态 |
| 白名单检查在 Mixin 中 | 白名单检查在 `StressStateMachine` 中 | 单点检查 |
| 两处 `isSafeKineticBE()` | 一处 `StressStateMachine.isSafe()` | 消除重复 |

**状态转换逻辑**（委托 `StressStateMachine`）：

```
prev=0, rpm≠0  → 从静止到旋转：setSpeed + setNetwork + attachKinetics + updateNetwork + sendData
prev≠0, rpm=0  → 从旋转到静止：detachKinetics → setSpeed(0) → setNetwork(null) → pendingReattach + sendData
prev≠0, rpm≠0  → 速度变化：detachKinetics + setSpeed + attachKinetics + updateNetwork + sendData
chunkUnloaded  → 区块卸载：network.remove(self) + detachKinetics + 清零所有状态
```

> **关键原则**：在调用 `setNetwork(null)`（内部调 `network.remove()`）时，必须确保 `getGeneratedSpeed()` 返回非零值，让 `isSource()` 返回 true，否则 `sources.remove(be)` 会被跳过。为此，`setNetwork(null)` 之前需设 `refreshedThisTick = true`，且 `rpm` 清零操作必须在 `setNetwork(null)` 之后。

> **注意**：`prev≠0, rpm=0` 时必须**先 detach 再 setSpeed(0)**。清理后必须调用 `pendingReattach = true`，下一 tick 执行 `attachKinetics()` 重连邻居网络。

### 5.4 Mixin 配置文件

`living_item.mixins-create.json`：

```json
{
  "required": false,
  "package": "com.qiqi.li.living.mixin.create",
  "compatibilityLevel": "JAVA_21",
  "plugin": "com.qiqi.li.living.compat.create.CreateMixinPlugin",
  "mixins": [
    "KineticBlockEntityMixin"
  ],
  "injectors": {
    "defaultRequire": 1
  }
}
```

- `required: false` — Mixin 加载失败不崩溃（Create 未安装时正常）
- `plugin` — 指定条件加载插件
- Mixin 类放在 `com.qiqi.li.living.mixin.create` 包中，与普通类 `com.qiqi.li.living.create` 分离

### 5.5 依赖配置

`build.gradle`：

```groovy
// Create 兼容（compileOnly 编译 + runtimeOnly 开发时加载，玩家可选安装）
compileOnly("com.simibubi.create:create-${minecraft_version}:6.0.10-280:slim") { transitive = false }
compileOnly("net.createmod.ponder:ponder-neoforge:1.0.82+mc${minecraft_version}")
compileOnly("dev.engine-room.flywheel:flywheel-neoforge-api-${minecraft_version}:1.0.6")
compileOnly("com.tterrag.registrate:Registrate:MC1.21-1.3.0+67")
runtimeOnly("com.simibubi.create:create-${minecraft_version}:6.0.10-280:slim") { transitive = false }
runtimeOnly("net.createmod.ponder:ponder-neoforge:1.0.82+mc${minecraft_version}")
runtimeOnly("dev.engine-room.flywheel:flywheel-neoforge-api-${minecraft_version}:1.0.6")
runtimeOnly("com.tterrag.registrate:Registrate:MC1.21-1.3.0+67")
```

- `compileOnly`：编译时可用，不打包进模组 jar
- `runtimeOnly`：开发环境运行时加载，玩家不需要安装
- `transitive = false`：不自动拉取 Create 的传递依赖，手动指定 Ponder/Flywheel/Registrate

`neoforge.mods.toml`：

```toml
[[dependencies.living_item]]
    modId="create"
    type="optional"
    versionRange="[6.0.10,6.1.0)"
    ordering="AFTER"
    side="BOTH"
```

- `type="optional"` — 不强制安装
- `ordering="AFTER"` — 在 Create 之后加载，确保 Create 的类已可用

### 5.6 应力单位换算

活水车的内部"应力"（力矩计算结果）与 Create 的 RPM/SU 换算关系：

```
活水车内部单位 → Create 单位

RPM        = -sign(netStress) × BASE_RPM (8.0)      负号修正旋转方向，大小固定
SU 容量    = |netStress| × BASE_SU_CAPACITY (32.0)     堆叠增加 SU，不增加转速
实际 SU    = SU容量 × |RPM|                             Create 网络自动计算
方向       = netStress > 0 ? CW : CCW
```

| 参数 | 值 | 说明 |
|------|-----|------|
| `BASE_RPM` | 8.0 | 基础转速，与原版小水车一致 |
| `BASE_SU_CAPACITY` | 32.0 | 基础 SU 容量，与原版小水车一致 |

**设计原则**：与原版小水车数值对齐。堆叠/多水流增加 SU 容量（能驱动更多设备），但不改变转速。

示例：

| 场景 | netStress | RPM | SU 容量 | 实际 SU |
|------|-----------|-----|---------|---------|
| 1 个活水车，力矩 +1 | +1 | +8 | 32 | 256 |
| 1 个活水车，力矩 +3 | +3 | +8 | 96 | 768 |
| 64 个活水车堆叠，力矩 +3 | +192 | +8 | 6144 | 49152 |
| 2 个活水车反向抵消 | 0 | 0 | 0 | 0 |

对比原版：

| | RPM | SU 容量 | 实际 SU |
|---|-----|---------|---------|
| 原版小水车 | 8 | 32 | 256 |
| 原版大水车 | 4 | 128 | 512 |
| **活水车（netStress=1）** | **8** | **32** | **256** |

### 5.7 宿主物品识别

活水车的宿主物品是 `create:water_wheel`，通过注册表动态查找：

```java
private static Item WATER_WHEEL_ITEM;

private static Item getWaterWheelItem() {
    if (WATER_WHEEL_ITEM == null) {
        WATER_WHEEL_ITEM = BuiltInRegistries.ITEM.get(
            ResourceLocation.fromNamespaceAndPath("create", "water_wheel"));
    }
    return WATER_WHEEL_ITEM;
}
```

- 安装 Create 时，返回水车物品，活水车功能正常
- 未安装 Create 时，注册表返回空气物品，`isWaterWheelItem()` 返回 false，活水车功能自动禁用

---

## 6. 物品栏 3D 渲染

### 6.1 设计目标

活水车在物品栏中以**3D 方块形式**渲染，并根据应力状态旋转：
- **无应力**：静止显示，正面朝向屏幕
- **有应力**：绕 Z 轴持续旋转，方向由净应力正负决定
- 旋转速度固定，与 RPM 无关（仅区分有/无应力）

### 6.2 渲染架构

活水车使用 Create 原版水车的 OBJ 模型，通过 Mixin 拦截 `ItemRenderer.render()` 方法实现自定义渲染：

```
ItemRenderer.render()
  │
  ├─ @Inject HEAD → captureItem()        设置 RENDERING_ITEM ThreadLocal
  │
  ├─ @Redirect → handleCameraTransforms()  ← 核心拦截点
  │     │
  │     ├── 非活水车 → 原始 handleCameraTransforms()
  │     │
  │     └── 活水车（GUI 上下文）
  │           │
  │           ├── 1. 修正漫反射光照方向
  │           │     RenderSystem.setShaderLights(FRONT_LIGHT_0, FRONT_LIGHT_1)
  │           │
  │           ├── 2. 跳过等轴测变换（不调用 handleCameraTransforms）
  │           │
  │           ├── 3. 应用旋转变换
  │           │     netStress == 0 → 仅 rotateX(π/2) + scale(0.7)
  │           │     netStress != 0 → rotateZ(-angle) + rotateX(π/2) + scale(0.625)
  │           │
  │           └── return model（跳过等轴测变换）
  │
  └─ @Inject RETURN → clearItem()        恢复漫反射光照 + 清理 ThreadLocal
```

### 6.3 旋转变换

#### 变换顺序

```
PoseStack 变换链（从右到左应用）：

1. rotateZ(-angle)    ← 绕 Z 轴旋转（屏幕平面内旋转）
2. rotateX(π/2)       ← 绕 X 轴旋转 90°（从等轴测视角转为正面朝向）
3. scale(0.625)       ← 缩放适配物品栏大小
```

#### 旋转角度计算

```java
float rpm = Math.signum(netStress) * StressOutputManager.BASE_RPM;  // ±8
float time = mc.level.getGameTime() + mc.getTimer().getGameTimeDeltaPartialTick(true);
float angle = (time * rpm * 3f / 10f) % 360f;
float radians = -angle / 180f * (float) Math.PI;          // 负号修正旋转方向
```

| 参数 | 值 | 说明 |
|------|-----|------|
| `BASE_RPM` | 8.0 | 基础转速，与原版小水车一致 |
| `time` | gameTick + partialTick | 连续时间，避免旋转卡顿 |
| `3f / 10f` | 0.3 | 视觉旋转速度系数 |
| 负号 | `-angle` | 修正旋转方向，使正应力对应顺时针 |

#### 无应力状态

```java
// netStress == 0：仅正面朝向，不旋转
poseStack.mulPose(new Quaternionf().rotateX((float) Math.PI / 2f));
poseStack.scale(0.7f, 0.7f, 0.7f);
```

无应力时缩放略大（0.7 vs 0.625），因为不旋转时视觉占位更小。

### 6.4 漫反射光照修正

#### 问题

Minecraft GUI 物品渲染使用**漫反射光照**（diffuse lighting），默认光源方向从左上方和右上方照射：

```
默认 GUI 光照（Lighting.setupForFlatItems）：
  Light_0 = normalize(-0.5, -1.0, -0.5)  ← 左上方
  Light_1 = normalize( 0.5, -1.0, -0.5)  ← 右上方
```

我们通过 `rotateX(π/2)` 将模型从等轴测视角转为正面朝向屏幕，但漫反射光源方向未改变。模型正面法线不再对准光源方向，导致渲染**过暗**。

#### 解决方案

在水车渲染前，临时将漫反射光源设为**从正前方照射**：

```java
private static final Vector3f FRONT_LIGHT_0 = new Vector3f(0.0f, 0.0f, 1.0f);  // 正前方
private static final Vector3f FRONT_LIGHT_1 = new Vector3f(-1.0f, 0.0f, 0.0f);  // 侧面补光

// 渲染前设置
RenderSystem.setShaderLights(FRONT_LIGHT_0, FRONT_LIGHT_1);

// 渲染后恢复
Lighting.setupForFlatItems();
```

| 光源 | 默认方向 | 正面方向 | 说明 |
|------|---------|---------|------|
| Light_0 | (-0.5, -1.0, -0.5) 归一化 | (0, 0, 1) | 主光源从正前方照射 |
| Light_1 | (0.5, -1.0, -0.5) 归一化 | (-1, 0, 0) | 补光从左侧照射 |

> **注意**：`combinedLight`（光照贴图）无需修改。`GuiGraphics.renderItem()` 传入的值已经是 `15728880`（即 `LightTexture.FULL_BRIGHT`），GUI 中的物品本身就使用最大光照贴图。真正需要修正的只有漫反射光源方向。

### 6.5 ThreadLocal 状态传递

`ItemRenderer.render()` 的 `@Redirect` 拦截点无法直接获取 `ItemStack` 参数，使用 ThreadLocal 传递：

```java
private static final ThreadLocal<ItemStack> RENDERING_ITEM = new ThreadLocal<>();
private static final ThreadLocal<Boolean> NEED_RESTORE_LIGHTING = ThreadLocal.withInitial(() -> false);

// HEAD 注入：捕获 ItemStack
RENDERING_ITEM.set(itemStack);

// Redirect 中：读取 ItemStack
ItemStack itemStack = RENDERING_ITEM.get();

// RETURN 注入：恢复光照 + 清理
if (NEED_RESTORE_LIGHTING.get()) {
    Lighting.setupForFlatItems();
}
RENDERING_ITEM.remove();
```

### 6.6 条件判断

水车渲染仅在以下条件**全部满足**时激活：

```
CreateCompat.isLoaded()        ← Create 已安装
  && isWaterWheel(itemStack)   ← 物品是 create:water_wheel
  && isGuiContext(context)     ← 渲染上下文是 GUI/GROUND/FIXED
  && LivingItemManager.isLivingItem(itemStack)  ← 物品有活物品标记
```

非活物品的普通水车、非 GUI 上下文（如世界中的实体渲染）、未安装 Create 时，均走原始渲染路径。

### 6.7 Mixin 配置

`living_item.client.mixins.json`：

```json
{
  "required": true,
  "package": "com.qiqi.li.client.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": [
    "AbstractContainerScreenMixin",
    "CreativeModeInventoryScreenMixin",
    "InventoryScreenMixin",
    "RecipeBookComponentMixin",
    "SlotWrapperAccessor",
    "SpriteIconButtonMixin",
    "ItemRendererWaterWheelMixin"
  ],
  "injectors": {
    "defaultRequire": 1
  },
  "overwrites": {
    "requireAnnotations": true
  }
}
```

`ItemRendererWaterWheelMixin` 注册在客户端 Mixin 配置中，仅在客户端加载。

---

## 7. Tooltip 显示

### 7.1 显示内容

| 状态 | 显示 | 颜色 |
|------|------|------|
| 无应力 | "↻ 顺时针: +0  ↺ 逆时针: -0  净应力: 0 (平衡)" | 灰色 |
| 顺时针为主 | "↻ 顺时针: +X  ↺ 逆时针: -X  净应力: +Y" | 金色 |
| 逆时针为主 | "↻ 顺时针: +X  ↺ 逆时针: -X  净应力: -Y" | 青色 |

### 7.2 翻译键

| 翻译键 | 中文 | English |
|--------|------|---------|
| `tooltip.livingitem.water_wheel.status` | §a活水车§r | §aLiving Water Wheel§r |
| `tooltip.livingitem.water_wheel.no_stress` | §7无应力§r | §7No Stress§r |
| `tooltip.livingitem.water_wheel.stress` | ↻ 顺时针: +%1$s  ↺ 逆时针: -%2$s  净应力: +%3$s | ↻ CW: +%1$s  ↺ CCW: -%2$s  Net: +%3$s |
| `tooltip.livingitem.water_wheel.balanced` | ↻ 顺时针: +%1$s  ↺ 逆时针: -%2$s  净应力: 0 (平衡) | ↻ CW: +%1$s  ↺ CCW: -%2$s  Net: 0 (balanced) |

---

## 8. 实现步骤

### 8.1 已完成

```
第一步：活水车 + 容器级应力（纯内部）  ✅
  ├── LivingWaterWheelFunction（活水车功能）
  ├── WaterWheelData record（应力数据）
  ├── LivingWaterWheelData（DataComponent）
  ├── ContainerStressData（容器级应力累加器）
  ├── 在 ContainerFluidData.tick() 后计算应力
  ├── Tooltip 显示 CW/CCW 应力值
  └── 无需 Create 依赖

第二步：容器 BlockEntity 存储应力  ✅
  ├── NeoForge AttachmentType（CONTAINER_STRESS_DATA）
  ├── 从 ContainerStressData 同步到 BlockEntity（be.setData）
  └── 仍无需 Create 依赖

第三步：Create 集成（软依赖）  ✅
  ├── CreateCompat（检测 Create 是否安装）
  ├── ModCreate（集成入口，常量定义，安全防护）
  ├── CreateIntegration（应力输出逻辑，通过接口操作下方/脚底 BE，含白名单过滤）
  ├── LivingItemStressOutput（接口注入模式）
  ├── CreateMixinPlugin（条件 Mixin 加载，getResource 检测）
  ├── KineticBlockEntityMixin（注入 getGeneratedSpeed/calculateAddedStressCapacity/tick）
  │   ├── 自过期机制：refreshedThisTick 布尔标记
  │   ├── 白名单过滤：isSafeKineticBE() 双重检查
  │   ├── 网络更新：setGeneratedRPM/setStressCapacity 立即更新网络
  │   └── 客户端同步：sendData() 确保渲染状态正确
  ├── living_item.mixins-create.json（Mixin 配置，required=false）
  ├── neoforge.mods.toml 添加 Create 为 optional 依赖
  ├── build.gradle 配置 compileOnly + runtimeOnly
  ├── 宿主物品从 compass 改为 create:water_wheel
  ├── 容器下方传动杆/齿轮成功旋转！
  ├── 玩家脚底应力传递（背包中活水车驱动脚下齿轮）
  └── 应力方向修正（-sign 修正旋转方向一致性）

第四步：物品栏 3D 渲染  ✅
  ├── ItemRendererWaterWheelMixin（Mixin 到 ItemRenderer.render）
  ├── @Redirect handleCameraTransforms → 跳过等轴测变换 + 应用旋转
  ├── @Inject HEAD/RETURN → ThreadLocal 状态传递 + 光照恢复
  ├── 旋转变换：rotateZ(-angle) + rotateX(π/2) + scale
  ├── 漫反射光照修正：RenderSystem.setShaderLights() 正前方光源
  ├── 旋转方向修正：负号使正应力对应顺时针
  ├── 条件判断：CreateCompat + isWaterWheel + isGuiContext + isLivingItem
  ├── LivingIconRegistry 条件注册活水车图标
  ├── living_item.client.mixins.json 注册 ItemRendererWaterWheelMixin
  └── 物品栏活水车正面朝向 + 有应力时旋转！
```

---

## 9. 踩坑记录

### 9.1 NoClassDefFoundError: CreateIntegration

**现象**：运行时 `CreateIntegration` 类找不到，导致游戏崩溃。

**根因**：`CreateIntegration` 直接 import 了 `com.simibubi.create.content.kinetics.base.KineticBlockEntity`。JVM 在加载 `CreateIntegration` 类时需要解析所有 import 的类。NeoForge 的模块类加载器（`ModuleClassLoader`）在加载活物品模组的类时，可能无法跨模块访问 Create 的类，导致 `ClassNotFoundException`。

**修复**：移除 `CreateIntegration` 中所有对 Create 类的直接 import，改用 `instanceof LivingItemStressOutput` 接口检查。`LivingItemStressOutput` 是我们自己定义的接口，不依赖任何 Create 的类。Mixin 让 `KineticBlockEntity` 在运行时实现此接口，`instanceof` 由 JVM 动态判断，不需要编译时链接。

```java
// 修复前（崩溃）
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
if (!(be instanceof KineticBlockEntity kineticBE)) return;

// 修复后（安全）
if (!(be instanceof LivingItemStressOutput stressOutput)) return;
```

### 9.2 MixinTargetAlreadyLoadedException

**现象**：Fabric API 的 `BlockEntityMixin` 无法应用，报错 `target net.minecraft.world.level.block.entity.BlockEntity was loaded too early`。

**根因**：`CreateMixinPlugin.onLoad()` 中使用了 `Class.forName("com.simibubi.create.content.kinetics.base.KineticBlockEntity")` 检测 Create 是否安装。这会触发 JVM 加载 `KineticBlockEntity` 类，而它继承自 `BlockEntity`，导致 `BlockEntity` 在 Mixin 准备阶段就被提前加载。之后 Fabric 的 Mixin 想要 target `BlockEntity` 时，发现类已经被加载，无法再应用 Mixin。

**修复**：将 `Class.forName()` 改为 `ClassLoader.getResource()`，只检查 `.class` 文件是否存在于类路径上，不会触发任何类的加载。

```java
// 修复前（触发类加载）
Class.forName("com.simibubi.create.content.kinetics.base.KineticBlockEntity");

// 修复后（仅检查资源存在性）
ClassLoader cl = CreateMixinPlugin.class.getClassLoader();
var url = cl.getResource("com/simibubi/create/content/kinetics/base/KineticBlockEntity.class");
createLoaded = url != null;
```

### 9.3 Mixin 类运行时引用问题

**现象**：直接引用 Mixin 类 `KineticBlockEntityMixin` 导致 `NoClassDefFoundError: KineticBlockEntityMixin is invalid`。

**根因**：Mixin 类在运行时由 Mixin 框架生成，不能被普通代码直接引用或强制类型转换。

**修复**：定义 `LivingItemStressOutput` 接口，Mixin 类实现该接口。运行时通过 `instanceof` 检测和接口方法调用，完全不需要引用 Mixin 类本身。

```java
// 修复前（崩溃）
((KineticBlockEntityMixin) (Object) kineticBE).livingItem$setGeneratedRPM(rpm);

// 修复后（安全）
if (kineticBE instanceof LivingItemStressOutput stressOutput) {
    stressOutput.livingItem$setGeneratedRPM(rpm);
}
```

### 9.4 Mixin 包冲突

**现象**：`com.qiqi.li.living.create` 包同时包含 Mixin 类和普通类，导致 `IllegalClassLoadError`。

**根因**：Mixin 框架对包路径有特殊要求，Mixin 类和普通类不能混在同一个包中。

**修复**：将 Mixin 类移至 `com.qiqi.li.living.mixin.create` 包，普通类保留在 `com.qiqi.li.living.create` 包。

### 9.5 硬依赖导致玩家必须安装 Create

**现象**：使用 `implementation` 配置 Create 依赖，导致 NeoForge 在加载时检查依赖是否存在，未安装 Create 的玩家无法使用活物品模组。

**修复**：将 Create 及其传递依赖改为 `compileOnly`（编译时可用，不打包进模组）+ `runtimeOnly`（开发环境运行时加载）。在 `neoforge.mods.toml` 中声明 Create 为 `optional` 依赖。

### 9.6 ModList.get() 在 Mixin 插件阶段不可用

**现象**：`CreateMixinPlugin` 使用 `ModList.get().isLoaded("create")` 检测 Create，但 Mixin 加载阶段 FML 可能尚未完全初始化，导致误判。

**修复**：改用 `ClassLoader.getResource()` 检测 Create 的类文件是否存在，这在 Mixon 加载的任何阶段都可靠。

### 9.7 下游齿轮空转：断开顺序错误

**现象**：移除容器内的活水车后，容器下方的齿轮正确停止旋转，但被齿轮带动的其他齿轮依旧在空转。

**根因**：`livingItem$setGeneratedRPM(0)` 中的断开顺序错误。原来先 `setSpeed(0)` 再 `detachKinetics()`，但 `detachKinetics()` 内部调用 `RotationPropagator.handleRemoved()` 时，发现 `speed == 0` 直接返回，不会向下游齿轮传播"源已移除"的消息。

**修复**：改为先 `detachKinetics()`（此时 speed 仍非零，`handleRemoved` 能正确传播），再 `setSpeed(0)` 和 `setNetwork(null)`。这与 Create 自己的 `GeneratingKineticBlockEntity.applyNewSpeed()` 逻辑一致。

```java
// 修复前（下游齿轮空转）
if (prev != 0 && rpm == 0) {
    self.setSpeed(0);        // speed 先变为 0
    self.removeSource();
    self.detachKinetics();   // handleRemoved 发现 speed==0，直接返回，不传播
}

// 修复后（正确传播移除）
if (prev != 0 && rpm == 0) {
    self.detachKinetics();   // speed 仍非零，handleRemoved 正确传播移除
    self.setSpeed(0);
    self.setNetwork(null);
}
```

### 9.8 多个 @Redirect 导致 MixinTransformerError 崩溃

**现象**：在 `ItemRenderer.render()` 方法上同时使用 3 个 `@Redirect`（`renderModelLists`、`renderByItem`、`handleCameraTransforms`），游戏启动时崩溃：`MixinTransformerError: An unexpected critical error was encountered`，导致 `RenderType` 类初始化失败。

**根因**：同一个方法上有多个 `@Redirect` 修改不同调用点时，Mixin 框架在字节码变换阶段可能产生冲突。特别是 `renderModelLists` 和 `renderByItem` 的 `@Redirect` 改变了方法结构，影响了后续 Mixin 对 `RenderType` 类的处理。

**修复**：移除 `renderModelLists` 和 `renderByItem` 的 `@Redirect`，只保留 `handleCameraTransforms` 一个 `@Redirect`。光照问题改用漫反射光源修正（`RenderSystem.setShaderLights()`）解决，无需拦截渲染路径。

### 9.9 @ModifyVariable ordinal 错误导致全黑

**现象**：使用 `@ModifyVariable(ordinal=1)` 修改 `combinedLight` 参数，结果所有物品变全黑。

**根因**：`ordinal=1` 对应的是 `combinedOverlay`（第二个 int 参数），不是 `combinedLight`。修改 overlay 为 `FULL_BRIGHT` 的值导致覆盖层异常。

**修复**：改用 `ordinal=0`。但最终发现 `combinedLight` 修改本身也是多余的——GUI 传入的值已经是 `FULL_BRIGHT`，真正需要修正的是漫反射光照方向。

### 9.10 @ModifyVariable 先于 @Inject 执行导致条件不触发

**现象**：`@ModifyVariable(at = @At("HEAD"))` 修改 `combinedLight`，但 `IS_WATER_WHEEL` ThreadLocal 始终为 false，条件不触发。

**根因**：`@ModifyVariable(at = @At("HEAD"))` 在方法入口最先执行，此时 `@Inject(at = @At("HEAD"))` 的 `captureItem()` 尚未执行，`IS_WATER_WHEEL` 还是默认值 false。

**修复**：将 `@ModifyVariable` 的注入点改为 `@At(value = "INVOKE", target = "handleCameraTransforms")`，确保在 `captureItem()` 之后执行。最终移除了 `@ModifyVariable`，改用漫反射光源修正。

### 9.11 漫反射光照方向导致模型过暗

**现象**：活水车正面朝向屏幕后，渲染明显偏暗。设置 `combinedLight = FULL_BRIGHT` 无效。

**根因**：Minecraft 物品渲染有两层光照：
1. **光照贴图**（`combinedLight`）— GUI 已传入 `FULL_BRIGHT`，无需修改
2. **漫反射光照**（`RenderSystem.setShaderLights()`）— 默认光源从左上/右上方照射，模型旋转后正面法线不对准光源

**修复**：在水车渲染前临时设置正前方漫反射光源：

```java
RenderSystem.setShaderLights(
    new Vector3f(0.0f, 0.0f, 1.0f),   // 主光源：正前方
    new Vector3f(-1.0f, 0.0f, 0.0f)   // 补光：左侧
);
```

渲染后调用 `Lighting.setupForFlatItems()` 恢复默认光照。

### 9.12 旋转方向反了

**现象**：正应力（CW）的活水车逆时针旋转，负应力（CCW）的顺时针旋转。

**根因**：`rotateZ(angle)` 中 angle 为正值时，在 Minecraft 的坐标系中（Y 轴朝下）对应逆时针旋转，与直觉相反。

**修复**：在弧度转换中加负号：`radians = -angle / 180f * π`。

### 9.13 应力源消失后应力残留（自过期机制）

**现象**：容器被破坏或玩家从齿轮上移开后，下方齿轮仍在旋转。实际应力已消失（服务端 RPM=0），但客户端仍显示旋转。

**根因**：最初的方案使用时间戳过期——记录最后一次设置 RPM 的时间，超过 2 tick 未刷新则清理。但存在两个问题：
1. **2 tick 延迟**：时间戳比较有容忍期，应力源消失后最多 2 tick 才清理
2. **客户端同步缺失**：清理时未调用 `sendData()`，客户端不知道应力已消失

**修复**：改用布尔标记 `refreshedThisTick`，实现自过期机制：

```
每 tick 流程：
  1. setGeneratedRPM() 被调用 → refreshedThisTick = true
  2. tick() 开始 → 检查 refreshedThisTick
     ├── true（有应力源）→ 重置为 false，下 tick 继续检测
     └── false（应力源已消失）→ 立即清理 RPM/SU + detachKinetics + setNetwork(null) + deferredSync
  3. getGeneratedSpeed() 被调用 → 检查 refreshedThisTick
     ├── false → 清零 RPM/SU，返回默认值
     └── true → 返回注入的 RPM
```

**优势**：
- 响应速度从 2 tick 提升到 1 tick
- `tick()` 中清理时通过 `deferredSync`（tick TAIL）安全同步客户端
- `getGeneratedSpeed()` 中也有兜底检测，双重保障

> **注意**：清理时，`generatedRPM` 和 `stressCapacity` 的清零操作必须在 `setNetwork(null)` 之后，且 `setNetwork(null)` 前需设 `refreshedThisTick = true`，确保 `network.remove()` 内部的 `isSource()` 返回 true，`sources.remove(be)` 被正确调用。详见 9.19。

### 9.14 复杂组件崩溃与白名单策略

**现象**：在容器下方放置竖直十字齿轮箱（`DirectionalShaftHalvesBlockEntity`），活水车输出应力后游戏崩溃，NPE。

**根因**：`DirectionalShaftHalvesBlockEntity` 等复杂组件依赖 `source` 字段来确定旋转来源。活水车注入应力时未设置 `source`，导致这些组件在内部逻辑中访问 `source` 时抛出 NPE。

**方案对比**：

| 方案 | 描述 | 优点 | 缺点 |
|------|------|------|------|
| A. 设置 source | 为注入的 BE 设置 source 字段 | 兼容更多组件 | source 语义不正确，validateKinetics() 会清掉 |
| B. 白名单过滤 | 仅允许简单组件接收应力 | 安全可靠，不会崩溃 | 限制了可用组件范围 |
| C. A+B 融合 | 白名单 + 对白名单内组件设置 source | 兼容性最好 | 增加复杂度，source 仍会被 validateKinetics 清掉 |

**最终选择方案 B**：白名单策略。原因：
1. `validateKinetics()` 每 60 tick 检查 source，发现容器不是 Kinetic BE 后会清除应力，方案 A 不可行
2. 简单传动组件（传动杆、齿轮）不依赖 source，白名单内安全
3. 复杂组件的内部逻辑过于复杂，强行注入风险高

**白名单**：`SimpleKineticBlockEntity`（传动杆、齿轮）和 `BracketedKineticBlockEntity`（支架齿轮）。

**双重检查**：`CreateIntegration` 和 `KineticBlockEntityMixin` 各自维护 `isSafeKineticBE()`，确保即使绕过第一层也不会崩溃。

### 9.15 应力输出方向与物品栏旋转不一致

**现象**：活水车在物品栏中顺时针旋转，但下方齿轮逆时针旋转（或反之）。

**根因**：`CreateIntegration` 中 RPM 计算使用 `Math.signum(netStress)`，正应力对应正 RPM。但 Create 的正 RPM 对应的旋转方向与物品栏渲染中的正应力旋转方向相反。

**修复**：在 RPM 计算中添加负号：

```java
// 修复前
rpm = Math.signum(netStress) * ModCreate.BASE_RPM;

// 修复后
rpm = -Math.signum(netStress) * ModCreate.BASE_RPM;
```

这确保物品栏中活水车的旋转方向与下方齿轮的旋转方向视觉一致。

### 9.16 取消应力后齿轮不恢复原发电机旋转

**现象**：齿轮已被原版发电机带动旋转，活水车叠加应力后取消注入，齿轮完全停止，不会被原发电机重新带动。

**根因**：取消应力时的清理流程过于彻底——`detachKinetics()` + `setSpeed(0)` + `setNetwork(null)` 将齿轮从网络中彻底断开，但**没有重新连接**。齿轮变成孤立状态，再也无法感知邻居的旋转。

```java
// 修复前：断开后不重连，齿轮成为孤魂野鬼
self.detachKinetics();
self.setSpeed(0);
self.setNetwork(null);
// ← 结束，齿轮永远停了
```

**修复**：在清理之后调用 `attachKinetics()`，让 `RotationPropagator` 重新评估邻居连接：

```java
// 修复后：断开后重连，自动寻找邻居网络
self.detachKinetics();
self.setSpeed(0);
self.setNetwork(null);
self.attachKinetics();    // ← 重新加入邻居的网络
```

**`attachKinetics()` 的工作原理**：调用 `RotationPropagator.handleAdded()`，遍历邻居计算传递速度。当 BE 的 speed=0 而邻居 speed≠0 时，`propagateNewSource()` 会走"邻居更快，接管当前 BE"分支，自动把齿轮重新接入原发电机的网络。

**修改了两处**：
1. `livingItem$setGeneratedRPM(0)` 主动取消路径
2. `livingItem$checkExpiry()` 自过期清理路径

### 9.17 旋转方向冲突导致方块销毁

**现象**：齿轮已被原版发电机带动旋转（如顺时针），活水车注入反方向应力（逆时针），齿轮直接变成掉落物。

**根因**：Create 的 `RotationPropagator.propagateNewSource()` 中有方向冲突检测：

```java
boolean incompatible =
    Math.signum(newSpeed) != Math.signum(speedOfNeighbour) && (newSpeed != 0 && speedOfNeighbour != 0);

if (incompatible) {
    world.destroyBlock(pos, true);  // 方块直接炸掉！
    return;
}
```

当注入的 BE 成为旋转源后，`attachKinetics()` 触发 `propagateNewSource`，如果邻居的旋转方向与注入方向相反，Create 认为这是"不可调和的冲突"，直接销毁方块。

**修复**：在 `CreateIntegration` 中注入前检查方向兼容性，方向不兼容则跳过注入（软侵入）：

```java
private static boolean isDirectionCompatible(BlockEntity be, float injectedRPM) {
    if (!(be instanceof KineticBlockEntity kbe)) return true;
    float existingSpeed = kbe.getTheoreticalSpeed();
    if (existingSpeed == 0) return true;                    // 静止状态，任何方向都可注入
    return Math.signum(injectedRPM) == Math.signum(existingSpeed);  // 方向一致才注入
}
```

**软侵入原则**：
- 齿轮静止 → 正常注入，活水车成为旋转源
- 齿轮旋转且方向一致 → 正常注入，叠加应力
- 齿轮旋转且方向相反 → **跳过注入**，清零残留 RPM，不干扰原有旋转

### 9.18 区块卸载后应力翻倍和变速结构爆炸

**现象**：含活水车的区块卸载重进后，应力翻倍（原本三万变七万，两次变 14 万）。卸载区块还可能爆掉网络里的变速结构（大小齿轮相接处、变速器爆炸）。

**根因**：`SmartBlockEntity.setRemoved()` 中，当 `chunkUnloaded = true`（区块卸载）时，`remove()` 方法**不会被调用**：

```java
// SmartBlockEntity.java
public final void setRemoved() {
    super.setRemoved();
    if (!chunkUnloaded)  // 卸载时跳过
        remove();
    invalidate();
}
```

这意味着 `KineticBlockEntity.remove()`（包含 `network.remove(this)` 和 `detachKinetics()`）不会在区块卸载时执行。导致：

1. **应力翻倍**：旧 block entity 残留在网络 `sources` 中，区块重载时新 block entity 又被加入同一网络，`calculateCapacity()` 遍历 `sources` 时同一个位置被计数两次
2. **变速结构爆炸**：网络中的残留 block entity 造成状态不一致，`getGeneratedSpeed()` 返回 0（`refreshedThisTick=false`），`isSource()` 返回 false，但 block entity 仍在 `sources` 中，触发 Create 内部异常

**修复**：在 `KineticBlockEntityMixin` 中新增 `onChunkUnloaded` 注入，在区块卸载时强制执行网络清理：

```java
@Inject(method = "onChunkUnloaded", at = @At("HEAD"), remap = false)
private void livingItem$onChunkUnloaded(CallbackInfo ci) {
    KineticBlockEntity self = (KineticBlockEntity) (Object) this;
    if (self.getLevel() == null || self.getLevel().isClientSide) return;

    if (livingItem$generatedRPM != 0) {
        livingItem$refreshedThisTick = true;  // 确保 isSource() 返回 true

        if (self.hasNetwork()) {
            self.getOrCreateNetwork().remove(self);  // 从网络正确移除
        }

        self.detachKinetics();  // 从旋转传播中分离

        livingItem$generatedRPM = 0;
        livingItem$stressCapacity = 0;
        livingItem$refreshedThisTick = false;
        livingItem$pendingReattach = false;
        livingItem$needsSync = false;
    }
}
```

**关键细节**：`network.remove(self)` 内部调用 `isSource()` → `getGeneratedSpeed()`。必须先设 `refreshedThisTick = true`，确保 `getGeneratedSpeed()` 返回实际 RPM（非零），从而 `isSource()` 返回 true，`sources.remove(be)` 被正确调用。`generatedRPM` 和 `stressCapacity` 的清零必须在 `network.remove()` 和 `detachKinetics()` 之后。

### 9.19 `isSource()` 返回 false 导致 `sources.remove()` 被跳过（通用问题）

**现象**：与 9.18 相同根因，但发生在**非区块卸载**场景——`checkExpiry` 自过期清理和 `setGeneratedRPM(rpm=0)` 主动取消应力时，`sources.remove(be)` 同样被跳过。

**根因**：三处代码路径都有相同的 bug 模式——在调用 `setNetwork(null)`（内部调 `network.remove()`）之前，`generatedRPM` 已被清零或 `refreshedThisTick` 为 false，导致 `getGeneratedSpeed()` 返回 0，`isSource()` 返回 false，`sources.remove(be)` 被跳过：

| 代码路径 | 触发条件 | 问题 |
|---------|---------|------|
| `checkExpiry` | tick 时 `refreshedThisTick=false` | `generatedRPM` 先清零，再调 `setNetwork(null)` |
| `setGeneratedRPM(rpm=0)` | 主动取消应力 | `generatedRPM = rpm`（即 0）在 `setNetwork(null)` 之前执行 |
| `onChunkUnloaded` | 区块卸载 | `refreshedThisTick` 为 false，`getGeneratedSpeed()` 副作用清零 RPM |

**修复**：统一原则——`setNetwork(null)` 之前确保 `getGeneratedSpeed()` 返回非零值：

**`checkExpiry` 修复**：先设 `refreshedThisTick = true`，再调 `setNetwork(null)`，最后再清零 `generatedRPM`：

```java
// 修复后
if (!livingItem$refreshedThisTick) {
    try {
        if (self instanceof GeneratingKineticBlockEntity gen) {
            gen.updateGeneratedRotation();
        } else {
            livingItem$refreshedThisTick = true;  // 确保 isSource() 返回 true
            self.detachKinetics();
            self.setSpeed(0);
            self.setNetwork(null);               // network.remove() 正确移除
            livingItem$pendingReattach = true;
            self.setChanged();
            livingItem$needsSync = true;
        }
    } catch ...
    livingItem$generatedRPM = 0;                // 最后再清零
    livingItem$stressCapacity = 0;
}
```

**`setGeneratedRPM(rpm=0)` 修复**：`generatedRPM` 保留原值（非零）直到 `setNetwork(null)` 之后：

```java
// 修复后
if (prev != 0 && rpm == 0) {
    // livingItem$generatedRPM 仍是 prev（非零），refreshedThisTick 已是 true
    self.detachKinetics();
    self.setSpeed(0);
    self.setNetwork(null);              // isSource() 返回 true，正确移除
    livingItem$pendingReattach = true;
    livingItem$generatedRPM = 0;        // 最后再清零
    livingItem$stressCapacity = 0;
}
```

**核心原则**：在调用 `network.remove()` 时，`getGeneratedSpeed()` 必须返回非零值，否则 `KineticNetwork.remove()` 中的 `sources.remove(be)` 被跳过，block entity 残留在网络 `sources` 中，导致：
- 应力翻倍（`calculateCapacity()` 重复计数）
- 网络状态异常（残留条目与活跃条目不一致）
- 变速结构爆炸（Create 内部校验失败）

### 9.20 活水桶移除后水流数据残留导致虚假应力

**现象**：活水桶被取走后，活水车依旧显示净应力（Tooltip 有值），且移动到不同槽位净应力还会变化，仿佛水流数据依旧存在。同时容器下方的齿轮可能不旋转，但旁边齿轮仍在转（且无应力）。

**根因**：水流数据缓存在 `CONTAINER_DATA` 嵌套 `fluid` 字段中，清除的唯一入口是 `ContainerFluidData.recalculate()`，而 `recalculate()` 只在 `LivingWaterBucketFunction.tickContainerData()` 中被调用。当活水桶被取走：

```
processContext() 扫描容器
  → grouped 中没有 LivingWaterBucketFunction（水桶已不在）
  → hcdEntries 没有它
  → tickContainerData() 不被调用
  → recalculate() 不执行
  → CONTAINER_DATA 中的旧水流数据（fluid 字段）一直残留
  → LivingWaterWheelFunction.tickContainerData() 仍被调用
  → 用残留水流数据计算应力 → 虚假应力
```

**为何 `recalculate()` 不在水车侧调用**：水流数据是共享资源，被多个水桶和水车共同使用，职责归属在水桶功能（`LivingWaterBucketFunction`）而非水车功能。水车只读水流数据，不负责其生命周期。

**为何之前没发现**：水流数据有 120 秒超时清理，但 120 秒内虚假应力一直存在，且每 tick 都在计算。

**修复**：在 `ContainerLivingItemHandler.processContext()` 中，`hcdEntries` 循环之前，检查 `grouped` 中是否有活水桶条目。如果没有，直接清除水流数据：

```java
// 在 hcdEntries 循环之前
boolean hasWaterBucket = false;
for (var entry : grouped.entrySet()) {
    if ("living_water_bucket".equals(entry.getKey().getFunctionId())) {
        hasWaterBucket = true;
        break;
    }
}
if (!hasWaterBucket && tick.fluidData != null && tick.fluidData != ContainerFluidData.EMPTY) {
    tick.fluidData.getFlows().clear();
}
```

**效果**：`LivingWaterWheelFunction.tickContainerData()` 计算时看到空水流 → 产出零应力 → `CreateIntegration.updateStressOutput()` 注入 `rpm=0` → 齿轮应力正确清零，Tooltip 显示无应力。

**涉及文件**：`ContainerLivingItemHandler.java`

### 9.21 `deferredSync` 延迟同步导致客户端空状态（齿轮不转 + 应力网络无应力）

**现象**：重构后容器下方齿轮不转，但连接的其它齿轮会旋转（无应力、只有动画）。应力网络计算显示无应力，但齿轮动画正常。

**根因**：重构引入 `needsSync` 标记 + `deferredSync` TAIL 注入模式，将 `sendData()` 从 `applyStress()` 中延迟到 `tick()` TAIL 执行。但 `tick()` HEAD 先执行了自过期清理逻辑：

```
tick() HEAD → stressState.tick()
  ├── 若 refreshedThisTick = false（刚被 applyStress 设为 true，但 tick 在 applyStress 之后？）
  │   └── 清理：detachKinetics + setSpeed(0) + setNetwork(null) + rpm=0
  └── 重置 refreshedThisTick = false

tick() TAIL → deferredSync()
  └── sendData() → 发送的是被 HEAD 清理后的空状态（speed=0, network=null）
```

**时序问题**：`applyStress()` 在 `ServerTickEvent.Pre` 中通过 `ContainerLivingItemHandler.processContainer()` 调用，设置 `needsSync = true`。方块实体 `tick()` 也在同一 tick 执行。`tick()` HEAD 先执行清理逻辑，由于 `refreshedThisTick` 已在 `applyStress` 中设为 true，清理不会触发。但 `needsSync` 被设为 true，TAIL 的 `sendData()` 发送的是**当前状态**。

然而，在 `ServerTickEvent.Post` 模式下，`applyStress()` 在 `tick()` 之后执行，`tick()` HEAD 发现 `refreshedThisTick = false`（上一 tick 重置的），触发清理，然后 TAIL 的 `deferredSync` 发送空状态。紧接着 `applyStress()` 在 `Post` 中设置 `needsSync = true`，但此时 `tick()` 已结束，`deferredSync` 不会再被调用，`needsSync` 永远得不到处理。

**修复**：改为在 `applyStress()` 中直接调用 `self.sendData()`，移除 `deferredSync` 延迟模式：

```java
// 修复前
self.setChanged();
needsSync = true;    // 延迟到 tick() TAIL → 时序问题

// 修复后
self.setChanged();
self.sendData();     // 即时同步 → 状态正确
```

**涉及文件**：`StressStateMachine.java`（移除 `needsSync` 字段和 `deferredSync` 方法）、`KineticBlockEntityMixin.java`（移除 TAIL 注入）

---

## 附录：Tick 时序

```
ServerTickEvent.Pre → LivingItem.onServerTick()
  │
  ├─ processContainer(player.getInventory()) → 处理玩家背包
  │
  └─ processLevelContainers(level) → 处理所有世界中容器
       │
       └─ ContainerLivingItemHandler.processContext()
            │
            ├─ 1. 扫描容器，按功能 ID 分组活物品 → grouped
            │
            ├─ 2. 创建 TickContext（含 fluidData 缓存、stressData 容器）
            │
            ├─ 3. 调用各功能的 tick()（按扫描顺序）
            │     ├─ LivingWaterBucketFunction.tick() → 注册/移除水源
            │     ├─ LivingWaterWheelFunction.tick() → （空操作）
            │     └─ 其他功能...
            │
            ├─ 3.5 若无活水桶条目，清除水流数据
            │     → 防止活水桶移除后水流数据残留
            │
            ├─ 4. 按优先级调用 tickContainerData()（HasContainerData 接口）
            │     ├─ [优先级 0] LivingWaterBucketFunction
            │     │     ├─ ContainerFluidData.tick()
            │     │     │     ├─ recalculate() → BFS 水流蔓延
            │     │     │     └─ pushItems() → 沿水流推动物品
            │     │     └─ postTickSync() → 同步水流到水桶物品
            │     │
            │     └─ [优先级 1] LivingWaterWheelFunction
            │           ├─ ContainerStressData.calculate() → 计算每个水车的力矩
            │           └─ postTickSync() → 同步应力到水车物品
            │
            ├─ 5. 写入 BlockEntity 应力数据（Attachment 持久化）
            │
            ├─ 6. StressOutputManager.apply() → 容器底部/玩家脚底输出应力
            │     ├─ CreateCompat.isLoaded() → 检测 Create
            │     ├─ 找到下方 BE → instanceOf LivingItemStressOutput
            │     ├─ 白名单过滤 → SimpleKineticBlockEntity / BracketedKineticBlockEntity
            │     ├─ 方向兼容性检查
            │     ├─ RPM = -sign(netStress) × 8
            │     ├─ SU = |netStress| × 32
            │     └─ stressOutput.livingItem$applyStress(rpm, suCapacity)
            │           └─ StressStateMachine.applyStress()
            │                 ├─ setSpeed / setNetwork / attachKinetics
            │                 ├─ updateNetwork → updateCapacityFor + updateStressFor + updateStress
            │                 └─ self.sendData() → 即时同步客户端
            │
            └─ 7. 清理 + 释放 TickContext

方块实体 tick()
  │
  └─ KineticBlockEntityMixin.livingItem$checkExpiry() (HEAD)
       └─ StressStateMachine.tick()
            ├─ pendingReattach → attachKinetics + sendData
            ├─ rpm=0 → 跳过
            └─ !refreshedThisTick → 自过期清理 + sendData

> **关键时序**：`ServerTickEvent.Pre` 确保容器处理在方块实体 tick 之前执行，
> `applyStress()` 设置的状态在 `tick()` HEAD 中不会被误清理（因为 `refreshedThisTick` 已被设为 true）。
```

---

## 附录：验证清单

- [x] 活水车阻挡水流
- [x] 活水车周围水流产生力矩
- [x] CW/CCW 应力正确计算
- [x] 多水车应力叠加
- [x] 反向应力抵消
- [x] 堆叠放大应力
- [x] Tooltip 显示应力值
- [x] 中英文翻译
- [x] BlockEntity 存储应力数据
- [x] Create 集成：容器底部输出旋转
- [x] Create 集成：RPM/SU 换算
- [x] Create 集成：传动杆/齿轮接收旋转
- [x] Create 集成：白名单过滤（仅简单传动组件）
- [x] Create 集成：自过期机制（应力源消失后1 tick清理）
- [x] Create 集成：客户端同步（sendData 确保渲染状态正确）
- [x] Create 集成：应力输出方向与物品栏旋转一致
- [x] Create 集成：方向兼容性检查（软侵入，方向冲突不注入）
- [x] Create 集成：取消应力后齿轮恢复原发电机旋转（attachKinetics 重连）
- [x] 玩家脚底应力传递（背包中活水车驱动脚下齿轮）
- [x] 容器破坏后应力自动消失
- [x] 物品栏 3D 渲染：活水车以方块形式显示
- [x] 物品栏 3D 渲染：正面朝向屏幕（跳过等轴测变换）
- [x] 物品栏 3D 渲染：有应力时绕 Z 轴旋转
- [x] 物品栏 3D 渲染：旋转方向与应力方向一致
- [x] 物品栏 3D 渲染：漫反射光照正常（正前方光源）
- [x] 物品栏 3D 渲染：无应力时静止显示
- [x] 物品栏 3D 渲染：非活物品水车不受影响
- [x] 物品栏 3D 渲染：渲染后光照正确恢复
- [x] 软依赖：无 Create 时活物品模组正常运行
- [x] 活水桶移除后水流数据立即清除（无虚假应力）
- [x] 水流数据残留修复：无活水桶时自动清除 CONTAINER_DATA 中的 fluid 字段
- [x] 区块卸载后应力翻倍修复（onChunkUnloaded 注入）
- [x] 区块卸载后变速结构爆炸修复（网络清理顺序修正）
- [x] 自过期/取消应力时 `sources.remove()` 正确执行（isSource 返回 true）
- [x] 重构：StressStateMachine 提取状态管理逻辑（可测试纯 Java 类）
- [x] 重构：StressOutputManager 合并 ModCreate + CreateIntegration
- [x] 重构：livingItem$applyStress 统一入口替代 setGeneratedRPM/setStressCapacity 分步调用
- [x] 重构：getGeneratedSpeed() 纯 getter（移除副作用）
- [x] 修复：deferredSync 延迟同步导致客户端空状态（改为即时 sendData()）
- [x] 修复：ServerTickEvent.Pre 确保容器处理在方块实体 tick 之前执行
- [x] 修复：移除 applyStress 中多余的 isRemoved() 检查

---

### 9.22 抗卸载韧性审查：对比 Create 原版动力源机制

> **注意**：以下分析基于对 Create 6.0.10 源码的逆向阅读理解，可能存在疏漏或误读。结论仅供参考，不应被视为绝对无误的判定。

**背景**：活水车和活水桶在早期版本中存在"不抗卸载"的问题——区块卸载后重进，活水车莫名其妙不转。经过多轮修复（`onChunkUnloaded` 注入、`StressStateMachine` 自过期、`ServerTickEvent.Pre` 时序调整），当前代码已基本稳定。但仍有必要从 Create 原版动力源的机制出发，审视我们的修复是否必要、是否过度、以及是否还有潜在风险。

#### 9.22.1 Create 原版动力源的生命周期

**区块卸载时**：Create 原版**不主动清理**动力源。关键代码路径：

```
SmartBlockEntity.onChunkUnloaded()
  → chunkUnloaded = true       // 标记为"区块卸载"

SmartBlockEntity.setRemoved()
  → if (!chunkUnloaded) remove()   // chunkUnloaded=true → 跳过 remove()!
  → invalidate()                    // 仅卸载 behavior，不清理网络
```

所有状态（`speed`、`stress`、`capacity`、`network` ID、`source`）通过 NBT 序列化保存到磁盘。Create 的设计哲学是：**卸载时保留一切，重载时从 NBT 恢复**。

**区块重载时**：通过 `initialize()` 静默恢复：

```
KineticBlockEntity.read()          // 从 NBT 恢复 speed/stress/capacity/network/source
KineticBlockEntity.initialize()    // 区块加载完成后同步调用
  → getOrCreateNetwork()           // 重建网络（同一个 ID）
  → network.initFromTE(...)        // 恢复网络容量/应力状态
  → network.addSilently(...)       // 静默加回网络（不触发重算）
KineticBlockEntity.tick()          // 第一个 tick
  → attachKinetics()               // 重新连接邻居
  → validateKinetics()             // 验证源 BE 仍然存在
```

对于 `GeneratingKineticBlockEntity`（水车、风车等），还有 `reActivateSource` 标记，在 `tick()` 中调用 `updateGeneratedRotation()` 重新计算转速。

**动力源被破坏时**：**立即、彻底**清理：

```
KineticBlockEntity.remove()
  → getOrCreateNetwork().remove(this)   // 从 sources + members 中移除
  → detachKinetics()                     // 断开旋转传播
  → super.remove()

KineticNetwork.remove(KineticBlockEntity be)
  → sources.remove(be)                   // 从源列表移除
  → members.remove(be)                   // 从成员列表移除
  → be.updateFromNetwork(0, 0, 0)        // 清零 BE 的状态
  → 若 members 为空 → 注销整个网络
  → 否则 → 标记一个剩余成员 networkDirty = true → 触发网络重算
```

**网络自清理机制**：`KineticNetwork.calculateCapacity()` 和 `calculateStress()` 在遍历时自动跳过无效 BE：

```java
// 遍历 sources/members 时自动清理
if (be.getLevel().getBlockEntity(be.getBlockPos()) != be) {
    iterator.remove();  // BE 已不在世界中 → 自动移除
    continue;
}
```

#### 9.22.2 我们的架构与 Create 原版的根本差异

| 方面 | Create 原版动力源 | 活水车 |
|------|-----------------|--------|
| 转速来源 | 方块实体自身决定（`getGeneratedSpeed()` 读取世界状态） | 由容器中的物品计算得出，注入到下方方块实体 |
| 恢复时机 | `initialize()` 阶段即可恢复（区块加载时同步调用） | 依赖 `processLevelContainers()` 在 tick 事件中重新计算 |
| 网络归属 | 动力源方块实体**就是**网络成员 | 下方方块实体（轴/齿轮）是网络成员，但它本身不是自主动力源 |
| 状态存储 | NBT 序列化（`speed`/`stress`/`capacity`/`network`） | 容器 Attachment（`ContainerStressData`）+ 下方 BE 的 NBT |

**核心矛盾**：我们的"动力源逻辑"不在方块实体上，而在容器处理循环中。这使得区块重载后，下方方块实体无法像 Create 原版动力源那样在 `initialize()` 中自主恢复转速——它必须等容器处理循环跑完才知道自己的转速。

#### 9.22.3 现有修复的必要性分析

**修复 A：`onChunkUnloaded` 注入 → 主动清理网络**

```java
// StressStateMachine.onChunkUnloaded()
self.getOrCreateNetwork().remove(self);   // 从网络移除
self.detachKinetics();                     // 断开旋转传播
rpm = 0; capacity = 0;                     // 清零状态
```

**Create 原版做法**：卸载时不清理，靠 NBT 序列化保留。

**我们的必要性**：**可能仍然必要**。原因在于，如果不清零 `rpm`，我们的 Mixin 覆写的 `getGeneratedSpeed()` 会返回非零值，`isSource()` 返回 `true`。当区块重载后，Create 的 `initialize()` 会从 NBT 恢复 `speed` 字段（非零），而 `getGeneratedSpeed()` 也非零，`validateKinetics()` 不会清理这个 BE。但此时容器处理还没跑，这个 BE 的转速是**上一次的残留值**，不是当前容器内容的真实反映。

如果不清零，最坏情况：区块重载后，下方的轴/齿轮会以残留转速旋转 1 tick，然后容器处理重新计算应力并覆盖。这 1 tick 的残留转速可能导致：
- 应力网络瞬间出现不准确的应力值
- 如果残留转速方向与重新计算的方向不同，可能触发方向冲突检测

**但另一方面**，Create 原版在卸载时也不清理，重载后 `initialize()` 恢复的状态同样是"上一次的残留值"。Create 原版动力源依赖 `updateGeneratedRotation()` 在第一个 tick 中重新计算覆盖。我们的差异在于：我们的重新计算有 1 tick 延迟（见下文修复 B 分析）。

**结论**：`onChunkUnloaded` 清理可能是**防御性正确**的做法，但未必是唯一正确的做法。如果未来能将容器处理移到 `ServerTickEvent.Post` 并解决时序问题，或许可以模仿 Create 原版的"不清理"策略。

**修复 B：`ServerTickEvent.Pre` 中的容器处理**

当前时序：
```
ServerTickEvent.Pre → processLevelContainers() → chunk 不在缓存（尚未加载）
ServerLevel.tick() → 区块加载 → ChunkEvent.Load → chunk 加入缓存
KineticBlockEntity.tick() → rpm=0 → 空转

下一个 tick:
ServerTickEvent.Pre → processLevelContainers() → chunk 在缓存 → 处理 → 应力恢复
```

这导致了 **1 tick（50ms）的应力真空期**。Create 原版没有这个延迟，因为 `initialize()` 在区块加载过程中同步调用。

**可能无法消除**：要将容器处理移到 `initialize()` 阶段，需要容器数据（物品栏内容）在 `initialize()` 时就可用。但 `ContainerChunkCache` 依赖 `ServerLevel` 的能力系统查询 `IItemHandler`，这在区块加载的早期阶段可能不可用。此外，水流 BFS 计算需要完整的物品栏状态，而物品栏可能在 `initialize()` 之后才完全就绪。

**修复 C：`removeDataByPos()` 中的应力清零**

当前行为：容器被破坏时，`removeDataByPos()` 清理缓存，但下方 BE 的应力靠 `StressStateMachine` 自过期（1 tick 后）清理。

**可能的改进**：在 `removeDataByPos()` 中显式调用 `StressOutputManager.apply(level, pos, ContainerStressData.EMPTY)`。

**必要性存疑**：Create 原版在动力源被破坏时确实立即清理（`remove()` → `network.remove(this)`），但我们的"动力源"（容器）和"网络成员"（下方 BE）是分离的。下方 BE 仍然存在，只是不再接收应力。`StressStateMachine` 的自过期在 1 tick 内就能处理，且 `KineticNetwork` 的自动清理机制也能兜底。1 tick 的幽灵应力不太可能造成实际影响。

#### 9.22.4 仍可能存在的潜在风险

以下风险点是基于代码分析推测的，**未经实际测试验证**，可能根本不会触发：

1. **1 tick 真空期 + 网络重组**：区块重载后第 1 tick，下方 BE 的 rpm=0，`getGeneratedSpeed()` 返回 0。如果该 BE 恰好是一个网络中的关键节点（如齿轮箱），其 rpm 归零可能导致 Create 的 `RotationPropagator` 触发网络重组。当下一个 tick 应力恢复时，网络需要重新建立连接。这个过程中，如果网络中有变速结构（大小齿轮、变速器），方向变化可能导致方块销毁。不过，当前代码中 `StressStateMachine.applyStress()` 在 rpm=0 时不会调用 `attachKinetics()`，所以网络重组应该不会发生。

2. **`getChunkNow()` 在异步加载中返回 null**：`processLevelContainers` 使用 `getChunkNow()` 检查区块是否加载。如果区块正在异步加载中，`getChunkNow()` 可能返回 null，导致区块被从缓存中移除。虽然 `ChunkEvent.Load` 会在同 tick 内重新加回，但如果 `ChunkEvent.Load` 在更早的 tick 已经触发过（区块"逻辑上已加载"但数据还在异步传输），则没有事件能重新加回。不过，`ChunkEvent.Load` 是在区块**完全加载完成后**才触发的，此时 `getChunkNow()` 应该返回非 null，所以这个场景在实际中几乎不可能发生。

3. **容器被破坏后下方 BE 的 `isSource()` 状态**：当容器被破坏时，下方 BE 的 `getGeneratedSpeed()` 仍返回旧的 rpm（因为 `StressStateMachine` 还没自过期），`isSource()` 返回 true。如果恰好在这一 tick 内网络重算（因为另一个成员触发了 `networkDirty`），下方 BE 会被当作源计入应力计算。但 `refreshedThisTick` 为 false，自过期将在同一 tick 触发。除非网络重算发生在自过期之前，且重算结果被用于某个关键判定（如超载检测），否则不会有实际影响。

4. **跨区块网络的一致性**：如果活水车驱动了一个跨越多个区块的 Create 网络，且只有容器的区块被卸载/重载，其他区块中的网络成员可能在 1 tick 真空期内看到应力消失。Create 的 `validateKinetics()` 会周期性地清理失去源的 BE（`removeSource()` + `detachKinetics()`），但验证频率由配置控制（`kineticValidationFrequency`，默认可能较高），可能在 1 tick 内不会触发。

#### 9.22.5 总结

**当前代码的抗卸载能力评估**：经过多轮修复，抗卸载机制已相当健壮。`onChunkUnloaded` 清理 + `StressStateMachine` 自过期 + NBT 持久化形成了多层防护。1 tick 的应力真空期是架构固有的限制，50ms 的延迟肉眼不可见，不太可能造成实际游戏体验问题。

**与 Create 原版的差异**：我们的架构决定了我们无法完全模仿 Create 原版的"不清理"策略。Create 原版动力源的转速由方块实体自身决定，可以在 `initialize()` 中恢复；而我们的转速依赖容器处理循环，必须等待 tick 事件。

**建议**：当前代码已达到合理的安全水平，不建议进行大规模重构。如果未来出现新的抗卸载问题，优先排查方向应为：
1. 区块重载后 `processLevelContainers` 是否确实在运行（检查缓存状态）
2. `StressOutputManager.apply()` 是否被正确调用（检查应力数据是否非空）
3. 下方 BE 的 `initialize()` 是否恢复了旧的转速（检查 NBT 序列化内容）

### 9.23 齿轮旋转但无应力输出 + 应力消失后邻居齿轮空转

**现象**：
1. 活水车产生净应力输出到容器下方的齿轮后，齿轮只会旋转，但没有实际应力输出（邻居齿轮不转或转但无应力）。
2. 容器内没有净应力时，容器下方的齿轮会停止转动，但齿轮旁的其它齿轮依旧在转动，没有实际应力输出。

**根因**：两个问题同源——`KineticBlockEntityMixin` 缺少对 `calculateStressApplied()` 的 Mixin 注入，以及 `applyStress(rpm=0)` 清理路径未正确从网络中移除 BE。

#### 9.23.1 缺少 `calculateStressApplied()` Mixin

Create 的 `KineticNetwork` 维护两个映射：
- **`sources`**：应力源（`isSource() == true`），提供 SU 容量
- **`members`**：所有 BE，贡献应力消耗（stress impact）

当活水车注入应力使齿轮成为 source 时：
- `getGeneratedSpeed()` 被 Mixin 覆盖 → 返回注入的 RPM ✅ → `isSource() == true` ✅
- `calculateAddedStressCapacity()` 被 Mixin 覆盖 → 返回注入的 SU 容量 ✅
- **`calculateStressApplied()` 没有被覆盖** → 返回齿轮自身的 impact（通常为 0）❌

`StressStateMachine.updateNetwork()` 调用：
```java
self.getOrCreateNetwork().updateStressFor(self, self.calculateStressApplied());
```

齿轮的 `calculateStressApplied()` 返回 `BlockStressValues.getImpact()` = 0（齿轮不是应力消耗者），所以 `members` 中齿轮的应力消耗为 0。邻居齿轮通过 `RotationPropagator` 获得了转速（视觉旋转），但网络中 source 齿轮的 `calculateStressApplied()` 返回 0，导致 `calculateStress()` 算出的总消耗为 0，网络认为"无应力消耗"，不触发 `overStressed` 检查，也不传播应力信息给邻居。

**本质**：活水车注入的齿轮应该是一个**纯应力源**（只提供容量，不消耗应力），但 `calculateStressApplied()` 返回了原始值而非 0，导致网络应力计算异常。

**修复**：在 `KineticBlockEntityMixin` 中新增 `calculateStressApplied()` 注入：

```java
@Inject(method = "calculateStressApplied", at = @At("HEAD"), cancellable = true, remap = false)
private void livingItem$calculateStressApplied(CallbackInfoReturnable<Float> cir) {
    if (stressState.isActive()) {
        cir.setReturnValue(0f);  // 活水车注入的 BE 是纯应力源，不消耗应力
    }
}
```

#### 9.23.2 `applyStress(rpm=0)` 清理路径未从网络移除

当 `applyStress(self, 0, 0)` 被调用时（应力消失），原代码走 `prev != 0 && newRpm == 0` 分支：

```java
// 修复前
self.detachKinetics();
self.setSpeed(0);
self.setNetwork(null);   // ← setNetwork(null) 内部调 network.remove(this)
pendingReattach = true;   // ← 下一 tick 重新 attachKinetics！
```

两个问题：
1. **`pendingReattach = true`**：下一 tick `StressStateMachine.tick()` 会执行 `self.attachKinetics()`，将速度为 0 的 BE 重新加入动力学网络，可能触发邻居重新寻找 source
2. **`setNetwork(null)` 在 `detachKinetics()` 之后**：`detachKinetics()` → `RotationPropagator.handleRemoved()` 会通知邻居"源已移除"，但此时 BE 还在网络中（`setNetwork(null)` 尚未执行），邻居的 `propagateMissingSource()` 可能产生不一致状态

**修复**：在 `detachKinetics()` 之前先从网络中移除，且不再设置 `pendingReattach`：

```java
// 修复后
if (self.hasNetwork()) {
    self.getOrCreateNetwork().remove(self);  // 先从网络移除
}
self.detachKinetics();                      // 再通知邻居
self.setSpeed(0);
self.setNetwork(null);                      // 清除网络引用
// 不再设置 pendingReattach
```

同样修复 `tick()` 中的自过期清理路径（`!refreshedThisTick` 分支），在 `detachKinetics()` 之前先从网络移除，并移除 `pendingReattach`。

**涉及文件**：`KineticBlockEntityMixin.java`（新增 `calculateStressApplied` 注入）、`StressStateMachine.java`（修复清理路径）

### 9.24 Mixin 目标方法不存在导致整个 Mixin 类失效——齿轮旋转但无应力输出

**现象**：
齿轮被活水车注入应力后，只有旋转动画，但护目镜不显示应力信息，邻居齿轮也不受应力驱动。日志中 `updateNetwork BEFORE` 显示 `genSpeed=0.0, isSource=false, calcCap=0.0`，即使 `StressStateMachine` 内部 `rpm=-8.0, capacity=2944.0` 已正确设置。

**根因**：`KineticBlockEntityMixin` 中注入了 `onChunkUnloaded` 方法，但该方法**不在 `KineticBlockEntity` 中**，而在其父类 `SmartBlockEntity` 中。Mixin 框架在类加载时验证所有 `@Inject` 目标，找不到 `onChunkUnloaded` 就抛出 `InvalidInjectionException`，**拒绝应用整个 Mixin 类**。

```
Mixin apply for mod living_item failed: @Inject annotation on livingItem$onChunkUnloaded 
could not find any targets matching 'onChunkUnloaded' in KineticBlockEntity.
```

这导致 `KineticBlockEntityMixin` 中的**所有注入全部失效**：
- `getGeneratedSpeed()` → 始终返回 0 → `isSource() == false`
- `calculateAddedStressCapacity()` → 返回齿轮默认值 0 → 网络无应力容量
- `calculateStressApplied()` → 未被覆盖 → 网络应力计算异常
- `tick()` → 自过期机制失效 → 应力永不清除

齿轮的 `setSpeed()` 仍被 `StressStateMachine.applyStress()` 调用，所以齿轮有旋转动画（`speed` 字段被设置），但 `getGeneratedSpeed()` 返回 0，所以 `KineticNetwork` 不认为它是应力源，`sources` 映射为空，网络容量为 0。

#### 9.24.1 为什么难以发现

1. **Mixin 失败日志被淹没**：Mixin 框架在启动时打印了 `WARN` 级别的失败日志，但在大量 mod 加载的日志中容易被忽略
2. **部分功能仍正常**：`StressStateMachine` 是独立的 Java 对象（非 Mixin 注入），其 `applyStress()` 仍能调用 `setSpeed()`/`setNetwork()`/`attachKinetics()`，所以齿轮有旋转动画，看起来"部分工作"
3. **日志误导**：`StressStateMachine` 的日志显示 `rpm=-8.0, capacity=2944.0`，看起来值正确，但实际上 `self.getGeneratedSpeed()` 和 `self.isSource()` 调用的是**原始方法**（Mixin 未生效），返回 0/false

#### 9.24.2 修复方案

**核心修复**：将 `onChunkUnloaded` 注入从 `KineticBlockEntityMixin` 移出，新建 `SmartBlockEntityMixin` 注入到父类 `SmartBlockEntity`：

```java
// SmartBlockEntityMixin.java — 新建
@Mixin(SmartBlockEntity.class)
public abstract class SmartBlockEntityMixin {
    @Inject(method = "onChunkUnloaded", at = @At("HEAD"), remap = false)
    private void livingItem$onChunkUnloaded(CallbackInfo ci) {
        if ((Object) this instanceof KineticBlockEntity kbe) {
            if (kbe instanceof LivingItemStressOutput stressOutput) {
                stressOutput.livingItem$onChunkUnloaded();
            }
        }
    }
}
```

在 `living_item.mixins-create.json` 中注册新 Mixin：
```json
"mixins": [
    "KineticBlockEntityMixin",
    "SmartBlockEntityMixin"
]
```

在 `LivingItemStressOutput` 接口中新增方法：
```java
void livingItem$onChunkUnloaded();
```

在 `KineticBlockEntityMixin` 中实现该方法，委托给 `StressStateMachine.onChunkUnloaded()`。

**附带修复**（在排查过程中发现并修复）：

1. **`capacity` 赋值时序错误**：`applyStress()` 中 `capacity = newCap` 在 `setNetwork()` 之后赋值，但 `setNetwork()` 内部调用 `network.add(this)` → `calculateAddedStressCapacity()`，此时 Mixin 返回 `stressState.getCapacity()` 还是 0。修复：将 `capacity = newCap` 移到 `setNetwork()` 之前。

2. **`lastCapacityProvided` / `lastStressApplied` 未被设置**：Mixin 在 `@Inject HEAD` + `cancellable` 取消了原始方法，导致 `KineticBlockEntity` 的这两个字段不会被赋值。它们在 `initialize()` → `network.initFromTE()` 和 NBT 序列化中使用。修复：添加 `@Shadow` 字段并在 Mixin 中显式赋值。

3. **`isSource()` 注入缺失**：原代码依赖 `getGeneratedSpeed() != 0` 判断 `isSource()`，但单独注入 `isSource()` 更可靠，避免 `getGeneratedSpeed()` 注入失效时连带 `isSource()` 也失效。

**涉及文件**：
- `KineticBlockEntityMixin.java`（移除 `onChunkUnloaded` 注入，添加 `@Shadow`、`isSource` 注入、`livingItem$onChunkUnloaded` 实现）
- `SmartBlockEntityMixin.java`（新建，注入 `SmartBlockEntity.onChunkUnloaded`）
- `LivingItemStressOutput.java`（新增 `livingItem$onChunkUnloaded()` 接口方法）
- `living_item.mixins-create.json`（注册 `SmartBlockEntityMixin`）
- `StressStateMachine.java`（修复 `capacity` 赋值时序）
- `StressOutputManager.java`（修正 RPM 符号 `-Math.signum`，日志降级为 `debug`）

**教训**：
1. **Mixin 目标方法必须存在于目标类中**（包括父类方法也不行，除非用 `remap` 或目标父类）。如果方法在父类中，应新建 Mixin 注入父类，或在子类 Mixin 中用 `@Inject(method = "onChunkUnloaded", at = @At("HEAD"))` + 确保子类有 `@Override` 声明
2. **Mixin 类中任何一个注入失败都会导致整个类失效**——这是最危险的特性，因为其他看似无关的注入也会被连带取消
3. **启动时检查 Mixin 应用日志**：搜索 `Mixin apply.*failed` 或 `InvalidInjectionException`，不要只关注运行时日志
4. **`@Shadow` 字段必须显式赋值**：当 Mixin 用 `cancellable = true` 取消原始方法时，原始方法中的字段赋值（如 `this.lastCapacityProvided = capacity`）不会执行，必须在 Mixin 中手动赋值