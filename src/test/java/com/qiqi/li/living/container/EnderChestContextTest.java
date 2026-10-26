package com.qiqi.li.living.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 末影箱锚点口径（2026-10-27 拍板：**存放处**）。
 *
 * <p><b>被守的行为</b>：{@link EnderChestContainerContext#getAnchor()} 必须是 {@code null} ——
 * 末影箱内容 = 货物，与箱子同权（不随身、不开机）。默认实现会经 {@code getOwnerPlayer()}
 * 退化成 {@code AtPlayer}，而那个退化正是 2026-10-27 审查抓到的两端不同源：
 * 背包在末影箱里被 K2 当「玩家宿主」上报 ⇒ 客户端算进随身辅助、服务端不展开末影箱。</p>
 */
class EnderChestContextTest {

    @Test
    @DisplayName("末影箱 ctx ⇒ 无锚点（存放处：不随身、不开机；退化成 AtPlayer 就是两端不同源的根因）")
    void anchorIsNull_storedNotCarried() {
        EnderChestContainerContext ctx = new EnderChestContainerContext(
            new ItemStackHandler(27), mock(Player.class), mock(Level.class));

        assertNull(ctx.getAnchor(), "必须覆写成 null；退化成 AtPlayer ⇒ K2 当玩家宿主上报");
        assertNull(ctx.getHostAnchor(), "非物品形态 ⇒ 宿主锚点恒 null（不借宿主开机）");
        assertEquals(-1, ctx.getHostSlot(), "非物品形态 ⇒ 无宿主槽位");
    }
}
