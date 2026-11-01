package com.qiqi.li.living.domain.sponge;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.util.PerItemLedger;
import com.qiqi.li.living.util.RepresentativeFluids;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import net.neoforged.neoforge.fluids.FluidType;

import javax.annotation.Nullable;

/**
 * 活海绵物品流体接口 —— <b>唯一能力面</b>（设计稿 §2.3）：海绵 ⇄ 模组流体世界的适配器。
 *
 * <p><b>一堆一罐</b>（⑩，Jade 观感好）：{@code getTanks() = 1}，tank 0 = 堆总量视图
 * （floor 报量，损耗向），容量 = 64,000 mB × count。mB 对外、mmB 对内（×1000 换算层，§3.2）：
 * 充 = 内核 {@link PerItemLedger#distribute}（均分 floor），排 = {@link PerItemLedger#withdrawCeil}（交付 = 决定交付的整 mB）；
 * <b>报账口径（规则 12）</b>：fill 返回 ≥ 实充、drain 返回 ≤ 实扣 —— 任何取整偏差只许损耗向，
 * 往返不造账（守卫 = {@code SpongeFluidRoundTripTest} 红线）。</p>
 *
 * <p>⚠️ <b>仅已活化海绵有本接口</b>（{@link #of} 返回 {@code null} = 无能力，灯泡 2026-10-09
 * 收紧同款）：capability 按原版物品注册，未活化海绵也会被问到；不判则外部 mod 能给
 * 「原版海绵」充水，绕过活化门槛。取消活化清空 {fluid, mmB} ⇒ 不存在「带流体的未活化海绵」。</p>
 *
 * <p>§8.2#1：handler 持<b>活栈引用</b>（provider 每次查询现建，组件读写都经它现解析），
 * 绝不 copy 副本改写 —— 外部 mod 缓存本 handler 跨 tick 用时写的就是槽内活栈。</p>
 */
public class SpongeItemFluidHandler implements IFluidHandlerItem {

    /** 外部 mB → 内部 mmB 的换算倍率（§3.2 换算层）。 */
    private static final long MM_B_PER_MB = 1_000L;

    /**
     * 取某栈的流体接口 —— <b>准入判据的唯一归属地</b>（BulbItemEnergyStorage.of 同款）。
     *
     * @return 已活化的海绵 → 实例；否则 {@code null}（NeoForge 约定 = 无该能力）
     */
    @Nullable
    public static SpongeItemFluidHandler of(ItemStack stack) {
        return LivingItemManager.isLivingItem(stack) ? new SpongeItemFluidHandler(stack) : null;
    }

    private final ItemStack stack;

    private SpongeItemFluidHandler(ItemStack stack) {
        this.stack = stack;
    }

    @Override
    public ItemStack getContainer() {
        return stack;
    }

    private LivingSpongeData data() {
        return LivingSpongeData.of(stack);
    }

    @Override
    public int getTanks() {
        return 1;
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        if (tank != 0) return FluidStack.EMPTY;
        LivingSpongeData d = data();
        if (!d.isWet()) return FluidStack.EMPTY;
        long mB = d.totalMmB(stack.getCount()) / MM_B_PER_MB;   // floor 报量（尘量不虚报）
        return fluidStackOf(d.fluid(), mB);
    }

    @Override
    public int getTankCapacity(int tank) {
        if (tank != 0) return 0;
        // 堆容量 mB = 64,000 × count ≤ 4.096e6，恒在 int 域内
        return (int) (LivingSpongeData.totalCapacityMmB(stack.getCount()) / MM_B_PER_MB);
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack resource) {
        if (tank != 0 || resource == null || resource.isEmpty()) return false;
        LivingSpongeData d = data();
        return !d.isWet() || d.fluid() == resource.getFluid().getFluidType();   // 异种拒绝（规则 5）
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource == null || resource.isEmpty() || resource.getAmount() <= 0) return 0;
        LivingSpongeData d = data();
        FluidType incoming = resource.getFluid().getFluidType();
        if (d.isWet() && d.fluid() != incoming) return 0;             // 异种拒绝（规则 5）
        int count = stack.getCount();
        long spaceMmB = LivingSpongeData.totalCapacityMmB(count) - d.totalMmB(count);
        if (spaceMmB <= 0) return 0;
        // 尘量区（剩余 < 1 mB 的 mmB 零头）装不下整 mB ⇒ 不收（与 drain 的 floor 报量同口径）
        int takeMb = (int) Math.min(resource.getAmount(), spaceMmB / MM_B_PER_MB);
        if (takeMb <= 0) return 0;

        var dist = PerItemLedger.distribute(d.mmB(), count,
            LivingSpongeData.MAX_MM_B, (long) takeMb * MM_B_PER_MB);
        if (dist.accepted() <= 0) return 0;
        if (action.execute()) {
            // ⚠️ 单次构造（吸收同款教训）：干海绵首口定流体 + 入账一步到位，
            //    两步 wither 的中间态 (fluid, 0) 会被不变量归一回干态。
            LivingSpongeData.set(stack,
                new LivingSpongeData(d.isWet() ? d.fluid() : incoming, dist.newQ()));
        }
        // 报账 ≥ 实充（规则 12）：ceil(实充 mmB → mB)，封顶拟充 —— 支付方按返回值扣账
        return (int) Math.min(takeMb, (dist.accepted() + MM_B_PER_MB - 1) / MM_B_PER_MB);
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (resource == null || resource.isEmpty() || resource.getAmount() <= 0) return FluidStack.EMPTY;
        LivingSpongeData d = data();
        if (!d.isWet() || d.fluid() != resource.getFluid().getFluidType()) return FluidStack.EMPTY;
        return drainBy(d, resource.getAmount(), action);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        if (maxDrain <= 0) return FluidStack.EMPTY;
        LivingSpongeData d = data();
        if (!d.isWet()) return FluidStack.EMPTY;
        return drainBy(d, maxDrain, action);
    }

    /**
     * 排出（<b>ceil 口径</b>，2026-11-02 实测修复）：交付 = 决定交付的整 mB，分毫不差 ——
     * 每枚扣 ⌈takeMb×1000 / count⌉，实扣 ≥ 交付（损耗向）；floor 版会随请求量逐次缩水
     * （999 请求实交 998），SIMULATE 与 EXECUTE 分叉，尾差沉成「显示 1mB 却抽不出」的
     * 卡死尘。同请求 simulate / execute 必同结果（§8.2#2）。残余尘 &lt;1mB 对 mB 世界
     * 隐身（getFluidInTank 报 0 = 空罐），留在账上继续累积（不是死账，§6）。
     */
    private FluidStack drainBy(LivingSpongeData d, int maxDrain, FluidAction action) {
        int count = stack.getCount();
        long availableMb = d.totalMmB(count) / MM_B_PER_MB;   // 尘量（<1mB）不参与交付
        if (availableMb <= 0) return FluidStack.EMPTY;
        int takeMb = (int) Math.min(maxDrain, availableMb);
        if (takeMb <= 0) return FluidStack.EMPTY;

        var wd = PerItemLedger.withdrawCeil(d.mmB(), count, (long) takeMb * MM_B_PER_MB);
        if (action.execute()) {
            // newQ = 0 ⇒ 默认值移除 = 干态；残余尘 <1mB 留在账上（不销毁）
            LivingSpongeData.set(stack, d.withMmB(wd.newQ()));
        }
        // 交付 = takeMb 整数 mB，分毫不差（ceil 保证实扣 ≥ takeMb×1000，损耗向）
        return fluidStackOf(d.fluid(), takeMb);
    }

    private static FluidStack fluidStackOf(FluidType type, long mB) {
        if (mB <= 0) return FluidStack.EMPTY;
        Fluid fluid = RepresentativeFluids.of(type);
        return fluid == null ? FluidStack.EMPTY : new FluidStack(fluid, (int) Math.min(mB, Integer.MAX_VALUE));
    }
}
