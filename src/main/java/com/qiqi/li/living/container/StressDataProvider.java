package com.qiqi.li.living.container;

import com.qiqi.li.living.domain.water.ContainerStressData;

public interface StressDataProvider {

    ContainerStressData livingItem$getStressData();

    void livingItem$setStressData(ContainerStressData data);
}