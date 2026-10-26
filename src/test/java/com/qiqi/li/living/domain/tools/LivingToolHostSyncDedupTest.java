package com.qiqi.li.living.domain.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.container.HostRef;
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
        return List.of(new Entry(
            HostRef.ofBlock(HOST), List.of(new ToolRay(stack, true, false))));
    }

    /**
     * 宿主本身必须参与去重 —— 内容一模一样、只是宿主换了，也要重发。
     *
     * <p>守的是物品形态容器（活背包）：背包从箱子挪到玩家身上（或换了个玩家），
     * 工具内容没变，但<b>客户端要画的位置变了</b>。宿主不进键 ⇒ 客户端永远停在旧位置。</p>
     */
    /**
     * 同一个箱子里的两个背包（{@code AtBlock} + 不同槽位）也必须是两条条目。
     *
     * <p>守的是 2026-10-26 审查抓到的漏：{@code hostRefOf} 的 {@code AtBlock} 分支一开始
     * <b>把 slot 丢了</b> —— 玩家身上的两个背包（{@code AtPlayer}）修好了，但**放在箱子里的**
     * 两个背包仍然撞（一个箱子一个 pos）⇒ 后写覆盖先写 ⇒ 只画一个。</p>
     */
    @Test
    @DisplayName("同一个箱子里的两个背包（AtBlock + 不同槽位）⇒ 必须判为不同条目")
    void sameBlockHostDifferentSlot_areDistinct() {
        ItemStack stack = tool();
        List<Entry> slot3 = List.of(new Entry(
            HostRef.ofBlock(HOST, 3), List.of(new ToolRay(stack, true, false))));
        List<Entry> slot7 = List.of(new Entry(
            HostRef.ofBlock(HOST, 7), List.of(new ToolRay(stack, true, false))));

        assertFalse(LivingToolHostSync.same(slot3, slot7),
            "同一个箱子里两个背包撞成一条 ⇒ 只画一个（AtBlock 分支漏 slot 就是这个症状）");
    }

    /**
     * 同一宿主的两个背包（不同槽位）必须是<b>两条</b>条目。
     *
     * <p>守的是 2026-10-26 发现的覆盖 bug：{@code current} 是 {@code Map<HostRef, …>}，
     * 若键只到「宿主」，玩家身上两个活背包两次上报会撞成同一条 ⇒ 后写覆盖先写
     * ⇒ 客户端只画一个。槽位是它们的唯一区分（一槽只能放一件物品）。</p>
     *
     * <p>⚠️ 不能用容器 UUID 区分：中键复制的两个背包共享同一个 UUID，而它们必然在不同槽位。</p>
     */
    @Test
    @DisplayName("同一玩家的两个背包（不同槽位）⇒ 必须判为不同条目（否则只画一个）")
    void sameHostDifferentSlot_areDistinct() {
        ItemStack stack = tool();
        UUID player = UUID.randomUUID();
        List<Entry> slot10 = List.of(new Entry(
            HostRef.ofPlayer(player, 10), List.of(new ToolRay(stack, true, false))));
        List<Entry> slot20 = List.of(new Entry(
            HostRef.ofPlayer(player, 20), List.of(new ToolRay(stack, true, false))));

        assertFalse(LivingToolHostSync.same(slot10, slot20),
            "同一宿主不同槽位被判为无变化 ⇒ 两个背包撞成一条 ⇒ 客户端只画一个");
    }

    @Test
    @DisplayName("宿主不同（方块 → 玩家）⇒ 必须判为有变化（否则背包换了位置客户端不刷新）")
    void hostKindChangeIsChange() {
        ItemStack stack = tool();
        List<Entry> blockHost = List.of(new Entry(
            HostRef.ofBlock(HOST), List.of(new ToolRay(stack, true, false))));
        List<Entry> playerHost = List.of(new Entry(
            HostRef.ofPlayer(UUID.randomUUID(), 10),
            List.of(new ToolRay(stack, true, false))));

        assertFalse(LivingToolHostSync.same(blockHost, playerHost),
            "宿主种类不同却被判为无变化 ⇒ 背包换宿主后客户端不刷新位置");

        List<Entry> otherPlayer = List.of(new Entry(
            HostRef.ofPlayer(UUID.randomUUID(), 10),
            List.of(new ToolRay(stack, true, false))));
        assertFalse(LivingToolHostSync.same(playerHost, otherPlayer),
            "换了玩家却被判为无变化 ⇒ 两个背包的工具会画在同一个人身上");
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
