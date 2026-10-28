package com.qiqi.li.living.domain.tnt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemFunction.SlotEntry;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.config.GameplayConfig;
import com.qiqi.li.living.container.ContainerAnchor;
import com.qiqi.li.living.container.ItemEntityContainerContext;
import com.qiqi.li.living.container.ItemFormContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 爆炸后**必须消耗**活 TNT（2026-10-23 真机实录）。
 *
 * <p>现场：能炸了，但炸完 TNT 还在（并且是「未点燃」的新状态）。</p>
 *
 * <p>根因不在 {@link ExplosionComponent#ignite}（它明明把容器里所有活 TNT 槽位写成了
 * {@code EMPTY}），而在 {@link LivingTntFunction#tick} 的**写回**：爆炸分支之后没有结束，
 * 于是继续执行「把本地 {@code stack} 写回槽位」—— 那个 {@code stack} 是 tick 开始时的快照，
 * 里面还是那颗 TNT ⇒ 刚清掉的槽位被原样填回，效果就是「炸了但没消耗」。
 * 多颗时更糟：{@code ignite} 一次消耗全部，而 {@code entries} 里其余条目照样会各自写回。</p>
 *
 * <p>本用例钉住两件事：</p>
 * <ol>
 *   <li>炸完槽位必须是空的（单颗 / 多颗都一样）；</li>
 *   <li>炸不了时（没有世界锚点）**必须熄灭**，不许保留待爆态
 *       （早先的「无限倒计时」与后来的「挪走即爆」都从这条口子进来过）。</li>
 * </ol>
 */
class LivingTntConsumeTest {

    private static final BlockPos POS = new BlockPos(10, 64, 10);

    // ---------- 替身 ----------

    /** 活 TNT（带 IS_LIVING + 指定引信状态）。 */
    private static ItemStack livingTnt(ExplosionData explosion, int count) {
        ItemStack stack = new ItemStack(Items.TNT, count);
        LivingItemManager.setLiving(stack, true);
        LivingTntData.set(stack, new LivingTntData(explosion));
        return stack;
    }

    /**
     * 能走到「爆炸成功」的 {@link ServerLevel} 替身。
     *
     * <p>⚠️ {@code Level.random} 是<b>字段</b>而不是 getter（{@code level.random.nextFloat()}），
     * Mockito 对字段无效 ⇒ 只能反射塞一个真 {@link RandomSource}，否则爆炸音效那一步 NPE。</p>
     */
    private static ServerLevel explodingLevel() {
        ServerLevel level = mock(ServerLevel.class);
        setRandom(level, RandomSource.create(1234L));
        when(level.dimension()).thenReturn(Level.OVERWORLD);

        DimensionDataStorage storage = mock(DimensionDataStorage.class);
        // 账本：真实例即可（它是纯内存记账，schedule 之后由 tick 推进）
        doReturn(new ExplosionLedger()).when(storage).computeIfAbsent(any(), anyString());
        when(level.getDataStorage()).thenReturn(storage);

        // getEntities 默认返回空表（Mockito 对 List 的默认答案），伤害阶段自然什么都不做
        return level;
    }

    private static void setRandom(ServerLevel level, RandomSource random) {
        try {
            Field field = Level.class.getDeclaredField("random");
            field.setAccessible(true);
            field.set(level, random);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法给 ServerLevel 替身注入 random（Level.random 可能变成了 final）", e);
        }
    }

    // ---------- 1. 消耗 ----------

    @Test
    @DisplayName("单颗炸完 ⇒ 槽位空（旧实现会把快照写回，效果「炸了但 TNT 还在」）")
    void exploded_singleTnt_slotBecomesEmpty() {
        FakeContainerContext ctx = new FakeContainerContext(27, 9).withBlockPos(POS);
        ctx.set(3, livingTnt(new ExplosionData(true, 0), 1));   // 引信已归零

        LivingTntFunction function = new LivingTntFunction();
        function.tick(List.of(new SlotEntry(3, ctx.getItem(3))), ctx, new TickContext(ctx), explodingLevel());

        assertTrue(ctx.getItem(3).isEmpty(),
            "爆炸的消耗由 ignite 完成（它把槽位写成 EMPTY）⇒ tick 绝不能把 tick 开始时的快照写回去");
    }

    @Test
    @DisplayName("多颗一起炸 ⇒ 全部消耗（ignite 一次清空；快照写回会把没轮到的复活）")
    void exploded_multipleTnt_allConsumed() {
        FakeContainerContext ctx = new FakeContainerContext(27, 9).withBlockPos(POS);
        ctx.set(3, livingTnt(new ExplosionData(true, 0), 1));    // 这颗引爆
        ctx.set(5, livingTnt(new ExplosionData(true, 40), 2));   // 这颗引信还没走完

        LivingTntFunction function = new LivingTntFunction();
        function.tick(List.of(
                new SlotEntry(3, ctx.getItem(3)),
                new SlotEntry(5, ctx.getItem(5))),
            ctx, new TickContext(ctx), explodingLevel());

        assertTrue(ctx.getItem(3).isEmpty(), "引爆的那颗必须消失");
        assertTrue(ctx.getItem(5).isEmpty(),
            "ignite 一次消耗容器里**所有**活 TNT ⇒ 没轮到的那颗也不能被写回复活");
    }

    @Test
    @DisplayName("掉落物形态：炸在原地并消耗（走 ContainerAnchor.AtEntity）")
    void droppedTnt_explodesAndIsConsumed() {
        ItemStack lit = livingTnt(new ExplosionData(true, 0), 1);

        ItemEntity entity = mock(ItemEntity.class);
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        when(entity.getItem()).thenReturn(lit);
        when(entity.position()).thenReturn(new Vec3(8.5, 64, 8.5));   // AtEntity ⇒ 爆炸中心取它
        ItemEntityContainerContext ctx = new ItemEntityContainerContext(entity, explodingLevel());

        new LivingTntFunction().tick(List.of(new SlotEntry(0, lit)), ctx, new TickContext(ctx), ctx.getLevel());

        verify(entity).setItem(org.mockito.ArgumentMatchers.argThat(ItemStack::isEmpty));
    }
    // ---------- 宿主锚点开关（GameplayConfig，默认关）----------

    /** 反射改配置静态字段 —— 生产代码不留测试 seam（配置值本来就该由 NeoForge 装载）。 */
    private static void setTntHostAnchor(boolean value) {
        try {
            Field field = GameplayConfig.class.getDeclaredField("tnt");
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法设置 GameplayConfig.tnt", e);
        }
    }

    /** 一个带宿主锚点的物品形态容器（= 活背包在玩家身上）。 */
    private static ItemFormContainerContext itemFormOnPlayer(ItemStack stack, Player player) {
        ItemStackHandler handler = new ItemStackHandler(1);
        handler.setStackInSlot(0, stack);
        return new ItemFormContainerContext(handler, UUID.randomUUID(), explodingLevel(), 9,
            new ContainerAnchor.AtPlayer(player));
    }

    @Test
    @DisplayName("宿主锚点默认关 ⇒ 背包里的活 TNT 熄灭（不留引信，挪走也不自爆）")
    void hostAnchorOffByDefault_extinguishes() {
        ItemStack lit = livingTnt(new ExplosionData(true, 0), 1);
        Player player = mock(Player.class);
        ItemFormContainerContext ctx = itemFormOnPlayer(lit, player);

        new LivingTntFunction().tick(List.of(new SlotEntry(0, lit)), ctx, new TickContext(ctx), ctx.getLevel());

        assertFalse(ctx.getItem(0).isEmpty(),
            "配置关 ⇒ 没有可炸的位置 ⇒ 不消耗物品（只是熄灭）");
        ExplosionData after = LivingTntData.of(ctx.getItem(0)).explosion();
        assertFalse(after.ignited(), "无锚点引爆 ⇒ 必须熄灭，不许留待爆态");
        assertEquals(0, after.fuseTimer(), "熄灭 = ignited / fuseTimer 都归零");
    }

    @Test
    @DisplayName("熄灭后挪进有锚点的箱子 ⇒ 不会不点自爆（旧「哑弹待爆」语义已撤销）")
    void extinguishedTnt_doesNotExplodeWhenMovedToAnchor() {
        ItemStack lit = livingTnt(new ExplosionData(true, 1), 1);
        Player player = mock(Player.class);
        ItemFormContainerContext bag = itemFormOnPlayer(lit, player);

        new LivingTntFunction().tick(List.of(new SlotEntry(0, lit)), bag, new TickContext(bag), bag.getLevel());
        assertFalse(LivingTntData.of(bag.getItem(0)).explosion().ignited(), "前置：背包里已熄灭");

        FakeContainerContext chest = new FakeContainerContext(27, 9).withBlockPos(POS);
        chest.set(3, bag.getItem(0));
        new LivingTntFunction().tick(
            List.of(new SlotEntry(3, chest.getItem(3))), chest, new TickContext(chest), explodingLevel());

        assertFalse(chest.getItem(3).isEmpty(),
            "熄灭后挪到有锚点处必须保持原样：不点燃、不爆炸（否则又回到「挪走即爆」）");
        assertFalse(LivingTntData.of(chest.getItem(3)).explosion().ignited(), "仍必须保持未点燃");
    }

    @Test
    @DisplayName("宿主锚点打开 ⇒ 背包里的活 TNT 炸在宿主处并消耗")
    void hostAnchorOn_explodesAtHost() {
        setTntHostAnchor(true);
        try {
            ItemStack lit = livingTnt(new ExplosionData(true, 0), 1);
            Player player = mock(Player.class);
            when(player.position()).thenReturn(new Vec3(1, 64, 2));   // 宿主 = 玩家脚下
            ItemFormContainerContext ctx = itemFormOnPlayer(lit, player);

            new LivingTntFunction().tick(List.of(new SlotEntry(0, lit)), ctx, new TickContext(ctx), ctx.getLevel());

            assertTrue(ctx.getItem(0).isEmpty(),
                "配置打开 ⇒ 有锚点 ⇒ 正常爆炸并消耗（消耗由 ignite 完成）");
        } finally {
            setTntHostAnchor(false);
        }
    }
}
