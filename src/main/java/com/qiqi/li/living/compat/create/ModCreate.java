package com.qiqi.li.living.compat.create;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import com.qiqi.li.living.domain.water.ContainerStressData;

/**
 * 活物品与 Create 模组的集成入口。
 *
 * <p>封装 {@link CreateCompat} 的加载探测与 {@link StressOutputManager} 的实际注入，
 * 对外暴露 {@link #init} 与 {@link #updateStressOutput} 两个入口。</p>
 */
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