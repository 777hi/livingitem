package com.qiqi.li;

import com.mojang.datafixers.util.Either;
import com.qiqi.li.client.icon.LivingIconRegistry;
import com.qiqi.li.living.domain.tools.LivingToolHostClientCache;
import com.qiqi.li.client.render.LivingChestTooltipRenderer;
import com.qiqi.li.client.render.LivingToolModelRenderer;
import com.qiqi.li.client.render.LivingToolRayRenderer;
import com.qiqi.li.client.render.LivingWaxedCopperTooltipRenderer;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.chest.LivingChestTooltipComponent;
import com.qiqi.li.living.domain.power.LivingWaxedCopperFunction;
import com.qiqi.li.living.domain.power.LivingWaxedCopperTooltipComponent;
import com.qiqi.li.living.domain.power.LivingWaxedGeneratorData;
import com.qiqi.li.living.domain.runtime.LivingItemClientCache;
import com.qiqi.li.living.domain.runtime.LivingItemRuntimeData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
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
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
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
     * 活涂蜡发电机的仪器面板：追加到所有 tooltip 末尾（不再依赖 F3+H）。
     *
     * <p>面板包含相位圆盘、锈级柱状图、展开条三块图形内容，属于发电机核心信息，
     * 不应被 F3+H 开关挡住。文本层（{@code addToTooltip}）已随 F3+H 切换显示
     * 数字 / 公式，但图形面板始终可见。</p>
     *
     * <p>刻意不走 {@code ItemStack.getTooltipImage()}——NeoForge 会把那个组件插在
     * 索引 1（紧跟物品名之后），而仪器面板属于核心信息，应当与文本层一同置底。</p>
     *
     * <p>数据源：发电遥测走运行时缓存 + 网络同步。若读 DataComponent 会拿到全 0，
     * 相位圆盘与锈级柱状图整块消失（ItemTooltipEvent 的 ThreadLocal 在本事件触发前
     * 已清空，需按悬停槽位自行定位）。</p>
     */
    @SubscribeEvent
    static void onGatherTooltipComponents(RenderTooltipEvent.GatherComponents event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        if (!LivingItemManager.isLivingItem(stack)) return;
        if (!LivingWaxedCopperFunction.isWaxedCopperBlock(stack.getItem())) return;
        if (LivingWaxedCopperFunction.isWaxedBulb(stack.getItem())) return;

        event.getTooltipElements().add(
            Either.right(LivingWaxedCopperTooltipComponent.from(generatorDataFor(stack), stack.getCount())));
    }

    /**
     * 登录世界时清空 {@code K2} 的容器同步缓存。
     *
     * <p>维度校验只能挡住"同存档换维度"，挡不住"退出存档 → 进另一个存档"
     * （单人游戏常见，且不重启 JVM）—— 两者可能都是主世界，维度相同。
     * 不清就会把上一个存档的活工具残留渲染出来。</p>
     */
    @SubscribeEvent
    static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        LivingToolHostClientCache.clear();
    }

    /**
     * 活工具记忆射线可视化（{@code L19} / {@code L20}）—— 把"活工具打算挖哪"画给玩家看。
     *
     * <p>时机：<b>手持</b>始终显示，其余宿主仅 {@code F3+B} 时显示（详见渲染器类注释）。
     * 命中判定的分工见 {@code L48} —— 容器形态由服务端同步布尔，其余本地 {@code clip}。</p>
     */
    @SubscribeEvent
    static void onRenderLevelStage(RenderLevelStageEvent event) {
        LivingToolRayRenderer.render(event);
        LivingToolModelRenderer.render(event);
    }

    /**
     * 发电机仪表盘数据：优先运行时缓存（按悬停槽位定位），回退 DataComponent 兜底。
     */
    private static LivingWaxedGeneratorData generatorDataFor(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
            Slot hoveredSlot = containerScreen.getSlotUnderMouse();
            if (hoveredSlot != null && hoveredSlot.getItem() == stack) {
                // 玩家背包 GUI 的遥测在独立 player 缓存（服务端背包包直发本人）
                var runtimeData = mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen
                    || mc.screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
                    ? LivingItemClientCache.getPlayer(hoveredSlot.getContainerSlot())
                    : LivingItemClientCache.get(hoveredSlot.getContainerSlot());
                if (runtimeData.isGenerator()) return runtimeData.generatorTelemetry();
            }
        }
        // 兜底：特殊 GUI（创造模式物品栏 tab 等）槽位索引与 Inventory 不对齐时，
        // 按物品引用在玩家背包中定位
        if (mc.player != null) {
            var inv = mc.player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (inv.getItem(i) == stack) {
                    var runtimeData = LivingItemClientCache.getPlayer(i);
                    if (runtimeData.isGenerator()) return runtimeData.generatorTelemetry();
                    break;
                }
            }
        }
        return LivingItemManager.getGeneratorData(stack);
    }
}