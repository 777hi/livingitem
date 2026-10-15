package com.qiqi.li.living.domain.beacon;

import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;

/**
 * 活信标的功能实现（设计稿 {@code docs/buffer/living-beacon-design.md}）。
 *
 * <p>Phase 1（2026-10-16）：仅骨架 —— canApply 识别 + 空 tick。
 * 宿主检测 / 下方 9 列金字塔等级 / buff 施加 / 遥测段属 Phase 2/3
 * （见设计稿 §6 落地顺序、§2.7 复用图谱）。</p>
 *
 * <p>光束是<b>纯客户端渲染</b>（{@code LivingBeamRenderer} 读本机背包本地画），
 * 故 Phase 1 服务端无逻辑。</p>
 */
public class BeaconFunction implements LivingItemFunction {

    public static final String ID = "living_beacon";

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.BEACON) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() {
        return ID;
    }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext container, TickContext tick, Level level) {
        if (level.isClientSide) {
            return;
        }
        // Phase 1 无服务端逻辑（等级判定 / buff / 开盖同步属后续阶段）
    }
}
