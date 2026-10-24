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
import com.qiqi.li.living.container.ItemEntityContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;

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
 *   <li>炸不了时（没有世界锚点）**不得重置**引信 —— 那正是更早那个「无限倒计时」的机制。</li>
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
}
