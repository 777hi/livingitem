package com.qiqi.li.living.compat.create;

/**
 * 由 Mixin 注入到 Create 方块实体上的应力输出接口。
 *
 * <p>提供 RPM / 应力容量的读写、理论转速查询与安全注入判据，供
 * {@link StressOutputManager} 驱动 Create 动力网；具体 Create 操作由 {@link KineticOps} 隔离。</p>
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