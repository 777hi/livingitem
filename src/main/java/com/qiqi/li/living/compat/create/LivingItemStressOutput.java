package com.qiqi.li.living.compat.create;

public interface LivingItemStressOutput {

    void livingItem$setGeneratedRPM(float rpm);

    float livingItem$getGeneratedRPM();

    void livingItem$setStressCapacity(float capacity);

    float livingItem$getStressCapacity();

    boolean livingItem$isSafeForStressInjection();

    float livingItem$getTheoreticalSpeed();
}