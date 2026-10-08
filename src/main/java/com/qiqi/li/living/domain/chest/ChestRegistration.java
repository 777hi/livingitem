package com.qiqi.li.living.domain.chest;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerSnapshot;

/**
 * 活箱子域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 */
public final class ChestRegistration {

    private ChestRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingChestFunction());
        ContainerSnapshot.registerProvider(new ChestSnapshotProvider());
        // 框架中继（2026-10-08：transfer 不再认识本领域）——
        // ① 槽位访问器 Provider（活箱子槽位可展开为虚拟存储）
        com.qiqi.li.living.transfer.SlotAccessorFactory.registerProvider(LivingChestAccessor::tryCreate);
        // ② 容器类谓词（供 SlotAccessorFactory / SlotInteractions 判断「活物品能否作传输端」）
        com.qiqi.li.living.transfer.ContainerLikeItems.register(LivingChestFunction::isLivingChest);
    }
}
