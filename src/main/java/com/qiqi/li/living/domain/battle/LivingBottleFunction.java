package com.qiqi.li.living.domain.battle;

import java.util.List;
import java.util.Set;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * 活不祥之瓶 —— 大战僵尸的驱动器活物品（D-battle-06）。
 *
 * <p>瓶本体也是一个**棋子**（§4.2 全活物品参战，作为王棋被啃）；它的**战局进度组件**
 * 归本功能 owned（取消活化清空——用户生命周期设想第 4 条）。战局的建场/清场/波次
 * 编排在容器级（{@link LivingBattleFunction}，读瓶驱动），本功能 per-item tick 为空。</p>
 */
public class LivingBottleFunction implements LivingItemFunction {

    public static final String ID = "living_bottle";

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.OMINOUS_BOTTLE) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() {
        return ID;
    }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext container, TickContext tick, Level level) {
        // no-op：战局编排走容器级 LivingBattleFunction（读瓶驱动）
    }

    /** 进度组件归瓶：取消活化时随之清空（用户设想第 4 条）。 */
    @Override
    public Set<DataComponentType<?>> getOwnedComponentTypes() {
        return Set.of(BattleComponents.BATTLE_PROGRESS.get());
    }
}
