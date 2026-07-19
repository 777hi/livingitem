# 🌐 活物品容器兼容性完整指南

## 📋 目录
- [五种方案总览](#-五种方案总览)
- [快速开始](#-快速开始)
- [详细方案说明](#-详细方案说明)
  - [方案A：懒加载缓存](#方案a懒加载缓存推荐)
  - [方案B：适配器模式](#方案b适配器模式特殊形状容器)
  - [方案C：声明式配置](#方案c声明式配置已知模组列表)
  - [方案D：运行时验证](#方案d运行时验证最安全但性能较低)
  - [方案E：混合架构](#方案e混合架构生产环境推荐)
- [最佳实践](#-最佳实践)
- [性能优化建议](#-性能优化建议)
- [常见问题FAQ](#-常见问题faq)

---

## 🎯 五种方案总览

```
┌─────────────────────────────────────────────────────────────┐
│                    容器兼容性解决方案                         │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌───────────┐   ┌───────────┐   ┌───────────┐            │
│  │  Layer 1  │ → │  Layer 2  │ → │  Layer 3  │            │
│  │   缓存    │   │   配置    │   │   适配器  │            │
│  │ (0ms)     │   │ (0.001ms) │   │ (0.01ms)  │            │
│  └───────────┘   └───────────┘   └───────────┘            │
│         ↓               ↓               ↓                  │
│  ┌───────────┐   ┌───────────┐   ┌───────────┐            │
│  │  Layer 4  │ → │  Layer 5  │ → │  安全返回  │            │
│  │  标准逻辑 │   │ 运行时验证│   │           │            │
│  │ (0.1ms)   │   │ (1ms)     │   │           │            │
│  └───────────┘   └───────────┘   └───────────┘            │
│                                                             │
│  优先级：从上到下，越上层越快，越下层越安全                   │
└─────────────────────────────────────────────────────────────┘
```

| 方案 | 适用场景 | 性能 | 兼容性 | 复杂度 | 推荐度 |
|------|---------|------|--------|--------|--------|
| **A: 缓存** | 通用场景 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| **B: 适配器** | 特殊形状 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| **C: 配置** | 已知模组 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐ |
| **D: 验证** | 未知容器 | ⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| **E: 混合** | 生产环境 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |

---

## 🚀 快速开始

### 最简集成（3步）

#### 步骤1：使用混合解析器（推荐）

```java
// 在FunctionExecutor中替换原有逻辑
public void tick(Container container, int slot, ItemStack stack, 
                 LivingFunctionConfig config, Level level) {
    
    // 使用混合解析器（自动选择最优策略）
    var result = HybridContainerResolver.INSTANCE.resolve(container, slot, config);
    
    if (!result.isValid()) {
        return;  // 无法安全解析，跳过本次tick
    }
    
    int[] slots = result.getSlots();
    int inputSlot = slots[0];
    int fuelSlot = slots[1];
    int outputSlot = slots[2];
    
    // 正常的业务逻辑...
}
```

#### 步骤2：添加常用模组支持（可选）

```java
// 在mod初始化阶段
@Mod.EventHandler
public void init(FMLInitializationEvent event) {
    // 注册Iron Chests支持
    ContainerCompatibilityConfig.register(
        new ResourceLocation("ironchests", "iron_chest"),
        ContainerRule.builder()
            .containerSize(45)
            .layoutType(RECTANGULAR_9X5)
            .validHostSlots(range(0, 44))
            .directionMapping(LEFT, -1)
            .directionMapping(RIGHT, 1)
            .directionMapping(UP, -9)
            .directionMapping(DOWN, 9)
            .build()
    );
    
    // 注册Storage Drawers支持
    AdapterRegistry.getInstance().register(new StorageDrawersAdapter());
}
```

#### 步骤3：监控与调优（可选）

```java
// 定期检查缓存效率
@SubscribeEvent
public void onServerTick(TickEvent.ServerTickEvent event) {
    if (event.phase == Phase.END && event.server.getTickCount() % 6000 == 0) {  // 每5分钟
        var cacheStats = ContainerCacheManager.getInstance().getStats();
        var validationStats = RuntimeContainerValidator.INSTANCE.getStats();
        
        if (cacheStats.hitRate() < 0.8) {
            LOGGER.warn("Low cache hit rate: {:.2f}%", cacheStats.hitRate() * 100);
        }
    }
}
```

---

## 📖 详细方案说明

### 方案A：懒加载缓存（推荐⭐⭐⭐⭐⭐）

#### 工作原理

```
首次访问新类型容器:
ContainerCacheManager.getOrCreate(container, config)
    ↓
生成指纹: "size=27|type=Chest|maxStack=64"
    ↓
查找缓存 → 未命中 (MISS)
    ↓
计算完整映射表:
  hostSlot=0 → input=-1(无效), fuel=9, output=1
  hostSlot=1 → input=0, fuel=10, output=2
  ...
  hostSlot=26 → input=25, fuel=-1(无效), output=-1(无效)
    ↓
缓存结果并返回
    ↓
后续相同类型容器访问 → 命中 (HIT) ✅ ~0ms延迟
```

#### 核心代码示例

```java
// 获取槽位映射（自动处理缓存）
ContainerCacheManager.ContainerSlotMapping mapping = 
    ContainerCacheManager.getInstance().getOrCreateMapping(container, config);

if (mapping != null) {
    // O(1)时间复杂度的查询
    int[] slots = mapping.resolveSlots(hostSlot);
    
    if (slots != null) {
        int inputSlot = slots[0];   // 已经过验证的安全索引
        int fuelSlot = slots[1];
        int outputSlot = slots[2];
        
        // 安全使用...
    }
}
```

#### 优势

✅ **零配置**：无需预先了解容器特性  
✅ **自动适应**：任何大小的容器都能处理  
✅ **高性能**：相同类型的容器共享缓存  
✅ **内存友好**：弱引用+LRU淘汰机制  
✅ **统计完善**：命中率、淘汰数等监控指标  

#### 局限性

❌ 首次访问有计算开销（~1ms）  
❌ 不理解容器的语义（只看大小和类名）  
❌ 对动态变化的容器可能需要手动失效  

---

### 方案B：适配器模式（特殊形状容器）

#### 适用场景

| 容器类型 | 形状 | 标准方法是否适用 |
|---------|------|----------------|
| 箱子 (27格) | 3×9矩形 | ✅ 适用 |
| 大箱子 (54格) | 6×9矩形 | ✅ 适用 |
| **漏斗 (5格)** | **线性** | ❌ **不适用** |
| **储物抽屉** | **不规则** | ❌ **不适用** |
| **自定义GUI** | **任意** | ❌ **可能不适用** |

#### 如何创建自定义适配器

```java
// 示例：为Storage Drawers模组创建适配器
public class StorageDrawersAdapter implements ContainerAdapter {

    @Override
    public boolean supports(Container container) {
        return container.getClass().getName().contains("storagedrawers");
    }

    @Override
    public ContainerLayout getLayout(Container container) {
        // 储物抽屉的布局是特殊的
        Map<Integer, SlotInfo> details = new HashMap<>();
        
        // 抽屉1: slot 0-1 (主槽位+升级槽位)
        details.put(0, new SlotInfo(0, 0, 0, true, new Direction2D[]{RIGHT}));
        details.put(1, new SlotInfo(1, 0, 1, false, new Direction2D[]{LEFT}));
        
        // 抽屉2: slot 2-3
        details.put(2, new SlotInfo(2, 1, 0, true, new Direction2D[]{LEFT, RIGHT}));
        details.put(3, new SlotInfo(3, 1, 1, false, new Direction2D[]{LEFT}));
        
        return new ContainerLayout(
            container.getContainerSize(),
            LayoutType.IRREGULAR,
            2, 4,  // 自定义行列
            details
        );
    }

    @Override
    public int resolveSlot(Container container, int hostSlot, Direction2D direction) {
        // 自定义的解析逻辑
        switch (direction) {
            case LEFT:
                if (hostSlot % 2 == 1) return hostSlot - 1;  // 升级槽→主槽
                break;
            case RIGHT:
                if (hostSlot % 2 == 0 && hostSlot < container.getContainerSize() - 1) {
                    return hostSlot + 1;  // 主槽→升级槽
                }
                break;
            default:
                return -1;  // 不支持其他方向
        }
        return -1;
    }

    @Override
    public boolean isValidHostPosition(Container container, int slot) {
        // 只有偶数号槽位（主槽位）可以放置活物品
        return slot >= 0 && slot < container.getContainerSize() && slot % 2 == 0;
    }
}

// 注册适配器
AdapterRegistry.getInstance().register(new StorageDrawersAdapter());
```

#### 适配器发现机制

**方式1：ServiceLoader（推荐）**

```
META-INF/services/
  └── com.qiqi.li.living.core.adapters.ContainerAdapter
      内容: com.example.mod.MyCustomAdapter
```

**方式2：API调用**

```java
// 其他模组在初始化时调用
@Mod("my_mod")
public class MyMod {
    @SubscribeEvent
    public void onFMLInit(FMLInitializationEvent event) {
        AdapterRegistry.getInstance().register(new MyCustomAdapter());
    }
}
```

---

### 方案C：声明式配置（已知模组列表）

#### 配置文件格式

```json
// livingitem_containers.json
{
  "containers": [
    {
      "id": "minecraft:chest",
      "containerSize": 27,
      "layoutType": "RECTANGULAR_9X3",
      "validHostSlots": [0, 1, 2, "...", 26],
      "directionMappings": {
        "LEFT": -1,
        "RIGHT": 1,
        "UP": -9,
        "DOWN": 9
      },
      "edgeBehavior": "INVALIDATE",
      "description": "标准箱子"
    },
    {
      "id": "ironchests:iron_chest",
      "containerSize": 45,
      "layoutType": "RECTANGULAR_9X5",
      "validHostSlots": [0, 1, 2, "...", 44],
      "directionMappings": {
        "LEFT": -1,
        "RIGHT": 1,
        "UP": -9,
        "DOWN": 9
      },
      "edgeBehavior": "INVALIDATE",
      "description": "铁箱子模组"
    },
    {
      "id": "minecraft:hopper",
      "containerSize": 5,
      "layoutType": "LINEAR",
      "validHostSlots": [1, 2, 3],
      "directionMappings": {
        "LEFT": -1,
        "RIGHT": 1,
        "UP": -1,
        "DOWN": -1
      },
      "edgeBehavior": "INVALIDATE",
      "description": "漏斗"
    }
  ],
  "globalSettings": {
    "enableRuntimeValidation": false,
    "maxCacheEntries": 1000,
    "logUnknownContainers": true
  }
}
```

#### 加载配置

```java
@Mod.EventHandler
public void preInit(FMLPreInitializationEvent event) {
    File configFile = event.getModConfigurationDirectory()
        .toPath()
        .resolve("livingitem_containers.json")
        .toFile();
    
    if (configFile.exists()) {
        ContainerCompatibilityConfig.loadFromJson(configFile.getAbsolutePath());
        LOGGER.info("Loaded custom container compatibility configuration");
    }
}
```

#### 优势

✅ **用户可编辑**：非程序员也能修改配置  
✅ **热重载**：不需要重启游戏即可更新  
✅ **精确控制**：可以针对特定版本微调行为  
✅ **易于调试**：配置即文档  

#### 局限性

❌ 需要提前知道要支持的模组  
❌ 维护成本高（每个新模组都要添加）  
❌ 无法处理完全未知的容器  

---

### 方案D：运行时验证（最安全）

#### 使用场景

```java
// 场景1：调试阶段排查崩溃问题
HybridContainerResolver.INSTANCE.setRuntimeValidation(true);

// 场景2：处理来自不可信来源的容器
if (isUntrustedContainer(container)) {
    var result = RuntimeContainerValidator.INSTANCE.resolveAndValidate(
        container, hostSlot, LEFT, DOWN, RIGHT
    );
    
    if (!result.isSuccess()) {
        logSecurityEvent(result);
        return;
    }
}

// 场景3：作为最终安全网（即使前面都失败了）
try {
    // 尝试正常流程
    ...
} catch (Exception e) {
    // 回退到最安全的验证
    var safeResult = runtimeValidator.resolveAndValidate(...);
    if (!safeResult.isSuccess()) {
        return;  // 放弃本次操作
    }
    // 使用验证通过的结果继续
}
```

#### 验证内容清单

```
RuntimeContainerValidator验证项目：

✅ 1. 容器完整性检查
   ├─ null检查
   ├─ 类型检查
   └─ 基本方法可用性测试

✅ 2. 容器大小合理性
   ├─ 范围检查: 0 < size ≤ 1024
   ├─ 异常值检测: 负数、超大值
   └─ 动态变化检测: 多次获取是否一致

✅ 3. 主槽位有效性
   ├─ 范围: 0 ≤ slot < size
   └─ 边界: 不是负数或超出范围

✅ 4. 解析后槽位验证
   ├─ 每个方向解析后的索引有效
   ├─ 不超过容器边界
   └─ 可以实际访问（读测试）

✅ 5. 冲突检测
   └─ input/fuel/output三个槽位互不相同

✅ 6. 访问安全性测试
   ├─ 读操作测试: getItem(slot)
   ├─ 写操作测试: setItem(slot, EMPTY)
   └─ 无异常抛出
```

#### 性能影响评估

```
每次调用的额外开销：

Layer 1-4 (无验证): ~0.01ms
Layer 5 (完整验证): ~1ms (+100倍!)

典型服务器负载：
- 10个活物品同时工作
- 每秒20 tick
- 每tick每个活物品调用1次

总开销对比：
- 无验证: 10 × 20 × 0.01ms = 2ms/s (可忽略)
- 有验证: 10 × 20 × 1ms = 200ms/s (明显卡顿!)

建议：
- 仅在开发/调试阶段启用
- 生产环境仅在首次遇到新容器类型时启用一次
- 或仅对"可疑"容器启用
```

---

### 方案E：混合架构（生产环境推荐）

#### 架构设计图

```
                    ┌──────────────────────┐
                    │  FunctionExecutor    │
                    │  (业务逻辑层)        │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │ HybridContainerResolver│ ← 统一入口
                    └──────────┬───────────┘
                               │
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
     ┌─────────────┐  ┌─────────────┐  ┌─────────────┐
     │ CacheManager│  │ ConfigSystem │  │AdapterRegistry│
     │  (Layer 1)  │  │  (Layer 2)  │  │  (Layer 3)  │
     └──────┬──────┘  └──────┬──────┘  └──────┬──────┘
            │                │                │
            ▼                ▼                ▼
     ┌─────────────┐  ┌─────────────┐  ┌─────────────┐
     │ SlotResolver│  │ SlotResolver│  │ Custom Logic │
     │  (Layer 4)  │  │  (Layer 4)  │  │  (Layer 4)  │
     └──────┬──────┘  └──────┬──────┘  └──────┬──────┘
            │                │                │
            └────────────────┼────────────────┘
                             ▼
                    ┌──────────────────────┐
                    │RuntimeContainerValidator│ ← 可选(Layer 5)
                    │  (最终安全网)          │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │   ResolveResult       │ ← 统一结果格式
                    │ {valid, slots, source}│
                    └──────────────────────┘
```

#### 使用示例

```java
public class OptimizedLivingFurnaceExecutor {

    private final HybridContainerResolver resolver = HybridContainerResolver.INSTANCE;

    public void tick(ContainerContext ctx, int hostSlot, ItemStack stack,
                     LivingFunctionConfig config, Level level) {
        
        // 一步完成所有解析和验证
        var result = resolver.resolve(ctx.getContainer(), hostSlot, config);
        
        switch (result.getSource()) {
            case CACHE -> handleCachedResult(ctx, result);       // 最快路径
            case CONFIG -> handleConfigResult(ctx, result);      // 快速路径
            case ADAPTER -> handleAdapterResult(ctx, result);    // 中等速度
            case STANDARD -> handleStandardResult(ctx, result);  // 较慢
            case INVALID -> skipTick(ctx, result);               // 跳过
        }
    }

    private void handleCachedResult(ContainerContext ctx, ResolveResult result) {
        // 直接使用，无需额外验证
        executeFurnaceLogic(ctx, result.getSlots());
    }

    private void handleConfigResult(ContainerContext ctx, ResolveResult result) {
        // 可选：将此结果加入缓存以加速后续访问
        executeFurnaceLogic(ctx, result.getSlots());
    }

    private void handleAdapterResult(ContainerContext ctx, ResolveResult result) {
        // 适配器已处理特殊情况，直接使用
        executeFurnaceLogic(ctx, result.getSlots());
    }

    private void handleStandardResult(ContainerContext ctx, ResolveResult result) {
        // 标准逻辑，可能需要额外的安全检查
        if (validateAtRuntime(ctx, result.getSlots())) {
            executeFurnaceLogic(ctx, result.getSlots());
        } else {
            logWarning("Standard resolution failed validation");
        }
    }

    private void skipTick(ContainerContext ctx, ResolveResult result) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Skipping tick for invalid resolution: {}", result.getReason());
        }
    }
}
```

---

## 💡 最佳实践

### 1️⃣ 分层防御原则

```java
// ✅ 好的做法：即使使用了缓存，仍然保留基本检查
int[] slots = cachedMapping.resolveSlots(hostSlot);
if (slots != null) {
    // 信任缓存的槽位，但仍做最小化验证
    for (int slot : slots) {
        if (slot < 0 || slot >= containerSize) {
            invalidateCache();  // 缓存可能已过期
            return;
        }
    }
    // 继续执行...
}

// ❌ 坏的做法：完全不做任何检查
int[] slots = cachedMapping.resolveSlots(hostSlot);
// 直接使用，如果缓存损坏就崩溃了！
```

### 2️⃣ 渐进式增强策略

```
发布阶段：

v1.0 (MVP):
  ├── 只实现方案A (缓存)
  ├── 支持原版容器 (箱子、大箱)
  └── 目标：核心功能可用

v1.1 (稳定):
  ├── 添加方案E (混合架构)
  ├── 加入方案D作为可选开关
  └── 目标：生产级稳定性

v1.2 (扩展):
  ├── 实现方案B核心接口
  ├── 为漏斗等常见容器提供内置适配器
  └── 目标：覆盖80%常见场景

v1.3 (生态):
  ├── 开放适配器API
  ├── 提供ServiceLoader集成
  └── 目标：支持第三方模组接入

v2.0 (成熟):
  ├── 实现方案C (JSON配置)
  ├── 提供GUI配置工具
  └── 目标：用户友好的完全兼容
```

### 3️⃣ 监控与告警

```java
@SubscribeEvent
public void onServerTick(TickEvent.ServerTickEvent event) {
    if (event.phase == Phase.END && shouldReportStats()) {
        reportPerformanceMetrics();
    }
}

private void reportPerformanceMetrics() {
    var cacheStats = ContainerCacheManager.getInstance().getStats();
    
    // 命中率过低警告
    if (cacheStats.hitRate() < 0.7 && cacheStats.misses() > 10000) {
        LOGGER.warn("""
            Performance Alert!
            Cache hit rate is low: {:.2f}%
            This may indicate many different container types are being used.
            Consider adding explicit configurations or adapters.
            """, cacheStats.hitRate() * 100);
    }
    
    // 内存占用过高警告
    if (cacheStats.cacheSize() > 500) {
        LOGGER.warn("Large cache size detected: {} entries", cacheStats.cacheSize());
    }
}
```

---

## ⚡ 性能优化建议

### 1. 缓存预热

```java
// 在世界加载时预计算常见容器
@SubscribeEvent
public void onWorldLoad(LevelEvent.Load event) {
    if (event.getLevel() instanceof ServerLevel serverLevel) {
        // 预热：为标准箱子和大箱子生成缓存
        warmUpCacheForStandardContainers();
    }
}

private void warmUpCacheForStandardContainers() {
    LivingFunctionConfig standardFurnaceConfig = createStandardConfig();
    
    // 模拟标准箱子
    mockContainerAndWarmCache(27, standardFurnaceConfig);
    
    // 模拟大箱子
    mockContainerAndWarmCache(54, standardFurnaceConfig);
    
    LOGGER.info("Cache pre-warming completed");
}
```

### 2. 批量操作优化

```java
// 如果一个容器中有多个活物品，批量处理它们的映射
public void processMultipleLivingItems(List<LivingItemEntry> items, Container container) {
    // 一次性获取整个容器的映射
    ContainerCacheManager.ContainerSlotMapping mapping = 
        cacheManager.getOrCreateMapping(container, config);
    
    if (mapping == null) return;
    
    // 批量查询所有活物品的位置
    for (LivingItemEntry item : items) {
        int[] slots = mapping.resolveSlots(item.slotIndex());
        if (slots != null) {
            processSingleItem(item, slots);
        }
    }
}
```

### 3. 异步预计算（高级）

```java
// 对于大型容器，可以在后台线程预计算
public CompletableFuture<ContainerSlotMapping> prefetchMappingAsync(Container container) {
    return CompletableFuture.supplyAsync(() -> {
        return cacheManager.getOrCreateMapping(container, config);
    }, asyncExecutor);
}

// 在活物品放入容器时就触发预计算
public void onItemPlaced(Container container, int slot) {
    if (isLivingItem(container.getItem(slot))) {
        prefetchMappingAsync(container).thenAccept(mapping -> {
            // 缓存已经准备好，下次同步调用时会命中
        });
    }
}
```

---

## ❓ 常见问题 FAQ

### Q1: 应该选择哪种方案？

**A**: 
- **大多数情况**：直接用**方案E（混合架构）**
- **只需要简单功能**：**方案A（缓存）**足够
- **有特殊形状容器需求**：加上**方案B（适配器）**
- **追求极致性能且容器类型固定**：**方案C（配置）**
- **调试问题时**：临时开启**方案D（验证）**

### Q2: 内存占用会很大吗？

**A**: 不会。对于最常见的场景：
- 1种容器类型（如标准箱子）：~1KB缓存
- 10种容器类型：~10KB
- 即使100种：~100KB（仍可忽略）

而且系统会自动淘汰不常用的条目。

### Q3: 如何处理AE2/RS这类虚拟存储？

**A**: 这类容器通常：
1. 不实现标准的`Container`接口
2. 槽位是动态分配的
3. 可能跨网络访问

**建议**：
- 通过**方案B（适配器）**为其提供专用逻辑
- 或者明确声明**不支持**此类容器（合理的设计决策）

### Q4: 大箱子的跨BlockEntity问题怎么处理？

**A**: 大箱子由两个独立的BlockEntity组成。解决方案：

```java
// 在ContainerRule中标记
.register("minecraft:double_chest", builder()
    .crossBlockEntitySupport(true)  // 启用特殊处理
    .edgeBehavior(Wrap)             // 允许跨越边界
    .build()
)

// 在解析器中检测并处理
private int[] resolveDoubleChestSlots(int hostSlot, ...) {
    BlockEntity primaryBE = getPrimaryBlockEntity();
    BlockEntity secondaryBE = getSecondaryBlockEntity();
    
    // 跨BlockEntity寻址
    if (shouldAccessSecondary(hostSlot, direction)) {
        return accessSecondaryContainer(secondaryBE, ...);
    }
    
    return normalResolve(primaryBE, hostSlot, ...);
}
```

### Q5: 如何测试兼容性？

**A**: 推荐的测试矩阵：

```java
@Test
void testStandardChest() { ... }           // 27格
@Test
void testDoubleChest() { ... }             // 54格
@Test
void testHopper() { ... }                  // 5格，线性
@Test
void testIronChest() { ... }               // 45格
@Test
void testStorageDrawers() { ... }          // 不规则
@Test
void testEmptyContainer() { ... }           // 边界情况
@Test
void testContainerBeingDestroyed() { ... }  // 竞态条件
@Test
void testConcurrentAccess() { ... }         // 并发安全
```

---

## 📚 总结

### 核心要点

1. **没有银弹**：每种方案都有其适用场景
2. **分层防御**：组合多种方案效果最佳
3. **渐进式采用**：从简单开始，按需增加复杂度
4. **监控驱动**：根据实际数据决定优化方向
5. **社区共建**：开放API让其他模组作者贡献适配器

### 推荐的实施路线

```
现在 → v1.0:
  ✅ 实现方案A (ContainerCacheManager)
  ✅ 修复当前崩溃问题
  
下个月 → v1.1:
  ✅ 集成方案E (HybridContainerResolver)
  ✅ 添加基础监控

未来 → v1.2+:
  ✅ 实现方案B核心框架
  ✅ 为常见模组提供适配器
  ✅ 视情况添加方案C/D
```

**记住**：完美是优秀的敌人。先让系统能工作，再逐步优化！

---

*最后更新: 2026-07-06*
*适用于: Minecraft 1.21.1 + NeoForge*