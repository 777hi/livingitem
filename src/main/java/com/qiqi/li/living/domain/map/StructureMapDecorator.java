package com.qiqi.li.living.domain.map;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.*;

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

    public static void scanStructuresLazy(ServerLevel level, ServerPlayer player, ItemStack mapStack, MapItemSavedData data) {
        MapId mapIdObj = mapStack.get(DataComponents.MAP_ID);
        if (mapIdObj == null) return;
        int mapId = mapIdObj.id();

        if (!isPlayerNearMap(player, data)) return;

        ensureIconMappingBuilt(level);

        Set<Long> scannedChunks = SCANNED_CHUNKS.computeIfAbsent(mapId, k -> new HashSet<>());
        doScan(level, data, scannedChunks);
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

        structureIconMap = Map.copyOf(iconMap);
        undergroundStructureSet = Set.copyOf(undergroundSet);
    }

    private static boolean isUndergroundStep(GenerationStep.Decoration step) {
        return step == GenerationStep.Decoration.UNDERGROUND_STRUCTURES
                || step == GenerationStep.Decoration.STRONGHOLDS;
    }

    private static void doScan(ServerLevel level, MapItemSavedData data, Set<Long> scannedChunks) {
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

                    for (long ref : entry.getValue()) {
                        ChunkPos startChunkPos = new ChunkPos(ref);
                        double x = startChunkPos.getMinBlockX() + 8;
                        double z = startChunkPos.getMinBlockZ() + 8;

                        String structId = registry.getKey(structure).toString().replace(':', '_');
                        String id = idPrefix + structId + "_" + ref;

                        data.addDecoration(icon, level, id, x, z, 0.0, null);
                    }
                }
            }
        }
    }
}