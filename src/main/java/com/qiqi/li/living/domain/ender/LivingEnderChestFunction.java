package com.qiqi.li.living.domain.ender;

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
import net.minecraft.world.level.Level;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
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
        LivingEnderChestData data = LivingItemManager.getEnderChestData(stack);
        EnderChannelData channel = data.channel();

        // 频道键由绑定 UUID + 堆叠数决定，客户端可独立算出（DataComponent 随物品同步）
        EnderChannelKey key = EnderChannelKey.of(stack);

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.ender_chest.status"));

        if (channel.boundPlayerUuid().isPresent()) {
            String name = channel.boundPlayerName().orElse("???");
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
                    locStr = entry.sourcePos().toShortString();
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

    public static boolean isLivingEnderChest(ItemStack stack) {
        return stack.is(Items.ENDER_CHEST) && LivingItemManager.isLivingItem(stack);
    }

    public static boolean hasBoundPlayer(ItemStack stack) {
        if (!isLivingEnderChest(stack)) return false;
        return LivingItemManager.getEnderChestData(stack).channel().boundPlayerUuid().isPresent();
    }

    public static UUID getBoundPlayerUuid(ItemStack stack) {
        if (!isLivingEnderChest(stack)) return null;
        return LivingItemManager.getEnderChestData(stack).channel().getPlayerUuid().orElse(null);
    }

    public static String getBoundPlayerName(ItemStack stack) {
        if (!isLivingEnderChest(stack)) return null;
        return LivingItemManager.getEnderChestData(stack).channel().boundPlayerName().orElse(null);
    }

    public static void setBoundPlayer(ItemStack stack, UUID uuid, String name) {
        LivingEnderChestData data = LivingItemManager.getEnderChestData(stack);
        EnderChannelData channel = data.channel().withBoundPlayer(uuid, name);
        LivingItemManager.setEnderChestData(stack, data.withChannel(channel));
    }

    public static void clearBoundPlayer(ItemStack stack) {
        LivingEnderChestData data = LivingItemManager.getEnderChestData(stack);
        LivingItemManager.setEnderChestData(stack, data.withChannel(EnderChannelData.EMPTY));
    }

    @Override
    public Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of();
    }
}