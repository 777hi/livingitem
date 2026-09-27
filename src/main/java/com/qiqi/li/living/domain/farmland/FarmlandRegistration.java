package com.qiqi.li.living.domain.farmland;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.interaction.BonemealHandler;
import com.qiqi.li.living.interaction.InteractionEntry;
import com.qiqi.li.living.interaction.InteractionRegistry;
import com.qiqi.li.living.interaction.PlantCropHandler;
import com.qiqi.li.living.interaction.TillToFarmlandHandler;

/**
 * 活耕地域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 *
 * <p>本域的交互规则有一个特点：<b>不枚举物品清单，而是用能力谓词</b>
 * （{@code Tillables::canTillWith} 按 HOE_TILL 能力识别锄头、
 * {@code PlantCropHandler::canPlantWith} 收窄到「可种植种子」）⇒ 模组工具自动兼容。
 */
public final class FarmlandRegistration {

    private FarmlandRegistration() {}

    public static void register() {
        // ── 功能 ──
        LivingItemManager.registerFunction(new LivingFarmlandFunction());

        // ── 交互：活锄头耕活土 ──
        // 锄头不枚举：通配条目 + 谓词按 HOE_TILL 能力识别，模组锄头自动兼容。
        // 可耕目标逐条注册（映射见 Tillables）；谓词把拦截面收窄到「活着的锄头」。
        InteractionRegistry.registerHandler("till_to_farmland", new TillToFarmlandHandler());
        for (Item tillable : Tillables.tillableTargets()) {
            InteractionRegistry.register(new InteractionEntry(tillable, null, 1, "till_to_farmland",
                false, Tillables::canTillWith));
        }

        // ── 交互：种植 + 骨粉 ──
        // 种植：通配条目 + triggerFilter 收窄到「可种植种子」——只拦种子光标，
        //       空手/其他物品右键不拦截（原版拿起/分堆操作不受影响）
        // 骨粉：精确触发器（活骨粉）——两趟优先级匹配分流（见 InteractionRegistry javadoc）
        InteractionRegistry.registerHandler("plant_crop", new PlantCropHandler());
        InteractionRegistry.register(new InteractionEntry(Items.FARMLAND, null, 1, "plant_crop",
            false, PlantCropHandler::canPlantWith));

        InteractionRegistry.registerHandler("bonemeal", new BonemealHandler());
        InteractionRegistry.register(new InteractionEntry(Items.FARMLAND, Items.BONE_MEAL, 1, "bonemeal"));
    }
}
