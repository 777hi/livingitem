package com.qiqi.li.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import com.qiqi.li.LivingItem;
import com.qiqi.li.living.domain.tools.LivingToolMemory;
import com.qiqi.li.living.domain.tools.LivingToolRayTuning;
import com.qiqi.li.living.domain.tools.LivingToolRecorder;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 活工具/活武器<b>射线微调</b>网络包（客户端 → 服务端，2026-09-30）。
 *
 * <p>来源：背包界面上<b>点击玩家小人</b> —— 左键点躯干部位 = 设置射线起点锚点
 * （点脑袋 = 清除回默认眼睛），右键 = 切换朝向跟随（绑定玩家身体朝向）。
 * 配置写进<b>选中槽位（主手）</b>的物品，仅主动模式（有记忆）可配。</p>
 *
 * <p>安全性（同 {@code HopperDirectionPacket} 口径）：只收一个动作枚举，
 * <b>服务端自己定位物品与写入</b>，不信任客户端传来的任何其它数据；
 * 服务端重新校验「主手 = 有记忆的活工具/活武器」。</p>
 */
public record ToolRayTuningPacket(int action) implements CustomPacketPayload {

    public static final int ACTION_ANCHOR_TORSO_CENTER = 0;
    public static final int ACTION_ANCHOR_TORSO_BOTTOM = 1;
    public static final int ACTION_CLEAR_ANCHOR = 2;
    public static final int ACTION_TOGGLE_FOLLOW = 3;

    public static final Type<ToolRayTuningPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(LivingItem.MOD_ID, "tool_ray_tuning"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ToolRayTuningPacket> STREAM_CODEC =
        StreamCodec.composite(
            net.minecraft.network.codec.ByteBufCodecs.VAR_INT, ToolRayTuningPacket::action,
            ToolRayTuningPacket::new
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ToolRayTuningPacket packet, IPayloadContext context) {
        // ⚠️ 参考朝向用 getYRot()（视线朝向），不用 yBodyRot ——
        //    服务端的 ServerPlayer.yBodyRot 从不随移动包更新（handleMovePlayer 只写 yRot，
        //    LivingEntity 的 body 旋转推进只走 AI 分支），用它当参考 = 拿陈旧值当原点，
        //    绑定瞬间射线会整体跳变（= 重置玩家与射线的关系，2026-09-30 实测踩坑）。
        //    对玩家而言站立时身体朝向 == 视线朝向，两端（服务端捕获/客户端渲染）统一用 yRot。
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || player.isFakePlayer()) {
                return;
            }
            ItemStack stack = player.getMainHandItem();
            if (!LivingToolRecorder.isLivingToolOrWeapon(stack)) {
                return;
            }
            if (LivingToolMemory.of(stack).isEmpty()) {
                return;   // 被动模式（无记忆）没有射线，不配置
            }

            LivingToolRayTuning tuning = LivingToolRayTuning.of(stack);
            switch (packet.action()) {
                case ACTION_ANCHOR_TORSO_CENTER -> {
                    LivingToolRayTuning.set(stack, new LivingToolRayTuning(
                        LivingToolRayTuning.RayAnchor.TORSO_CENTER, tuning.followBody(), tuning.followRefYaw()));
                    feedback(player, "msg.livingitem.tool.ray.origin.center");
                }
                case ACTION_ANCHOR_TORSO_BOTTOM -> {
                    LivingToolRayTuning.set(stack, new LivingToolRayTuning(
                        LivingToolRayTuning.RayAnchor.TORSO_BOTTOM, tuning.followBody(), tuning.followRefYaw()));
                    feedback(player, "msg.livingitem.tool.ray.origin.bottom");
                }
                case ACTION_CLEAR_ANCHOR -> {
                    LivingToolRayTuning.set(stack, new LivingToolRayTuning(
                        null, tuning.followBody(), tuning.followRefYaw()));
                    feedback(player, "msg.livingitem.tool.ray.origin.default");
                }
                case ACTION_TOGGLE_FOLLOW -> {
                    boolean follow = !tuning.followBody();
                    LivingToolRayTuning.set(stack, new LivingToolRayTuning(
                        tuning.anchor(), follow, follow ? player.getYRot() : tuning.followRefYaw()));
                    feedback(player, follow ? "msg.livingitem.tool.ray.follow.on" : "msg.livingitem.tool.ray.follow.off");
                }
                default -> { }
            }

            // 客户端立即拿到新配置 —— 射线渲染依赖它；
            // （broadcastChanges 对「只变自定义组件」可能检测不到，见 syncStateFlip 的已知坑）
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
                -2, 0, player.getInventory().selected, stack.copy()));
        });
    }

    private static void feedback(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }
}
