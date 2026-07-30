# Living Water Wheel (活水车) 技术文档

> **文档版本**: 2026.07 v6  
> **最后更新**: 2026-07-30  
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
| `LivingWaterWheelFunction` | `function/LivingWaterWheelFunction.java` | 活水车功能入口，管理应力同步和 Tooltip |
| `WaterWheelData` | `data/WaterWheelData.java` | 水车应力 record：cwStress/ccwStress/netStress |
| `LivingWaterWheelData` | `data/LivingWaterWheelData.java` | 活水车数据容器：包含 WaterWheelData |
| `ContainerStressData` | `container/ContainerStressData.java` | 容器级应力累加器，遍历所有活水车计算力矩 |
| `StressDataProvider` | `container/StressDataProvider.java` | 接口，定义 BlockEntity 的应力读写方法 |
| `BlockEntityMixin` | `mixin/BlockEntityMixin.java` | Mixin 到 BlockEntity，添加 stressData 字段 |

#### Create 兼容类（软依赖，无 Create 时不加载）

| 类名 | 文件位置 | 职责 |
|------|---------|------|
| `CreateCompat` | `create/CreateCompat.java` | 检测 Create 是否安装（`ModList.get().isLoaded`） |
| `ModCreate` | `create/ModCreate.java` | Create 集成入口，常量定义，安全调用 CreateIntegration |
| `CreateIntegration` | `create/CreateIntegration.java` | 应力输出逻辑：找到容器下方/玩家脚底 BE 并设置 RPM，含白名单过滤 |
| `LivingItemStressOutput` | `create/LivingItemStressOutput.java` | 接口，定义 Mixin 注入的方法签名 |
| `CreateMixinPlugin` | `create/CreateMixinPlugin.java` | Mixin 条件加载插件，仅 Create 安装时应用 Mixin |
| `KineticBlockEntityMixin` | `mixin/create/KineticBlockEntityMixin.java` | Mixin 到 KineticBlockEntity，实现应力输出、自过期机制、白名单过滤 |

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

容器 BlockEntity 也通过 Mixin 持有应力数据：

```
BlockEntity (via BlockEntityMixin)
└── livingItem$stressData: ContainerStressData   ← 容器级应力
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

通过 Mixin 给所有 `BlockEntity` 添加 `livingItem$stressData` 字段：

```java
@Mixin(BlockEntity.class)
public class BlockEntityMixin implements StressDataProvider {
    @Unique
    private ContainerStressData livingItem$stressData = ContainerStressData.EMPTY;

    @Override
    public ContainerStressData livingItem$getStressData() {
        return livingItem$stressData;
    }

    @Override
    public void livingItem$setStressData(ContainerStressData data) {
        this.livingItem$stressData = data;
    }
}
```

每个容器 tick 结束后，`ContainerLivingItemHandler.processContext()` 将应力数据写入关联的 BlockEntity：

```java
if (stressData != null && context instanceof SimpleContainerContext simpleCtx) {
    for (BlockEntity be : simpleCtx.getAssociatedBlockEntities()) {
        if (be instanceof StressDataProvider provider) {
            provider.livingItem$setStressData(stressData);
        }
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
        if (be instanceof StressDataProvider provider) {
            provider.livingItem$setStressData(stressData);
        }
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
│  ModCreate.updateStressOutput()                             │
│       │                                                     │
│       ├── CreateCompat.isLoaded() == false → 直接返回        │
│       │                                                     │
│       └── CreateCompat.isLoaded() == true                   │
│              │                                              │
│              └── CreateIntegration.updateStressOutput()     │
│                     │                                       │
│                     └── 检查容器下方 BE                      │
│                            │                                │
│                            ├── instanceof LivingItemStressOutput │
│                            │                                │
│                            ├── isSafeKineticBE() 白名单      │
│                            │                                │
│                            ├── isDirectionCompatible() 方向  │
│                            │                                │
│                            └── 设置 RPM                      │
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
│                    ├─ livingItem$generatedRPM 字段           │
│                    ├─ livingItem$stressCapacity 字段         │
│                    ├─ livingItem$refreshedThisTick 字段      │
│                    ├─ getGeneratedSpeed() 注入（含自过期检测） │
│                    ├─ tick() 注入（自过期清理 + 客户端同步）   │
│                    ├─ calculateAddedStressCapacity() 注入    │
│                    ├─ livingItem$setGeneratedRPM() 方法（含白名单 + 网络更新）│
│                    ├─ livingItem$setStressCapacity() 方法（含网络更新）│
│                    └─ isSafeKineticBE() 白名单过滤           │
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

#### ModCreate — 集成入口与安全防护

```java
public class ModCreate {
    public static final float BASE_RPM = 8.0f;            // 基础转速，与原版小水车一致
    public static final float BASE_SU_CAPACITY = 32.0f;   // 基础 SU 容量，与原版小水车一致

    public static void updateStressOutput(Level level, BlockPos containerPos,
                                           ContainerStressData stressData) {
        if (!CreateCompat.isLoaded()) return;               // 无 Create 直接返回
        if (!isIntegrationAvailable()) return;               // CreateIntegration 不可用则返回
        try {
            CreateIntegration.updateStressOutput(level, containerPos, stressData);
        } catch (NoClassDefFoundError e) {                  // 防止类加载失败崩溃
            integrationAvailable = false;
            integrationChecked = false;
        }
    }
}
```

**双重防护**：
1. `CreateCompat.isLoaded()` — 检测 Create 是否安装
2. `try-catch(NoClassDefFoundError)` — 防止运行时类加载失败导致崩溃

#### CreateIntegration — 应力输出逻辑

```java
public class CreateIntegration {
    static void updateStressOutput(Level level, BlockPos containerPos,
                                    ContainerStressData stressData) {
        if (level == null || level.isClientSide) return;

        BlockPos belowPos = containerPos.below();
        BlockEntity be = level.getBlockEntity(belowPos);

        if (!(be instanceof LivingItemStressOutput stressOutput)) return;

        if (!isSafeKineticBE(be)) {
            LOGGER.debug("[StressOutput] BE type {} is not in safe whitelist, skipping",
                be.getClass().getSimpleName());
            return;
        }

        float rpm = 0;
        float suCapacity = 0;
        if (stressData != null && !stressData.isEmpty()) {
            int netStress = stressData.getNetStress();
            rpm = -Math.signum(netStress) * ModCreate.BASE_RPM;       // 负号修正旋转方向
            suCapacity = Math.abs(netStress) * ModCreate.BASE_SU_CAPACITY;  // 堆叠增加 SU
        }

        if (rpm != 0 && !isDirectionCompatible(be, rpm)) {
            LOGGER.debug("[StressOutput] Direction incompatible on {} at {}, skipping injection",
                be.getClass().getSimpleName(), belowPos);
            stressOutput.livingItem$setGeneratedRPM(0);
            stressOutput.livingItem$setStressCapacity(0);
            return;
        }

        stressOutput.livingItem$setGeneratedRPM(rpm);
        stressOutput.livingItem$setStressCapacity(suCapacity);
    }

    private static boolean isDirectionCompatible(BlockEntity be, float injectedRPM) {
        if (!(be instanceof KineticBlockEntity kbe)) return true;
        float existingSpeed = kbe.getTheoreticalSpeed();
        if (existingSpeed == 0) return true;
        return Math.signum(injectedRPM) == Math.signum(existingSpeed);
    }

    private static boolean isSafeKineticBE(BlockEntity be) {
        String className = be.getClass().getName();
        if (className.startsWith("com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity")) {
            return true;
        }
        if (className.startsWith("com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntity")) {
            return true;
        }
        return false;
    }
}
```

**关键设计**：
- **RPM 固定为 ±8**：方向由净应力正负决定（负号修正旋转方向，使物品栏旋转与下方齿轮旋转一致），大小与原版小水车一致。堆叠/多水流不改变转速
- **SU 容量按应力缩放**：`|netStress| × 32`，堆叠越多 SU 越大，能驱动更多设备
- **白名单过滤**：仅允许 `SimpleKineticBlockEntity`（传动杆、齿轮）和 `BracketedKineticBlockEntity`（支架齿轮）接收应力，复杂组件一律跳过
- **方向兼容性检查**：注入前检查目标 BE 已有旋转方向，方向相反则跳过注入并清零残留 RPM，防止 `RotationPropagator` 销毁方块
- `CreateIntegration` **不直接 import 任何 Create 的类**，通过 `instanceof LivingItemStressOutput` 接口检查来操作下方方块实体

#### LivingItemStressOutput — 接口注入模式

```java
public interface LivingItemStressOutput {
    void livingItem$setGeneratedRPM(float rpm);
    float livingItem$getGeneratedRPM();
    void livingItem$setStressCapacity(float capacity);
    float livingItem$getStressCapacity();
}
```

这是**接口注入模式**的核心。Mixin 让 `KineticBlockEntity` 实现此接口，运行时代码通过 `instanceof` 检测和接口方法调用操作 Mixin 注入的功能，无需直接引用 Mixin 生成的类。

- `setGeneratedRPM` / `getGeneratedRPM` — 控制转速（RPM），方向由正负决定，大小固定为 8
- `setStressCapacity` / `getStressCapacity` — 控制应力容量（SU），堆叠/多水流时线性增长

#### KineticBlockEntityMixin — 核心 Mixin

```java
@Mixin(KineticBlockEntity.class)
public abstract class KineticBlockEntityMixin implements LivingItemStressOutput {
    private float livingItem$generatedRPM = 0;
    private float livingItem$stressCapacity = 0;
    private boolean livingItem$refreshedThisTick = false;   // 自过期标记

    @Inject(method = "getGeneratedSpeed", at = @At("HEAD"), cancellable = true, remap = false)
    private void livingItem$getGeneratedSpeed(CallbackInfoReturnable<Float> cir) {
        if (livingItem$generatedRPM != 0) {
            if (!livingItem$refreshedThisTick) {             // 应力源已消失
                livingItem$generatedRPM = 0;
                livingItem$stressCapacity = 0;
                return;
            }
            cir.setReturnValue(livingItem$generatedRPM);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void livingItem$checkExpiry(CallbackInfo ci) {
        if (livingItem$generatedRPM == 0) {
            livingItem$refreshedThisTick = false;
            return;
        }

        KineticBlockEntity self = (KineticBlockEntity) (Object) this;
        if (self.getLevel() == null || self.getLevel().isClientSide) return;

        if (!livingItem$refreshedThisTick) {                 // 应力源已消失，立即清理
            livingItem$generatedRPM = 0;
            livingItem$stressCapacity = 0;

            try {
                if (self instanceof GeneratingKineticBlockEntity gen) {
                    gen.updateGeneratedRotation();
                } else {
                    self.detachKinetics();
                    self.setSpeed(0);
                    self.setNetwork(null);
                    self.attachKinetics();                  // 重连邻居网络
                    self.setChanged();
                    self.sendData();                          // 同步客户端
                }
            } catch (Exception e) {
                LOGGER.warn("[LivingItem] Error during expiry cleanup on {} at {}",
                    self.getClass().getSimpleName(), self.getBlockPos(), e);
            }
        }

        livingItem$refreshedThisTick = false;                // 重置标记，下 tick 重新检测
    }

    @Inject(method = "calculateAddedStressCapacity", at = @At("HEAD"), cancellable = true, remap = false)
    private void livingItem$calculateAddedStressCapacity(CallbackInfoReturnable<Float> cir) {
        if (livingItem$generatedRPM != 0) {
            cir.setReturnValue(livingItem$stressCapacity);  // 动态返回 SU 容量
        }
    }

    @Override
    public void livingItem$setGeneratedRPM(float rpm) {
        KineticBlockEntity self = (KineticBlockEntity) (Object) this;
        if (!isSafeKineticBE(self)) {                        // 白名单过滤
            if (livingItem$generatedRPM != 0) {
                livingItem$generatedRPM = 0;
                livingItem$stressCapacity = 0;
            }
            return;
        }

        if (self.getLevel() != null && !self.getLevel().isClientSide) {
            livingItem$refreshedThisTick = true;             // 标记本 tick 有应力源
        }

        float prev = livingItem$generatedRPM;
        livingItem$generatedRPM = rpm;

        if (self.getLevel() == null || self.getLevel().isClientSide) return;
        if (Math.abs(prev - rpm) < 0.01f) return;           // 无变化跳过

        try {
            if (prev != 0 && rpm == 0) {
                self.detachKinetics();
                self.setSpeed(0);
                self.setNetwork(null);
                self.attachKinetics();                      // 重连邻居网络
            } else if (prev == 0 && rpm != 0) {
                self.setSpeed(rpm);
                self.setNetwork(self.getBlockPos().asLong());
                self.attachKinetics();
                if (self.hasNetwork()) {                     // 立即更新网络应力
                    self.getOrCreateNetwork().updateCapacityFor(self, livingItem$stressCapacity);
                    self.getOrCreateNetwork().updateStressFor(self, self.calculateStressApplied());
                    self.getOrCreateNetwork().updateStress();
                }
            } else {
                self.detachKinetics();
                self.setSpeed(rpm);
                self.attachKinetics();
                if (self.hasNetwork()) {
                    self.getOrCreateNetwork().updateCapacityFor(self, livingItem$stressCapacity);
                    self.getOrCreateNetwork().updateStressFor(self, self.calculateStressApplied());
                    self.getOrCreateNetwork().updateStress();
                }
            }
            self.setChanged();
            self.sendData();                                 // 同步客户端
        } catch (NullPointerException e) {
            LOGGER.warn("[LivingItem] Failed to set RPM on {} at {}: {}",
                self.getClass().getSimpleName(), self.getBlockPos(), e.getMessage());
            livingItem$generatedRPM = 0;
        } catch (Exception e) {
            LOGGER.warn("[LivingItem] Unexpected error setting RPM on {} at {}",
                self.getClass().getSimpleName(), self.getBlockPos(), e);
            livingItem$generatedRPM = 0;
        }
    }

    @Override
    public void livingItem$setStressCapacity(float capacity) {
        float prev = livingItem$stressCapacity;
        livingItem$stressCapacity = capacity;

        KineticBlockEntity self = (KineticBlockEntity) (Object) this;
        if (self.getLevel() == null || self.getLevel().isClientSide) return;
        if (Math.abs(prev - capacity) < 0.01f) return;
        if (livingItem$generatedRPM == 0) return;
        if (!self.hasNetwork()) return;

        try {
            self.getOrCreateNetwork().updateCapacityFor(self, capacity);
            self.getOrCreateNetwork().updateStressFor(self, self.calculateStressApplied());
            self.getOrCreateNetwork().updateStress();
        } catch (Exception e) {
            LOGGER.warn("[LivingItem] Error updating stress capacity on {} at {}",
                self.getClass().getSimpleName(), self.getBlockPos(), e);
        }
    }

    @Override
    public float livingItem$getStressCapacity() {
        return livingItem$stressCapacity;
    }

    private static boolean isSafeKineticBE(KineticBlockEntity self) {
        String className = self.getClass().getName();
        if (className.startsWith("com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity")) {
            return true;
        }
        if (className.startsWith("com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntity")) {
            return true;
        }
        return false;
    }
}
```

**Mixin 注入的方法**：

| 方法 | 注入目标 | 作用 |
|------|---------|------|
| `livingItem$getGeneratedSpeed` | `getGeneratedSpeed()` | 让 Create 认为该 BE 是旋转源，返回固定 RPM（±8）；含自过期检测 |
| `livingItem$checkExpiry` | `tick()` | 自过期机制：每 tick 检查 `refreshedThisTick`，未刷新则清理应力 |
| `livingItem$calculateAddedStressCapacity` | `calculateAddedStressCapacity()` | 让 Create 认为该 BE 提供应力容量，动态返回 SU |
| `livingItem$setGeneratedRPM` | 新增方法 | 外部调用设置 RPM，含白名单过滤、自过期标记、网络更新 |
| `livingItem$setStressCapacity` | 新增方法 | 外部调用设置 SU 容量，变更时立即更新网络应力 |
| `isSafeKineticBE` | 私有方法 | 白名单过滤：仅允许简单传动组件接收应力 |

**状态转换逻辑**：

```
prev=0, rpm≠0  → 从静止到旋转：setSpeed + setNetwork + attachKinetics
prev≠0, rpm=0  → 从旋转到静止：detachKinetics → setSpeed(0) → setNetwork(null) → attachKinetics（重连邻居网络）
prev≠0, rpm≠0  → 速度变化：detachKinetics + setSpeed + attachKinetics
```

> **注意**：`prev≠0, rpm=0` 时必须**先 detach 再 setSpeed(0)**。如果先 `setSpeed(0)` 再 `detach`，`detachKinetics()` 内部调用 `RotationPropagator.handleRemoved()` 时发现 speed==0 会直接返回，导致下游齿轮不会收到"源已移除"的通知，继续空转。清理后必须调用 `attachKinetics()` 重连邻居网络，否则已被其他发电机带动的齿轮会完全停止。

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

**实现方式**：双重白名单检查——`CreateIntegration` 和 `KineticBlockEntityMixin` 各自维护 `isSafeKineticBE()` 方法，使用类名前缀匹配。两处检查确保：
1. `CreateIntegration` 不会向非白名单 BE 发送应力
2. 即使绕过第一层检查，`KineticBlockEntityMixin.setGeneratedRPM()` 也会拒绝非白名单 BE

### 5.4 Mixin 配置文件

`living_item.mixins-create.json`：

```json
{
  "required": false,
  "package": "com.qiqi.li.living.mixin.create",
  "compatibilityLevel": "JAVA_21",
  "plugin": "com.qiqi.li.living.create.CreateMixinPlugin",
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
float rpm = Math.signum(netStress) * ModCreate.BASE_RPM;  // ±8
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
  ├── StressDataProvider 接口
  ├── BlockEntityMixin（添加 stressData 字段）
  ├── 从 ContainerStressData 同步到 BlockEntity
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
     └── false（应力源已消失）→ 立即清理 RPM/SU + detachKinetics + sendData
  3. getGeneratedSpeed() 被调用 → 检查 refreshedThisTick
     ├── false → 清零 RPM/SU，返回默认值
     └── true → 返回注入的 RPM
```

**优势**：
- 响应速度从 2 tick 提升到 1 tick
- `tick()` 中清理时调用 `sendData()` 同步客户端
- `getGeneratedSpeed()` 中也有兜底检测，双重保障

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

---

## 附录：Tick 时序

```
ContainerLivingItemHandler.processContext()
  │
  ├─ 1. 分组活物品（按功能 ID 分组）
  │
  ├─ 2. 调用各功能的 tick()
  │     ├─ LivingWaterBucketFunction.tick() → 注册水源
  │     ├─ LivingWaterWheelFunction.tick() → （空操作，应力在步骤3后计算）
  │     └─ 其他功能...
  │
  ├─ 3. ContainerFluidData.tick()
  │     ├─ recalculate() → BFS 水流蔓延
  │     └─ pushItems() → 沿水流推动物品
  │
  ├─ 4. LivingWaterBucketFunction.postTickSync() → 同步水流到水桶物品
  │
  ├─ 5. ContainerStressData.calculate() → 计算每个水车的力矩
  │
  ├─ 6. LivingWaterWheelFunction.postTickSync() → 同步应力到水车物品
  │
  ├─ 7. 写入 BlockEntity 应力数据  ← 第二步
  │
  ├─ 8. ModCreate.updateStressOutput() → 容器底部/玩家脚底输出应力  ← 第三步
  │     ├─ CreateCompat.isLoaded() → 检测 Create
  │     ├─ CreateIntegration.updateStressOutput() → 找到下方 BE
  │     ├─ isSafeKineticBE() → 白名单过滤
  │     ├─ isDirectionCompatible() → 方向兼容性检查（软侵入）
  │     ├─ RPM = -sign(netStress) × 8（负号修正方向，大小固定）
  │     ├─ SU = |netStress| × 32（堆叠增加 SU 容量）
  │     ├─ LivingItemStressOutput.livingItem$setGeneratedRPM() → 设置 RPM + refreshedThisTick
  │     └─ LivingItemStressOutput.livingItem$setStressCapacity() → 设置 SU 容量 + 更新网络
  │
  └─ 9. 清理 + 释放 TickContext
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
- [x] 软依赖：安装 Create 后自动激活活水车功能