package com.qiqi.li.living.domain.farmland;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.transfer.SlotInteraction;

/**
 * 内置槽位交互：骨粉 → 活耕地 = <b>施肥</b>（强制一次生长 tick）。
 *
 * <p>活耕地是活物品、非存储容器——通用插入对它必然失败
 * （{@code SlotAccessorFactory.create} 对非箱类活物品返回 null），
 * 「施肥」就是它的「插入」语义。</p>
 *
 * <p><b>触发物口径</b>（2026-09-15 定稿，与 GUI 右键有意区分）：
 * 本交互面向<b>传输语义</b>——<b>只认普通骨粉</b>。施肥是「活漏斗用传输能力把骨粉
 * 送进活耕地」，因此受漏斗自身的货物规则约束：<b>活物品不作货物</b>
 * （唯一定义点 {@code SlotInteractions.isEligibleCargo}）⇒ 活骨粉不施肥；
 * GUI 活骨粉右键（{@code BonemealHandler}）面向<b>手动能力</b>——要求活骨粉。
 * 口径 = 手动要活化、自动要普通。</p>
 *
 * <p>语义与消耗全部落在 {@link LivingFarmlandFunction#tryFertilize}：
 * 强制一次生长 tick（未成熟 +1 / 成熟待输出为空则冻结战利品表）、
 * equals 零空转（已冻结的成熟耕地不烧粉）、固定 1 粉/次不随漏斗堆叠放大。</p>
 *
 * <p>注册：{@code SlotInteractions} 静态块（内置条目）。此前该方程硬编码在
 * 三处传输分支里，拉取方向曾漏写导致「跨容器骨粉 → 同容器活耕地」不施肥
 * （2026-09-15 实测），现已收编为单条注册项。</p>
 */
public class FarmlandBonemealInteraction implements SlotInteraction {

    @Override
    public boolean matches(ItemStack cargo, ItemStack target) {
        return cargo.is(Items.BONE_MEAL)
            && target.is(Items.FARMLAND)
            && LivingItemManager.isLivingItem(target);
    }

    @Override
    public boolean interact(ItemStack cargo, ItemStack target, ServerLevel level) {
        // cargo 是模拟提取的拷贝——tryFertilize 会 shrink 它（改拷贝无副作用）
        return LivingFarmlandFunction.tryFertilize(target, cargo, level);
    }
}
