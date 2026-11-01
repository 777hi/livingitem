package com.qiqi.li.living.domain.sponge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemManager;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

/**
 * 活海绵 ⇄ 模组流体世界的<b>守恒主战场</b>（设计稿 §8.3，规则 12 红线）：
 * 充入 → 排出往返（部分充 / 部分排 / 溢出拒收 / 空排 / 异种拒绝 / 零负请求），
 * 每步断言<b>净迁移无凭空产生</b>：支付方扣的 = 海绵现存增量 + 有界损耗
 * （只许来自均分 floor 与报账 ceil，损耗向）。
 *
 * <p>🔴 红线（BulbBank javadoc 原话）：不许为了让它过而改它——测试红 = 实现有 bug，不是测试太严。</p>
 */
class SpongeFluidRoundTripTest {

    private static final net.neoforged.neoforge.fluids.FluidType WATER =
        Fluids.WATER.getFluidType();
    private static final net.neoforged.neoforge.fluids.FluidType LAVA =
        Fluids.LAVA.getFluidType();

    // ── 脚手架 ──────────────────────────────────────────────

    private static ItemStack livingSponge(int count) {
        ItemStack s = new ItemStack(Items.SPONGE, count);
        LivingItemManager.setLiving(s, true);
        return s;
    }

    /** 记账视图：海绵堆总量的 mmB（守恒断言的「现存」项）。 */
    private static long totalMmB(ItemStack sponge) {
        return LivingSpongeData.of(sponge).totalMmB(sponge.getCount());
    }

    /**
     * 守恒账本：每步记录「支付方扣账（×1000 换回 mmB）」与「海绵增量」，断言
     * 增量 ≤ 扣账 且 差额（损耗）累计有界 —— 每步 &lt; 1000 mmB（报账 ceil 差）
     * + (count−1)（均分 floor），累计 &lt; 步数 × 1000 + count−1。
     */
    private static final class Ledger {
        long spongeStart;
        long paidMmB;      // 支付方累计扣账（mmB 口径）
        long lossesMmB;    // 累计损耗 = Σ(扣账 − 存量增量)，恒 ≥ 0
        final int count;
        final List<String> steps = new ArrayList<>();

        Ledger(ItemStack sponge) {
            this.spongeStart = totalMmB(sponge);
            this.count = sponge.getCount();
        }

        void onFill(ItemStack sponge, int paidMb) {
            long now = totalMmB(sponge);
            long gained = now - lastNow;
            long gap = (long) paidMb * 1000L - gained;
            assertTrue(gap >= 0, "凭空造液：扣 " + paidMb + "mB 只该至多得 " + paidMb + "mB，实得更多 @ " + step());
            assertTrue(gap < 1000L + count - 1, "损耗越界 @ " + step() + " gap=" + gap);
            paidMmB += (long) paidMb * 1000L;
            lossesMmB += gap;
            steps.add(step());
        }

        void onDrain(ItemStack sponge, int receivedMb) {
            long now = totalMmB(sponge);
            long lost = lastNow - now;
            long gap = lost - (long) receivedMb * 1000L;
            assertTrue(gap >= 0, "凭空造液：海绵少 " + lost + " 只该换回 ≥ 同量，实收更少 @ " + step());
            assertTrue(gap < 1000L + count - 1, "损耗越界 @ " + step() + " gap=" + gap);
            lossesMmB += gap;
            steps.add(step());
        }

        private long lastNow;

        { lastNow = spongeStart; }

        void checkpoint(ItemStack sponge) { lastNow = totalMmB(sponge); }

        private String step() { return "第 " + (steps.size() + 1) + " 步"; }
    }

    // ── 用例 ────────────────────────────────────────────────

    @Test
    @DisplayName("① 附录算例：6mB 存进 7 枚 ⇒ 组件 857、堆总量 5999、报账 6（损耗 1 mmB）")
    void appendixSixMilliBucketIntoSeven() {
        ItemStack sponge = livingSponge(7);
        IFluidHandlerItem h = SpongeItemFluidHandler.of(sponge);
        assertEquals(6, h.fill(new FluidStack(Fluids.WATER, 6), FluidAction.EXECUTE), "报账 = ceil(5999/1000) = 6");
        assertEquals(857, LivingSpongeData.of(sponge).mmB());
        assertEquals(5999, totalMmB(sponge));
    }

    @Test
    @DisplayName("② 拆分守恒：7 枚拆 3+4，两侧总量之和精确不变（恒等式，零损益）")
    void splitIsExactConservation() {
        ItemStack sponge = livingSponge(7);
        LivingSpongeData.set(sponge, new LivingSpongeData(WATER, 857));
        long before = totalMmB(sponge);

        ItemStack sideA = sponge.copyWithCount(3);   // 组件复制（原版拆堆语义）
        ItemStack sideB = sponge.copyWithCount(4);
        assertEquals(before, totalMmB(sideA) + totalMmB(sideB), "3×857 + 4×857 = 7×857");
        assertEquals(LivingSpongeData.of(sideA), LivingSpongeData.of(sideB), "同 q ⇒ 可再合并复原");
    }

    @Test
    @DisplayName("③ 整桶往返 ×N：单枚满排再满充，零漂移")
    void wholeBucketRoundTripsDriftFree() {
        ItemStack sponge = livingSponge(1);
        LivingSpongeData.set(sponge, new LivingSpongeData(WATER, 1_000_000L));
        IFluidHandlerItem h = SpongeItemFluidHandler.of(sponge);

        for (int i = 0; i < 25; i++) {
            FluidStack out = h.drain(1000, FluidAction.EXECUTE);
            assertEquals(1000, out.getAmount(), "第 " + i + " 轮排出");
            assertTrue(LivingSpongeData.of(sponge).mmB() == 0, "排净 = 干态");
            assertEquals(1000, h.fill(new FluidStack(Fluids.WATER, 1000), FluidAction.EXECUTE));
            assertEquals(1_000_000L, LivingSpongeData.of(sponge).mmB(), "第 " + i + " 轮回满");
        }
    }

    @Test
    @DisplayName("④ 长序列部分充排：净迁移无凭空产生，损耗逐步有界（count=5）")
    void longSequenceBoundedLoss() {
        ItemStack sponge = livingSponge(5);
        IFluidHandlerItem h = SpongeItemFluidHandler.of(sponge);
        Ledger ledger = new Ledger(sponge);
        ledger.checkpoint(sponge);

        for (int i = 0; i < 50; i++) {
            int paid = h.fill(new FluidStack(Fluids.WATER, 333), FluidAction.EXECUTE);
            ledger.onFill(sponge, paid);
            ledger.checkpoint(sponge);
            FluidStack out = h.drain(77, FluidAction.EXECUTE);
            assertTrue(out.getAmount() > 0, "有存量时应能排出");
            ledger.onDrain(sponge, out.getAmount());
            ledger.checkpoint(sponge);
        }
        long net = totalMmB(sponge) - ledger.spongeStart;
        assertTrue(net >= 0 && net <= ledger.paidMmB, "净存量增量 ≤ 累计扣账");
    }

    @Test
    @DisplayName("⑤ 溢出拒收：装不下多少收多少（报账 = 实收），不越每枚上限")
    void overflowTakesOnlyWhatFits() {
        ItemStack sponge = livingSponge(2);
        LivingSpongeData.set(sponge, new LivingSpongeData(WATER, LivingSpongeData.MAX_MM_B - 500_000L));
        IFluidHandlerItem h = SpongeItemFluidHandler.of(sponge);

        int paid = h.fill(new FluidStack(Fluids.WATER, 2000), FluidAction.EXECUTE);
        assertEquals(1000, paid, "堆剩余空间 = 每枚 500mB × 2 枚 = 1000mB");
        assertEquals(2 * LivingSpongeData.MAX_MM_B, totalMmB(sponge), "恰好装满不越界（每枚满 × 2 枚）");
        assertEquals(0, h.fill(new FluidStack(Fluids.WATER, 1), FluidAction.EXECUTE), "已满再充 = 0");
    }

    @Test
    @DisplayName("⑥ 异种拒绝：湿海绵遇异种 fill=0 / drain=EMPTY（规则 5）")
    void alienFluidRejected() {
        ItemStack sponge = livingSponge(1);
        LivingSpongeData.set(sponge, new LivingSpongeData(WATER, 1_000_000L));
        IFluidHandlerItem h = SpongeItemFluidHandler.of(sponge);

        assertEquals(0, h.fill(new FluidStack(Fluids.LAVA, 1000), FluidAction.EXECUTE));
        assertEquals(FluidStack.EMPTY, h.drain(new FluidStack(Fluids.LAVA, 1000), FluidAction.EXECUTE));
        assertEquals(1_000_000L, totalMmB(sponge), "拒绝路径零副作用");
    }

    @Test
    @DisplayName("⑦ 零 / 负请求与空海绵：0 返回 / EMPTY，无副作用（§8.2#5）")
    void zeroNegativeAndDry() {
        ItemStack dry = livingSponge(1);
        IFluidHandlerItem hDry = SpongeItemFluidHandler.of(dry);
        assertEquals(0, hDry.fill(new FluidStack(Fluids.WATER, 0), FluidAction.EXECUTE));
        assertEquals(0, hDry.fill(new FluidStack(Fluids.WATER, -5), FluidAction.EXECUTE));
        assertEquals(FluidStack.EMPTY, hDry.drain(1000, FluidAction.EXECUTE), "干海绵排不出");
        assertFalse(LivingSpongeData.of(dry).isWet());

        ItemStack wet = livingSponge(1);
        LivingSpongeData.set(wet, new LivingSpongeData(WATER, 1_000_000L));
        IFluidHandlerItem hWet = SpongeItemFluidHandler.of(wet);
        assertEquals(0, hWet.fill(new FluidStack(Fluids.WATER, 0), FluidAction.EXECUTE));
        assertEquals(0, hWet.fill(null, FluidAction.EXECUTE), "null 资源安全");
        assertEquals(FluidStack.EMPTY, hWet.drain(0, FluidAction.EXECUTE));
        assertEquals(1_000_000L, totalMmB(wet));
    }

    @Test
    @DisplayName("⑧ 尘量行为：count=3 充 1mB 报 1 损 1mmB；尘量取不出但可继续累积（§6）")
    void dustBehavior() {
        ItemStack sponge = livingSponge(3);
        IFluidHandlerItem h = SpongeItemFluidHandler.of(sponge);

        assertEquals(1, h.fill(new FluidStack(Fluids.WATER, 1), FluidAction.EXECUTE),
            "报 ceil(999/1000) = 1");
        assertEquals(999, totalMmB(sponge));
        assertEquals(FluidStack.EMPTY, h.drain(1, FluidAction.EXECUTE), "floor(999/1000)=0 ⇒ 尘量取不出");
        assertEquals(1, h.fill(new FluidStack(Fluids.WATER, 1), FluidAction.EXECUTE), "继续累积");
        assertEquals(1998, totalMmB(sponge), "999 + 999：尘量不是死账");
    }

    @Test
    @DisplayName("⑩ 7 堆存 1 桶（2026-11-02 实测 bug 场景）：999.999 存量 ⇒ 交付 999 分毫不差，尘不销毁不卡死")
    void sevenStacksOneBucket() {
        ItemStack sponge = livingSponge(7);
        IFluidHandlerItem h = SpongeItemFluidHandler.of(sponge);
        assertEquals(1000, h.fill(new FluidStack(Fluids.WATER, 1000), FluidAction.EXECUTE));
        assertEquals(999_999L, totalMmB(sponge), "142857 × 7（均分 floor，损耗 1mmB 合法）");
        assertEquals(999, h.getFluidInTank(0).getAmount(), "罐报量 floor = 999");

        // 管道要 1000 只拿得到 999 —— 交付必须分毫不差（ceil 修复前：998，且逐次缩水）
        assertEquals(999, h.drain(1000, FluidAction.EXECUTE).getAmount());
        assertEquals(994L, totalMmB(sponge), "残余尘 994mmB（<1mB）留在账上，不销毁");
        assertEquals(FluidStack.EMPTY, h.getFluidInTank(0), "尘对 mB 世界隐身 ⇒ 管道干净停手");
        assertEquals(FluidStack.EMPTY, h.drain(1000, FluidAction.EXECUTE), "尘抽不出也不动账");

        // 再充 1 桶：尘参与回填（不是死账），罐重新可交付
        assertEquals(1000, h.fill(new FluidStack(Fluids.WATER, 1000), FluidAction.EXECUTE));
        assertEquals(1000, h.drain(1000, FluidAction.EXECUTE).getAmount(), "满桶交付分毫不差");
    }

    @Test
    @DisplayName("⑪ SIMULATE 与 EXECUTE 同请求必同结果（§8.2#2，ceil 排出的确定性）")
    void simulateMatchesExecute() {
        ItemStack sponge = livingSponge(7);
        LivingSpongeData.set(sponge, new LivingSpongeData(WATER, 999_999L));
        IFluidHandlerItem h = SpongeItemFluidHandler.of(sponge);

        for (int request : new int[]{1, 500, 999, 1000, 5000}) {
            int sim = h.drain(request, FluidAction.SIMULATE).getAmount();
            int exec = h.drain(request, FluidAction.EXECUTE).getAmount();
            assertEquals(sim, exec, "请求 " + request + "：模拟 ≠ 实做 = 管道循环卡死");
        }
    }

    @Test
    @DisplayName("⑫ fill SIMULATE 与 EXECUTE 同请求必同结果（排出侧三连炸的充入侧对偶检查）")
    void fillSimulateMatchesExecute() {
        ItemStack sponge = livingSponge(7);
        IFluidHandlerItem h = SpongeItemFluidHandler.of(sponge);

        for (int request : new int[]{1, 3, 100, 999, 1000, 5000}) {
            int sim = h.fill(new FluidStack(Fluids.WATER, request), FluidAction.SIMULATE);
            int exec = h.fill(new FluidStack(Fluids.WATER, request), FluidAction.EXECUTE);
            assertEquals(sim, exec, "请求 " + request + "：模拟 ≠ 实做");
        }
    }

    @Test
    @DisplayName("⑬ fill 全域扫描：count 1..64 × 请求量 —— 无报 0 死区、报账 ≤ 请求、增量 ≤ 报账×1000")
    void fillExhaustiveNoDeadZone() {
        for (int count = 1; count <= 64; count++) {
            ItemStack sponge = livingSponge(count);
            IFluidHandlerItem h = SpongeItemFluidHandler.of(sponge);
            long before = totalMmB(sponge);
            for (int request : new int[]{1, 3, 7, 100, 999, 1000, 1001, 5000}) {
                if (before >= LivingSpongeData.MAX_MM_B * (long) count) break;   // 已满
                int paid = h.fill(new FluidStack(Fluids.WATER, request), FluidAction.EXECUTE);
                assertTrue(paid >= 1, "count=" + count + " req=" + request
                    + "：未满却报 0（死区）");
                assertTrue(paid <= request, "报账越请求");
                long gain = totalMmB(sponge) - before;
                assertTrue(gain <= (long) paid * 1000L, "实充 > 报账 = 凭空造液");
                assertTrue((long) paid * 1000L - gain < 1000L + count - 1, "损耗越界");
                before = totalMmB(sponge);
            }
        }
    }

    @Test
    @DisplayName("⑭ 尘区收敛：存量带尘时反复 fill 有限步收敛到返 0（管道判满不绞死）")
    void dustZoneFillsConverge() {
        ItemStack sponge = livingSponge(7);
        IFluidHandlerItem h = SpongeItemFluidHandler.of(sponge);
        h.fill(new FluidStack(Fluids.WATER, 1000), FluidAction.EXECUTE);   // 存 999999（带尘）

        int guard = 0;
        int paid;
        while ((paid = h.fill(new FluidStack(Fluids.WATER, 64_000), FluidAction.EXECUTE)) > 0) {
            assertTrue(++guard < 100, "fill 未收敛（>100 次仍在收）");
            assertTrue(totalMmB(sponge) <= LivingSpongeData.MAX_MM_B * 7L, "越上限");
        }
        // 收敛后：剩余空间 < 1 mB（尘区），fill 恒 0 = 停止信号；账面不越、不损
        assertTrue(totalMmB(sponge) > LivingSpongeData.MAX_MM_B * 7L - 1000L,
            "收敛点应距满 < 1 mB");
        assertEquals(0, h.fill(new FluidStack(Fluids.WATER, 1), FluidAction.EXECUTE));
    }

    @Test
    @DisplayName("⑨ 一堆一罐：getTanks=1、报量 floor、容量 ×count（⑩）")
    void singleTankSemantics() {
        ItemStack sponge = livingSponge(4);
        LivingSpongeData.set(sponge, new LivingSpongeData(WATER, 1_500_000L)); // 1.5 mB/枚 × 4 = 6 mB? — 1.5e6 mmB/枚 ×4 = 6e6 mmB = 6000mB
        IFluidHandlerItem h = SpongeItemFluidHandler.of(sponge);

        assertEquals(1, h.getTanks());
        assertEquals(6000, h.getFluidInTank(0).getAmount(), "floor(6e6/1000)");
        assertEquals(64_000 * 4, h.getTankCapacity(0));
        assertTrue(h.isFluidValid(0, new FluidStack(Fluids.WATER, 100)));
        assertFalse(h.isFluidValid(0, new FluidStack(Fluids.LAVA, 100)));
    }
}
