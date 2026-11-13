package com.qiqi.li.living.mixin;

import java.util.Map;

import com.qiqi.li.living.container.PendingBlockEntityAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.ChunkAccess;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** ChunkAccess 的 pending NBT 接线；行为入口由低层接口约束。 */
@Mixin(ChunkAccess.class)
public abstract class LivingItemChunkAccessAccessor implements PendingBlockEntityAccess {

    @Accessor("pendingBlockEntities")
    protected abstract Map<BlockPos, CompoundTag> living_item$getPendingBlockEntities();

    @Override
    public boolean living_item$removePendingBlockEntity(BlockPos pos) {
        return living_item$getPendingBlockEntities().remove(pos) != null;
    }
}
