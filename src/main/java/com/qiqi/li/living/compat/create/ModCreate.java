package com.qiqi.li.living.compat.create;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import com.qiqi.li.living.domain.water.ContainerStressData;

public class ModCreate {

    public static final float BASE_RPM = StressOutputManager.BASE_RPM;
    public static final float BASE_SU_CAPACITY = StressOutputManager.BASE_SU_CAPACITY;

    public static void init() {
        if (!CreateCompat.isLoaded()) return;
    }

    public static void updateStressOutput(Level level, BlockPos containerPos, ContainerStressData stressData) {
        StressOutputManager.apply(level, containerPos, stressData);
    }
}