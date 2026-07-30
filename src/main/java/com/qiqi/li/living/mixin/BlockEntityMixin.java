package com.qiqi.li.living.mixin;

import com.qiqi.li.living.container.ContainerStressData;
import com.qiqi.li.living.container.StressDataProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.world.level.block.entity.BlockEntity;

@Mixin(BlockEntity.class)
public class BlockEntityMixin implements StressDataProvider {

    @Unique
    private ContainerStressData livingItem$stressData = ContainerStressData.EMPTY;

    @Override
    public ContainerStressData livingItem$getStressData() {
        return livingItem$stressData;
    }

    @Override
    public void livingItem$setStressData(ContainerStressData data) {
        this.livingItem$stressData = data;
    }
}