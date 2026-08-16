<!-- markdownlint-disable -->

# 活物品框架重构 —— 接口化设计

> **状态**: ✅ 已完成
> **日期**: 2026-08-17

---

## 1. 解决的问题

### 重构前

新增一个活物品（带容器级数据 + WASD 朝向），需要修改 **6 个文件**：

| 文件 | 原因 |
|------|------|
| `LivingItemManager.java` | DataComponent 注册 + getter/setter |
| `LivingItem.java` | 手动注册 Function |
| `ContainerLivingItemHandler.java` | 硬编码容器级数据创建+计算 |
| `TickContext.java` | 新增容器级数据字段 |
| `LivingItemInputHandler.java` | 硬编码物品类型判断 |
| `ServerPacketHandler.java` | 硬编码方向处理分支 |

### 重构后

新增任何活物品只需改 **2 个文件**：

| 文件 | 原因 |
|------|------|
| `LivingItemManager.java` | DataComponent 注册 + getter/setter |
| `LivingItem.java` | 手动注册 Function |

其余 4 个核心文件**永久锁定，不再修改**。

---

## 2. 新增接口

### 2.1 `HasDirection` — 声明 WASD 朝向配置

```java
package com.qiqi.li.living.api;

public interface HasDirection {
    // 需要几个按键（1 键 = 直接设置，多键 = 组合序列）
    int getDirectionKeyCount();

    // 各槽位名称（用于网络包 slotName 字段）
    String[] getDirectionSlotNames();

    // 更新指定槽位的方向
    boolean updateSlotDirection(ItemStack stack, String slotName, Pos2D direction);
}
```

**实现者**：`LivingFurnaceFunction`（3 键）、`LivingHopperFunction`（1 键）、`LivingRedstoneTorchFunction`（1 键）

**消费方**：
- `LivingItemInputHandler` — 检测 `HasDirection`，自动处理 WASD
- `ServerPacketHandler` — 检测 `HasDirection`，自动调用 `updateSlotDirection`

### 2.2 `HasContainerData` — 声明容器级数据计算

```java
package com.qiqi.li.living.api;

public interface HasContainerData {
    // 计算优先级（数字越小越先执行）
    int getPriority();

    // 容器级数据 tick
    void tickContainerData(List<SlotEntry> entries, ContainerContext ctx, TickContext tick);
}
```

**实现者**：

| 优先级 | 类 | 职责 |
|--------|-----|------|
| 0 | `LivingWaterBucketFunction` | 流体蔓延 + postTickSync |
| 1 | `LivingWaterWheelFunction` | 应力计算 + postTickSync |
| 2 | `LivingRedstoneFunction` | 红石信号传播 |
| 2 | `LivingRedstoneTorchFunction` | 红石信号传播（火把独立存在时） |

**消费方**：`ContainerLivingItemHandler.processContext()` — 遍历分组，收集 `HasContainerData` 实现者，按优先级排序后依次调用。

---

## 3. TickContext Map 扩展

```java
// 未来新增容器级数据，不再需要加字段
private final Map<Class<?>, Object> containerDataStore = new HashMap<>();

public <T> T getContainerData(Class<T> type) { ... }
public <T> void setContainerData(Class<T> type, T data) { ... }
```

现有字段 `fluidData`、`stressData`、`redstoneData` 保持不变，保证向后兼容。新数据类型可直接使用 Map。

---

## 4. 新增活物品步骤

```
1. 在 LivingItemManager.java 注册 DataComponent + getter/setter
2. 在 LivingItem.java 注册 Function
3. 新建 XxxFunction implements LivingItemFunction
     ├── (可选) implements HasDirection      → 自动获得 WASD 朝向
     └── (可选) implements HasContainerData  → 自动获得容器级数据计算
```

无需修改任何其他文件。