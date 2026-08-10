package com.qiqi.li.living.mixin;

import com.qiqi.li.living.perf.PerfMetrics;
import com.qiqi.li.logging.ModLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MapItem.class)
public class MapItemUpdateMixin {

    @Redirect(
        method = "update",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;")
    )
    private LevelChunk redirectGetChunk(Level level, int chunkX, int chunkZ) {
        if (level instanceof ServerLevel serverLevel) {
            LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (chunk == null) {
                PerfMetrics.recordMapChunkSkipped();
                ModLog.MAP.debug("Skip map update for unloaded chunk: dim={} pos=({},{})",
                    level.dimension().location(), chunkX, chunkZ);
            } else {
                PerfMetrics.recordMapChunkLoaded();
            }
            return chunk;
        }
        return level.getChunk(chunkX, chunkZ);
    }

    @Redirect(
        method = "update",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;isEmpty()Z")
    )
    private boolean redirectIsEmpty(LevelChunk chunk) {
        if (chunk == null) {
            return true;
        }
        return chunk.isEmpty();
    }
}