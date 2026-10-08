package com.qiqi.li.living.compat.create;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.qiqi.li.living.domain.water.ContainerStressData;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Create 应力输出的集成逻辑。
 *
 * <p>将活物品的 {@link ContainerStressData} 转为 Create 的 RPM 与应力容量，
 * 注入到容器下方实现了 {@link LivingItemStressOutput} 的方块实体；
 * 当方向与既有转速冲突时清零输出，避免破坏 Create 动力网。</p>
 */
public class CreateIntegration {

    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/Create");

    static void updateStressOutput(Level level, BlockPos containerPos, ContainerStressData stressData) {
        if (level == null || level.isClientSide) return;

        BlockPos belowPos = containerPos.below();
        BlockEntity be = level.getBlockEntity(belowPos);

        if (be == null) return;

        if (!(be instanceof LivingItemStressOutput stressOutput)) return;

        if (!stressOutput.livingItem$isSafeForStressInjection()) {
            LOGGER.debug("[StressOutput] BE type {} is not in safe whitelist, skipping",
                be.getClass().getSimpleName());
            return;
        }

        float rpm = 0;
        float suCapacity = 0;
        int netStress = 0;
        if (stressData != null && !stressData.isEmpty()) {
            netStress = stressData.getNetStress();
            rpm = Math.signum(netStress) * ModCreate.BASE_RPM;
            suCapacity = Math.abs(netStress) * ModCreate.BASE_SU_CAPACITY;
        }

        LOGGER.info("[StressOutput] at {}: netStress={}, rpm={}, suCapacity={}, beType={}",
            belowPos, netStress, rpm, suCapacity, be.getClass().getSimpleName());

        if (rpm != 0 && !isDirectionCompatible(stressOutput, rpm)) {
            LOGGER.debug("[StressOutput] Direction incompatible on {} at {}, skipping injection",
                be.getClass().getSimpleName(), belowPos);
            stressOutput.livingItem$setGeneratedRPM(0);
            stressOutput.livingItem$setStressCapacity(0);
            return;
        }

        stressOutput.livingItem$setGeneratedRPM(rpm);
        stressOutput.livingItem$setStressCapacity(suCapacity);
    }

    private static boolean isDirectionCompatible(LivingItemStressOutput stressOutput, float injectedRPM) {
        float existingSpeed = stressOutput.livingItem$getTheoreticalSpeed();
        if (existingSpeed == 0) return true;
        return Math.signum(injectedRPM) == Math.signum(existingSpeed);
    }
}