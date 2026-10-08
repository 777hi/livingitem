package com.qiqi.li.living.domain.ender;
import com.qiqi.li.living.components.LivingComponents;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.api.LivingItemActivation;
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

/**
 * 活末影箱的功能实现。
 *
 * <p>玩家在末影箱 GUI 内活化时绑定到该玩家（直连其末影箱背包），取消活化解绑；每 tick 通过
 * {@code EnderChannelRegistry} 维护与活漏斗之间的路由表。未绑定时落回「路由 / 公共频道」模式——这是其一等公民形态。</p>
 */
public class LivingEnderChestFunction implements LivingItemFunction {

    public static final String ID = "living_ender_chest";

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.ENDER_CHEST) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() { return ID; }

    /**
     * 【活化时机】在末影箱 GUI 内活化时绑定当前玩家 —— 原先写在网络包里。
     *
     * <p>绑定的是「某个玩家的末影箱」⇒ <b>语义必然依赖玩家</b>，没有玩家就没有绑定。
     * 而<b>未绑定不是错误状态</b>：本功能本就有一等公民的「路由模式（共享黑板）」，
     * 不绑定即落回该模式（{@code player == null} ⇒ 直接返回）。</p>
     *
     * <p>⚠️ 「是否在末影箱 GUI 内」问的是<b>玩家正在干什么</b>（触发场景），
     * 不是物品数据 —— 所以判定只在这里做，别的功能不需要知道。</p>
     */
    @Override
    public void onActivated(ItemStack stack, Level level, @Nullable Player player,
            LivingItemActivation.Via via) {
        if (player == null) return;                 // 无玩家 ⇒ 不绑定，落回路由模式
        if (!isInEnderChestGui(player)) return;     // 不在末影箱界面 ⇒ 不绑定
        setBoundPlayer(stack, player.getUUID(), player.getName().getString());
        com.qiqi.li.logging.ModLog.CONTAINER.info("活末影箱绑定玩家: {}", player.getName().getString());
    }

    /** 【活化时机】取消活化时清空绑定（原本就是网络包里的内联判断，收编到此处）。 */
    @Override
    public boolean onDeactivated(ItemStack stack, Level level, @Nullable Player player,
            LivingItemActivation.Via via) {
        clearBoundPlayer(stack);
        return true;                                 // 清绑定不需要玩家
    }

    /** 玩家当前是否正打开着自己的末影箱 —— 「在末影箱界面里」是唯一会绑定的前提。 */
    private static boolean isInEnderChestGui(Player player) {
        // 判据唯一实现点在 ContainerContexts（2026-10-06 第 ③ 次泄漏修复后与快照派发共用）
        return com.qiqi.li.living.container.ContainerContexts.isViewingEnderChest(player);
    }

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
        return Set.of(LivingEnderChestData.COMPONENT.value());
    }

    @Override
    public Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of();
    }
}