package com.qiqi.li.living.domain.ender;
import com.qiqi.li.living.transfer.LivingComponents;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.components.OwnerNameResolver;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.ender.EnderChannelClientCache;
import com.qiqi.li.living.domain.ender.EnderChannelRegistry;
import com.qiqi.li.living.domain.ender.EnderChannelData;
import com.qiqi.li.living.domain.ender.LivingEnderChestData;
import com.qiqi.li.living.domain.hopper.LivingHopperFunction;

public class LivingEnderChestFunction implements LivingItemFunction {

    public static final String ID = "living_ender_chest";

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.ENDER_CHEST) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() { return ID; }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        if (level.isClientSide) return;

        Set<Integer> activeEnderChestSlots = new HashSet<>();
        for (SlotEntry entry : entries) {
            activeEnderChestSlots.add(entry.slotIndex());
            // ⭐ 顺手刷新绑定玩家名的显示缓存（binding UUID 不动）：
            //    绑定玩家在线且同维度时，把「当前确认的名字」写回缓存 ——
            //    玩家改名必然发生在线上，下一次 tick 即跟上；名字没变时不写，无脏写开销。
            refreshBoundPlayerNameCache(entry, level);
        }

        Set<Integer> activeHopperSlots = tick.getFunctionSlots(LivingHopperFunction.ID);

        // 槽位 → 当前频道键。玩家拆分/合并堆叠会改变频道键甚至切换工作模式，
        // validateRoutes 据此清理旧频道下的遗留路由，避免路由泄漏。
        Map<Integer, EnderChannelKey> targetKeysBySlot = EnderChannelKey.ofSlots(context, activeEnderChestSlots);
        EnderChannelRegistry.getInstance().validateRoutes(context, activeHopperSlots, targetKeysBySlot);
    }

    @Override
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        LivingEnderChestData data = LivingEnderChestData.of(stack);
        EnderChannelData channel = data.channel();

        // 频道键由绑定 UUID + 堆叠数决定，客户端可独立算出（DataComponent 随物品同步）
        EnderChannelKey key = EnderChannelKey.of(stack);

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.ender_chest.status"));

        if (channel.boundPlayerUuid().isPresent()) {
            // ⭐ 与活工具/活武器统一（2026-09-30）：实时解析 → 绑定玩家名缓存 → 短 UUID，
            //    见 OwnerNameResolver#displayName；缓存的服务端刷新见下方 tick。
            String name = OwnerNameResolver.displayName(
                channel.getPlayerUuid().orElseThrow(), channel.boundPlayerName().orElse(null));
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.ender_chest.bound_player", name)
                .withStyle(style -> style.withColor(0xDD44FF).withBold(true)));
        }

        if (key.isDirect()) {
            // 直连模式：直接读写绑定玩家的末影箱背包，不经过路由表
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.ender_chest.direct_mode")
                .withStyle(style -> style.withColor(0x33E6C4)));
            return;
        }

        // 路由模式：玩家专属频道（已绑定且堆叠数 ≥ 2）或公共频道（未绑定）
        var snapshot = EnderChannelClientCache.getSnapshot(key);

        String channelKey = key.isPrivateChannel()
            ? "tooltip.livingitem.ender_chest.private_channel"
            : "tooltip.livingitem.ender_chest.channel";
        tooltipAdder.accept(Component.translatable(channelKey, key.count())
            .withStyle(style -> style.withColor(0xCC66FF)));

        tooltipAdder.accept(Component.translatable(
            "tooltip.livingitem.ender_chest.routes", snapshot.channelSize())
            .withStyle(style -> style.withColor(0xAA88FF)));

        if (snapshot.channelSize() > 0 && flag.isAdvanced()) {
            for (var entry : snapshot.entries()) {
                String locStr;
                if (entry.sourcePos() != null) {
                    // 方块容器：维度 + 坐标（高级模式需显式标注维度，路由可跨维度）
                    String dim = entry.dimKey() != null ? entry.dimKey() : "?";
                    locStr = dim + " " + entry.sourcePos().toShortString();
                } else if (entry.containerKey() != null && entry.containerKey().startsWith("player_")) {
                    // 玩家背包 / 末影箱：优先显示玩家名，离线则回退到 UUID（维度附在前面）
                    String dim = entry.dimKey() != null ? entry.dimKey() + " " : "";
                    String name = entry.playerName() != null ? entry.playerName() : parsePlayerId(entry.containerKey());
                    locStr = dim + "player " + name;
                } else if (entry.dimKey() != null) {
                    locStr = entry.dimKey();
                } else {
                    locStr = "???";
                }
                tooltipAdder.accept(Component.literal(
                    "  " + entry.itemType() + " @" + locStr + " slot=" + entry.sourceSlot())
                    .withStyle(style -> style.withColor(0x9966CC).withItalic(true)));
            }
        }
    }

    /**
     * 从玩家容器 key（"player_&lt;uuid&gt;" 或 "player_&lt;uuid&gt;_ender_chest"）中提取玩家 UUID 字符串。
     */
    private static String parsePlayerId(String containerKey) {
        String s = containerKey.substring("player_".length());
        if (s.endsWith("_ender_chest")) {
            s = s.substring(0, s.length() - "_ender_chest".length());
        }
        return s;
    }

    public static boolean isLivingEnderChest(ItemStack stack) {
        return stack.is(Items.ENDER_CHEST) && LivingItemManager.isLivingItem(stack);
    }

    public static boolean hasBoundPlayer(ItemStack stack) {
        if (!isLivingEnderChest(stack)) return false;
        return LivingEnderChestData.of(stack).channel().boundPlayerUuid().isPresent();
    }

    public static UUID getBoundPlayerUuid(ItemStack stack) {
        if (!isLivingEnderChest(stack)) return null;
        return LivingEnderChestData.of(stack).channel().getPlayerUuid().orElse(null);
    }

    public static void setBoundPlayer(ItemStack stack, UUID uuid, String nameCache) {
        LivingEnderChestData data = LivingEnderChestData.of(stack);
        EnderChannelData channel = data.channel().withBoundPlayer(uuid, nameCache);
        LivingEnderChestData.set(stack, data.withChannel(channel));
    }

    /**
     * 刷新绑定玩家名的<b>显示缓存</b>（tooltip 兜底，见 {@code OwnerNameResolver#displayName}）。
     *
     * <p>绑定玩家在线且同维度时把「当前确认的名字」写回缓存 —— 名字没变时不写；
     * 离线 / 跨维度自然跳过（此时缓存保持上次确认值，正是离线显示想要的）。</p>
     */
    private static void refreshBoundPlayerNameCache(LivingItemFunction.SlotEntry entry, Level level) {
        ItemStack stack = entry.stack();
        UUID bound = getBoundPlayerUuid(stack);
        if (bound == null) {
            return;
        }
        Player online = level.getPlayerByUUID(bound);
        if (online == null) {
            return;
        }
        String current = online.getName().getString();
        LivingEnderChestData data = LivingEnderChestData.of(stack);
        if (!current.equals(data.channel().boundPlayerName().orElse(null))) {
            LivingEnderChestData.set(stack, data.withChannel(
                data.channel().withPlayerNameCache(current)));
        }
    }

    public static void clearBoundPlayer(ItemStack stack) {
        LivingEnderChestData data = LivingEnderChestData.of(stack);
        LivingEnderChestData.set(stack, data.withChannel(EnderChannelData.EMPTY));
    }

    @Override
    public Set<DataComponentType<?>> getOwnedComponentTypes() {
        return Set.of(LivingComponents.LIVING_ENDER_CHEST_DATA.value());
    }

    @Override
    public Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of();
    }
}