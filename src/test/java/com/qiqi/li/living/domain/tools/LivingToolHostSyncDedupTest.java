package com.qiqi.li.living.domain.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.domain.tools.LivingToolHostPacket.Entry;
import com.qiqi.li.living.domain.tools.LivingToolHostPacket.ToolRay;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * K2 去重键的<b>口径</b>：只比「渲染真正依赖的字段」。
 *
 * <h3>守什么</h3>
 * <p>客户端渲染用的是<b>本包带来的</b> ItemStack 副本（{@code LivingToolHostClientCache}），
 * 不是容器槽位的实时内容 ⇒ 去重键漏比一个字段，客户端就永远收不到那个字段的变化。</p>
 *
 * <p>2026-10-26 真机症状：方块容器里的活工具「有外显模型、没有动画」。
 * 根因是键里只有物品种类 + 记忆，{@code LIVING_TOOL_PROGRESS} /
 * {@code LIVING_TOOL_LAST_ACTION} / {@code LIVING_TOOL_DIG_TICKS} 翻转不触发重发，
 * 客户端那份永远停在"没在挖"。</p>
 *
 * <h3>两个方向都要守</h3>
 * <ul>
 *   <li><b>少比一个</b> ⇒ 没动画（本类的前三条用例）；</li>
 *   <li><b>多比一个</b> ⇒ 每 tick 刷包（最后一条用耐久钉住，2026-09-19 的教训）。</li>
 * </ul>
 * <p>即判据是「渲染依赖」而非「内容相等」。</p>
 */
class LivingToolHostSyncDedupTest {

    private static final BlockPos HOST = new BlockPos(10, 64, 10);
    private static final BlockPos TARGET = new BlockPos(12, 64, 10);

    /** 一把没有任何组件的活工具（记忆 / 进度 / 动作全缺 = 闲着）。 */
    private static ItemStack tool() {
        return new ItemStack(Items.DIAMOND_PICKAXE);
    }

    /** 包住「一个宿主 + 一把工具」的条目；命中布尔固定，只让被测组件变化。 */
    private static List<Entry> one(ItemStack stack) {
        return List.of(new Entry(HOST, List.of(new ToolRay(stack, true, false))));
    }

    @Test
    @DisplayName("只有挖掘进度不同 ⇒ 必须判为有变化（否则容器形态模型不转圈）")
    void progressFlipIsChange() {
        ItemStack idle = tool();
        ItemStack digging = tool();
        LivingToolProgress.set(digging, new LivingToolProgress(TARGET, 100L));

        assertFalse(LivingToolHostSync.same(one(idle), one(digging)),
            "进度翻转被判为无变化 ⇒ 客户端收不到「开始挖」⇒ 模型只画待机位");
    }

    @Test
    @DisplayName("只有瞬时动作不同 ⇒ 必须判为有变化（否则交互脉冲不播）")
    void actionFlipIsChange() {
        ItemStack before = tool();
        ItemStack acted = tool();
        LivingToolAction.set(acted, new LivingToolAction(200L, TARGET));

        assertFalse(LivingToolHostSync.same(one(before), one(acted)),
            "动作翻转被判为无变化 ⇒ 客户端不知道「刚交互过」⇒ 不播脉冲");
    }

    @Test
    @DisplayName("只有预计总 tick 不同 ⇒ 必须判为有变化（转圈周期靠它）")
    void digTicksChangeIsChange() {
        ItemStack before = tool();
        ItemStack digging = tool();
        ToolComponents.setDigTicks(before, 6);
        ToolComponents.setDigTicks(digging, 12);

        assertFalse(LivingToolHostSync.same(one(before), one(digging)),
            "总 tick 变了却不重发 ⇒ 客户端按旧周期转，节奏与真实挖掘脱节");
    }

    @Test
    @DisplayName("内容完全一致 ⇒ 判为无变化（去重本身仍然生效）")
    void identicalIsSame() {
        assertTrue(LivingToolHostSync.same(one(tool()), one(tool())));
    }

    @Test
    @DisplayName("只有耐久不同 ⇒ 仍判为无变化（易变字段不能塞回键里）")
    void durabilityIsIgnored() {
        ItemStack a = tool();
        ItemStack b = tool();
        b.setDamageValue(7);

        assertTrue(LivingToolHostSync.same(one(a), one(b)),
            "耐久进了去重键 ⇒ 每次破坏都重发（2026-09-19 实测退化为每 tick 一个包）");
    }
}
