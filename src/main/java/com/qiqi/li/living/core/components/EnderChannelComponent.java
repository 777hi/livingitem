package com.qiqi.li.living.core.components;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.accessor.EnderChannelRegistry;
import com.qiqi.li.living.function.LivingEnderChestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * 活末影箱频道组件 (Ender Channel Component)
 *
 * <h2>功能概述</h2>
 * <p>
 * 负责活末影箱的路由生命周期管理和 Tooltip 显示：
 * </p>
 * <ul>
 *   <li><strong>路由清理</strong>：tick() 时清理已移走的活末影箱和源物品关联的路由</li>
 *   <li><strong>Tooltip 显示</strong>：根据绑定状态显示频道信息或路由详情</li>
 * </ul>
 *
 * <h2>架构定位</h2>
 * <p>
 * 本组件只负责路由的<strong>生命周期管理</strong>（清理 + 显示）。
 * 路由注册和提取逻辑由 {@link com.qiqi.li.living.core.accessor.LivingEnderChestAccessor} 处理，
 * 由 {@link ItemTransferComponent} 通过 SlotAccessor 触发。
 * </p>
 *
 * <h2>双模式支持</h2>
 * <ul>
 *   <li><strong>路由模式</strong>（无绑定玩家）：显示频道号、路由数量、路由详情</li>
 *   <li><strong>直连模式</strong>（有绑定玩家）：显示绑定玩家名称</li>
 * </ul>
 *
 * <h2>使用方式</h2>
 * <pre>
 * // 在 LivingEnderChestFunction 中注册
 * .addComponent(EnderChannelComponent.class, ComponentConfig.empty())
 * </pre>
 */
public class EnderChannelComponent implements ILivingComponent {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String ID = "ender_channel";

    private static final String KEY_CHANNEL = "channel";
    private static final String KEY_ROUTE_COUNT = "route_count";
    private static final String KEY_LAST_CLEANUP_TICK = "last_cleanup_tick";

    @Override
    public String getComponentId() {
        return ID;
    }

    @Override
    public ComponentState createDefaultState() {
        ComponentState state = new ComponentState();
        state.setInt(KEY_CHANNEL, 1);
        state.setInt(KEY_ROUTE_COUNT, 0);
        state.setLong(KEY_LAST_CLEANUP_TICK, 0);
        return state;
    }

    /**
     * tick() 核心逻辑：清理失效路由。
     *
     * <p>每 tick 执行一次，收集当前容器中所有活末影箱的槽位，
     * 调用 EnderChannelRegistry 清理：</p>
     * <ol>
     *   <li>targetSlot 不在活跃列表中的路由（活末影箱被移走）</li>
     *   <li>源物品已被移走或替换的路由</li>
     * </ol>
     *
     * <p>为避免多个活末影箱重复清理，使用 last_cleanup_tick 状态
     * 确保同一 tick 内只清理一次。</p>
     */
    @Override
    public void tick(ComponentContext context, int hostSlot, ItemStack hostStack,
                     ComponentState state, ComponentConfig config) {
        var level = context.level();
        if (level == null || level.isClientSide()) return;

        long currentTick = level.getGameTime();
        long lastCleanup = state.getLong(KEY_LAST_CLEANUP_TICK, 0);
        if (currentTick == lastCleanup) return;

        state.setLong(KEY_LAST_CLEANUP_TICK, currentTick);

        ContainerContext containerCtx = context.containerCtx();
        Set<Integer> activeEnderChestSlots = new HashSet<>();
        int containerSize = containerCtx.getSize();
        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = containerCtx.getItem(i);
            if (LivingEnderChestFunction.isLivingEnderChest(stack)) {
                activeEnderChestSlots.add(i);
            }
        }

        EnderChannelRegistry registry = EnderChannelRegistry.getInstance();
        registry.removeStaleEnderChestRoutes(activeEnderChestSlots);
        int cleaned = registry.cleanStaleSourceRoutes(containerCtx);

        // 更新路由统计
        int channel = hostStack.getCount();
        state.setInt(KEY_CHANNEL, channel);
        state.setInt(KEY_ROUTE_COUNT, registry.getChannelSize(channel));

        if (cleaned > 0) {
            LOGGER.debug("EnderChannelComponent: cleaned {} stale routes at tick {}", cleaned, currentTick);
        }
    }

    @Override
    public void appendTooltip(ComponentState state, Consumer<Component> tooltipAdder) {
        // 注意：实际 Tooltip 渲染需要访问 ItemStack 和 TooltipFlag，
        // 这些信息在 appendTooltip() 签名中不可用。
        // 因此 Tooltip 逻辑仍保留在 LivingEnderChestFunction.addToTooltip() 中，
        // 本组件只提供状态数据。
    }

    /**
     * 获取当前频道号。
     */
    public static int getChannel(ComponentState state) {
        return state.getInt(KEY_CHANNEL, 1);
    }

    /**
     * 获取当前路由数量。
     */
    public static int getRouteCount(ComponentState state) {
        return state.getInt(KEY_ROUTE_COUNT, 0);
    }

    /**
     * 构建 Tooltip 显示内容（供 LivingEnderChestFunction 调用）。
     */
    public static void buildTooltip(ItemStack stack, CompoundTag functionData,
                                    Consumer<Component> tooltipAdder, TooltipFlag flag) {
        if (LivingEnderChestFunction.hasBoundPlayer(stack)) {
            String name = LivingEnderChestFunction.getBoundPlayerName(stack);
            if (name == null) name = "???";
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.ender_chest.bound_player", name)
                .withStyle(style -> style.withColor(0x55FFFF)));
        } else {
            int channel = stack.getCount();
            var registry = EnderChannelRegistry.getInstance();
            int routeCount = registry.getChannelSize(channel);
            int totalRoutes = registry.getTotalRouteCount();

            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.ender_chest.channel", channel)
                .withStyle(style -> style.withColor(0xAAAAAA)));
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.ender_chest.routes", routeCount, totalRoutes)
                .withStyle(style -> style.withColor(0xAAAAAA)));

            if (routeCount > 0 && flag.isAdvanced()) {
                var entries = registry.getEntries(channel);
                for (var entry : entries) {
                    String locStr;
                    if (entry.sourcePos() != null) {
                        locStr = entry.sourcePos().toShortString();
                    } else if (entry.containerKey() != null) {
                        locStr = entry.containerKey();
                    } else {
                        locStr = "???";
                    }
                    tooltipAdder.accept(Component.literal(
                        "  " + entry.itemType() + " @" + locStr + " slot=" + entry.sourceSlot())
                        .withStyle(style -> style.withColor(0x777777)));
                }
            }
        }
    }
}