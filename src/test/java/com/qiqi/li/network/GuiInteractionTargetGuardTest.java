package com.qiqi.li.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.interaction.InteractionEntry;
import com.qiqi.li.living.interaction.InteractionRegistry;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * GUI 交互包的<b>服务端目标身份复验</b>（2026-10-27 审查 P3-2）。
 *
 * <p><b>被守的行为</b>：{@code isAcceptable} 只验「是活物品」——改过的客户端可对任意活物品
 * 发 {@code lever_toggle} 等任意 actionId，给非拉杆物品写入 {@code LIVING_LEVER_DATA}
 * （数据污染）。修复后分发处按 actionId 找规则条目、用<b>同一份</b>
 * {@code matchesTarget} 复验（与规则 JSON 的 target 同源，规则改了它自动跟）。</p>
 *
 * <p>⚠️ 判据用 item 型 target（tag 型条目在单测环境不绑标签，见 unit-testing.md 的已知限制）。</p>
 */
class GuiInteractionTargetGuardTest {

    private static void registerItemTarget(String actionId, net.minecraft.world.item.Item target) {
        InteractionRegistry.register(new InteractionEntry(
            target, null, null, null, 1, actionId, false, null));
    }

    @Test
    @DisplayName("lever_toggle + 拉杆 ⇒ 放行")
    void leverOnLever_accepted() {
        registerItemTarget("lever_toggle", Items.LEVER);
        assertTrue(GuiInteractionPacket.targetIdentityAccepted(
            "lever_toggle", new ItemStack(Items.LEVER)));
    }

    @Test
    @DisplayName("lever_toggle + 非拉杆 ⇒ 拒绝（改过的客户端不许污染任意活物品的数据）")
    void leverOnNonLever_rejected() {
        registerItemTarget("lever_toggle", Items.LEVER);
        assertFalse(GuiInteractionPacket.targetIdentityAccepted(
            "lever_toggle", new ItemStack(Items.DIAMOND_PICKAXE)),
            "不复验 ⇒ 任意活物品被写入 LIVING_LEVER_DATA");
    }

    @Test
    @DisplayName("规则表外的 actionId ⇒ 放行（守卫只管声明了 target 的规则）")
    void unknownActionId_accepted() {
        assertTrue(GuiInteractionPacket.targetIdentityAccepted(
            "no_such_action", new ItemStack(Items.DIAMOND_PICKAXE)));
    }

    @Test
    @DisplayName("目标格为空 ⇒ 放行（活桶汲/倒通道：判据在各自 handler 里重验）")
    void emptyTarget_accepted() {
        registerItemTarget("some_empty_slot_action", Items.LEVER);
        assertTrue(GuiInteractionPacket.targetIdentityAccepted(
            "some_empty_slot_action", ItemStack.EMPTY));
    }

    @Test
    @DisplayName("同源判据：条目的 matchesTarget 说什么，守卫就放行什么（stone_button 条目只认石按钮）")
    void guardFollowsEntryPredicate() {
        registerItemTarget("buttons_press", Items.STONE_BUTTON);
        assertTrue(GuiInteractionPacket.targetIdentityAccepted(
            "buttons_press", new ItemStack(Items.STONE_BUTTON)));
        assertFalse(GuiInteractionPacket.targetIdentityAccepted(
            "buttons_press", new ItemStack(Items.LEVER)));
    }
}
