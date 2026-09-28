package com.qiqi.li.living.interaction;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.farmland.Tillables;

/**
 * 活锄头右键活土 → 转换为耕地（物品转换型处理器）。
 *
 * <p>逻辑（docs/idea.md「获取方式」「交互处理器设计」）：
 *   - <b>锄头判定</b>：{@link Tillables#canTillWith} —— 光标能执行 HOE_TILL 且是活物品。
 *     原版锄头与声明了该能力的模组锄头一视同仁，不再枚举物品清单
 *   - <b>产物</b>：查 {@link Tillables#tilledResultOf} —— 泥土/草方块/土径 → 活耕地，
 *     砂土/缠根泥土 → 活泥土（可再耕一跳变活耕地）
 *   - <b>创造模式</b>：不消耗耐久，但光标的锄头校验与生存模式一致（与
 *     PlantCropHandler 同口径——创造模式光标经 carriedTag 已在服务端恢复，能正常校验）
 *   - 目标槽位物品原地替换，保留堆叠数与 IS_LIVING 标记</p>
 */
public class TillToFarmlandHandler implements InteractionHandler {

    @Override
    public void handle(ServerPlayer player, Slot targetSlot) {
        ItemStack source = targetSlot.getItem();
        Item result = Tillables.tilledResultOf(source);
        if (result == null) return;

        // 与客户端拦截共用同一门槛（含「双方都是活物品」校验）
        ItemStack carried = player.containerMenu.getCarried();
        if (!Tillables.canTillWith(carried, source)) return;

        if (!player.isCreative()) {
            // 不可损坏的模组锄头在 hurtAndBreak 内部静默跳过，不会报错
            carried.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
        }

        ItemStack tilled = new ItemStack(result, source.getCount());
        LivingItemManager.setLiving(tilled, true);
        targetSlot.set(tilled);

        player.containerMenu.broadcastChanges();
    }
}
