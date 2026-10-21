package com.qiqi.li.living.domain.power;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerHandoff;
import com.qiqi.li.living.container.ContainerTickHooks;
import com.qiqi.li.living.runtime.RuntimeSegmentRegistry;

/**
 * 红电（电力层）域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 */
public final class PowerRegistration {

    private PowerRegistration() {}

    public static void register() {
        LivingItemManager.registerFunction(new LivingWaxedCopperFunction());
        // 运行时片段：发电机把「检测仪表盘」的展示数据交给 runtime 机制（档 2）
        RuntimeSegmentRegistry.register(GeneratorSegment.INSTANCE);
        // 框架中继：相位快照写回钩子（2026-10-08 计划 ⑤）——
        // 原逻辑硬编码在 ContainerLivingItemHandler#writebackBlockEntities，现移入本领域。
        ContainerTickHooks.register(new ContainerPhaseWriteback());
        // 容器级数据的第三方交接（2026-10-21）：破坏/放置之外，模组自己搬走容器时（如精妙蹲下拾取）
        // 也要把相位搬过去 —— 与破坏路径共用 PhaseDataMigration.moveOut / moveIn 同一份实现。
        // ⚠️ 必须写**匿名类**而不是方法引用（方法引用只会覆盖它指向的那个方向）。
        ContainerHandoff.registerMover(new ContainerHandoff.Mover() {
            @Override
            public void moveOut(net.neoforged.neoforge.attachment.IAttachmentHolder host, net.minecraft.world.item.ItemStack target) {
                PhaseDataMigration.moveOut(host, target);
            }

            @Override
            public void moveIn(net.neoforged.neoforge.attachment.IAttachmentHolder host, net.minecraft.world.item.ItemStack source) {
                PhaseDataMigration.moveIn(host, source);
            }
        });
    }
}
