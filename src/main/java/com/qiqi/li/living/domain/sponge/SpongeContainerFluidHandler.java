package com.qiqi.li.living.domain.sponge;

import java.util.ArrayList;
import java.util.List;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerDataKey;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.TickableContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import javax.annotation.Nullable;

/**
 * 「海绵垫起的」容器级流体能力（设计稿 §2.3，⑭：2026-11-02 用户拍板 —— 旧活流体暴露给
 * 管道的能力退役后，角色由活海绵接棒：管道指着**放了湿活海绵的容器**即可抽 / 灌）。
 *
 * <p>与已退役的 {@code ContainerFluidHandler} 的本质区别：本能力暴露的是**海绵存量**
 * （mmB，封闭缓冲），**不是容器水网**（generatedSources 永不暴露，规则 7 对源网络仍然成立）
 * ⇒ 抽 / 灌全委托 {@link SpongeItemFluidHandler}（均分内核 + 报账口径 §8 全量复用），
 * 「分数源污染」问题依然从根上不存在。</p>
 *
 * <p><b>罐模型</b>：每个湿活海绵堆 = 一罐（按槽位稳定序）。抽 = 均分扣那堆；
 * 灌 = 按槽序依次充入（同种流体约束规则 5，干海绵首口定流体）。
 * 每次操作<b>现扫容器</b>（槽内容可能已变），不缓存罐列表（§8.2#1 同款纪律）。</p>
 *
 * <p>⚠️ <b>能力存在性 = 有活海绵（湿或干）</b>（§10.4 教训直接继承）：管道用「能力是否
 * 非 null」判邻居是不是储液 ⇒ 无活海绵必须返回 {@code null}（恒发实例会把管道自身判成
 * 储液，整张网络断在假 tank 上，2026-10-31 机械动力冲突）。**干海绵 = 空罐**（0 内容、
 * 容量在位）⇒ 管道可以灌装空弹匣；未活化海绵仍不暴露（活化门槛）。存在性翻转走
 * {@link #EDGE} 标志 + 驱动侧 {@code invalidateCapabilities}（管道缓存的
 * BlockCapabilityCache 才肯重查）。</p>
 */
public class SpongeContainerFluidHandler implements IFluidHandler {

    /** 能力通告标志（跨 tick 内存态，不落盘；key 随类登记 ⇒ R-06 sweep 自动覆盖）。 */
    public static final ContainerDataKey<Boolean> EDGE =
        ContainerDataKey.persistent("sponge_capability_advertised", () -> Boolean.FALSE);

    /** 该容器当前是否应暴露能力（有活海绵——湿或干，干 = 空罐可灌装）。 */
    public static boolean isExposed(ContainerContext ctx) {
        return !spongeStacks(ctx).isEmpty();
    }

    /** 驱动侧边沿检测（容器 tick 开始时调用）：存在性翻转 ⇒ 逐关联位置失效能力缓存。 */
    public static void tickEdge(ContainerContext ctx) {
        boolean exposed = isExposed(ctx);
        Boolean advertised = ctx.peekContainerData(EDGE);
        if (exposed == Boolean.TRUE.equals(advertised)) return;
        ContainerLivingItemHandler.putContainerData(ctx, EDGE, exposed);
        Level level = ctx.getLevel();
        if (level != null) {
            for (BlockPos pos : ctx.getAssociatedBlockPositions()) {
                level.invalidateCapabilities(pos);
            }
        }
    }

    /** 按槽位序收集活海绵堆（湿或干；每次能力操作现扫，不缓存）。 */
    public static List<ItemStack> spongeStacks(ContainerContext ctx) {
        List<ItemStack> out = new ArrayList<>();
        int size = ctx.getSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = ctx.getItem(slot);
            if (stack.isEmpty() || !stack.is(Items.SPONGE)) continue;
            if (!LivingItemManager.isLivingItem(stack)) continue;
            out.add(stack);
        }
        return out;
    }

    /** provider 入口：非容器 / 无湿海绵 ⇒ {@code null}（NeoForge 约定 = 无该能力）。 */
    @Nullable
    public static SpongeContainerFluidHandler resolveProvider(BlockEntity be, @Nullable Direction side) {
        Level level = be.getLevel();
        if (level == null || level.isClientSide()) return null;   // 客户端不参与
        TickableContainerContext ctx =
            ContainerLivingItemHandler.resolveContextAt(level, be.getBlockPos(), null);
        if (ctx == null || !isExposed(ctx)) return null;
        return new SpongeContainerFluidHandler(ctx);
    }

    private final TickableContainerContext ctx;

    private SpongeContainerFluidHandler(TickableContainerContext ctx) {
        this.ctx = ctx;
    }

    private List<ItemStack> spongeStacks() {
        return spongeStacks(ctx);
    }

    @Override
    public int getTanks() {
        return spongeStacks().size();
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        List<ItemStack> stacks = spongeStacks();
        if (tank < 0 || tank >= stacks.size()) return FluidStack.EMPTY;
        return itemHandler(stacks.get(tank)).getFluidInTank(0);
    }

    @Override
    public int getTankCapacity(int tank) {
        List<ItemStack> stacks = spongeStacks();
        if (tank < 0 || tank >= stacks.size()) return 0;
        return itemHandler(stacks.get(tank)).getTankCapacity(0);
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack resource) {
        List<ItemStack> stacks = spongeStacks();
        if (tank < 0 || tank >= stacks.size()) return false;
        return itemHandler(stacks.get(tank)).isFluidValid(0, resource);
    }

    private record SpongeSlot(int slot, ItemStack before) {}

    /** 本次操作的槽位快照，工作副本提交前不得改读取结果。 */
    private List<SpongeSlot> spongeSlots() {
        List<SpongeSlot> out = new ArrayList<>();
        for (int slot = 0; slot < ctx.getSize(); slot++) {
            ItemStack stack = ctx.getItem(slot);
            if (!stack.isEmpty() && stack.is(Items.SPONGE) && LivingItemManager.isLivingItem(stack)) {
                out.add(new SpongeSlot(slot, stack.copy()));
            }
        }
        return out;
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource == null || resource.isEmpty() || resource.getAmount() <= 0) return 0;
        int remaining = resource.getAmount();
        int total = 0;
        for (SpongeSlot entry : spongeSlots()) {
            if (remaining <= 0) break;
            if (!ctx.supportsItemComponentWrite(entry.slot())) continue;
            ItemStack working = entry.before().copy();
            int filled = itemHandler(working).fill(resource.copyWithAmount(remaining), action);
            if (filled <= 0) continue;
            if (action.execute() && !ctx.commitItemComponents(entry.slot(), entry.before(), working)) break;
            total += filled;
            remaining -= filled;
        }
        return total;
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (resource == null || resource.isEmpty() || resource.getAmount() <= 0) return FluidStack.EMPTY;
        int want = resource.getAmount();
        FluidStack result = FluidStack.EMPTY;
        for (SpongeSlot entry : spongeSlots()) {
            int remaining = want - result.getAmount();
            if (remaining <= 0) break;
            if (!ctx.supportsItemComponentWrite(entry.slot())) continue;
            ItemStack working = entry.before().copy();
            FluidStack out = itemHandler(working).drain(resource.copyWithAmount(remaining), action);
            if (out.isEmpty()) continue;
            if (action.execute() && !ctx.commitItemComponents(entry.slot(), entry.before(), working)) break;
            if (result.isEmpty()) result = out.copy();
            else result.grow(out.getAmount());
        }
        return result;
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        if (maxDrain <= 0) return FluidStack.EMPTY;
        // 首个成功抽取的罐决定流体；拒写停止结算，异种不扣也不挡后续同种罐。
        FluidStack result = FluidStack.EMPTY;
        for (SpongeSlot entry : spongeSlots()) {
            int remaining = maxDrain - result.getAmount();
            if (remaining <= 0) break;
            if (!ctx.supportsItemComponentWrite(entry.slot())) continue;
            ItemStack working = entry.before().copy();
            IFluidHandler handler = itemHandler(working);
            FluidStack out;
            if (result.isEmpty()) {
                FluidStack probe = handler.drain(remaining, FluidAction.SIMULATE);
                if (probe.isEmpty()) continue;
                out = action.execute() ? handler.drain(probe, action) : probe;
            } else {
                out = handler.drain(result.copyWithAmount(remaining), action);
            }
            if (out.isEmpty()) continue;
            if (action.execute() && !ctx.commitItemComponents(entry.slot(), entry.before(), working)) break;
            if (result.isEmpty()) result = out.copy();
            else result.grow(out.getAmount());
        }
        return result;
    }

    private IFluidHandler itemHandler(ItemStack stack) {
        IFluidHandler h = SpongeItemFluidHandler.of(stack);
        // 栈已过湿活海绵双重过滤 ⇒ of 恒非 null；防御分支给 no-op 罐（不炸管道）
        return h != null ? h : NO_OP;
    }

    private static final IFluidHandler NO_OP = new IFluidHandler() {
        @Override public int getTanks() { return 0; }
        @Override public FluidStack getFluidInTank(int tank) { return FluidStack.EMPTY; }
        @Override public int getTankCapacity(int tank) { return 0; }
        @Override public boolean isFluidValid(int tank, FluidStack stack) { return false; }
        @Override public int fill(FluidStack resource, FluidAction action) { return 0; }
        @Override public FluidStack drain(FluidStack resource, FluidAction action) { return FluidStack.EMPTY; }
        @Override public FluidStack drain(int maxDrain, FluidAction action) { return FluidStack.EMPTY; }
    };
}
