# 活箱子架构改进草稿：UUID 引用 + 服务端存储

> 状态：草稿，暂不实施，未来架构改进参考
> 日期：2026-07-25
> 前提：不考虑现有架构迁移，从零设计

---

## 一、当前架构的问题

### 1.1 核心问题：物品 NBT 随存储内容线性增长

```
当前: ItemStack → DataComponents.CONTAINER → 完整物品列表
  ├─ 27个普通物品 ≈ 1-2KB
  ├─ 27个附魔物品 ≈ 3-5KB
  ├─ 27个满载活箱子（深度1） ≈ 40-100KB
  └─ 27个满载活箱子（深度2） ≈ 1MB+
```

### 1.2 触发的连锁问题

| 问题 | 触发条件 | 后果 |
|------|---------|------|
| 网络包超限 | 单个容器内物品总NBT > 2MB | 客户端被踢出，永久无法登录 |
| 同步延迟 | 物品NBT > 100KB | 背包操作卡顿，物品不同步 |
| 存档膨胀 | 大量大NBT物品 | 保存缓慢，区块损坏风险 |
| 套娃爆炸 | 深度2+嵌套 | NBT指数增长，秒超2MB |
| Tooltip卡顿 | 大NBT物品hover | 客户端解析延迟 |

### 1.3 2MB 网络硬限制

原版 Netty 配置 `maxPayloadSize = 2097152`（2MB）。

- 单个物品 < 2MB → 正常同步
- 容器内物品总和 > 2MB → 客户端被踢
- 玩家背包物品总和 > 2MB → 玩家无法登录

**关键：限制是容器级别的，不是单个物品级别。** 27个100KB活箱子在大箱子里 = 2.7MB → 踢人。

---

## 二、UUID 引用架构设计

### 2.1 核心思想

```
物品NBT只存UUID引用（~36字节），实际数据存在服务端SavedData中。

同步时: 只发送UUID → 几十字节 → 永远不会超2MB
查看时: 客户端请求 → 服务端返回物品列表 → 按需加载
```

### 2.2 数据存储层

```java
public class LivingChestStorage extends SavedData {

    // 核心存储: UUID → 物品列表
    private final Map<UUID, NonNullList<ItemStack>> storage = new HashMap<>();

    // 字节容量追踪: UUID → 当前字节用量
    private final Map<UUID, Integer> byteUsage = new HashMap<>();

    // 最大字节容量（可配置）
    private static final int MAX_BYTES = 1048576; // 1MB

    // 获取活箱子内容（内存操作，无序列化开销）
    public List<ItemStack> getItems(UUID chestId) {
        return storage.getOrDefault(chestId, emptyList());
    }

    // 设置活箱子内容（同时更新字节用量）
    public boolean setItems(UUID chestId, List<ItemStack> items) {
        int bytes = calculateBytes(items);
        if (bytes > MAX_BYTES) return false; // 容量不足
        storage.put(chestId, NonNullList.copyOf(items));
        byteUsage.put(chestId, bytes);
        setDirty();
        return true;
    }

    // 删除活箱子数据
    public void removeChest(UUID chestId) {
        storage.remove(chestId);
        byteUsage.remove(chestId);
        setDirty();
    }

    // 计算物品列表的序列化字节大小
    private int calculateBytes(List<ItemStack> items) {
        // 方案A: 精确计算（序列化后测量）
        // 方案B: 估算（每个ItemStack按平均大小计算）
    }

    // 全局实例（存储在 overworld 的 SavedData 中）
    public static LivingChestStorage get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(...), "living_chest_storage");
    }
}
```

### 2.3 物品 NBT 变化

```
当前活箱子NBT（可能1MB+）:
{
  id: "minecraft:chest",
  count: 1,
  components: {
    "minecraft:container": { items: [ ...27个完整ItemStack... ] },
    "living_item:internal_storage": { _us: 15 },
    "living_item:uuid": "a1b2c3d4-..."
  }
}

UUID引用后（~100字节）:
{
  id: "minecraft:chest",
  count: 1,
  components: {
    "living_item:storage_id": "a1b2c3d4-...",   // 指向SavedData的引用
    "living_item:internal_storage": { _us: 15 },
    "living_item:uuid": "a1b2c3d4-..."
  }
  // 没有 minecraft:container 组件！
}
```

### 2.4 同步流程

```
=== 打开背包 ===
当前: 服务端发送所有物品NBT（含完整CONTAINER）→ 可能超2MB
改进: 服务端发送所有物品NBT（仅UUID引用）→ 几KB ✅

=== 打开配方书活箱子标签页 ===
当前: 客户端直接从CONTAINER读取 → 即时显示
改进: 客户端发送 LOAD_ALL 请求 → 服务端返回所有活箱子内容摘要 → 客户端缓存并显示

=== 点击活箱子物品 ===
当前: 客户端直接操作CONTAINER → 发送DEPOSIT/WITHDRAW包
改进: 客户端发送DEPOSIT/WITHDRAW包(携带UUID) → 服务端操作SavedData → 返回更新后的内容

=== 关闭标签页 ===
当前: 无操作
改进: 客户端丢弃缓存 → 释放内存
```

### 2.5 客户端缓存策略

```java
// 客户端缓存: UUID → 物品列表
// 生命周期: 打开配方书标签页时加载，关闭时清除
public class ClientChestCache {
    private static final Map<UUID, List<ItemStack>> cache = new HashMap<>();

    // 服务端推送更新时调用
    public static void update(UUID chestId, List<ItemStack> items) {
        cache.put(chestId, items);
    }

    // 关闭配方书时调用
    public static void clear() {
        cache.clear();
    }

    // Tooltip查询（可能为空，需要异步请求）
    public static List<ItemStack> getIfPresent(UUID chestId) {
        return cache.get(chestId);
    }
}
```

---

## 三、字节容量限制

### 3.1 双约束模型

```
存入条件:
  1. 有空槽位（或可合并的同类物品）  ← 槽位约束（UI渲染需要）
  2. 当前字节用量 + 新物品字节 ≤ 1MB  ← 字节约束（安全上限）
  3. 不能把自己放进自己              ← 自引用约束
```

### 3.2 容量计算

```java
// 精确计算物品序列化后的字节大小
public static int calculateItemBytes(ItemStack stack, HolderLookup.Provider registries) {
    CompoundTag tag = (CompoundTag) stack.saveOptional(registries);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (DataOutputStream dos = new DataOutputStream(baos)) {
        NbtIo.write(tag, dos);
    }
    return baos.size();
}

// 活箱子当前总字节用量
public static int getCurrentBytes(UUID chestId) {
    return byteUsage.getOrDefault(chestId, 0);
}
```

### 3.3 1MB 容量下的实际存储能力

| 物品类型 | 单个大小 | 27个总计 | 占1MB |
|----------|---------|---------|-------|
| 普通方块 | 50B | 1.3KB | 0.1% |
| 附魔物品 | 150B | 4KB | 0.4% |
| 满潜影盒 | 2KB | 54KB | 5% |
| 满活箱子（普通物品） | 1.5KB | 40KB | 4% |
| 满活箱子（附魔物品） | 4KB | 108KB | 10% |
| 深度1套娃（满活箱子） | 40KB | 1.08MB | 103% ← 超限！ |

**结论：1MB字节容量对正常使用几乎无感，但天然阻止深度套娃。**

### 3.4 容量显示

```
Tooltip: 存储: 256KB / 1MB (15/27槽)
配方书: 容量进度条 [████████░░░░░░░░░░░░] 256KB/1MB
```

---

## 四、关键难点与解决方案

### 4.1 物品复制（创造模式/命令）

```
问题: ItemStack.copy() → 两个物品共享同一个UUID → 数据冲突

解决: Mixin ItemStack.copy()
  @Inject(method = "copy", at = @At("RETURN"))
  private void onCopy(CallbackInfoReturnable<ItemStack> cir) {
      ItemStack original = (ItemStack)(Object)this;
      ItemStack copy = cir.getReturnValue();
      UUID originalId = getStorageId(original);
      if (originalId != null) {
          UUID newId = UUID.randomUUID();
          setStorageId(copy, newId);
          // 深拷贝数据到新UUID
          LivingChestStorage.get(server).deepCopy(originalId, newId);
      }
  }
```

### 4.2 客户端无法直接读取内容

```
问题: 客户端只有UUID，没有实际物品数据
影响:
  - Tooltip 无法显示活箱子内容预览
  - 配方书标签页无法直接遍历物品
  - Shift+左键存入时不知道活箱子是否满

解决: 服务端主动同步摘要
  打开背包时:
    服务端扫描背包中所有活箱子UUID
    → 将每个活箱子的内容摘要（物品ID列表+数量+字节用量）打包发送
    → 客户端缓存，用于Tooltip和配方书
  物品变动时:
    服务端推送更新（仅变更的UUID对应的数据）
```

### 4.3 跨维度/跨世界

```
问题: SavedData 是每个世界独立的
      玩家从主世界带活箱子去下界 → 数据在哪？

解决: 全局存储
  LivingChestStorage 始终存储在 overworld 的 SavedData 中
  所有维度通过 server.overworld() 访问同一个实例
  数据不随维度变化
```

### 4.4 数据清理（垃圾回收）

```
问题: 活箱子被销毁 → UUID永远留在SavedData中 → 内存泄漏

解决: 定期扫描清理
  每5分钟（6000 tick）扫描一次:
    1. 遍历所有已加载的玩家背包、容器、地面实体
    2. 收集所有活箱子UUID → 存入 Set<UUID> activeIds
    3. 遍历 SavedData 的 storage.keySet()
    4. 不在 activeIds 中的UUID → 删除
    5. 记录清理日志

  注意: 未加载区块中的活箱子UUID不能清理
    → 需要扫描区块存档或标记"最近确认存在"的时间戳
    → 简化方案: 只清理超过24小时未被任何tick引用的UUID
```

### 4.5 网络包设计

```
新增网络包:

1. ClientboundChestContentsPacket (服务端→客户端)
   - UUID chestId
   - List<ItemStack> items
   - int byteUsage
   - int maxBytes
   用途: 打开配方书时推送活箱子内容

2. ClientboundChestSummaryPacket (服务端→客户端)
   - Map<UUID, ChestSummary> summaries
   - ChestSummary: { List<ItemId> items, int byteUsage, boolean isFull }
   用途: 打开背包时推送所有活箱子摘要（用于Tooltip）

3. ServerboundChestRequestPacket (客户端→服务端)
   - UUID chestId
   用途: 客户端请求特定活箱子的完整内容
```

---

## 五、架构对比

| 维度 | 当前架构 | UUID引用架构 |
|------|---------|-------------|
| 物品NBT大小 | 可能1MB+ | ~100字节 |
| 网络同步 | 全量发送，可能超2MB | 只发UUID，几KB |
| 客户端读取 | 直接读CONTAINER | 需网络请求 |
| 套娃安全 | 无保护 | 字节容量天然限制 |
| 存档结构 | 分散在各物品中 | 集中在SavedData |
| 物品复制 | 自动深拷贝 | 需要特殊处理 |
| 数据丢失风险 | 物品丢=数据丢 | UUID丢失=数据丢失 |
| 改动量 | - | 大（几乎所有活箱子代码） |

---

## 六、实施路线（未来）

### Phase 1: 字节容量限制（小改动，可立即实施）
- InternalStorageComponent 添加字节计算
- insertItem() 添加容量检查
- Tooltip 显示字节用量
- Config 添加 maxStorageBytes

### Phase 2: UUID引用 + SavedData（大改动，需要完整测试）
- 新增 LivingChestStorage (SavedData)
- InternalStorageComponent 改为读写 SavedData
- 移除 CONTAINER 组件依赖
- 新增客户端缓存和网络包
- ItemStack.copy() Mixin 处理UUID复制
- 数据迁移逻辑（CONTAINER → SavedData）

### Phase 3: 优化（持续改进）
- 客户端缓存策略优化
- 垃圾回收机制
- 容量显示UI优化
- 配置项完善

---

## 七、NBT大小风险参考

### 7.1 单个物品NBT达到1MB的后果

| 层面 | 后果 | 严重程度 |
|------|------|---------|
| 网络同步 | 背包同步包暴涨，延迟/丢包 | 🔴 致命（多人） |
| 跨容器嵌套 | 多个1MB物品叠加 > 2MB → 踢人 | 🔴 致命 |
| 服务端性能 | 序列化/反序列化阻塞主线程 | 🟡 严重 |
| GC压力 | 频繁创建大NBT对象 | 🟡 严重 |
| 存档膨胀 | playerdata/区块文件暴涨 | 🟡 中等 |
| 客户端渲染 | Tooltip/GUI解析延迟 | 🟢 轻微 |

### 7.2 2MB vs 1MB 边界

- **< 1MB**: 正常运行，但需注意容器内物品叠加
- **1MB ~ 2MB**: 严重卡顿、同步错乱、存档膨胀
- **> 2MB**: 原版硬限制，直接断开连接、无法加载存档、永久锁玩家