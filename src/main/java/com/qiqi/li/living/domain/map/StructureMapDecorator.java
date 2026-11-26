package com.qiqi.li.living.domain.map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import it.unimi.dsi.fastutil.longs.LongSet;

import javax.annotation.Nullable;
import java.util.*;

/**
 * 结构地图装饰器（服务端）。
 *
 * <p>惰性扫描地图覆盖区块，把命中的结构以旗帜图标 / 靶心 / 红叉装饰画到活地图上。
 * 图标映射按原版结构标签与生成阶段（地下 / 地表）区分；落点走原版 locate 口径
 * （{@link StructurePlacement#getLocatePos}），物品自带 {@code MAP_DECORATIONS} 已覆盖的位置不再重复添加。
 * 所有跨存档缓存（图标表 / 已扫描区块）在服务器停止时由 {@link #reset} 清空。</p>
 */
public final class StructureMapDecorator {

    private record TagIconEntry(
            TagKey<Structure> tag,
            Holder<MapDecorationType> icon
    ) {}

    private static final List<TagIconEntry> VANILLA_TAG_ICONS = List.of(
            new TagIconEntry(StructureTags.ON_WOODLAND_EXPLORER_MAPS, MapDecorationTypes.WOODLAND_MANSION),
            new TagIconEntry(StructureTags.ON_OCEAN_EXPLORER_MAPS, MapDecorationTypes.OCEAN_MONUMENT),
            new TagIconEntry(StructureTags.ON_TRIAL_CHAMBERS_MAPS, MapDecorationTypes.TRIAL_CHAMBERS),
            new TagIconEntry(StructureTags.ON_DESERT_VILLAGE_MAPS, MapDecorationTypes.DESERT_VILLAGE),
            new TagIconEntry(StructureTags.ON_PLAINS_VILLAGE_MAPS, MapDecorationTypes.PLAINS_VILLAGE),
            new TagIconEntry(StructureTags.ON_SAVANNA_VILLAGE_MAPS, MapDecorationTypes.SAVANNA_VILLAGE),
            new TagIconEntry(StructureTags.ON_SNOWY_VILLAGE_MAPS, MapDecorationTypes.SNOWY_VILLAGE),
            new TagIconEntry(StructureTags.ON_TAIGA_VILLAGE_MAPS, MapDecorationTypes.TAIGA_VILLAGE),
            new TagIconEntry(StructureTags.ON_JUNGLE_EXPLORER_MAPS, MapDecorationTypes.JUNGLE_TEMPLE),
            new TagIconEntry(StructureTags.ON_SWAMP_EXPLORER_MAPS, MapDecorationTypes.SWAMP_HUT)
    );

    private static Map<Holder<Structure>, Holder<MapDecorationType>> structureIconMap = null;
    private static Set<Holder<Structure>> undergroundStructureSet = null;

    private static final Map<Integer, Set<Long>> SCANNED_CHUNKS = new HashMap<>();

    private StructureMapDecorator() {}

    /**
     * 清空全部静态缓存，必须在服务器停止时调用。
     * <p>三个缓存都持有与单个存档绑定的数据：
     * <ul>
     *   <li>{@code structureIconMap}/{@code undergroundStructureSet} 持有 {@link Holder} 引用，
     *       其 equals 依赖 ResourceKey 的引用相等，跨存档复用会导致查找全部失效（图标退化为靶心），
     *       同时强引用旧 Registry 阻止其被回收。</li>
     *   <li>{@code SCANNED_CHUNKS} 的 key 是单调递增且永不复用的 mapId，不清理会无界增长。</li>
     * </ul>
     */
    public static void reset() {
        structureIconMap = null;
        undergroundStructureSet = null;
        SCANNED_CHUNKS.clear();
    }

    public static void scanStructuresLazy(ServerLevel level, ServerPlayer player, ItemStack mapStack, MapItemSavedData data) {
        MapId mapIdObj = mapStack.get(DataComponents.MAP_ID);
        if (mapIdObj == null) return;
        int mapId = mapIdObj.id();

        if (!isPlayerNearMap(player, data)) return;

        ensureIconMappingBuilt(level);

        Set<Long> scannedChunks = SCANNED_CHUNKS.computeIfAbsent(mapId, k -> new HashSet<>());
        doScan(level, mapStack, data, scannedChunks);
    }

    private static boolean isPlayerNearMap(ServerPlayer player, MapItemSavedData data) {
        int mapRange = 128 * (1 << data.scale);
        int halfRange = mapRange / 2;
        double px = player.getX();
        double pz = player.getZ();
        return px >= data.centerX - halfRange && px <= data.centerX + halfRange
                && pz >= data.centerZ - halfRange && pz <= data.centerZ + halfRange;
    }

    private static void ensureIconMappingBuilt(ServerLevel level) {
        if (structureIconMap != null) return;

        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Map<Holder<Structure>, Holder<MapDecorationType>> iconMap = new HashMap<>();
        Set<Holder<Structure>> undergroundSet = new HashSet<>();

        for (var entry : VANILLA_TAG_ICONS) {
            var tag = registry.getTag(entry.tag());
            if (tag.isEmpty()) continue;
            for (var holder : tag.get()) {
                iconMap.put(holder, entry.icon());
            }
        }

        // 遍历 registry 全量：没有专属图标的结构（含无标签的模组结构）按生成阶段区分地下/地表
        for (Holder.Reference<Structure> holder : registry.holders().toList()) {
            if (iconMap.containsKey(holder)) continue;
            if (isUndergroundStep(holder.value().step())) {
                undergroundSet.add(holder);
            }
        }

        // undergroundStructureSet 必须先赋值：structureIconMap 非 null 是构建完成的判定条件，
        // 若顺序颠倒会出现 structureIconMap 已就绪而 undergroundStructureSet 仍为 null 的窗口
        undergroundStructureSet = Set.copyOf(undergroundSet);
        structureIconMap = Map.copyOf(iconMap);
    }

    private static boolean isUndergroundStep(GenerationStep.Decoration step) {
        return step == GenerationStep.Decoration.UNDERGROUND_STRUCTURES
                || step == GenerationStep.Decoration.STRONGHOLDS;
    }

    private static void doScan(ServerLevel level, ItemStack mapStack, MapItemSavedData data, Set<Long> scannedChunks) {
        int scale = data.scale;
        int centerX = data.centerX;
        int centerZ = data.centerZ;
        int mapRange = 128 * (1 << scale);
        int halfRange = mapRange / 2;

        int minCX = (centerX - halfRange) >> 4;
        int minCZ = (centerZ - halfRange) >> 4;
        int maxCX = (centerX + halfRange - 1) >> 4;
        int maxCZ = (centerZ + halfRange - 1) >> 4;

        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        ChunkGeneratorStructureState structureState = level.getChunkSource().getGeneratorState();
        Map<Holder<Structure>, List<StructurePlacement>> placementsByStructure = new HashMap<>();
        MapDecorations componentDecorations =
                mapStack.getOrDefault(DataComponents.MAP_DECORATIONS, MapDecorations.EMPTY);

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                long chunkPos = ChunkPos.asLong(cx, cz);
                if (scannedChunks.contains(chunkPos)) continue;

                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;

                scannedChunks.add(chunkPos);

                Map<Structure, LongSet> refs = chunk.getAllReferences();
                if (refs.isEmpty()) continue;

                for (var entry : refs.entrySet()) {
                    Structure structure = entry.getKey();
                    Optional<Holder.Reference<Structure>> holderOpt = registry.getHolder(registry.getId(structure));
                    if (holderOpt.isEmpty()) continue;

                    Holder<Structure> holder = holderOpt.get();
                    Holder<MapDecorationType> icon = structureIconMap.get(holder);
                    String idPrefix = "struct_";
                    if (icon == null) {
                        boolean underground = undergroundStructureSet.contains(holder);
                        icon = underground ? MapDecorationTypes.RED_X : MapDecorationTypes.TARGET_X;
                        idPrefix = underground ? "underground_" : "surface_";
                    }

                    List<StructurePlacement> placements =
                            placementsByStructure.computeIfAbsent(holder, structureState::getPlacementsForStructure);

                    for (long ref : entry.getValue()) {
                        ChunkPos startChunkPos = new ChunkPos(ref);
                        BlockPos locatePos = resolveLocatePos(structureState, placements, startChunkPos);

                        String structId = registry.getKey(structure).toString().replace(':', '_');
                        String id = idPrefix + structId + "_" + ref;

                        Map.Entry<String, MapDecorations.Entry> covered = findComponentCoveringEntry(
                                componentDecorations, centerX, centerZ, 1 << scale,
                                locatePos.getX(), locatePos.getZ(), icon);
                        if (covered != null) {
                            // 物品条目是权威目标：按原版 tickCarriedBy 口径补写它（未被携带过的地图也要有图标），
                            // 并清掉旧扫描按区块中心写下的同 id 错位标记
                            MapDecorations.Entry target = covered.getValue();
                            data.addDecoration(target.type(), level, covered.getKey(),
                                    target.x(), target.z(), target.rotation(), null);
                            data.removeDecoration(id);
                            continue;
                        }

                        data.addDecoration(icon, level, id, locatePos.getX(), locatePos.getZ(), 0.0, null);
                    }
                }
            }
        }
    }

    /**
     * 结构标记的世界坐标，口径同原版 {@code ChunkGenerator#getStructureGeneratingAt}：
     * 命中该区块的 {@link StructurePlacement} 用 {@link StructurePlacement#getLocatePos}
     * （区块角 + {@code locate_offset}）。
     *
     * <p>⚠️ 不能硬编码区块中心：埋藏宝藏的 structure_set 配了 {@code locate_offset [9,0,9]}，
     * 箱子就在区块 (9,9)；按中心打点会偏 1 格，原版藏宝图上则重叠成半像素重影。</p>
     *
     * <p>没有 placement 命中（数据包自定义放置 / 结构集被移除）时退回区块中心，保持旧行为。</p>
     */
    private static BlockPos resolveLocatePos(ChunkGeneratorStructureState structureState,
                                             List<StructurePlacement> placements, ChunkPos chunkPos) {
        for (StructurePlacement placement : placements) {
            if (placement.isStructureChunk(structureState, chunkPos.x, chunkPos.z)) {
                return placement.getLocatePos(chunkPos);
            }
        }
        if (placements.size() == 1) {
            return placements.get(0).getLocatePos(chunkPos);
        }
        return new BlockPos(chunkPos.getMinBlockX() + 8, 0, chunkPos.getMinBlockZ() + 8);
    }

    /**
     * 在物品自带 {@code MAP_DECORATIONS} 里找与落点同处的条目（同图标 + 同像素 ±0.5）。
     *
     * <p>原版探险图 / 藏宝图把目标世界坐标写进组件条目，携带时又经
     * {@code MapItemSavedData#tickCarriedBy} 并进地图装饰；再按结构自算坐标补一枚
     * 只会重叠成重影（像素取整还会差半格）。命中即以条目为准。</p>
     *
     * @param blocksPerPixel {@code 1 << data.scale}
     * @return 命中的条目（键 = 原版写入地图数据时用的装饰 id）；无命中返回 {@code null}
     */
    @Nullable
    static Map.Entry<String, MapDecorations.Entry> findComponentCoveringEntry(
            MapDecorations componentDecorations, int centerX, int centerZ, int blocksPerPixel,
            double x, double z, Holder<MapDecorationType> icon) {
        Map<String, MapDecorations.Entry> entries = componentDecorations.decorations();
        if (entries.isEmpty()) return null;

        float pixelX = (float) ((x - centerX) / blocksPerPixel + 64.0);
        float pixelZ = (float) ((z - centerZ) / blocksPerPixel + 64.0);
        for (Map.Entry<String, MapDecorations.Entry> entry : entries.entrySet()) {
            MapDecorations.Entry target = entry.getValue();
            if (!target.type().equals(icon)) continue;
            float entryPixelX = (float) ((target.x() - centerX) / blocksPerPixel + 64.0);
            float entryPixelZ = (float) ((target.z() - centerZ) / blocksPerPixel + 64.0);
            if (Math.abs(entryPixelX - pixelX) <= 0.5F && Math.abs(entryPixelZ - pixelZ) <= 0.5F) {
                return entry;
            }
        }
        return null;
    }
}