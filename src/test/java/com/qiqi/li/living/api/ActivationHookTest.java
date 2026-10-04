package com.qiqi.li.living.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.chest.LivingChestFunction;
import com.qiqi.li.living.domain.ender.LivingEnderChestData;
import com.qiqi.li.living.domain.ender.LivingEnderChestFunction;
import com.qiqi.li.living.domain.tools.LivingToolFunction;
import com.qiqi.li.living.domain.tools.LivingToolOwnerName;

/**
 * 活化时机钩子守卫 —— 锁住「各功能自声明活化/取消活化要挂什么」这条契约（2026-10-04）。
 *
 * <h3>为什么需要</h3>
 * <p>这五段「活化这一刻要挂什么」原先散落在 {@code LivingTagPacket} 的内联类型判断里
 * （活箱子掉物 / 活末影箱绑定与解绑 / 活工具写主人）。收编成钩子后，
 * 新的失败模式是<b>「功能忘了覆盖钩子」</b> ⇒ 主人不记、绑定不清、物品不掉，
 * <b>而系统不报错</b> —— 与 A2 的「忘了声明 owned component ⇒ 孤儿数据」是同一类静默失败。
 * 本测试把这个失败模式钉死。</p>
 *
 * <h3>为什么在 {@code @BeforeAll} 注册而不是每个用例一次</h3>
 * <p><b>踩过的坑</b>：功能注册表是<b>全局且只增不减</b>的（没有注销 API），
 * 而派发目标 {@code getApplicableFunctions} 读的就是它。
 * 若每个用例都 new 一个记账功能注册进去，早先用例留下的实例会继续参与派发 ——
 * 于是「④ 否决」用例里那个 {@code veto=true} 的旧实例会把「① 正常取消活化」也否掉。
 * ⇒ 记账功能<b>全局只注册一个</b>，否决开关做成静态字段由用例设置。</p>
 *
 * <h3>可测性边界</h3>
 * <p>单测<b>造不出真实玩家</b>（{@code ServerPlayer} 需要服务器）⇒ 「有玩家」分支
 * （工具写 owner、末影箱绑定、箱子掉物）无法在此断言；那三条已于 2026-10-04
 * <b>游戏内逐项验证通过</b>（行为与收编前一致），详见
 * {@code docs/system-design/api-contract.md} §1.5「可测性边界」。
 * 本测试守的是<b>玩家缺席</b>那侧 —— 也就是本契约新增的部分。</p>
 */
@DisplayName("活化时机钩子守卫 · 各功能自声明（A3 收编）")
class ActivationHookTest {

    /** 全局唯一的记账功能（理由见类注释）。 */
    private static final RecordingFunction RECORDER = new RecordingFunction();

    private Level level;

    @BeforeAll
    static void registerFunctions() {
        // registerFunction 会清 APPLICABLE_CACHE，所以注册后再查询拿到的就是最新结果
        // （api-contract §1.3）。
        LivingItemManager.registerFunction(new LivingChestFunction());
        LivingItemManager.registerFunction(new LivingEnderChestFunction());
        LivingItemManager.registerFunction(new LivingToolFunction());
        LivingItemManager.registerFunction(RECORDER);
    }

    @BeforeEach
    void setUp() {
        level = Mockito.mock(Level.class);
        RECORDER.reset();
    }

    // ---------------------------------------------------------------- 派发契约

    @Test
    @DisplayName("① 活化 / 取消活化各派发一次，且 level・player・via 原样送达")
    void dispatchesOnceWithContext() {
        ItemStack paper = new ItemStack(Items.PAPER);

        assertTrue(LivingItemActivation.apply(paper, level, null,
            LivingItemActivation.Via.PLAYER, true), "活化应成功");
        assertEquals(1, RECORDER.activated, "onActivated 应恰好派发一次");
        assertSame(LivingItemActivation.Via.PLAYER, RECORDER.via, "via 应原样送达");
        assertSame(level, RECORDER.level, "level 应原样送达（功能不该依赖 player 才能拿世界）");
        assertNull(RECORDER.player, "本用例模拟玩家缺席");

        assertTrue(LivingItemActivation.apply(paper, level, null,
            LivingItemActivation.Via.INTERNAL, false), "取消活化应成功");
        assertEquals(1, RECORDER.deactivated, "onDeactivated 应恰好派发一次");
        assertSame(LivingItemActivation.Via.INTERNAL, RECORDER.via, "via 应原样送达");
        assertFalse(LivingItemManager.isLivingItem(paper), "取消活化后 IS_LIVING 应被清");
    }

    @Test
    @DisplayName("② 顺序不变量：两个方向派发时物品都仍处于「活」状态")
    void hookAlwaysSeesLivingStack() {
        // ⭐ 这是硬顺序约束的守卫：判据（isLivingChest / isLivingToolOrWeapon）都含 isLivingItem
        //    ⇒ 活化必须「先写标记后派发」，取消活化必须「先派发后清标记」。
        ItemStack paper = new ItemStack(Items.PAPER);
        LivingItemActivation.apply(paper, level, null, LivingItemActivation.Via.PLAYER, true);
        LivingItemActivation.apply(paper, level, null, LivingItemActivation.Via.PLAYER, false);

        assertTrue(RECORDER.livingSeenWhenActivated,
            "onActivated 时物品必须已带 IS_LIVING（否则功能认不出它）");
        assertTrue(RECORDER.livingSeenWhenDeactivated,
            "onDeactivated 时物品必须仍带 IS_LIVING（否则箱子不掉物、末影箱不清绑定）");
    }

    @Test
    @DisplayName("③ 未被任何功能认领的物品：标记照翻，但不派发（无功能认领 = 无事可做）")
    void unclaimedItemJustFlipsFlag() {
        ItemStack stick = new ItemStack(Items.STICK);
        assertTrue(LivingItemActivation.apply(stick, level, null,
            LivingItemActivation.Via.PLAYER, true));
        assertEquals(0, RECORDER.activated, "STICK 不该被记账功能认领");
        assertTrue(LivingItemManager.isLivingItem(stick), "但 IS_LIVING 仍然写入 —— 活物品隔离仍生效");
    }

    // ---------------------------------------------------------------- 否决通道

    @Test
    @DisplayName("④ 数据安全否决：功能拒绝取消活化 ⇒ 框架不清任何数据")
    void vetoKeepsDataIntact() {
        RECORDER.vetoDeactivation = true;
        try {
            ItemStack paper = new ItemStack(Items.PAPER);
            LivingItemActivation.apply(paper, level, null, LivingItemActivation.Via.INTERNAL, true);

            assertFalse(LivingItemActivation.apply(paper, level, null,
                    LivingItemActivation.Via.INTERNAL, false),
                "功能返回 false ⇒ apply 应如实报告「状态未被切换」");
            assertTrue(LivingItemManager.isLivingItem(paper),
                "否决 ⇒ IS_LIVING 必须保留（清掉就等于销毁数据）");
        } finally {
            RECORDER.vetoDeactivation = false;
        }
    }

    // ---------------------------------------------------------------- 真实功能 · 玩家缺席

    @Test
    @DisplayName("⑤ 活箱子：玩家缺席时拒绝取消活化，27 格内容原样保住")
    void chestRefusesDeactivationWithoutPlayer() {
        ItemStack chest = new ItemStack(Items.CHEST);
        LivingItemActivation.apply(chest, level, null, LivingItemActivation.Via.PLAYER, true);
        chest.set(DataComponents.CONTAINER,
            ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND))));

        assertFalse(LivingItemActivation.apply(chest, level, null,
                LivingItemActivation.Via.PLAYER, false),
            "掉落需要位置，无玩家时应拒绝而不是把内容清成空气");
        assertTrue(LivingItemManager.isLivingItem(chest), "仍应是活箱子");
        assertEquals(Items.DIAMOND, LivingChestFunction.getItems(chest).getFirst().getItem(),
            "内容必须完好 —— 这条是「宁可不活化，也不销毁数据」的落点");
    }

    @Test
    @DisplayName("⑥ 活末影箱：玩家缺席时不绑定（落回路由模式），取消活化照常清绑定")
    void enderChestStaysUnboundWithoutPlayer() {
        ItemStack chest = new ItemStack(Items.ENDER_CHEST);
        LivingItemActivation.apply(chest, level, null, LivingItemActivation.Via.PLAYER, true);

        assertTrue(LivingEnderChestData.of(chest).channel().getPlayerUuid().isEmpty(),
            "无玩家 ⇒ 不绑定；未绑定是已存在的合法模式（路由模式 / 公共黑板）");

        assertTrue(LivingItemActivation.apply(chest, level, null,
            LivingItemActivation.Via.PLAYER, false), "清绑定不需要玩家 ⇒ 不该被否决");
        assertFalse(LivingItemManager.isLivingItem(chest));
    }

    @Test
    @DisplayName("⑦ 活工具：owner 只由活工具的钩子写 —— 非工具物品结构上拿不到这次派发")
    void toolOwnerIsWrittenOnlyByToolHook() {
        // 活镐 ⇒ 认领它的功能里包含 living_tool ⇒ 它才有资格写 owner
        ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        LivingItemActivation.apply(pickaxe, level, null, LivingItemActivation.Via.PLAYER, true);
        assertTrue(claimedBy(pickaxe, LivingToolFunction.class),
            "活镐必须被 living_tool 认领，否则它的钩子不会派发、owner 无从写入");
        assertNull(LivingItemManager.getToolOwner(pickaxe), "玩家缺席 ⇒ 无主（既有合法态）");
        assertNull(LivingToolOwnerName.of(pickaxe), "无玩家 ⇒ 没有名字可缓存");

        // ⭐ 回归守卫：收编前 owner 对**任何**物品都写（内联判断在网络包里），
        //    收编后只有 living_tool 的钩子会写 ⇒ 非工具物品结构上不可能再拿到它。
        ItemStack bone = new ItemStack(Items.BONE_MEAL);
        LivingItemActivation.apply(bone, level, null, LivingItemActivation.Via.PLAYER, true);
        assertFalse(claimedBy(bone, LivingToolFunction.class),
            "骨头不属于活工具 ⇒ 不会派发给它 ⇒ 不可能写 owner");
        assertNull(LivingItemManager.getToolOwner(bone), "非工具物品不应携带 owner");
        assertNull(LivingToolOwnerName.of(bone), "非工具物品不应携带主人名字缓存");
    }

    // ---------------------------------------------------------------- 工具

    private static boolean claimedBy(ItemStack stack, Class<? extends LivingItemFunction> type) {
        return LivingItemManager.getApplicableFunctions(stack).stream()
            .anyMatch(type::isInstance);
    }

    /**
     * 记账型功能 —— 认领 {@code minecraft:paper}，把派发到的参数与时机记下来。
     *
     * <p>选 paper 是因为它<b>不被任何内置功能认领</b>（活地图认领 filled_map、
     * 末影珍珠认领末影珍珠），所以本功能的记账不会被别的钩子污染。</p>
     */
    private static final class RecordingFunction implements LivingItemFunction {

        int activated;
        int deactivated;
        boolean livingSeenWhenActivated;
        boolean livingSeenWhenDeactivated;
        LivingItemActivation.Via via;
        Level level;
        @Nullable Player player;

        /** 置 true 模拟「数据安全否决」（如活箱子在无玩家时拒绝取消活化）。 */
        boolean vetoDeactivation;

        void reset() {
            activated = 0;
            deactivated = 0;
            livingSeenWhenActivated = false;
            livingSeenWhenDeactivated = false;
            via = null;
            level = null;
            player = null;
        }

        @Override
        public boolean canApply(ItemStack stack) {
            return stack.is(Items.PAPER);
        }

        @Override
        public String getFunctionId() {
            return "test_recording";
        }

        @Override
        public void tick(List<SlotEntry> entries, ContainerContext container, TickContext tick, Level level) {
        }

        @Override
        public void onActivated(ItemStack stack, Level level, @Nullable Player player,
                LivingItemActivation.Via via) {
            activated++;
            livingSeenWhenActivated = LivingItemManager.isLivingItem(stack);
            this.via = via;
            this.level = level;
            this.player = player;
        }

        @Override
        public boolean onDeactivated(ItemStack stack, Level level, @Nullable Player player,
                LivingItemActivation.Via via) {
            deactivated++;
            livingSeenWhenDeactivated = LivingItemManager.isLivingItem(stack);
            this.via = via;
            this.level = level;
            this.player = player;
            return !vetoDeactivation;
        }
    }
}
