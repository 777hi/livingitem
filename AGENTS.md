# Living Item Template (活物品模板)

**Minecraft 1.21.1 + NeoForge 21.1.x**

## 项目概述

将世界中的方块功能（熔炉、漏斗等）**活化到物品层面**。活物品在容器（箱子、背包等）内自动运行，状态通过 NBT 持久化，跟随物品跨容器迁移。

### 核心特性

- **活按钮 UI**：在容器界面点击按钮，将手持物品转化为活物品
- **容器内自动执行**：含活物品的被加载容器会自动 tick
- **组件化架构**：功能拆分为可复用的原子组件，通过声明式配置组合
- **方向配置组件**：`DirectionModeComponent` 统一管理槽位方向，支持 SLOTS（多槽位映射）和 TRANSFER（传输方向）两种模式
- **WASD 输入配置**：在容器界面拿起活漏斗悬停活按钮上，通过 WASD 键入改变传输方向
- **状态持久化**：所有运行数据保存在物品 NBT 中，跨容器迁移不丢失
- **活物品隔离**：活物品不会被其他活物品当作普通物品处理（不传输、不熔炼、不作为燃料）

---

## 架构设计

### 整体数据流

```
服务端 tick (LivingItem.onServerTick)
    ↓
ContainerChunkCache (只遍历含容器的区块)
    ↓
ContainerLivingItemHandler (扫描容器、按功能分组)
    ↓
LivingItemFunction.tick() (活熔炉 / 活漏斗)
    ↓
FunctionExecutor (加载组件状态、编排执行)
    ↓
┌─────────────────────────────────────────────┐
│  DirectionModeComponent  → 方向/槽位解析     │
│  ItemTransferComponent   → 物品传输（活漏斗） │
│  ProgressComponent       → 进度计时          │
│  FuelConsumeComponent    → 燃料消耗          │
│  ItemTransformComponent  → 配方匹配与转化    │
└─────────────────────────────────────────────┘
    ↓
ContainerContext / SimpleContainerContext (容器读写 + 客户端同步)
```

### 客户端输入流（活漏斗方向配置）

```
玩家在容器界面按 WASD 键
    ↓
ScreenEvent.CharacterTyped.Pre (LivingItemInputHandler)
    ├─ 检查：是否在容器界面？
    ├─ 检查：光标是否悬停在活按钮上？
    ├─ 检查：手持物品是否为活漏斗？
    ├─ 检查：按键是否为有效键（W/A/S/D）？
    ├─ 收集 2 次按键 → InputSession（2 秒超时）
    ↓
processInput() → DirectionModeComponent.updateFromInput()
    ├─ WASDSequenceParser 解析按键序列为 SlotMapping
    ├─ 更新客户端物品 NBT（即时 Tooltip 反馈）
    └─ 发送 HopperDirectionPacket(mappingData)
        ↓
ServerPacketHandler (服务端)
    ├─ getCarried() 获取光标活漏斗
    ├─ 更新 DirectionModeComponent 状态到 NBT
    └─ ClientboundContainerSetSlotPacket 同步到客户端
```

### 组件体系

```
ILivingComponent (接口)
    ├── DirectionModeComponent  — 方向/槽位配置（双模式）
    │     ├── SLOTS 模式：命名槽位映射（活熔炉的 input/fuel/output）
    │     └── TRANSFER 模式：传输方向映射（活漏斗的 source→target）
    ├── ItemTransferComponent   — 物品传输逻辑
    ├── ProgressComponent       — 进度计时与暂停
    ├── FuelConsumeComponent    — 燃料消耗与可用性检查
    └── ItemTransformComponent  — 配方匹配与物品转化
```

### 模型层

```
core/model/
    ├── Pos2D       — 不可变 2D 坐标（record），含方向常量（UP/DOWN/LEFT/RIGHT 等）
    └── SlotMapping — 不可变槽位映射（record），含 12 种预设方向 + NBT 序列化
```

---

## 核心文件索引

```
src/main/java/com/qiqi/li/
├── LivingItem.java                          # Mod 主类：tick 入口、网络包注册
├── LivingItemClient.java                    # 客户端入口
├── Config.java                              # NeoForge 配置
│
├── living/
│   ├── LivingItemManager.java               # 核心管理器：DataComponent 注册、数据读写、功能注册
│   ├── LivingItemFunction.java              # 功能接口定义
│   ├── LivingFunctionData.java              # NBT 数据容器
│   ├── LivingFurnaceFunction.java           # 活熔炉：SLOTS 模式配置方向
│   ├── LivingHopperFunction.java            # 活漏斗：TRANSFER 模式配置方向
│   ├── ContainerContext.java                # 容器操作抽象接口（含客户端同步）
│   ├── SimpleContainerContext.java          # 容器上下文实现（带异常保护 + 同步逻辑）
│   ├── ContainerLivingItemHandler.java      # 容器扫描、分组调度、大箱子去重
│   ├── ContainerChunkCache.java             # 区块级容器缓存（事件驱动维护）
│   │
│   └── core/
│       ├── FunctionExecutor.java            # ⭐ 组件编排核心：加载状态、解析方向、执行 tick、处理完成
│       ├── SlotResolver.java                # 槽位解析：基于 9 列网格的相对偏移计算
│       ├── LivingFunctionConfig.java        # 配置声明：组件注册、预配置实例、配置参数
│       ├── ComponentConfig.java             # 组件配置参数容器
│       ├── ComponentContext.java            # 组件执行上下文（容器 + 槽位 + 世界 + 状态）
│       ├── ComponentState.java              # 组件运行时状态（NBT 包装器）
│       │
│       ├── model/
│       │   ├── Pos2D.java                   # 不可变 2D 坐标，方向常量，NBT 序列化
│       │   └── SlotMapping.java             # 不可变槽位映射，12 种预设，NBT 序列化
│       │
│       ├── components/
│       │   ├── ILivingComponent.java        # 组件接口：tick + createDefaultState + appendTooltip
│       │   ├── DirectionModeComponent.java  # 方向配置组件（SLOTS/TRANSFER 双模式）
│       │   ├── ItemTransferComponent.java   # 物品传输组件（活漏斗）
│       │   ├── ProgressComponent.java       # 进度组件（计时、暂停、回退）
│       │   ├── FuelConsumeComponent.java    # 燃料组件（消耗、可用性检查）
│       │   └── ItemTransformComponent.java  # 转化组件（配方匹配、物品转化）
│       │
│       ├── adapters/
│       │   ├── ContainerAdapter.java        # 适配器接口
│       │   ├── HopperAdapter.java           # 漏斗适配器
│       │   └── AdapterRegistry.java         # 适配器注册中心
│       │
│       └── config/
│           ├── ContainerCompatibilityConfig.java  # 容器兼容性配置
│           └── TransferStrategy.java               # 传输策略
│
├── client/
│   ├── LivingItemInputHandler.java          # 客户端输入处理：WASD 方向配置 + InputSession
│   ├── LivingItemTooltip.java               # Tooltip 渲染
│   ├── gui/
│   │   └── LivingButton.java                # 活按钮：点击切换 IS_LIVING 标记
│   └── mixin/
│       ├── AbstractContainerScreenMixin.java # 容器界面 Mixin（注入活按钮）
│       └── InventoryScreenMixin.java         # 背包界面 Mixin
│
└── network/
    ├── LivingTagPacket.java                 # 活物品标签切换包（客户端→服务端）
    ├── HopperDirectionPacket.java           # 漏斗方向配置包（客户端→服务端，v2 格式）
    └── ServerPacketHandler.java             # 服务端包处理：更新光标物品 NBT + 同步
```

---

## 关键设计决策

### 1. DirectionModeComponent 双模式设计

| 模式 | 用途 | 数据结构 | 输入方式 |
|------|------|----------|----------|
| **SLOTS** | 活熔炉等需要多个命名槽位的场景 | `Map<String, Pos2D>`（如 input→LEFT, fuel→DOWN, output→RIGHT） | 代码配置，运行时不可变 |
| **TRANSFER** | 活漏斗等需要动态传输方向的场景 | `SlotMapping`（sourceOffset + targetOffset） | WASD 键入，运行时可变 |

SLOTS 模式的方向数据存储在 `ComponentState` 中（`slot_input_x`, `slot_input_y` 等），TRANSFER 模式存储 `src_x`, `src_y`, `tgt_x`, `tgt_y`。

### 2. 不可变数据模型

`Pos2D` 和 `SlotMapping` 使用 Java record，确保数据不可变性。NBT 序列化使用纯整数坐标（`src_x`, `src_y` 等），避免字符串解析的歧义问题。

### 3. 活物品隔离

所有组件在处理物品时检查 `LivingItemManager.isLivingItem()`：
- `ItemTransferComponent`：不传输活物品
- `FuelConsumeComponent`：不消耗活物品作为燃料
- `ItemTransformComponent`：不熔炼活物品

### 4. 服务端权威 + 手动同步

物品数据在服务端是权威的。活物品 tick 修改 NBT 后，通过 `ContainerContext.syncSlotToClients()` 主动发送 `ClientboundContainerSetSlotPacket` 同步到客户端，因为原版 `broadcastChanges()` 无法检测自定义 DataComponent 的变化。

### 5. 光标物品操作

活漏斗方向配置时，物品被拿在光标上（`containerMenu.getCarried()`），不在任何槽位中。服务端通过 `getCarried()` 获取引用，修改后用 `ClientboundContainerSetSlotPacket(-1, stateId, -1, ...)` 同步回客户端。

### 6. 容器区块缓存

`ContainerChunkCache` 通过事件驱动（区块加载/卸载、方块放置/破坏）维护含容器的区块列表，避免每 tick 全量扫描所有区块。

---

## 已完成功能

### 基础设施
- [x] 活按钮 UI 与物品活化机制（`LivingButton` + `LivingTagPacket`）
- [x] DataComponent 数据持久化系统
- [x] 容器自动扫描与 tick 分发（`ContainerChunkCache` + `ContainerLivingItemHandler`）
- [x] 多活物品并行处理（按功能分组，无冲突）
- [x] 组件化架构（`ILivingComponent` + `FunctionExecutor`）
- [x] 不可变数据模型（`Pos2D` + `SlotMapping` record）
- [x] 活物品隔离（不传输/不熔炼/不作为燃料）

### 活熔炉功能
- [x] SLOTS 模式方向配置（input→LEFT, fuel→DOWN, output→RIGHT）
- [x] 配方匹配与物品转化
- [x] 燃料消耗与燃烧时间管理
- [x] 无效条件时暂停并回退进度
- [x] 跨容器状态保持（移动后保留 burnTime）
- [x] 多实例加速（不同槽位的活熔炉独立工作）

### 活漏斗功能
- [x] TRANSFER 模式方向配置（默认上传下 UP→DOWN）
- [x] WASD 键入改变传输方向（需悬停活按钮 + 拿起活漏斗）
- [x] 网络包同步（`HopperDirectionPacket` v2 格式）
- [x] Tooltip 实时显示当前传输方向
- [x] 传输冷却机制（基于物品数量动态调整）

### 容器兼容性
- [x] 标准矩形容器（27 格箱子、54 格大箱子）
- [x] 线性容器（5 格漏斗）
- [x] 边界检查与异常安全
- [x] 大箱子去重（避免左右两半被分别处理）

---

## 开发进展

### 当前版本: v0.3-alpha

**最近更新** (2026-07-08):
- ✅ 重构：将 `TransferDirection`、`Direction2D`、`HopperModeController`、`LivingHopperInputHandler` 合并为 `DirectionModeComponent` + `LivingItemInputHandler`
- ✅ 重构：提取 `Pos2D` 和 `SlotMapping` 为独立 model 类
- ✅ 重构：`DirectionModeComponent` 支持 SLOTS/TRANSFER 双模式，替代 `LivingFunctionConfig` 的方向配置功能
- ✅ 重构：`LivingFunctionConfig` 精简为纯配置容器，支持预配置组件实例
- ✅ 修复：活漏斗传输方向修改后 NBT/Tooltip/实际传输未同步更新的问题
- ✅ 修复：活漏斗传输功能失效（`SlotResolver` 网格宽度计算错误）
- ✅ 修复：WASD 输入事件未注册导致方向配置功能不可用
- ✅ 新增：活物品隔离（不传输/不熔炼/不作为燃料）
- ✅ 简化：网络包移除 slotIndex，直接使用 `getCarried()` 获取光标物品

### 待办事项

#### 高优先级
- [ ] 更多活物品类型（活投掷器、活发射器等）
- [ ] 活漏斗支持过滤模式（只传输指定物品）
- [ ] 活熔炉 Tooltip 增强（显示工作模式、预计剩余时间）

#### 中优先级
- [ ] 调试命令 `/livingitem info`
- [ ] 成就系统集成
- [ ] 音效差异化（不同状态的音效变化）

#### 低优先级 / 未来规划
- [ ] 活物品状态切换机制（更多配置选项）
- [ ] 第三方模组适配器 API 开放
- [ ] JSON 配置文件支持（用户自定义容器规则）
- [ ] 槽位内活物品的配置支持（当前仅支持光标物品配置）

---

## 技术栈

- **Java 21** + **NeoForge 21.1.x**
- **Minecraft 1.21.1**
- 构建工具: Gradle
- 数据持久化: Minecraft DataComponent API + NBT
- 容器访问: NeoForge Container 接口
- 网络通信: NeoForge CustomPacketPayload API

---

## 设计原则

1. **能力组件化**：功能拆分为可复用的原子组件，通过 `LivingFunctionConfig` 声明式组合
2. **配置驱动执行**：组件通过 `ComponentConfig` 接收参数，行为由配置决定
3. **状态完全持久化**：所有数据存储在 NBT，跟随物品迁移
4. **服务端权威**：客户端只负责输入和显示，数据修改在服务端执行后同步回客户端
5. **防御性编程**：多层边界检查，优雅降级不崩溃
6. **开放扩展**：新活物品类型只需实现 `LivingItemFunction` + 配置组件

---

## 贡献指南

### 新增活物品类型

1. 实现 `LivingItemFunction` 接口（`canApply`、`tick`、`addToTooltip`、`getFunctionId`）
2. 创建 `LivingFunctionConfig`，声明所需组件和配置
3. 在 `LivingItem.commonSetup()` 中注册功能

### 新增组件

1. 实现 `ILivingComponent` 接口（`getComponentId`、`tick`、`createDefaultState`）
2. 在活物品的 `LivingFunctionConfig` 中通过 `addComponent()` 注册
3. 如需跨组件数据访问，通过 `ComponentContext.getComponentState()` 读取其他组件状态

### 代码风格

- 使用中文注释（与项目语言一致）
- 遵循现有命名约定（Config/Component/Context 后缀）
- 异常处理必须使用 try-catch 包装容器操作
- 不可变数据优先使用 Java record

---

*最后更新: 2026-07-08*
*状态: Alpha 测试阶段 - 活熔炉和活漏斗核心功能已完成*