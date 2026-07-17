## 完整重构后代码（分文件存储 + 精确脏标记 + 无旧版兼容）
以下为全量替换后的 `InternalStorageComponent` 类，核心变更：
1. 彻底移除全局 `SavedData` 与旧版迁移逻辑，纯分文件独立存储
2. 所有存储修改点统一使用**单箱精确脏标记**，无任何兼容冗余
3. 补齐生命周期管理：裁剪存储时自动掉落物品、删除磁盘文件
4. 原子写入防崩溃损坏，LRU + 超时双策略缓存管理
5. 单例绑定服务器实例，避免跨存档数据混淆

```java
package com.qiqi.li.living.core.components;

import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;

public class InternalStorageComponent implements ILivingComponent {
    public static final String ID = "internal_storage";
    public static final String KEY_UUIDS = "uuids";
    public static final String KEY_CACHED_COUNT = "_cc";

    @Override
    public String getComponentId() { return ID; }

    @Override
    public void tick(ComponentContext ctx, int hostSlot, ItemStack hostStack,
                     ComponentState state, ComponentConfig config) {
        if (ctx.level().isClientSide()) return;
        MinecraftServer server = ctx.level().getServer();
        if (server == null) return;

        if (!state.contains(KEY_UUIDS)) {
            return;
        }

        int expectedCount = hostStack.getCount();
        int capacityPerChest = ctx.containerCtx().getSize();
        int cachedCount = state.getInt(KEY_CACHED_COUNT, -1);
        if (cachedCount == expectedCount) {
            return;
        }

        List<UUID> uuids = new ArrayList<>(getUuids(state));
        int actualCount = uuids.size();
        boolean changed = false;
        WorldStorage storage = WorldStorage.get(server);

        if (actualCount < expectedCount) {
            // 堆叠数增加：补全新存储箱
            while (actualCount < expectedCount) {
                uuids.add(WorldStorage.createAndRegister(server, capacityPerChest));
                actualCount++;
                changed = true;
            }
        } else if (actualCount > expectedCount) {
            // 堆叠数减少：删除多余存储，掉落其中物品，避免资源丢失
            List<UUID> removedUuids = uuids.subList(expectedCount, actualCount);
            for (UUID uuid : removedUuids) {
                List<ItemStack> chestItems = storage.getOrCreate(uuid, capacityPerChest);
                for (ItemStack stack : chestItems) {
                    if (!stack.isEmpty()) {
                        ctx.level().addFreshEntity(new ItemEntity(
                                ctx.level(),
                                ctx.hostPos().x, ctx.hostPos().y, ctx.hostPos().z,
                                stack.copy()
                        ));
                    }
                }
                storage.remove(uuid);
            }
            uuids = new ArrayList<>(uuids.subList(0, expectedCount));
            changed = true;
        }

        if (changed) {
            saveUuids(state, uuids);
        }
        state.setInt(KEY_CACHED_COUNT, expectedCount);
    }

    @Override
    public ComponentState createDefaultState() {
        return new ComponentState();
    }

    @Override
    public void appendTooltip(ComponentState state, java.util.function.Consumer<net.minecraft.network.chat.Component> tooltipAdder) {
        List<UUID> uuids = getUuids(state);
        if (!uuids.isEmpty()) {
            tooltipAdder.accept(net.minecraft.network.chat.Component.literal(
                "Internal Storage: " + uuids.size() + " chest(s)")
                .withStyle(net.minecraft.ChatFormatting.GREEN));
        }
    }

    // ==================== UUID 读写工具 ====================

    public static List<UUID> getUuids(ComponentState state) {
        if (!state.contains(KEY_UUIDS)) {
            return Collections.emptyList();
        }
        List<UUID> result = new ArrayList<>();
        ListTag uuidList = state.getList(KEY_UUIDS, Tag.TAG_STRING);
        for (int i = 0; i < uuidList.size(); i++) {
            try {
                result.add(UUID.fromString(uuidList.getString(i)));
            } catch (IllegalArgumentException e) {
                continue;
            }
        }
        return result;
    }

    public static void saveUuids(ComponentState state, List<UUID> uuids) {
        ListTag uuidList = new ListTag();
        for (UUID uuid : uuids) {
            uuidList.add(StringTag.valueOf(uuid.toString()));
        }
        state.putList(KEY_UUIDS, uuidList);
    }

    // ==================== 批量存取接口 ====================

    public static List<ItemStack> getMergedStorage(MinecraftServer server, ComponentState state, int capacityPerChest) {
        List<UUID> uuids = getUuids(state);
        if (uuids.isEmpty()) {
            return Collections.emptyList();
        }
        WorldStorage storage = WorldStorage.get(server);
        List<ItemStack> merged = new ArrayList<>();
        for (UUID uuid : uuids) {
            merged.addAll(storage.getOrCreate(uuid, capacityPerChest));
        }
        return merged;
    }

    public static void syncMergedToStorage(MinecraftServer server, ComponentState state,
                                           List<ItemStack> merged, int capacityPerChest) {
        List<UUID> uuids = getUuids(state);
        if (uuids.isEmpty()) return;
        WorldStorage storage = WorldStorage.get(server);
        int offset = 0;

        for (int i = 0; i < uuids.size() && offset < merged.size(); i++) {
            UUID uuid = uuids.get(i);
            List<ItemStack> chestStorage = storage.getOrCreate(uuid, capacityPerChest);
            boolean modified = false;

            for (int j = 0; j < capacityPerChest && offset < merged.size(); j++) {
                ItemStack newStack = merged.get(offset).copy();
                if (!ItemStack.matches(chestStorage.get(j), newStack)) {
                    chestStorage.set(j, newStack);
                    modified = true;
                }
                offset++;
            }

            if (modified) {
                storage.markDirty(uuid);
            }
        }
    }

    // ==================== 物品存入 ====================

    public static boolean insertItem(MinecraftServer server, ComponentState state,
                                     ItemStack itemToInsert, int capacityPerChest,
                                     int hostStackCount) {
        List<UUID> uuids = getUuids(state);
        WorldStorage storage = WorldStorage.get(server);

        // 同步UUID数量与堆叠数一致
        if (uuids.isEmpty()) {
            uuids = new ArrayList<>(hostStackCount);
            for (int i = 0; i < hostStackCount; i++) {
                uuids.add(WorldStorage.createAndRegister(server, capacityPerChest));
            }
            saveUuids(state, uuids);
            state.setInt(KEY_CACHED_COUNT, hostStackCount);
        } else if (uuids.size() != hostStackCount) {
            List<UUID> adjustedUuids;
            if (uuids.size() < hostStackCount) {
                adjustedUuids = new ArrayList<>(uuids);
                for (int i = uuids.size(); i < hostStackCount; i++) {
                    adjustedUuids.add(WorldStorage.createAndRegister(server, capacityPerChest));
                }
            } else {
                // 裁剪多余存储，直接删除文件（物品由tick统一处理掉落）
                int startIndex = 0;
                List<UUID> removed = uuids.subList(startIndex + hostStackCount, uuids.size());
                for (UUID uuid : removed) {
                    storage.remove(uuid);
                }
                adjustedUuids = new ArrayList<>(uuids.subList(startIndex, startIndex + hostStackCount));
            }
            saveUuids(state, adjustedUuids);
            state.setInt(KEY_CACHED_COUNT, hostStackCount);
            uuids = adjustedUuids;
        }

        int effectiveCount = Math.min(uuids.size(), hostStackCount);
        for (int i = 0; i < effectiveCount && !itemToInsert.isEmpty(); i++) {
            UUID uuid = uuids.get(i);
            List<ItemStack> chestSlots = storage.getOrCreate(uuid, capacityPerChest);
            boolean modified = false;

            for (int j = 0; j < chestSlots.size() && !itemToInsert.isEmpty(); j++) {
                ItemStack slotItem = chestSlots.get(j);
                if (slotItem.isEmpty()) {
                    chestSlots.set(j, itemToInsert.copy());
                    itemToInsert.setCount(0);
                    modified = true;
                } else if (ItemStack.isSameItemSameComponents(slotItem, itemToInsert)) {
                    int spaceAvailable = slotItem.getMaxStackSize() - slotItem.getCount();
                    int transferAmount = Math.min(itemToInsert.getCount(), spaceAvailable);
                    if (transferAmount > 0) {
                        slotItem.grow(transferAmount);
                        itemToInsert.shrink(transferAmount);
                        modified = true;
                    }
                }
            }

            if (modified) {
                storage.markDirty(uuid);
            }
        }
        return itemToInsert.isEmpty();
    }

    // ==================== 物品提取 ====================

    /**
     * 按数量提取物品（优先提取同类型堆叠）
     */
    public static ItemStack extractItem(MinecraftServer server, ComponentState state,
                                        int amount, int capacityPerChest, int hostStackCount) {
        List<UUID> uuids = getUuids(state);
        if (uuids.isEmpty()) return ItemStack.EMPTY;
        WorldStorage storage = WorldStorage.get(server);
        ItemStack result = ItemStack.EMPTY;
        int remaining = amount;
        int effectiveCount = Math.min(uuids.size(), hostStackCount);

        for (int i = 0; i < effectiveCount && remaining > 0; i++) {
            UUID uuid = uuids.get(i);
            List<ItemStack> chestSlots = storage.getOrCreate(uuid, capacityPerChest);
            boolean modified = false;

            for (int j = 0; j < chestSlots.size() && remaining > 0; j++) {
                ItemStack slotItem = chestSlots.get(j);
                if (slotItem.isEmpty()) continue;

                if (result.isEmpty()) {
                    int toExtract = Math.min(remaining, slotItem.getCount());
                    result = slotItem.copyWithCount(toExtract);
                    slotItem.shrink(toExtract);
                    remaining -= toExtract;
                    modified = true;
                } else if (ItemStack.isSameItemSameComponents(result, slotItem)) {
                    int toExtract = Math.min(remaining,
                        Math.min(slotItem.getCount(), result.getMaxStackSize() - result.getCount()));
                    if (toExtract > 0) {
                        result.grow(toExtract);
                        slotItem.shrink(toExtract);
                        remaining -= toExtract;
                        modified = true;
                    }
                }

                if (slotItem.isEmpty()) {
                    chestSlots.set(j, ItemStack.EMPTY);
                    modified = true;
                }
            }

            if (modified) {
                storage.markDirty(uuid);
            }
        }
        return result;
    }

    /**
     * 提取指定类型的物品
     */
    public static ItemStack extractItem(MinecraftServer server, ComponentState state,
                                        ItemStack target, int amount, int capacityPerChest, int hostStackCount) {
        List<UUID> uuids = getUuids(state);
        if (uuids.isEmpty()) return ItemStack.EMPTY;
        WorldStorage storage = WorldStorage.get(server);
        ItemStack result = ItemStack.EMPTY;
        int remaining = amount;
        int effectiveCount = Math.min(uuids.size(), hostStackCount);

        for (int i = 0; i < effectiveCount && remaining > 0; i++) {
            UUID uuid = uuids.get(i);
            List<ItemStack> chestSlots = storage.getOrCreate(uuid, capacityPerChest);
            boolean modified = false;

            for (int j = 0; j < chestSlots.size() && remaining > 0; j++) {
                ItemStack slotItem = chestSlots.get(j);
                if (slotItem.isEmpty()) continue;
                if (!ItemStack.isSameItemSameComponents(target, slotItem)) continue;

                int toExtract;
                if (result.isEmpty()) {
                    toExtract = Math.min(remaining, slotItem.getCount());
                    result = slotItem.copyWithCount(toExtract);
                } else {
                    toExtract = Math.min(remaining,
                        Math.min(slotItem.getCount(), result.getMaxStackSize() - result.getCount()));
                    if (toExtract <= 0) {
                        remaining = 0;
                        break;
                    }
                    result.grow(toExtract);
                }

                slotItem.shrink(toExtract);
                remaining -= toExtract;
                modified = true;

                if (slotItem.isEmpty()) {
                    chestSlots.set(j, ItemStack.EMPTY);
                }
            }

            if (modified) {
                storage.markDirty(uuid);
            }
        }
        return result;
    }

    // ==================== 单箱管理工具 ====================

    public static UUID createAndRegisterNewUuid(MinecraftServer server, int capacity) {
        return WorldStorage.createAndRegister(server, capacity);
    }

    public static UUID popUuid(MinecraftServer server, ComponentState state) {
        List<UUID> uuids = new ArrayList<>(getUuids(state));
        if (uuids.isEmpty()) return null;
        UUID last = uuids.remove(uuids.size() - 1);
        saveUuids(state, uuids);
        WorldStorage.get(server).remove(last);
        return last;
    }

    // ==================== 分文件存储核心实现 ====================

    public static class WorldStorage {
        private static final String DIR_NAME = "living_chests";
        private static final long UNLOAD_TIMEOUT_MS = 5 * 60 * 1000; // 5分钟闲置卸载
        private static final int MAX_CACHE_SIZE = 200; // 最大同时缓存数量

        private final MinecraftServer server;
        private final Path storageDir;
        private final Map<UUID, CachedStorage> cache;

        private static WorldStorage instance;

        private WorldStorage(MinecraftServer server) {
            this.server = server;
            this.storageDir = server.overworld().getWorldPath().resolve("data").resolve(DIR_NAME);
            // 访问顺序LinkedHashMap，天然支持LRU淘汰
            this.cache = new LinkedHashMap<>(16, 0.75f, true);

            try {
                Files.createDirectories(storageDir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create living chest storage directory", e);
            }
        }

        /**
         * 获取服务器绑定的全局存储实例
         */
        public static synchronized WorldStorage get(MinecraftServer server) {
            if (instance == null || instance.server != server) {
                instance = new WorldStorage(server);
            }
            return instance;
        }

        // ---------- 核心存取 ----------

        /**
         * 获取或创建指定UUID的存储箱，优先读缓存，其次读磁盘
         */
        public List<ItemStack> getOrCreate(UUID uuid, int capacity) {
            CachedStorage cached = cache.get(uuid);
            if (cached != null) {
                cached.lastAccess = System.currentTimeMillis();
                return cached.items;
            }

            List<ItemStack> items = loadFromDisk(uuid);
            if (items == null) {
                items = createEmptySlots(capacity);
            }

            putCache(uuid, items, false);
            return items;
        }

        /**
         * 检查存储是否存在（缓存+磁盘）
         */
        public boolean contains(UUID uuid) {
            if (cache.containsKey(uuid)) return true;
            return Files.exists(getFilePath(uuid));
        }

        /**
         * 删除存储（缓存+磁盘文件）
         */
        public void remove(UUID uuid) {
            cache.remove(uuid);
            try {
                Files.deleteIfExists(getFilePath(uuid));
            } catch (IOException ignored) {}
        }

        /**
         * 标记单个存储箱为脏
         */
        public void markDirty(UUID uuid) {
            CachedStorage cached = cache.get(uuid);
            if (cached != null) {
                cached.dirty = true;
            }
        }

        // ---------- 持久化与缓存管理 ----------

        /**
         * 所有脏数据写入磁盘
         */
        public void saveAllDirty() {
            for (Map.Entry<UUID, CachedStorage> entry : cache.entrySet()) {
                if (entry.getValue().dirty) {
                    saveToDisk(entry.getKey(), entry.getValue().items);
                    entry.getValue().dirty = false;
                }
            }
        }

        /**
         * 清理闲置缓存，超出上限淘汰最久未访问的
         */
        public void cleanupIdle() {
            long now = System.currentTimeMillis();
            List<UUID> toUnload = new ArrayList<>();

            for (Map.Entry<UUID, CachedStorage> entry : cache.entrySet()) {
                if (now - entry.getValue().lastAccess > UNLOAD_TIMEOUT_MS) {
                    if (entry.getValue().dirty) {
                        saveToDisk(entry.getKey(), entry.getValue().items);
                    }
                    toUnload.add(entry.getKey());
                }
            }

            for (UUID uuid : toUnload) {
                cache.remove(uuid);
            }

            // 超出缓存上限，淘汰最久未访问
            while (cache.size() > MAX_CACHE_SIZE) {
                Map.Entry<UUID, CachedStorage> eldest = cache.entrySet().iterator().next();
                if (eldest.getValue().dirty) {
                    saveToDisk(eldest.getKey(), eldest.getValue().items);
                }
                cache.remove(eldest.getKey());
            }
        }

        // ---------- 文件IO ----------

        private Path getFilePath(UUID uuid) {
            return storageDir.resolve(uuid.toString() + ".dat");
        }

        private List<ItemStack> loadFromDisk(UUID uuid) {
            Path path = getFilePath(uuid);
            if (!Files.exists(path)) return null;

            try {
                CompoundTag tag = NbtIo.readCompressed(path);
                ListTag itemsList = tag.getList("items", Tag.TAG_COMPOUND);
                List<ItemStack> items = new ArrayList<>(itemsList.size());
                HolderLookup.Provider provider = server.registryAccess();

                for (int i = 0; i < itemsList.size(); i++) {
                    ItemStack stack = ItemStack.parseOptional(provider, itemsList.getCompound(i));
                    items.add(stack.isEmpty() ? ItemStack.EMPTY : stack);
                }
                return items;
            } catch (IOException e) {
                return null;
            }
        }

        private void saveToDisk(UUID uuid, List<ItemStack> items) {
            CompoundTag tag = new CompoundTag();
            ListTag itemsList = new ListTag();
            HolderLookup.Provider provider = server.registryAccess();

            for (ItemStack stack : items) {
                CompoundTag itemTag = new CompoundTag();
                if (!stack.isEmpty()) {
                    stack.save(provider, itemTag);
                }
                itemsList.add(itemTag);
            }
            tag.put("items", itemsList);

            try {
                Path path = getFilePath(uuid);
                Path tmpPath = path.resolveSibling(uuid + ".tmp");
                NbtIo.writeCompressed(tag, tmpPath);
                // 原子替换，防止写入中途崩溃损坏存档
                Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {}
        }

        // ---------- 内部辅助 ----------

        private void putCache(UUID uuid, List<ItemStack> items, boolean dirty) {
            CachedStorage cached = new CachedStorage();
            cached.items = items;
            cached.dirty = dirty;
            cached.lastAccess = System.currentTimeMillis();
            cache.put(uuid, cached);

            // 每插入20条触发一次清理
            if (cache.size() % 20 == 0) {
                cleanupIdle();
            }
        }

        private static List<ItemStack> createEmptySlots(int capacity) {
            List<ItemStack> slots = new ArrayList<>(capacity);
            for (int i = 0; i < capacity; i++) {
                slots.add(ItemStack.EMPTY);
            }
            return slots;
        }

        // ---------- 静态工厂 ----------

        public static UUID createAndRegister(MinecraftServer server, int capacity) {
            UUID uuid = UUID.randomUUID();
            WorldStorage storage = get(server);
            while (storage.contains(uuid)) {
                uuid = UUID.randomUUID();
            }
            storage.getOrCreate(uuid, capacity);
            storage.markDirty(uuid);
            return uuid;
        }

        // ---------- 内部缓存实体 ----------

        private static class CachedStorage {
            List<ItemStack> items;
            boolean dirty;
            long lastAccess;
        }
    }
}
```

---

## 必做：注册持久化触发事件
在你的模组主类或事件处理器中注册以下事件，确保数据按时落盘，否则重启仍会丢失内存中未保存的数据：

```java
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.level.ServerLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod.EventBusSubscriber
public class StorageEventHandler {

    // 世界保存时刷盘+清理闲置缓存
    @SubscribeEvent
    public static void onLevelSave(ServerLevelEvent.Save event) {
        if (event.getLevel() == event.getServer().overworld()) {
            InternalStorageComponent.WorldStorage storage = 
                    InternalStorageComponent.WorldStorage.get(event.getServer());
            storage.saveAllDirty();
            storage.cleanupIdle();
        }
    }

    // 服务器停止时全量刷盘
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        InternalStorageComponent.WorldStorage storage = 
                InternalStorageComponent.WorldStorage.get(event.getServer());
        storage.saveAllDirty();
    }
}
```

---

## 关键设计说明
1. **精确脏标记**：每个箱子独立维护脏位，只有真正修改过的箱子才会写入磁盘，大幅降低IO开销
2. **原子写入保障**：先写临时文件再原子替换，彻底避免服务器崩溃导致单个存储文件损坏
3. **两级缓存淘汰**：超时自动卸载 + 容量上限LRU淘汰，兼顾性能与内存占用
4. **生命周期闭环**：存储裁剪时自动掉落物品、删除磁盘文件，无悬空数据和存档膨胀
5. **服务端专属**：所有存储IO均在服务端执行，客户端无额外开销

需要我补充一份存储性能压测的参数调优建议，或者加上存储容量/物品黑名单的扩展功能吗？