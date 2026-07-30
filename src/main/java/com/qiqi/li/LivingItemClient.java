package com.qiqi.li;

import com.qiqi.li.client.icon.LivingIconRegistry;
import com.qiqi.li.client.render.LivingChestTooltipRenderer;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.ender.LivingChestTooltipComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;
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

    public LivingItemClient(ModContainer container) {
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
    }
}