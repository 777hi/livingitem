package com.qiqi.li.living.compat.tlm;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.qiqi.li.living.container.EntityContainerContext;
import net.minecraft.server.level.ServerLevel;

/** 六列女仆背包；仅包装 TLM 的可用存储视图，不包含装备、饰品或隐藏格。 */
public final class MaidContainerContext extends EntityContainerContext {
    public MaidContainerContext(EntityMaid maid, ServerLevel level) {
        super(maid, level, maid.getAvailableBackpackInv());
    }

    @Override
    public int getLiveColumns() {
        return 6;
    }
}
