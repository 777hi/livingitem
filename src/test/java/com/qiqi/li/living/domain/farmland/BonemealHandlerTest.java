package com.qiqi.li.living.domain.farmland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * 活骨粉 GUI 交互的<b>服务端信任边界</b>（2026-10-27 审查 P3）。
 *
 * <p><b>被守的行为</b>：光标校验对创造模式同样生效 —— {@code targetIdentityAccepted}
 * 只复验目标身份，触发物全靠 handler 自查；创造模式曾连「光标是不是活骨粉」都不验
 * ⇒ 改包客户端可空手刷生长。同域 {@code PlantCropHandler} / {@code TillToFarmlandHandler}
 * 的口径是「照验免扣」，本 handler 对齐。</p>
 *
 * <p>pin 手法：未成熟植株（age=0, maxAge=3）的 {@code forceGrowthTick} 完全不触碰
 * level ⇒ 「age 是否 +1」即「校验是否通过」的行为判据，无需打桩生长概率。</p>
 */
class BonemealHandlerTest {

    private static ItemStack livingFarmlandWithCrop() {
        ItemStack stack = new ItemStack(Items.FARMLAND);
        LivingItemManager.setLiving(stack, true);
        FarmlandPlantComponent.set(stack, new FarmlandPlantComponent(
            Items.WHEAT_SEEDS, 0, 3, 0L, -1, List.of()));
        return stack;
    }

    private static ItemStack livingBonemeal(int count) {
        ItemStack stack = new ItemStack(Items.BONE_MEAL, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    /** 服务端玩家替身：创造/生存 + 光标内容。 */
    private static ServerPlayer player(boolean creative, ItemStack carried) {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.isCreative()).thenReturn(creative);
        AbstractContainerMenu menu = mock(AbstractContainerMenu.class);
        when(menu.getCarried()).thenReturn(carried);
        player.containerMenu = menu;
        when(player.level()).thenReturn(mock(ServerLevel.class));
        return player;
    }

    private static net.minecraft.world.inventory.Slot slotWith(ItemStack farmland) {
        net.minecraft.world.inventory.Slot slot = mock(net.minecraft.world.inventory.Slot.class);
        when(slot.getItem()).thenReturn(farmland);
        return slot;
    }

    private static int ageOf(ItemStack farmland) {
        return FarmlandPlantComponent.of(farmland).age();
    }

    @Test
    @DisplayName("创造模式 + 光标是活骨粉 ⇒ 放行（age+1）且免扣")
    void creativeWithBonemeal_accepted() {
        ItemStack farmland = livingFarmlandWithCrop();
        ItemStack cursor = livingBonemeal(2);

        new BonemealHandler().handle(player(true, cursor), slotWith(farmland));

        assertEquals(1, ageOf(farmland), "校验通过 ⇒ 生长 +1");
        assertEquals(2, cursor.getCount(), "创造模式免扣");
    }

    @Test
    @DisplayName("创造模式 + 光标非骨粉 ⇒ 拒绝（信任缺口回归：曾空手可刷生长）")
    void creativeWithNonBonemeal_rejected() {
        ItemStack farmland = livingFarmlandWithCrop();

        new BonemealHandler().handle(player(true, new ItemStack(Items.DIRT)),
            slotWith(farmland));

        assertEquals(0, ageOf(farmland),
            "改包客户端空手发 bonemeal 包不许刷生长（服务端必须自查触发物）");
    }

    @Test
    @DisplayName("生存模式 + 活骨粉 ⇒ 放行且扣 1 个（既有口径回归）")
    void survivalWithBonemeal_consumes() {
        ItemStack farmland = livingFarmlandWithCrop();
        ItemStack cursor = livingBonemeal(2);

        new BonemealHandler().handle(player(false, cursor), slotWith(farmland));

        assertEquals(1, ageOf(farmland));
        assertEquals(1, cursor.getCount(), "生存模式扣 1 个");
    }

    @Test
    @DisplayName("生存模式 + 普通骨粉（未活化）⇒ 拒绝（活化门槛）")
    void survivalWithPlainBonemeal_rejected() {
        ItemStack farmland = livingFarmlandWithCrop();
        ItemStack cursor = new ItemStack(Items.BONE_MEAL, 2);

        new BonemealHandler().handle(player(false, cursor), slotWith(farmland));

        assertEquals(0, ageOf(farmland), "未活化的骨粉不是活骨粉");
    }
}
