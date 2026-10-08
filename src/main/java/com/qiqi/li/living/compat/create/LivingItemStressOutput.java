package com.qiqi.li.living.compat.create;

/**
 * 由 Mixin 注入到 Create 方块实体上的应力输出接口。
 *
 * <p>提供 RPM / 应力容量的读写与理论转速查询，以及是否允许注入的判据（白名单），
 * 供 {@link CreateIntegration} 在不依赖 Create 编译期依赖的前提下驱动 Create 动力网。</p>
 */
public interface LivingItemStressOutput {

    void livingItem$applyStress(float rpm, float capacity);

    void livingItem$setGeneratedRPM(float rpm);

    float livingItem$getGeneratedRPM();

    void livingItem$setStressCapacity(float capacity);

    float livingItem$getStressCapacity();

    boolean livingItem$isSafeForStressInjection();

    float livingItem$getTheoreticalSpeed();

    void livingItem$onChunkUnloaded();
}