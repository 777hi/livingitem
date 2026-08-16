package com.qiqi.li.living.api;

import java.util.List;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;

public interface HasContainerData {

    int getPriority();

    void tickContainerData(List<LivingItemFunction.SlotEntry> entries, ContainerContext ctx, TickContext tick);
}