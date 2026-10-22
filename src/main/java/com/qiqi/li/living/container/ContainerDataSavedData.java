package com.qiqi.li.living.container;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.fml.util.thread.SidedThreadGroups;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import com.qiqi.li.logging.ModLog;
import com.qiqi.li.living.api.LivingMod;

/**
 * 容器级数据的 <b>SavedData 持久层</b>（2026-10-22，活背包 / `D-container-13`）——
 * 按**容器 UUID** 索引，全局一份。
 *
 * <h3>为什么需要它</h3>
 * <p>物品形态容器（精妙背包 / 潜影盒）<b>没有 BE 可挂附件</b> ⇒ {@code getDataHosts()}
 * 为空 ⇒ {@link ContainerContext#persist} 的 default 路径写不进去 ⇒ 数据只在内存表，
 * LRU 一回收就丢。本类是它的档案柜。</p>
 *
 * <h3>三层模型里的位置</h3>
 * <pre>
 *   身份   UUID            BE 附件（方块）/ 物品组件（物品）   ← 跨形态信物
 *   数据   本类            按 UUID 索引，全局一份             ← 跨形态真相
 *   缓存   CONTAINER_DATA  内存表，按 cuuid 键                ← 热数据，LRU 可弃
 * </pre>
 *
 * <h3>参考实现：精妙背包的 {@code BackpackStorage}</h3>
 * <p>同款思路的成熟实现（{@code libs/src/SophisticatedBackpacks-1.21.x}），借了四条：</p>
 * <ol>
 *   <li><b>存 Overworld 的 DataStorage、文件名 = mod id</b> ⇒ 全局一份不分维度
 *       （容器会跨维度被带走）</li>
 *   <li>{@code Map<UUID, CompoundTag>} + {@code computeIfAbsent} + {@code setDirty()}
 *       （MC 自己的保存周期落盘，无需自管策略）</li>
 *   <li><b>加载时丢弃空条目</b> ⇒ 存档瘦身；只保留有数据的 UUID</li>
 *   <li>客户端只作占位（{@link #get()} 在非服务端线程返回空壳）—— 客户端没有消费方，
 *       GUI 数据走网络包同步轨 ⇒ 不清也不脏</li>
 * </ol>
 *
 * <h3>与精妙的差别</h3>
 * <ul>
 *   <li>值是<b>按 key 编码的 NBT</b> 而不是裸 CompoundTag：哪个 key 能进来由
 *       {@link ContainerDataKey#isSaved()} 判（attachment 与 codec 都声明了）——
 *       与 I-C9「只有带 {@code serialize} 的才搬」同源，应力 / 红石这类运行时数据进不来。</li>
 *   <li>没有 {@code accessLogRecords}（无所有者概念）⇒ 清理只按「条目为空」。</li>
 * </ul>
 *
 * <p>⚠️ <b>线程</b>：只在服务端主线程读写（SavedData 的约定）；客户端返回空壳副本。
 * 探测用 {@link SidedThreadGroups#SERVER}（与精妙同款判据，不触发类加载）。</p>
 */
public final class ContainerDataSavedData extends SavedData {

    private static final String SAVED_DATA_NAME = LivingMod.ID + "_container_data";

    private static final String TAG_ENTRIES = "entries";
    private static final String TAG_UUID = "uuid";
    private static final String TAG_KEYS = "keys";

    /** UUID → 该容器的各 key 值（按 key.id 编码的 NBT）。 */
    private final Map<UUID, CompoundTag> entries = new HashMap<>();

    /** 客户端空壳（不同步 SavedData，GUI 数据走网络包）。 */
    private static final ContainerDataSavedData clientCopy = new ContainerDataSavedData();

    private ContainerDataSavedData() {}

    // ============================================================
    // 取实例
    // ============================================================

    /** 当前线程的实例：服务端取真身，客户端取空壳。 */
    public static ContainerDataSavedData get() {
        if (Thread.currentThread().getThreadGroup() == SidedThreadGroups.SERVER) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) return get(server);
        }
        return clientCopy;
    }

    public static ContainerDataSavedData get(ServerLevel level) {
        return get(level.getServer());
    }

    public static ContainerDataSavedData get(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            // 开局加载早期（维度还没建好）：返回空壳，调用方的写入会丢 ⇒ 宁可丢也不要崩
            ModLog.CONTAINER.warn("[SavedData] Overworld 尚不可用，本次容器数据不落盘");
            return clientCopy;
        }
        DimensionDataStorage storage = overworld.getDataStorage();
        return storage.computeIfAbsent(
            new Factory<>(ContainerDataSavedData::new, ContainerDataSavedData::load), SAVED_DATA_NAME);
    }

    // ============================================================
    // 序列化
    // ============================================================

    public static ContainerDataSavedData load(CompoundTag nbt, HolderLookup.Provider registries) {
        ContainerDataSavedData data = new ContainerDataSavedData();
        for (Tag t : nbt.getList(TAG_ENTRIES, Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) t;
            if (!entry.hasUUID(TAG_UUID)) continue;
            CompoundTag keys = entry.getCompound(TAG_KEYS);
            if (keys.isEmpty()) continue;   // 空条目不进内存（存档瘦身）
            data.entries.put(entry.getUUID(TAG_UUID), keys);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, CompoundTag> e : entries.entrySet()) {
            if (e.getValue().isEmpty()) continue;   // 与加载对称：空条目不写盘
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_UUID, e.getKey());
            entry.put(TAG_KEYS, e.getValue());
            list.add(entry);
        }
        tag.put(TAG_ENTRIES, list);
        return tag;
    }

    // ============================================================
    // 读写
    // ============================================================

    /**
     * 写入某个 key 的值（懒创建该 UUID 的条目）。
     *
     * @param value 为空 ⇒ 等价于 {@link #remove(UUID, ContainerDataKey)}（调用方传 EMPTY 时由上层判）
     */
    public <T> void put(UUID containerUuid, ContainerDataKey<T> key, T value) {
        if (containerUuid == null || !key.isSaved()) {
            ModLog.CONTAINER.warn("[SavedData] 拒绝写入：uuid={} key={} isSaved={}",
                containerUuid, key.id(), key.isSaved());
            return;
        }
        Codec<T> codec = key.codec();
        if (codec == null) return;   // isSaved 已保证非空，双保险

        Tag encoded;
        try {
            encoded = codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow();
        } catch (RuntimeException e) {
            ModLog.CONTAINER.error("[SavedData] 编码失败，该 key 本次不落盘：key=" + key.id(), e);
            return;
        }

        entries.computeIfAbsent(containerUuid, u -> new CompoundTag()).put(key.id(), encoded);
        setDirty();
    }

    /** 读出某个 key 的值；不存在或解码失败返回 {@code null}。 */
    @Nullable
    public <T> T get(UUID containerUuid, ContainerDataKey<T> key) {
        if (containerUuid == null || !key.isSaved()) return null;
        CompoundTag keys = entries.get(containerUuid);
        if (keys == null) return null;
        Tag encoded = keys.get(key.id());
        if (encoded == null) return null;

        return key.codec().parse(NbtOps.INSTANCE, encoded).result().orElse(null);
    }

    /** 移除某个 key（该容器没有任何 key 时连条目一起删，保持存档瘦身）。 */
    public <T> void remove(UUID containerUuid, ContainerDataKey<T> key) {
        if (containerUuid == null) return;
        CompoundTag keys = entries.get(containerUuid);
        if (keys == null) return;
        keys.remove(key.id());
        if (keys.isEmpty()) entries.remove(containerUuid);
        setDirty();
    }

    /** 整个容器的数据（物品销毁时用）。 */
    public void remove(UUID containerUuid) {
        if (entries.remove(containerUuid) != null) setDirty();
    }

    /** 当前有多少个容器条目（测试与调试）。 */
    public int size() {
        return entries.size();
    }

    /** 测试用：清空（不影响已落盘内容，落盘由 setDirty 触发）。 */
    public void clearForTest() {
        entries.clear();
    }

    /** 测试专用：造一个空实例（生产代码走 {@link #get()}，构造器私有是为了挡住误用）。 */
    static ContainerDataSavedData createForTest() {
        return new ContainerDataSavedData();
    }
}
