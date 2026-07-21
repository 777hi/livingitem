# Living Item Template (活物品模板)

**Minecraft 1.21.1 + NeoForge 21.1.x**

## 项目概述

将世界中的方块功能（熔炉、漏斗等）**活化到物品层面**。活物品在容器（箱子、背包等）内自动运行，状态通过 NBT 持久化，跟随物品跨容器迁移。

### 核心特性

- **活按钮 UI**：在容器界面点击按钮，将手持物品转化为活物品
- **容器内自动执行**：含活物品的被加载容器会自动 tick
- **组件化架构**：功能拆分为可复用的原子组件，通过声明式配置组合
- **编排器模式**：通过 `LivingOrchestrator` 定义组件协作流程，新活物品只需选择编排器 + 配置组件
- **方向配置组件**：`DirectionModeComponent` 统一管理槽位方向，支持 SLOTS（多槽位映射）和 TRANSFER（传输方向）两种模式
- **WASD 输入配置**：在容器界面拿起活漏斗悬停活按钮上，通过 WASD 键入改变传输方向
- **状态持久化**：所有运行数据保存在物品 NBT 中，跨容器迁移不丢失
- **活物品隔离**：活物品不会被其他活物品当作普通物品处理（不传输、不熔炼、不作为燃料）
- **GUI交互系统**：容器界面中活物品之间的鼠标交互（如活打火石右键活TNT），声明式规则 + 统一拦截 + 服务端处理
- **活TNT爆炸**：容器中的可爆炸活物品，引信倒计时后爆炸，威力随数量缩放，支持普通/大当量双模式
- **活物品图标系统**：组件化的客户端图标框架，声明式配置即可实现活物品图标根据 NBT 动态切换，支持上下文感知（GUI/手持显示不同图标）和 ItemDecorator 叠加层
- **活箱子系统**：将箱子虚拟化到物品 NBT 中，堆叠数 × 27 槽 = 虚拟箱子容量。UUID 映射管理、LRU 缓存、磁盘持久化、漏斗自动传输、跨容器传输、GUI 拆分/合并 UUID 自动分配

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
BaseLivingFunction.tick() (通用编排框架)
    ↓
LivingOrchestrator.orchestrate() (编排器决定组件协作流程)
    ↓
┌─────────────────────────────────────────────┐
│  DirectionModeComponent  → 方向/槽位解析     │
│  ItemTransferComponent   → 物品传输（活漏斗） │
│  ProgressComponent       → 进度计时          │
│  FuelConsumeComponent    → 燃料消耗          │
│  ItemTransformComponent  → 配方匹配与转化    │
│  ExplosionComponent      → 引信倒计时+爆炸   │
└─────────────────────────────────────────────┘
    ↓
ContainerContext / SimpleContainerContext (容器读写 + 客户端同步)
```

### 编排器体系

编排器将"如何协调组件执行"从活物品功能类中分离出来，使新活物品只需选择合适的编排器，无需重写编排逻辑。

```
LivingOrchestrator (接口)
    ├── SimpleOrchestrator       — 直接遍历组件 tick
    │     适用：活漏斗（无进度/燃料概念）
    │
    ├── ProgressOrchestrator     — 检查输入 → tick/pauseTick → 完成时转化
    │     适用：活磨石（有进度和转化，无燃料）
    │
    └── FuelProgressOrchestrator — 检查燃料+输入 → tick/pauseTick → 完成时转化
          适用：活熔炉、活酿造台（燃料+进度+转化）
```

| 编排器 | canProgress 检查 | pauseTick | handleCompletion | 输入槽位占用 |
|--------|-----------------|-----------|-----------------|-------------|
| SIMPLE | 无 | 无 | 无 | 无 |
| PROGRESS | 输入有效性 | 进度回退 | 转化+重置 | ✅ |
| FUEL_PROGRESS | 燃料+输入有效性 | 进度+燃料回退 | 燃料检查+转化+重置 | ✅ |

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
    ├─ LivingHopperFunction.updateTransferMapping() 更新方向
    └─ ClientboundContainerSetSlotPacket 同步到客户端
```

### GUI交互数据流（活物品间交互）

```
玩家在容器界面鼠标点击（右键活TNT等）
    ↓
Screen Mixin (mouseClicked / mouseReleased HEAD注入)
    ├─ AbstractContainerScreenMixin  — 通用容器（箱子、潜影盒）
    ├─ InventoryScreenMixin          — 生存模式背包
    └─ CreativeModeInventoryScreenMixin — 创造模式背包
    ↓
GuiInteractionHelper.tryInteract(hoveredSlot, button, menu)
    ├─ 获取光标物品(trigger)和槽位物品(target)
    ├─ InteractionRegistry.findInteraction(trigger, target, button)
    │     遍历所有 InteractionEntry，匹配 triggerItem + targetItem + button
    │     同时验证双方都是活物品
    ├─ resolveContainerSlot() — 解析真实容器索引（兼容 SlotWrapper）
    ├─ resolveCarriedTag() — 创造模式背包下序列化光标物品NBT
    └─ 发送 GuiInteractionPacket(slotIndex, containerSlot, actionId, carriedTag)
        ↓
GuiInteractionPacket.handle() (服务端)
    ├─ 创造模式 + carriedTag非空 → menu.setCarried() 恢复光标物品
    ├─ resolveSlot() 定位目标槽位（slotIndex优先，containerSlot回退）
    ├─ InteractionRegistry.getHandler(actionId) 查找处理器
    ├─ handler.handle(player, targetSlot) 执行交互逻辑
    └─ 创造模式光标被修改 → CarriedUpdatePacket 同步回客户端
        ↓
CarriedUpdatePacket.handle() (客户端)
    └─ 直接更新 menu.setCarried()，绕过原版对 CreativeModeInventoryScreen 的排除
```

**交互规则声明示例（活TNT）：**
```java
// LivingTntFunction.CONFIG 中声明两条交互规则：
new InteractionEntry(Items.TNT, Items.FLINT_AND_STEEL, 1, "ignite")
//  目标=活TNT, 触发器=活打火石, 右键 → actionId="ignite" → IgniteHandler

new InteractionEntry(Items.FLINT_AND_STEEL, Items.TNT, 1, "ignite_carried")
//  目标=活打火石, 触发器=活TNT, 右键 → actionId="ignite_carried" → IgniteCarriedHandler
```

**新增交互只需两步：**
1. 在 `LivingFunctionConfig` 中 `addInteraction(new InteractionEntry(...))`
2. 注册处理器 `InteractionRegistry.registerHandler("actionId", new XxxHandler())`

### 组件体系

```
ILivingComponent (接口)
    ├── DirectionModeComponent  — 方向/槽位配置（双模式）
    │     ├── SLOTS 模式：命名槽位映射（活熔炉的 input/fuel/output）
    │     └── TRANSFER 模式：传输方向映射（活漏斗的 source→target）
    ├── ItemTransferComponent   — 物品传输逻辑（含跨容器传输触发 + SlotAccessor 调度）
    ├── CrossContainerTransfer  — 跨容器传输工具类（方向映射 + 大箱子处理 + 邻居容器查找）
    ├── ProgressComponent       — 进度计时与暂停
    ├── FuelConsumeComponent    — 燃料消耗与可用性检查
    ├── ItemTransformComponent  — 配方匹配与物品转化
    └── ExplosionComponent      — 引信倒计时 + 爆炸逻辑（活TNT）
          ├── 普通模式 (≤64 TNT)：原版 setBlock，支持原版/100%两种掉落模式
          └── 大当量模式 (>64 TNT)：直接修改区块数据，无掉落物

SlotAccessor 存储后端抽象（独立于组件体系，供传输引擎使用）
    ├── SlotAccessor          — 接口：extract/insert/rollback/isEmpty/isFull/markTransferred/sync
    ├── PlainSlotAccessor     — 普通槽位：直接读写 ContainerContext
    ├── LivingChestAccessor   — 活箱子：通过 LivingChestFunction API 操作虚拟存储
    └── SlotAccessorFactory   — 工厂：根据槽位物品类型创建对应访问器

交互体系（独立于组件，处理GUI中的活物品间交互）
    ├── InteractionEntry   — 交互规则（record：targetItem + triggerItem + button + actionId）
    ├── InteractionRegistry — 交互注册表（规则查询 + 处理器注册）
    ├── InteractionHandler  — 处理器接口（服务端执行交互逻辑）
    ├── IgniteHandler       — 点燃槽位TNT（活打火石→活TNT）
    └── IgniteCarriedHandler — 点燃光标TNT（活TNT→活打火石）
```

### 模型层

```
core/model/
    ├── Pos2D       — 不可变 2D 坐标（record），含方向常量（UP/DOWN/LEFT/RIGHT 等）
    └── SlotMapping — 不可变槽位映射（record），含 12 种预设方向 + NBT 序列化
```

### 客户端图标系统

活物品图标采用三层架构，通过声明式配置（`LivingIconSpec`）驱动，新增活物品图标无需编写任何 Java 类。

```
┌─────────────────────────────────────────────────────┐
│  Layer 3: IItemDecorator（可选）                     │  ← 箭头叠加层（仅物品栏）
│  例: LivingHopperDecorator                          │
│      hopper_arrow_in.png / hopper_arrow_out.png     │
├─────────────────────────────────────────────────────┤
│  Layer 2: GenericContextAwareModel                   │  ← 上下文切换（GUI vs 手持）
│  ┌──────────────────┬──────────────────────┐        │
│  │ GUI: 活物品图标    │ 手持/地面: 原版图标   │        │
│  └──────────────────┴──────────────────────┘        │
├─────────────────────────────────────────────────────┤
│  Layer 1: GenericLivingModelWrapper                  │  ← 模型注入（区分活/原版）
│  └→ GenericLivingItemOverrides.resolve()             │
│     ├─ 不是活物品 → 返回原版模型                      │
│     └─ 是活物品 → 遍历 Variant.predicate 匹配变体     │
└─────────────────────────────────────────────────────┘
```

**注册示例（在 `LivingIconRegistry.registerAll()` 中）：**

```java
// 活熔炉：两种状态
register(LivingIconSpec.builder(Items.FURNACE)
    .addVariant("idle", "item/furnace_idle", stack -> !isBurning(stack))
    .addVariant("active", "item/furnace_active", stack -> isBurning(stack))
    .build());

// 活TNT：闪烁动画（引信倒计时 % 10 == 0 时切换图标）
register(LivingIconSpec.builder(Items.TNT)
    .addVariant("lit", "item/tnt_lit", stack -> getFuseTimer(stack) > 0 && getFuseTimer(stack) % 10 == 0)
    .addVariant("idle", "item/tnt_idle", stack -> true)  // 兜底
    .build());

// 活漏斗：基础图标 + 箭头叠加层
register(LivingIconSpec.builder(Items.HOPPER)
    .addVariant("base", "item/hopper_living", stack -> true)
    .decorator(new LivingHopperDecorator())
    .build());
```

**新增活物品图标只需两步：**
1. 在 `LivingIconRegistry.registerAll()` 中添加一个 `LivingIconSpec` 声明
2. 准备对应的纹理 PNG 和模型 JSON 文件

**当前支持的活物品图标：**

| 活物品 | 变体 | 纹理 | 特效 |
|--------|------|------|------|
| 活漏斗 | `base` | `hopper_base.png` | 箭头叠加层（方向旋转） |
| 活熔炉 | `idle` / `active` | `furnace_idle.png` / `furnace_active.png` | 燃烧状态切换 |
| 活TNT | `idle` / `lit` | `tnt_idle.png` / `tnt_lit.png` | 引信闪烁动画（每10 tick切换） |
| 活箱子 | `base` | `chest_living.png` | 无 |

---

### 活箱子系统架构

活箱子将原版箱子的格子存储虚拟化到物品 NBT 中，每个物品堆叠计数对应一个虚拟箱子（27 槽），通过 UUID 映射到磁盘持久化文件。

#### 数据流

```
ItemStack (NBT)
    └── LIVING_FUNCTION_DATA
        └── living_chest
            └── internal_storage
                ├── _us : int              ← 已用槽位计数（O(1) 空/满判断）
                └── uuids: ListTag<String>  ← 每个堆叠对应一个 UUID
                        ↓
                WorldStorage (LRU 缓存, 最大 200 条)
                        ↓  磁盘路径: data/living_chests/xx/uuid.dat
                ItemStack[27]  ← 每个 UUID 对应一个虚拟箱子内容
```

#### UUID 生命周期

```
创建
├── 活箱子首次 tick → 初始化所有 UUID
├── 堆叠数增加（合并）→ 追加新 UUID
├── insertItem() 发现 UUID 不足 → 自动补充
└── createAndRegisterNewUuid() → UUID.randomUUID() + 注册到缓存

读取
├── getUuids() → 从 ComponentState 解析 UUID 列表
├── getStorageState() → 从 ItemStack NBT 读取完整状态
└── getOrCreate(uuid) → 从缓存/磁盘加载虚拟箱子数据

⚠️ UUID 只增不减：堆叠数减少、insertItem 溢出等均不删除 UUID
删除（唯一路径：用户取消活化）
├── popUuid() → 弹出最后一个 UUID（调用方需先处理物品）
├── 取消活化（dropAllItems）→ 清理所有 UUID 和磁盘文件
└── cleanupOrphanedFiles() → 被动清理无引用的孤儿空文件

持久化
├── saveUuids() → UUID 列表 → ComponentState → ItemStack NBT
├── saveToDisk(uuid, items) → 虚拟箱子数据写入磁盘
├── markDirty(uuid) → 标记脏数据，等待定时保存
└── cleanupIdle() → 5 分钟未访问 → 从缓存淘汰（脏数据先保存）
```

#### 关键设计决策

**堆叠倍增模型**：1 个堆叠 = 1 个虚拟箱子（27 槽）。64 个活箱子 = 64 × 27 = 1728 槽。物品插入优先填充已有箱子（先填满现有的），提取优先从已有箱子取；只有满了才新增/空了才删除。

**LRU 缓存策略**：最大 200 条缓存条目，5 分钟空闲超时。淘汰前检查脏标记，脏数据先写盘。避免反复加载/卸载同一 UUID 数据。

**快速短路判断**：`_us`（used slots）字段记录已用槽位总数，O(1) 判断空/满。漏斗传输前先检查源是否空/目标是否满，避免无效的完整插入/提取调用链。

**漏斗自动传输**：`ItemTransferComponent` 在活箱子 tick 时，自动通过漏斗向相邻容器推拉物品。传输方向由 `DirectionModeComponent` 的 TRANSFER 模式控制。

**跨容器传输**：活箱子在容器边界时，通过 `CrossContainerTransfer` 向相邻容器传输物品。方向映射基于容器方块朝向旋转。

**GUI 拆分/合并 UUID 分配**：`ItemStackMixin` 拦截 `split()`/`grow()`/`shrink()`/`copyWithCount()`，通过 `LivingChestStackHandler` 自动分配/合并 UUID。`LivingChestStackFlags` 线程局部标志允许跨 UUID 堆叠。

**被动孤儿清理**：`cleanupOrphanedFiles()` 在 `onLevelSave` 时每 10 次执行一次，扫描 `data/living_chests/` 下所有文件，删除 NBT 全空且无活跃 UUID 引用的孤儿文件，防止取消活化后磁盘文件泄漏。

#### 核心文件

| 文件 | 职责 |
|------|------|
| `LivingChestFunction` | 活箱子功能：insertItem/extractItem/getStorageState 等公开 API |
| `InternalStorageComponent` | 底层实现：UUID 管理、LRU 缓存、磁盘 I/O、WorldStorage |
| `LivingChestStackHandler` | UUID 列表工具：标准化、创建、拆分、合并、数据校验 |
| `LivingChestStackFlags` | 线程局部标志：允许跨 UUID 堆叠（GUI 操作期间） |
| `ChestTransaction` | 事务包装器：确保多次操作间原子保存状态 |

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
│   ├── LivingItemFunction.java              # 功能接口定义（含 appendComponentTooltips 默认方法）
│   ├── BaseLivingFunction.java              # ⭐ 功能基类：通用 tick 编排 + Tooltip 实现
│   ├── LivingFunctionData.java              # 活物品功能数据定义（DataComponent 载体）
│   │
│   ├── function/                            # 各活物品功能实现
│   │   ├── LivingChestFunction.java         # 活箱子：堆叠倍增模型、UUID 管理、物品存取 API
│   │   ├── LivingFurnaceFunction.java       # 活熔炉：FUEL_PROGRESS 编排器 + SLOTS 模式方向
│   │   ├── LivingHopperFunction.java        # 活漏斗：SIMPLE 编排器 + TRANSFER 模式方向 + NBT 工具
│   │   ├── LivingTntFunction.java           # 活TNT：引信倒计时 + 爆炸，声明两条交互规则
│   │   └── LivingFlintAndSteelFunction.java # 活打火石：交互触发器，无 tick 逻辑
│   │
│   ├── container/                           # 容器上下文与处理器
│   │   ├── ContainerContext.java            # 容器操作抽象接口（含客户端同步 + 槽位占用 + getWidth）
│   │   ├── SimpleContainerContext.java      # 容器上下文实现（带异常保护 + 同步逻辑 + 三级回退列宽）
│   │   ├── ContainerLivingItemHandler.java  # 容器扫描、分组调度、大箱子去重
│   │   └── ContainerChunkCache.java         # 区块级容器缓存（事件驱动维护）
│   │
│   ├── chest/                               # 活箱子辅助工具
│   │   ├── LivingChestStackHandler.java     # UUID 列表工具：标准化、创建、拆分、合并、数据校验
│   │   ├── LivingChestStackFlags.java       # 线程局部标志：允许跨 UUID 堆叠（GUI 操作期间）
│   │   └── ChestTransaction.java            # 事务包装器：确保多次操作间原子保存状态
│   │
│   └── core/
│       ├── FunctionExecutor.java            # 纯工具类：状态加载/保存、槽位解析、组件查找
│       ├── SlotResolver.java                # 槽位解析：基于动态列宽的相对偏移计算（支持非9列容器）
│       ├── LivingFunctionConfig.java        # 配置声明：组件注册 + 编排器选择 + 交互规则 + 配置参数
│       ├── ComponentConfig.java             # 组件配置参数容器
│       ├── ComponentContext.java            # 组件执行上下文（容器 + 解析槽位 + 世界 + 状态）
│       ├── ComponentState.java              # 组件运行时状态（NBT 包装器）
│       │
│       ├── accessor/                        # SlotAccessor 存储后端抽象
│       │   ├── SlotAccessor.java            # 接口：extract/insert/rollback/isEmpty/isFull/markTransferred/sync
│       │   ├── PlainSlotAccessor.java       # 普通槽位：直接读写 ContainerContext
│       │   ├── LivingChestAccessor.java     # 活箱子：通过 LivingChestFunction API 操作虚拟存储
│       │   └── SlotAccessorFactory.java     # 工厂：根据槽位物品类型创建对应访问器
│       │
│       ├── model/
│       │   ├── Pos2D.java                   # 不可变 2D 坐标，方向常量，NBT 序列化
│       │   └── SlotMapping.java             # 不可变槽位映射，12 种预设，NBT 序列化
│       │
│       ├── components/
│       │   ├── ILivingComponent.java        # 组件接口：tick + createDefaultState + appendTooltip
│       │   ├── InternalStorageComponent.java # ⭐ 活箱子核心：UUID 管理、LRU 缓存、磁盘 I/O、物品存取
│       │   ├── DirectionModeComponent.java  # 方向配置组件（SLOTS/TRANSFER 双模式 + NBT 自治）
│       │   ├── ItemTransferComponent.java   # 物品传输组件（活漏斗/活箱子，含跨容器传输触发）
│       │   ├── CrossContainerTransfer.java  # 跨容器传输工具类（方向映射 + 大箱子半箱选择 + 邻居容器查找）
│       │   ├── ProgressComponent.java       # 进度组件（计时、暂停、回退）
│       │   ├── FuelConsumeComponent.java    # 燃料组件（消耗、可用性检查）
│       │   ├── ItemTransformComponent.java  # 转化组件（配方匹配、物品转化）
│       │   └── ExplosionComponent.java      # 爆炸组件（引信倒计时、双模式爆炸、流体防爆）
│       │
│       ├── orchestrator/
│       │   ├── LivingOrchestrator.java      # 编排器接口 + 通用辅助方法
│       │   ├── SimpleOrchestrator.java      # 简单编排器：直接遍历组件 tick
│       │   ├── ProgressOrchestrator.java    # 进度编排器：检查输入 → tick/pauseTick → 完成时转化
│       │   ├── FuelProgressOrchestrator.java # 燃料+进度编排器：检查燃料+输入 → tick/pauseTick → 完成时转化
│       │   └── Orchestrators.java           # 编排器工厂（SIMPLE / PROGRESS / FUEL_PROGRESS 常量）
│       │
│       ├── interaction/
│       │   ├── InteractionEntry.java        # 交互规则 record（targetItem + triggerItem + button + actionId）
│       │   ├── InteractionRegistry.java     # 交互注册表（规则查询 + 处理器注册）
│       │   ├── InteractionHandler.java      # 处理器接口（服务端执行交互逻辑）
│       │   ├── IgniteHandler.java           # 点燃槽位TNT（活打火石→活TNT）
│       │   └── IgniteCarriedHandler.java    # 点燃光标TNT（活TNT→活打火石）
│       │
│       ├── adapters/
│       │   ├── ContainerAdapter.java        # 适配器接口
│       │   ├── HopperAdapter.java           # 漏斗适配器
│       │   └── AdapterRegistry.java         # 适配器注册中心
│       │
│       └── config/
│           ├── ContainerCompatibilityConfig.java  # 容器兼容性配置（含 columns 字段 + findRuleBySize 按大小匹配）
│           └── TransferStrategy.java               # 传输策略
│
├── client/
│   ├── GuiInteractionHelper.java            # ⭐ 客户端GUI交互统一工具（查询规则+解析槽位+序列化光标+发包）
│   ├── LivingItemInputHandler.java          # 客户端输入处理：WASD 方向配置 + InputSession
│   ├── LivingItemTooltip.java               # Tooltip 渲染
│   ├── LivingHopperDecorator.java           # 活漏斗箭头叠加层（IItemDecorator，旋转绘制输入/输出箭头）
│   ├── gui/
│   │   └── LivingButton.java                # 活按钮：点击切换 IS_LIVING 标记
│   ├── icon/                                # ⭐ 活物品图标系统（组件化，声明式配置）
│   │   ├── LivingIconSpec.java              # 图标声明式配置（建造者模式：变体+谓词+叠加层）
│   │   ├── LivingIconRegistry.java          # 图标注册中心（统一管理所有活物品图标配置和模型注入）
│   │   ├── GenericLivingModelWrapper.java   # 通用模型包装器（注入自定义 ItemOverrides）
│   │   ├── GenericContextAwareModel.java    # 通用上下文切换模型（GUI 显示自定义图标，手持显示原版图标）
│   │   └── GenericLivingItemOverrides.java  # 通用覆盖解析器（根据 Variant.predicate 匹配变体模型）
│   └── mixin/
│       ├── AbstractContainerScreenMixin.java # 容器界面 Mixin（注入活按钮 + 交互拦截）
│       ├── InventoryScreenMixin.java         # 生存模式背包 Mixin（交互拦截）
│       ├── CreativeModeInventoryScreenMixin.java # 创造模式背包 Mixin（交互拦截 + SlotWrapper兼容）
│       ├── SlotWrapperAccessor.java          # SlotWrapper 访问器接口（获取 target 字段）
│       └── SpriteIconButtonMixin.java        # 按钮渲染 Mixin
│
└── network/
    ├── GuiInteractionPacket.java            # ⭐ 通用GUI交互包（客户端→服务端：slotIndex + containerSlot + actionId + carriedTag）
    ├── CarriedUpdatePacket.java             # 光标更新包（服务端→客户端：绕过创造模式光标同步限制）
    ├── LivingTagPacket.java                 # 活物品标签切换包（客户端→服务端）
    ├── HopperDirectionPacket.java           # 漏斗方向配置包（客户端→服务端，v2 格式）
    └── ServerPacketHandler.java             # 服务端包处理：更新光标物品 NBT + 同步
```

---

## 关键设计决策

### 1. 编排器模式（Orchestrator Pattern）

活物品的组件协作流程通过 `LivingOrchestrator` 定义，而非硬编码在 Function 或 FunctionExecutor 中。

**为什么不用 FunctionExecutor 编排？**
- FunctionExecutor 是通用工具类，不应包含任何活物品的业务逻辑
- 不同活物品有不同的协作流程（活漏斗只需 tick，活熔炉需要 canProgress/pauseTick/handleCompletion）
- 编排器可复用：活熔炉和活酿造台都用 `FUEL_PROGRESS`，无需重复代码

**为什么不用组件内部编排？**
- 组件应该是原子的、独立的，不应知道其他组件的存在
- 编排逻辑跨组件，放在任何单个组件中都会导致职责泄漏

### 2. BaseLivingFunction 基类

`BaseLivingFunction` 提供通用的 tick 编排和 Tooltip 实现，子类只需：
- 返回 `LivingFunctionConfig`（包含组件列表 + 编排器）
- 实现 `canApply()` 和 `getFunctionId()`

**新增活物品只需 30-50 行代码**：
```java
public class LivingBrewingStandFunction extends BaseLivingFunction {
    private static final LivingFunctionConfig CONFIG = new LivingFunctionConfig()
        .withFunctionId("living_brewing_stand")
        .withOrchestrator(Orchestrators.FUEL_PROGRESS)  // 选择编排器
        .addComponent(new DirectionModeComponent(Map.of(
            "input", Pos2D.LEFT, "fuel", Pos2D.DOWN, "output", Pos2D.RIGHT)))
        .addComponent(FuelConsumeComponent.class,
            ComponentConfig.of("recipe_type", RecipeType.BREWING))
        .addComponent(ProgressComponent.class,
            ComponentConfig.of("total_ticks", 400))
        .addComponent(ItemTransformComponent.class,
            ComponentConfig.of("recipe_type", RecipeType.BREWING));

    @Override protected LivingFunctionConfig getConfig() { return CONFIG; }
    @Override protected String getTooltipTitleKey() { return "tooltip.livingitem.brewing_stand.status"; }
    @Override public boolean canApply(ItemStack stack) { return stack.is(Items.BREWING_STAND) && LivingItemManager.isLivingItem(stack); }
    @Override public String getFunctionId() { return "living_brewing_stand"; }
}
```

### 3. DirectionModeComponent 双模式设计

| 模式 | 用途 | 数据结构 | 输入方式 |
|------|------|----------|----------|
| **SLOTS** | 活熔炉等需要多个命名槽位的场景 | `Map<String, Pos2D>`（如 input→LEFT, fuel→DOWN, output→RIGHT） | 代码配置，运行时不可变 |
| **TRANSFER** | 活漏斗等需要动态传输方向的场景 | `SlotMapping`（sourceOffset + targetOffset） | WASD 键入，运行时可变 |

SLOTS 模式的方向数据存储在 `ComponentState` 中（`slot_input_x`, `slot_input_y` 等），TRANSFER 模式存储 `src_x`, `src_y`, `tgt_x`, `tgt_y`。

### 4. DirectionModeComponent NBT 自治

`DirectionModeComponent` 提供静态方法 `updateStateInStack()` 和 `readStateFromStack()`，自己管理自己的 NBT 持久化，调用方无需知道内部结构（ID 常量、默认状态创建、NBT 存储格式）。

### 5. 不可变数据模型

`Pos2D` 和 `SlotMapping` 使用 Java record，确保数据不可变性。NBT 序列化使用纯整数坐标（`src_x`, `src_y` 等），避免字符串解析的歧义问题。

### 6. 活物品隔离

所有组件在处理物品时检查 `LivingItemManager.isLivingItem()`：
- `ItemTransferComponent`：不传输活物品
- `FuelConsumeComponent`：不消耗活物品作为燃料
- `ItemTransformComponent`：不熔炼活物品

### 7. 服务端权威 + 手动同步

物品数据在服务端是权威的。活物品 tick 修改 NBT 后，通过 `ContainerContext.syncSlotToClients()` 主动发送 `ClientboundContainerSetSlotPacket` 同步到客户端，因为原版 `broadcastChanges()` 无法检测自定义 DataComponent 的变化。

### 8. 光标物品操作

活漏斗方向配置时，物品被拿在光标上（`containerMenu.getCarried()`），不在任何槽位中。服务端通过 `getCarried()` 获取引用，修改后用 `ClientboundContainerSetSlotPacket(-1, stateId, -1, ...)` 同步回客户端。

### 9. 容器区块缓存

`ContainerChunkCache` 通过事件驱动（区块加载/卸载、方块放置/破坏）维护含容器的区块列表，避免每 tick 全量扫描所有区块。

### 10. GUI交互系统（声明式规则 + 统一拦截）

活物品间的GUI交互通过声明式规则驱动，而非硬编码物品判断：

- **规则声明**：`InteractionEntry(targetItem, triggerItem, button, actionId)` 在 `LivingFunctionConfig` 中注册
- **统一拦截**：所有 Screen Mixin 调用 `GuiInteractionHelper.tryInteract()`，查询 `InteractionRegistry` 匹配规则
- **服务端处理**：`GuiInteractionPacket` 携带 `actionId`，服务端通过 `InteractionRegistry.getHandler()` 查找处理器

新增交互类型只需两步：配置 `InteractionEntry` + 注册 `InteractionHandler`，无需修改任何 Mixin 代码。

### 11. 活物品图标系统（声明式配置 + 通用组件）

活物品图标采用组件化设计，通过 `LivingIconSpec` 声明式配置驱动：

**之前的问题**：每加一种活物品图标需要新建 3-4 个 Java 类（ContextAwareXxxModel、LivingXxxModelWrapper、LivingXxxItemOverrides），代码高度重复。

**解决方案**：
- `LivingIconSpec` — 声明式配置（建造者模式），描述变体列表和判断谓词
- 三个通用组件替代所有物品特定的类：`GenericLivingModelWrapper`、`GenericContextAwareModel`、`GenericLivingItemOverrides`
- `LivingIconRegistry` — 注册中心，统一处理模型注册、注入和叠加层

**核心原理**：
1. `ModelEvent.ModifyBakingResult` 在模型烘焙后注入 `GenericLivingModelWrapper`，替换原版物品模型
2. `GenericLivingItemOverrides.resolve()` 在渲染时根据 `Variant.predicate` 匹配当前变体
3. `GenericContextAwareModel.applyTransform()` 根据 `ItemDisplayContext` 切换：GUI 显示自定义图标，手持显示原版图标
4. `VariantModelStore` 桥接烘焙阶段和渲染阶段，存储变体模型的 `BakedModel` 引用

### 12. 创造模式光标物品同步

创造模式使用 `ItemPickerMenu`，光标物品是客户端虚拟的，服务端 `menu.getCarried()` 返回空。此外原版 `ClientboundContainerSetSlotPacket(containerId=-1)` 明确排除了 `CreativeModeInventoryScreen`。

解决方案分两层：
- **客户端→服务端**：`GuiInteractionPacket` 携带 `carriedTag`（光标物品NBT），仅在 `CreativeModeInventoryScreen` 下发送；服务端收到后 `menu.setCarried()` 恢复光标物品
- **服务端→客户端**：`CarriedUpdatePacket` 自定义包，绕过原版排除逻辑，直接更新客户端 `menu.setCarried()`

注意：创造模式打开容器（箱子等）时使用普通容器界面，光标由服务端管理，不需要 `carriedTag`。`resolveCarriedTag()` 仅在 `CreativeModeInventoryScreen` 下返回非空。

### 12. 创造模式 SlotWrapper 兼容

创造模式 INVENTORY 标签页中，快捷栏槽位被 `CreativeModeInventoryScreen.SlotWrapper` 包装：
- `hoveredSlot.index` = 客户端显示索引
- `SlotWrapper.target.index` = 服务端实际槽位索引

`GuiInteractionHelper.resolveContainerSlot()` 通过 `SlotWrapperAccessor` 获取 `target` 字段，统一处理此差异。`GuiInteractionPacket` 携带双索引（`slotIndex` + `containerSlot`），服务端优先通过 `containerSlot` 遍历匹配。

### 13. 跨容器传输方向映射

活漏斗在容器边界时触发跨容器传输，需要将容器GUI的二维方向（上下左右）转换为世界三维方向（东南西北）。

**方向映射算法**：
1. 以方块朝向北方为基准：UP→SOUTH(后方), DOWN→NORTH(前方), LEFT→EAST(右方), RIGHT→WEST(左方)
2. 根据方块实际朝向进行Y轴顺时针旋转（北0°、东90°、南180°、西270°）

**大箱子半箱选择**：
大箱子由LEFT和RIGHT两个半箱组成，不同边界的跨容器传输需要基于不同半箱的位置查找邻居：
- UP/DOWN方向：以RIGHT半箱位置为基准（RIGHT半箱对应GUI下半部分）
- LEFT/RIGHT方向：以RIGHT半箱位置为基准（RIGHT半箱对应GUI右半部分）

**防内部传输**：通过位置比较（而非实例比较）检测相邻容器是否为大箱子的另一半箱。`ChestBlock.getContainer()` 每次返回新的 CompoundContainer 实例，`==` 比较无效。

### 14. ExplosionComponent 双模式爆炸

活TNT爆炸根据数量自动选择模式：

| 模式 | TNT数量 | 方块破坏方式 | 掉落物 | 适用场景 |
|------|---------|-------------|--------|---------|
| 普通模式 | ≤64 | 原版 `setBlock()` + `onExplosionHit()` | 可选原版衰减/100%掉落 | 小规模精确爆炸 |
| 大当量模式 | >64 | 直接修改 `LevelChunkSection` 底层数据 | 无 | 大规模性能优化 |

**流体防爆**：
- 普通模式：检查 `FluidState.getExplosionResistance()`，`effectiveResistance >= 100.0F` 的方块（水、岩浆等）绝对不炸
- 大当量模式：由 `power <= effectiveResistance` 自然判断，威力足够大时可突破流体

**爆炸威力公式**：`radius = 4.0 × √(活TNT总数)`
- 1个活TNT → 半径4.0（等同原版TNT）
- 64个活TNT → 半径32.0
- 1728个活TNT → 半径166.0

---

## 已完成功能

### 基础设施
- [x] 活按钮 UI 与物品活化机制（`LivingButton` + `LivingTagPacket`）
- [x] DataComponent 数据持久化系统
- [x] 容器自动扫描与 tick 分发（`ContainerChunkCache` + `ContainerLivingItemHandler`）
- [x] 多活物品并行处理（按功能分组，无冲突）
- [x] 组件化架构（`ILivingComponent` + `FunctionExecutor` 工具类）
- [x] 编排器模式（`LivingOrchestrator` + 3 种内置编排器）
- [x] 功能基类（`BaseLivingFunction`：通用 tick + Tooltip）
- [x] 不可变数据模型（`Pos2D` + `SlotMapping` record）
- [x] 活物品隔离（不传输/不熔炼/不作为燃料）

### GUI交互系统
- [x] 声明式交互规则（`InteractionEntry` record + `InteractionRegistry` 注册表）
- [x] 客户端统一拦截（`GuiInteractionHelper.tryInteract()`，所有 Screen Mixin 共用）
- [x] 通用交互网络包（`GuiInteractionPacket`：slotIndex + containerSlot + actionId + carriedTag）
- [x] 创造模式光标物品同步（`CarriedUpdatePacket` 绕过原版排除逻辑）
- [x] 创造模式 SlotWrapper 兼容（`SlotWrapperAccessor` + 双索引机制）
- [x] 交互处理器注册（`InteractionHandler` 接口 + `IgniteHandler` / `IgniteCarriedHandler`）

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
- [x] 跨容器传输（活漏斗在容器边界时与相邻容器交互）
- [x] 跨容器方向映射（GUI方向 ↔ 世界方向，基于方块朝向旋转）
- [x] 大箱子跨容器传输（根据边界方向选择LEFT/RIGHT半箱作为基准位置）
- [x] 网络包同步（`HopperDirectionPacket` v2 格式）
- [x] Tooltip 实时显示当前传输方向
- [x] 传输冷却机制（基于物品数量动态调整）

### 活TNT功能
- [x] 引信倒计时（80 tick = 4秒，与原版TNT一致）
- [x] 两种点燃方式（活打火石右键活TNT / 活TNT右键活打火石）
- [x] 爆炸威力随数量缩放（radius = 4.0 × √数量）
- [x] 普通模式（≤64 TNT）：原版掉落物 + 可选100%掉落
- [x] 大当量模式（>64 TNT）：直接修改区块数据，高性能
- [x] 流体防爆（普通模式绝对防爆，大当量模式威力突破时可炸流体）
- [x] 实体伤害与击退（原版公式）
- [x] 创造模式/生存模式全兼容

### 活箱子功能
- [x] 堆叠倍增模型（1 堆叠 = 1 虚拟箱子 = 27 槽，64 堆叠 = 1728 槽）
- [x] UUID 映射管理（自动创建、拆分、合并、删除）
- [x] LRU 缓存策略（最大 200 条，5 分钟空闲超时，脏数据先保存）
- [x] 磁盘持久化（`data/living_chests/xx/uuid.dat`，分片存储）
- [x] 快速空/满判断（`_us` 已用槽位计数，O(1) 短路判断）
- [x] 漏斗自动传输（活箱子 tick 时通过漏斗推拉物品）
- [x] 跨容器传输（活箱子在容器边界时与相邻容器交互）
- [x] GUI 拆分/合并 UUID 自动分配（`ItemStackMixin` 拦截 split/grow/shrink/copyWithCount）
- [x] 跨 UUID 堆叠支持（`LivingChestStackFlags` 线程局部标志）
- [x] 被动孤儿文件清理（`cleanupOrphanedFiles()` 防止取消活化后磁盘泄漏）
- [x] 事务包装器（`ChestTransaction` 确保多次操作间原子保存）
- [x] 方块放置自动填充（生存模式消耗头部UUID + 填充物品到实体箱子，创造模式保留UUID）
- [x] 三层防护体系（split拦截 + 发射器空分发 + 投掷器选槽拦截，禁止自动化系统操作活箱子）
- [x] 铁砧重命名堆叠修复（副本比较法，只忽略UUID差异，保留名称等NBT差异）
- [x] UUID 操作方向统一（头部优先：存入、提取、拆分均从头部开始；尾部弹出：popUuid从尾部移除）
- [x] 活箱子图标（`chest_living.png`）

### 容器兼容性
- [x] 标准矩形容器（27 格箱子、54 格大箱子）
- [x] 线性容器（5 格漏斗）
- [x] 边界检查与异常安全
- [x] 大箱子去重（避免左右两半被分别处理）

---

## 开发进展

### 当前版本: v0.6-alpha

**最近更新** (2026-07-22):
- ✅ 重构：活漏斗传输引擎引入 SlotAccessor 统一架构（extract → insert → rollback 统一流程）
- ✅ 新增：`SlotAccessor` 接口 + `PlainSlotAccessor` + `LivingChestAccessor` + `SlotAccessorFactory`
- ✅ 删除：4 个旧传输方法（`transferBetweenSlots`/`transferToLivingChest`/`transferFromLivingChest`/`transferBetweenLivingChests`），~400 行重复代码
- ✅ 修复：混搭黑白名单链传递失效（`inheritFilter` 同时继承邻居的黑白名单）
- ✅ 修复：过滤器拦截时冷却未设置导致无限循环（冷却设置移到 if(success) 外部）

**历史更新** (2026-07-20):
- ✅ 新增：方块放置自动填充（生存模式消耗头部UUID + 填充物品，创造模式保留UUID）
- ✅ 新增：三层防护体系（split拦截 + 发射器空分发 + 投掷器选槽拦截）
- ✅ 新增：铁砧重命名堆叠修复（副本比较法）
- ✅ 统一：UUID 操作方向（头部优先存取/拆分，尾部弹出）

**历史更新** (2026-07-17):
- ✅ 新增：活箱子系统（`LivingChestFunction` + `InternalStorageComponent` + `ChestTransaction`）
- ✅ 新增：UUID 映射管理（`LivingChestStackHandler`：创建、拆分、合并、标准化）
- ✅ 新增：堆叠倍增模型（1 堆叠 = 1 虚拟箱子 = 27 槽，64 堆叠 = 1728 槽）
- ✅ 新增：LRU 缓存策略（最大 200 条，5 分钟空闲超时，脏数据先保存）
- ✅ 新增：快速短路判断（`_us` 已用槽位计数，O(1) 空/满判断，避免无效传输）
- ✅ 新增：漏斗自动传输（活箱子 tick 时通过漏斗推拉物品）
- ✅ 新增：跨容器传输（活箱子在容器边界时与相邻容器交互）
- ✅ 新增：GUI 拆分/合并 UUID 自动分配（`ItemStackMixin` 拦截堆叠操作）
- ✅ 新增：跨 UUID 堆叠支持（`LivingChestStackFlags` 线程局部标志）
- ✅ 新增：被动孤儿文件清理（防止取消活化后磁盘文件泄漏）
- ✅ 新增：事务包装器（`ChestTransaction` 确保多次操作间原子保存）
- ✅ 重构：living 文件夹按职责拆分为 `function/`、`container/`、`chest/` 子包
- ✅ 修复：堆叠活箱子时漏斗只能访问到一个活箱子的问题
- ✅ 修复：UUID 创建时机从惰性初始化改为首次 tick 主动初始化
- ✅ 修复：跨包访问权限（`saveStorageState` → public static）

**历史更新** (2026-07-11):
- ✅ 新增：活TNT功能（`LivingTntFunction` + `ExplosionComponent`）
- ✅ 新增：活打火石功能（`LivingFlintAndSteelFunction`，交互触发器，无 tick 逻辑）
- ✅ 新增：GUI交互系统（`InteractionEntry` + `InteractionRegistry` + `InteractionHandler`）
- ✅ 新增：客户端统一交互工具（`GuiInteractionHelper.tryInteract()`）
- ✅ 新增：通用交互网络包（`GuiInteractionPacket`：双索引 + actionId + carriedTag）
- ✅ 新增：创造模式光标同步（`CarriedUpdatePacket` 绕过原版排除逻辑）
- ✅ 新增：创造模式 SlotWrapper 兼容（`SlotWrapperAccessor` + 双索引机制）
- ✅ 新增：ExplosionComponent 双模式爆炸（普通模式 + 大当量模式）
- ✅ 新增：流体防爆机制（普通模式绝对防爆，大当量模式威力突破时可炸流体）
- ✅ 修复：创造模式背包中活打火石无法点燃活TNT
- ✅ 修复：创造模式光标物品消失问题（`resolveCarriedTag` 仅在 `CreativeModeInventoryScreen` 下发送）
- ✅ 修复：创造模式打开容器时光标物品消失（区分背包界面和容器界面的光标管理）
- ✅ 重构：所有 Screen Mixin 统一使用 `GuiInteractionHelper.tryInteract()`，移除硬编码物品判断
- ✅ 重构：引入编排器模式（`LivingOrchestrator` + `SimpleOrchestrator` / `ProgressOrchestrator` / `FuelProgressOrchestrator`）
- ✅ 重构：提取 `BaseLivingFunction` 基类，通用 tick 编排 + Tooltip 实现
- ✅ 重构：`LivingFurnaceFunction` 从 317 行 → 82 行（-74%），只保留配置
- ✅ 重构：`LivingHopperFunction` 从 219 行 → 75 行（-66%），只保留配置 + NBT 工具
- ✅ 重构：`FunctionExecutor` 从调度器变为纯工具类，移除 `tick()` 方法和所有业务逻辑
- ✅ 重构：`DirectionModeComponent` NBT 自治（`updateStateInStack` / `readStateFromStack`）
- ✅ 重构：`LivingFunctionConfig` 新增 `withOrchestrator()` 方法，支持声明式编排器选择
- ✅ 重构：`ComponentContext` 使用 `ResolvedSlots` 替代三个独立字段，方向解析内聚到 `DirectionModeComponent`
- ✅ 重构：`ContainerContext` 新增槽位占用机制（`getOccupiedSlots` / `getStableKey`），从 FunctionExecutor 实例字段迁移
- ✅ 简化：`LivingHopperFunction.canApply()` 移除冗余的物品匹配检查
- ✅ 简化：`LivingItemFunction` 接口新增 `appendComponentTooltips()` 默认方法，消除 Tooltip 重复代码

### 历史更新 (v0.3-alpha):
- ✅ 重构：将 `TransferDirection`、`Direction2D`、`HopperModeController`、`LivingHopperInputHandler` 合并为 `DirectionModeComponent` + `LivingItemInputHandler`
- ✅ 重构：提取 `Pos2D` 和 `SlotMapping` 为独立 model 类
- ✅ 修复：活漏斗传输方向修改后 NBT/Tooltip/实际传输未同步更新的问题
- ✅ 修复：活漏斗传输功能失效（`SlotResolver` 网格宽度计算错误）
- ✅ 新增：活物品隔离（不传输/不熔炼/不作为燃料）

### 待办事项

#### 高优先级
- [ ] 更多活物品类型（活投掷器、活发射器、活酿造台等）
- [ ] 活漏斗支持过滤模式（黑白名单，指定传输槽位）
- [ ] 活熔炉 Tooltip 增强（显示工作模式、预计剩余时间）
- [ ] 活TNT 红石信号触发（容器被红石激活时自动点燃）

#### 中优先级
- [ ] 调试命令 `/livingitem info`
- [ ] 成就系统集成
- [ ] 音效差异化（不同状态的音效变化）
- [ ] 活TNT 尊重 `tntExplosionDropDecay` 游戏规则

#### 低优先级 / 未来规划
- [ ] 活物品状态切换机制（更多配置选项）
- [ ] 第三方模组适配器 API 开放
- [ ] JSON 配置文件支持（用户自定义容器规则）
- [ ] JSON 驱动的活物品注册（无需编写 Java 类）
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
2. **编排器驱动**：组件协作流程通过 `LivingOrchestrator` 定义，新活物品只需选择编排器
3. **配置驱动执行**：组件通过 `ComponentConfig` 接收参数，行为由配置决定
4. **状态完全持久化**：所有数据存储在 NBT，跟随物品迁移
5. **服务端权威**：客户端只负责输入和显示，数据修改在服务端执行后同步回客户端
6. **防御性编程**：多层边界检查，优雅降级不崩溃
7. **开放扩展**：新活物品类型只需继承 `BaseLivingFunction` + 配置组件 + 选择编排器

---

## 贡献指南

### 新增活物品类型

1. 继承 `BaseLivingFunction`
2. 创建 `LivingFunctionConfig`，声明所需组件、编排器和配置参数
3. 实现 `canApply()`、`getFunctionId()`、`getConfig()`、`getTooltipTitleKey()`
4. 在 `LivingItem.commonSetup()` 中注册功能

**完整示例（约 30 行）**：
```java
public class LivingBrewingStandFunction extends BaseLivingFunction {
    private static final LivingFunctionConfig CONFIG = new LivingFunctionConfig()
        .withFunctionId("living_brewing_stand")
        .withOrchestrator(Orchestrators.FUEL_PROGRESS)
        .addComponent(new DirectionModeComponent(Map.of(
            "input", Pos2D.LEFT, "fuel", Pos2D.DOWN, "output", Pos2D.RIGHT)))
        .addComponent(FuelConsumeComponent.class,
            ComponentConfig.of("recipe_type", RecipeType.BREWING))
        .addComponent(ProgressComponent.class,
            ComponentConfig.of("total_ticks", 400))
        .addComponent(ItemTransformComponent.class,
            ComponentConfig.of("recipe_type", RecipeType.BREWING));

    @Override protected LivingFunctionConfig getConfig() { return CONFIG; }
    @Override protected String getTooltipTitleKey() { return "tooltip.livingitem.brewing_stand.status"; }
    @Override public boolean canApply(ItemStack stack) { return stack.is(Items.BREWING_STAND) && LivingItemManager.isLivingItem(stack); }
    @Override public String getFunctionId() { return "living_brewing_stand"; }
}
```

### 新增组件

1. 实现 `ILivingComponent` 接口（`getComponentId`、`tick`、`createDefaultState`）
2. 在活物品的 `LivingFunctionConfig` 中通过 `addComponent()` 注册
3. 如需跨组件数据访问，通过 `ComponentContext.getComponentState()` 读取其他组件状态

### 新增编排器

1. 实现 `LivingOrchestrator` 接口（`orchestrate` 方法）
2. 在 `Orchestrators` 工厂类中添加常量
3. 在活物品的 `LivingFunctionConfig` 中通过 `withOrchestrator()` 选择

### 代码风格

- 使用中文注释（与项目语言一致）
- 遵循现有命名约定（Config/Component/Context/Orchestrator 后缀）
- 异常处理必须使用 try-catch 包装容器操作
- 不可变数据优先使用 Java record

---

*最后更新: 2026-07-22*
*状态: Alpha 测试阶段 - 活箱子、活熔炉、活漏斗、活TNT核心功能已完成，跨容器传输已实现，模组容器兼容（IronChests等），GUI交互系统已就绪，客户端图标系统已组件化，代码结构已按职责重构为子包，SlotAccessor 统一传输架构已实现，三层防护体系已就绪，方块放置自动填充已实现*