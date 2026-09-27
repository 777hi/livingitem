package com.qiqi.li.living.domain.redstone;

import net.minecraft.world.item.Items;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerSnapshot;
import com.qiqi.li.living.interaction.ButtonPressHandler;
import com.qiqi.li.living.interaction.ComparatorToggleHandler;
import com.qiqi.li.living.interaction.InteractionEntry;
import com.qiqi.li.living.interaction.InteractionRegistry;
import com.qiqi.li.living.interaction.LeverToggleHandler;
import com.qiqi.li.living.interaction.RepeaterCycleHandler;

/**
 * 红石域注册入口 —— 本领域的 Function / 交互规则 / 交互处理器 / 快照贡献者。
 *
 * <p><b>为什么有这个类</b>（A1，2026-09-27）：原先这些都写在
 * {@code LivingItem#commonSetup} 里（一个 152 行的方法），任何改动都落在同一处 ⇒
 * fork 者与上游的 diff 反复撞车。改为各域自注册后，改动的 diff 落在<b>本域自己的文件</b>里。
 *
 * <p>归属判据：<b>按语义域，不是按 Java 包</b>。
 * {@code ButtonPressHandler} 等类住在 {@code living/interaction/} 包，
 * 但语义上属于红石域 ⇒ 注册跟语义走。
 *
 * <p>由 {@code LivingItem#commonSetup} 调用一次。
 */
public final class RedstoneRegistration {

    private RedstoneRegistration() {}

    public static void register() {
        // ── 功能 ──
        LivingItemManager.registerFunction(new LivingRedstoneFunction());
        LivingItemManager.registerFunction(new LivingRedstoneTorchFunction());
        LivingItemManager.registerFunction(new LivingButtonFunction());
        LivingItemManager.registerFunction(new LivingLeverFunction());
        LivingItemManager.registerFunction(new LivingRedstoneLampFunction());
        LivingItemManager.registerFunction(new LivingRepeaterFunction());
        LivingItemManager.registerFunction(new LivingComparatorFunction());
        LivingItemManager.registerFunction(new LivingRedstoneBlockFunction());
        LivingItemManager.registerFunction(new LivingCopperFunction());

        // ── 容器快照贡献者 ──
        ContainerSnapshot.registerProvider(new RedstoneSnapshotProvider());

        // ── 交互：按钮按压 ──
        // 十三种按钮逐个注册（原版没有统一的"按钮"标签）
        InteractionRegistry.registerHandler("button_press", new ButtonPressHandler());
        InteractionRegistry.register(new InteractionEntry(Items.STONE_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.POLISHED_BLACKSTONE_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.OAK_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.SPRUCE_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.BIRCH_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.JUNGLE_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.ACACIA_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.CHERRY_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.DARK_OAK_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.MANGROVE_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.BAMBOO_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.CRIMSON_BUTTON, null, 1, "button_press"));
        InteractionRegistry.register(new InteractionEntry(Items.WARPED_BUTTON, null, 1, "button_press"));

        // ── 交互：拉杆 / 中继器 / 比较器 ──
        InteractionRegistry.registerHandler("lever_toggle", new LeverToggleHandler());
        InteractionRegistry.register(new InteractionEntry(Items.LEVER, null, 1, "lever_toggle"));

        InteractionRegistry.registerHandler("repeater_cycle", new RepeaterCycleHandler());
        InteractionRegistry.register(new InteractionEntry(Items.REPEATER, null, 1, "repeater_cycle"));

        InteractionRegistry.registerHandler("comparator_toggle", new ComparatorToggleHandler());
        InteractionRegistry.register(new InteractionEntry(Items.COMPARATOR, null, 1, "comparator_toggle"));
    }
}
