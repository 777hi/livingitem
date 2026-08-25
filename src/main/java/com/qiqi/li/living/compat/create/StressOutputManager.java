package com.qiqi.li.living.compat.create;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.qiqi.li.living.domain.water.ContainerStressData;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 活水车应力输出管理器 —— 合并原 {@link ModCreate} 和 {@link CreateIntegration} 的职责。
 *
 * <p>单一入口方法 {@link #apply(Level, BlockPos, ContainerStressData)} 完成：
 * 找下方 BE → 白名单检查 → 方向兼容性检查 → RPM/SU 换算 → 注入应力。</p>
 *
 * <p>使用 {@link LivingItemStressOutput#livingItem$applyStress} 统一接口，
 * 不再拆分为两个独立调用，消除隐式顺序依赖。</p>
 */
public class StressOutputManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/Create");

    public static final float BASE_RPM = 8.0f;
    public static final float BASE_SU_CAPACITY = 32.0f;

    public static void apply(Level level, BlockPos containerPos, ContainerStressData stressData) {
        if (!CreateCompat.isLoaded()) return;
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
            rpm = Math.signum(netStress) * BASE_RPM;
            suCapacity = Math.abs(netStress) * BASE_SU_CAPACITY;
        }

        LOGGER.info("[StressOutput] at {}: netStress={}, rpm={}, suCapacity={}, beType={}",
            belowPos, netStress, rpm, suCapacity, be.getClass().getSimpleName());

        if (rpm != 0 && !isDirectionCompatible(stressOutput, rpm)) {
            LOGGER.debug("[StressOutput] Direction incompatible on {} at {}, skipping injection",
                be.getClass().getSimpleName(), belowPos);
            stressOutput.livingItem$applyStress(0, 0);
            return;
        }

        stressOutput.livingItem$applyStress(rpm, suCapacity);
    }

    private static boolean isDirectionCompatible(LivingItemStressOutput stressOutput, float injectedRPM) {
        float existingSpeed = stressOutput.livingItem$getTheoreticalSpeed();
        if (existingSpeed == 0) return true;
        return Math.signum(injectedRPM) == Math.signum(existingSpeed);
    }
}