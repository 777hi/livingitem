The user wants me to trace all places where UUIDs are operated on (created, read, modified, deleted) in the codebase. Let me do a comprehensive search.

Let me continue reading the grep results, and also search for UUID-related operations in other files:

Let me also check the `LivingChestFunction.java` for UUID-related operations:

Now let me also check `LivingChestStackHandler.java` for UUID operations:

Let me also check `LivingChestStackFlags.java` for UUID operations:

Let me also check `ItemStackMixin.java` for UUID-related operations:

Now let me also check the `ChestTransaction.java` for UUID operations:

Let me also check the network packet and `AbstractContainerMenuMixin` for UUID operations:

Now let me also check the network packets for UUID operations:

Now I have a comprehensive picture. Let me also check the `cleanupOrphanedFiles` method I just added for UUID operations:
Now I have a complete picture. Let me organize everything:

---

## 活箱子 UUID 全链路操作清单

### 一、UUID 生命周期全景

```
┌──────────────────────────────────────────────────────────────────────┐
│                        UUID 生命周期                                  │
│                                                                      │
│  创建 ──→ 持久化(NBT) ──→ 读写(磁盘) ──→ 调整(增减) ──→ 删除(遗弃)    │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

### 二、UUID 创建（4 处）

| # | 位置 | 文件 | 触发条件 |
|---|------|------|----------|
| 1 | `tick()` 首次初始化 | [InternalStorageComponent.java:L149](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/components/InternalStorageComponent.java#L149) | 活箱子放入容器后首次 tick |
| 2 | `tick()` 堆叠数增加 | [InternalStorageComponent.java:L176](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/components/InternalStorageComponent.java#L176) | 同种活箱子合并，堆叠数变大 |
| 3 | `insertItem()` 调整 | [InternalStorageComponent.java:L488](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/components/InternalStorageComponent.java#L488) | 插入时发现 UUID 数量不足 |
| 4 | `createAndRegisterNewUuid()` | [InternalStorageComponent.java:L707](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/components/InternalStorageComponent.java#L707) | 公开 API，外部可调用 |

**底层实现**：全部调用 `WorldStorage.createAndRegister()` → `UUID.randomUUID()` + `putCache(uuid, emptySlots, true)`

---

### 三、UUID 读取（6 处）

| # | 位置 | 文件 | 用途 |
|---|------|------|------|
| 1 | `getUuids()` | [InternalStorageComponent.java:L260](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/components/InternalStorageComponent.java#L260) | 从 ComponentState 解析 UUID 列表 |
| 2 | `getStorageState()` | [LivingChestFunction.java:L360](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/LivingChestFunction.java#L360) | 从 ItemStack NBT 读取完整状态 |
| 3 | `getUuids()` 快捷方法 | [LivingChestFunction.java:L161](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/LivingChestFunction.java#L161) | 供外部调用的便捷包装 |
| 4 | `getUuids()` 工具方法 | [LivingChestStackHandler.java:L81](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/LivingChestStackHandler.java#L81) | 通过 `getStorageState()` 读取 |
| 5 | `getAllItemsForDisplay()` | [InternalStorageComponent.java:L310](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/components/InternalStorageComponent.java#L310) | GUI 显示时遍历所有 UUID |
| 6 | `getFilePath()` / `getLegacyFilePath()` | [InternalStorageComponent.java:L949](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/components/InternalStorageComponent.java#L949) | UUID → 磁盘文件路径映射 |

---

### 四、UUID 写入/持久化（3 处）

| # | 位置 | 文件 | 说明 |
|---|------|------|------|
| 1 | `saveUuids()` | [InternalStorageComponent.java:L285](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/components/InternalStorageComponent.java#L285) | UUID → `ListTag<String>` → ComponentState |
| 2 | `saveStorageState()` | [LivingChestFunction.java:L378](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/LivingChestFunction.java#L378) | ComponentState → ItemStack NBT |
| 3 | `setUuids()` | [LivingChestStackHandler.java:L89](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/LivingChestStackHandler.java#L89) | 工具方法：UUID 列表 → 标准化 → ItemStack |

---

### 五、UUID 数据读写（涉及磁盘文件的操作）

| 操作 | 方法 | 文件 |
|------|------|------|
| 加载/创建 | `getOrCreate(uuid, capacity)` | [InternalStorageComponent.java:L1028](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/components/InternalStorageComponent.java#L1028) |
| 标记脏数据 | `markDirty(uuid)` | [InternalStorageComponent.java:L1095](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/components/InternalStorageComponent.java#L1095) |
| 写入磁盘 | `saveToDisk(uuid, items)` | [InternalStorageComponent.java:L1458](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/components/InternalStorageComponent.java#L1458) |
| 从磁盘加载 | `loadFromDisk(uuid)` | [InternalStorageComponent.java:L1385](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/components/InternalStorageComponent.java#L1385) |
| 检查存在 | `contains(uuid)` | [InternalStorageComponent.java:L1055](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/components/InternalStorageComponent.java#L1055) |

---

### 六、UUID 删除（5 处）

| # | 位置 | 文件 | 触发条件 |
|---|------|------|----------|
| 1 | `tick()` 堆叠数减少 | [InternalStorageComponent.java:L196](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/components/InternalStorageComponent.java#L196) | 活箱子拆分，先掉落物品再 `storage.remove(uuid)` |
| 2 | `insertItem()` 调整 | [InternalStorageComponent.java:L493](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/components/InternalStorageComponent.java#L493) | 插入时发现 UUID 过多 |
| 3 | `popUuid()` | [InternalStorageComponent.java:L729](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/components/InternalStorageComponent.java#L729) | 公开 API，弹出最后一个 UUID |
| 4 | `WorldStorage.remove()` | [InternalStorageComponent.java:L1067](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/components/InternalStorageComponent.java#L1067) | 底层实现：清缓存 + 删磁盘文件 |
| 5 | `cleanupOrphanedFiles()` | [InternalStorageComponent.java:L1376](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/components/InternalStorageComponent.java#L1376) | 被动清理孤儿空文件 |

---

### 七、UUID 拆分/合并（玩家 GUI 操作，3 处）

| # | 位置 | 文件 | 触发条件 |
|---|------|------|------|
| 1 | `onSplitReturn` | [ItemStackMixin.java:L101](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/mixin/ItemStackMixin.java#L101) | 右键拿起一半，UUID 按比例拆分 |
| 2 | `onGrow` + `onShrink` 配对 | [ItemStackMixin.java:L157](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/mixin/ItemStackMixin.java#L157)/[L226](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/mixin/ItemStackMixin.java#L226) | 右键合并/漏斗传输，UUID 自动转移 |
| 3 | `onCopyWithCount` | [ItemStackMixin.java:L271](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/mixin/ItemStackMixin.java#L271) | 右键拖拽分发，UUID 拆分 |

---

### 八、UUID 缓存淘汰（无删除，仅卸载）

| 操作 | 位置 | 说明 |
|------|------|------|
| `cleanupIdle()` | [InternalStorageComponent.java:L1320](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/components/InternalStorageComponent.java#L1320) | 5分钟未访问 → 从缓存移除（脏数据先保存） |
| LRU 淘汰 | [InternalStorageComponent.java:L1348](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/components/InternalStorageComponent.java#L1348) | 缓存超过 200 条 → 淘汰最旧的 |

---

### 九、UUID 泄漏路径（已知风险）

| 场景 | 泄漏内容 | 后果 |
|------|----------|------|
| 取消活化 | NBT 中 UUID 被 `remove()` 丢弃，磁盘文件残留 | 孤儿文件，`cleanupOrphanedFiles()` 可清理 |
| `popUuid()` 未处理物品 | 直接删 UUID 和磁盘文件，物品丢失 | 调用方需先取出物品 |
| 堆叠数减少时未掉落 | tick 中已处理（先掉落物品再删 UUID） | ✅ 安全 |

---

### 关键文件速查

| 文件 | 职责 |
|------|------|
| [InternalStorageComponent.java](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/core/components/InternalStorageComponent.java) | UUID 创建/读写/删除/缓存/磁盘 IO 全部底层实现 |
| [LivingChestFunction.java](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/LivingChestFunction.java) | 对外 API 层，封装 `getUuids`/`insertItem`/`extractItem` |
| [LivingChestStackHandler.java](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/LivingChestStackHandler.java) | UUID 列表标准化/合并/拆分/数据校验 |
| [ItemStackMixin.java](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/living/mixin/ItemStackMixin.java) | 拦截 `split`/`grow`/`shrink`/`copyWithCount` 实现 UUID 自动分配 |
| [LivingItem.java](file:///G:/777hi/mc/mymods/livingitem-template-1.21.1/src/main/java/com/qiqi/li/LivingItem.java) | 事件入口：`onLevelSave` 触发持久化 + 孤儿清理 |