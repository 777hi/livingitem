package com.qiqi.li.living.api;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;

public interface HasContainerData {

    void tickContainerData(ContainerContext ctx, TickContext tick);
}