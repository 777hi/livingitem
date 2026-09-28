package com.qiqi.li.living.domain.farmland;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.interaction.BonemealHandler;
import com.qiqi.li.living.interaction.InteractionPredicates;
import com.qiqi.li.living.interaction.InteractionRegistry;
import com.qiqi.li.living.interaction.PlantCropHandler;
import com.qiqi.li.living.interaction.TillToFarmlandHandler;
import org.jetbrains.annotations.ApiStatus;

/**
 * 活耕地域注册入口 —— 见 {@code RedstoneRegistration} 的类注释了解为何有这个类（A1）。
 *
 * <p>本域的交互规则有一个特点：<b>不枚举物品清单，而是用能力谓词</b>
 * （{@code Tillables::canTillWith} 按 HOE_TILL 能力识别锄头、
 * {@code PlantCropHandler::canPlantWith} 收窄到「可种植种子」）⇒ 模组工具自动兼容。
 */
@ApiStatus.Internal
public final class FarmlandRegistration {

    private FarmlandRegistration() {}

    public static void register() {
        // ── 功能 ──
        LivingItemManager.registerFunction(new LivingFarmlandFunction());

        // ── 交互：活锄头耕活土 / 种植 / 骨粉 ──
        // 规则在 interaction_rules.json（D2 全迁）；这里注册行为（handler）
        // 与规则引用的谓词（JSON 写谓词 ID，Java 提供实现 —— 见 InteractionPredicates）。
        InteractionPredicates.register("can_till", Tillables::canTillWith);
        InteractionPredicates.register("can_plant", PlantCropHandler::canPlantWith);

        InteractionRegistry.registerHandler("till_to_farmland", new TillToFarmlandHandler());
        InteractionRegistry.registerHandler("plant_crop", new PlantCropHandler());
        InteractionRegistry.registerHandler("bonemeal", new BonemealHandler());
    }
}
