package com.qiqi.li.living.domain.battle;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.qiqi.li.living.domain.battle.BattleMath.Outcome;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class BattleMathTest {

    // ── perPiece（硬度 × 10，§4.2）────────────────────────────

    @Test
    void perPieceFollowsHardnessTable() {
        assertEquals(20, BattleMath.perPiece(new ItemStack(Items.COBBLESTONE)), "圆石 2.0 → 20");
        assertEquals(500, BattleMath.perPiece(new ItemStack(Items.OBSIDIAN)), "黑曜石 50 → 500");
        assertEquals(3, BattleMath.perPiece(new ItemStack(Items.GLASS)), "玻璃 0.3 → 3");
        assertEquals(20, BattleMath.perPiece(new ItemStack(Items.STICK)), "非方块 → 20");
        assertEquals(50, BattleMath.perPiece(new ItemStack(Items.BEDROCK)), "负硬度（不可破坏档）→ 50");
    }

    // ── applyDamage（当前层累加 / 溢出滚层 / 吃光消失）──────────

    @Test
    void damageAccumulatesOnCurrentLayer() {
        Outcome o = BattleMath.applyDamage(20, 16, 5, 3);
        assertEquals(16, o.newCount());
        assertEquals(8, o.newLayerDamage());
        assertEquals(0, o.layersLost());
        assertFalse(o.destroyed());
    }

    @Test
    void exactFillRollsOneLayerWithZeroDamage() {
        Outcome o = BattleMath.applyDamage(20, 16, 19, 1);
        assertEquals(15, o.newCount(), "恰好填满当前层 ⇒ 掉一层");
        assertEquals(0, o.newLayerDamage());
        assertEquals(1, o.layersLost());
        assertFalse(o.destroyed());
    }

    @Test
    void overflowSpansLayersWithRemainderOnNewLayer() {
        Outcome o = BattleMath.applyDamage(20, 16, 19, 3);
        // 当前层剩 1，溢出 2 ⇒ 掉 1 层、新当前层损 2
        assertEquals(15, o.newCount());
        assertEquals(2, o.newLayerDamage());
        assertEquals(1, o.layersLost());
    }

    @Test
    void bigHitConsumesMultipleLayers() {
        Outcome o = BattleMath.applyDamage(20, 16, 0, 45);
        // 20 + 20 吃光两层，余 5 进第三层
        assertEquals(14, o.newCount());
        assertEquals(5, o.newLayerDamage());
        assertEquals(2, o.layersLost());
        assertFalse(o.destroyed());
    }

    @Test
    void exactTotalConsumptionDestroys() {
        Outcome o = BattleMath.applyDamage(20, 2, 19, 21);
        // 当前层剩 1 + 完整层 20 = 21 = 伤害 ⇒ 全灭
        assertEquals(0, o.newCount());
        assertTrue(o.destroyed());
    }

    @Test
    void overflowBeyondAllLayersDestroys() {
        Outcome o = BattleMath.applyDamage(20, 2, 0, 500);
        assertTrue(o.destroyed());
        assertEquals(2, o.layersLost(), "全灭时 layersLost = 全部层数");
    }

    @Test
    void zeroOrNegativeDamageIsNoOp() {
        Outcome o = BattleMath.applyDamage(20, 16, 5, 0);
        assertEquals(16, o.newCount());
        assertEquals(5, o.newLayerDamage());
        assertEquals(0, o.layersLost());
        assertFalse(o.destroyed());
    }

    @Test
    void singlePieceWallDiesAtOnce() {
        // 单件玻璃墙（3 血）吃 20 伤害 ⇒ 直接消失
        Outcome o = BattleMath.applyDamage(3, 1, 0, 20);
        assertTrue(o.destroyed());
    }
}
