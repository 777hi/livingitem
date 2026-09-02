package com.qiqi.li;

import com.mojang.datafixers.util.Either;
import com.qiqi.li.client.icon.LivingIconRegistry;
import com.qiqi.li.client.render.LivingChestTooltipRenderer;
import com.qiqi.li.client.render.LivingWaxedCopperTooltipRenderer;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.chest.LivingChestTooltipComponent;
import com.qiqi.li.living.domain.power.LivingWaxedCopperFunction;
import com.qiqi.li.living.domain.power.LivingWaxedCopperTooltipComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;

/**
 * 客户端入口类，通过 LivingIconRegistry 统一管理活物品图标。
 *
 * <p>添加新活物品图标只需两步：
 * <ol>
 *   <li>在 LivingIconRegistry.registerAll() 中添加 LivingIconSpec 声明</li>
 *   <li>准备对应的纹理和模型 JSON 文件</li>
 * </ol>
 *
 * <p>不再需要为每种活物品创建 ContextAwareXxxModel、LivingXxxModelWrapper、
 * LivingXxxItemOverrides 等类，这些已由通用组件替代。
 */
@Mod(value = LivingItem.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = LivingItem.MOD_ID, value = Dist.CLIENT)
public class LivingItemClient {

    public LivingItemClient(IEventBus modEventBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        LivingIconRegistry.registerAll();
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        LivingItem.LOGGER.info("HELLO FROM CLIENT SETUP");
        LivingItem.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());

        event.enqueueWork(() -> {
            ResourceLocation isLiving = ResourceLocation.fromNamespaceAndPath(LivingItem.MOD_ID, "is_living");
            ItemProperties.register(Items.HOPPER, isLiving, (stack, level, entity, seed) ->
                LivingItemManager.isLivingItem(stack) ? 1.0F : 0.0F);
            ItemProperties.register(Items.FURNACE, isLiving, (stack, level, entity, seed) ->
                LivingItemManager.isLivingItem(stack) ? 1.0F : 0.0F);
            ItemProperties.register(Items.TNT, isLiving, (stack, level, entity, seed) ->
                LivingItemManager.isLivingItem(stack) ? 1.0F : 0.0F);
        });
    }

    @SubscribeEvent
    static void onRegisterAdditionalModels(ModelEvent.RegisterAdditional event) {
        LivingIconRegistry.onRegisterAdditionalModels(event);
    }

    @SubscribeEvent
    static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        LivingIconRegistry.onModifyBakingResult(event);
    }

    @SubscribeEvent
    static void onRegisterItemDecorations(RegisterItemDecorationsEvent event) {
        LivingIconRegistry.onRegisterItemDecorations(event);
    }

    @SubscribeEvent
    static void onRegisterTooltipComponentFactories(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(LivingChestTooltipComponent.class, LivingChestTooltipRenderer::new);
        event.register(LivingWaxedCopperTooltipComponent.class, LivingWaxedCopperTooltipRenderer::new);
    }

    /**
     * 活涂蜡发电机的仪器面板：追加到 tooltip 末尾，仅 F3+H 高级模式显示。
     *
     * <p>刻意不走 {@code ItemStack.getTooltipImage()}——NeoForge 会把那个组件插在
     * 索引 1（紧跟物品名之后），而仪器面板属于进阶诊断信息，应当置底。</p>
     */
    @SubscribeEvent
    static void onGatherTooltipComponents(RenderTooltipEvent.GatherComponents event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        if (!LivingItemManager.isLivingItem(stack)) return;
        if (!LivingWaxedCopperFunction.isWaxedCopperBlock(stack.getItem())) return;
        if (LivingWaxedCopperFunction.isWaxedBulb(stack.getItem())) return;
        if (!Minecraft.getInstance().options.advancedItemTooltips) return;

        var data = LivingItemManager.getGeneratorData(stack);
        event.getTooltipElements().add(
            Either.right(LivingWaxedCopperTooltipComponent.from(data)));
    }
}