package com.qiqi.li.living.domain.water;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import com.qiqi.li.living.container.ContainerDataKeys;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.TickableContainerContext;

/**
 * 容器活水源的<b>对外流体能力</b>（技术文档 §10「管道抽取」，2026-10-06 实施）。
 *
 * <p><b>方言</b>：NeoForge {@code Capabilities.FluidHandler.BLOCK}（{@link IFluidHandler}）——
 * 管道模组的通用接口：Pipez / Mekanism 直接用它；<b>Create 6 也用它</b>（内部 tank 就是 NeoForge 的
 * {@code FluidTank} 模板，管道与机械泵按 {@code FluidHandler.BLOCK} 拉取邻居）⇒ <b>无需兼容代码</b>。</p>
 *
 * <h3>语义（逐条对应 §10 定案）</h3>
 * <ul>
 *   <li><b>抽取 = 消耗源</b>：drain 扣该源余额，扣到 0 ⇒ 源消失（{@code removeGeneratedSource}），
 *       等价「自动化汲走」；两源夹一格 + 抽中间 + 晋升补中间 ⇒ 原版无限水的工业化形态。</li>
 *   <li><b>一源 = 一 tank = 1000mB</b>，且支持<b>部分抽取</b>（2026-10-06 实测修正）：drain 按请求量
 *       扣该源<b>余额</b>（{@code ContainerFluidData.sourceRemaining}，<b>落盘</b>）⇒ 慢管道
 *       （如 100mB/t）一滴滴抽、按自己的速率抽空一个源。源在游戏层仍是二进制资产（有 / 无），
 *       余额只是管道账本（汲走 / 挤没 / 转化消耗一律按整源处理）。</li>
 *   <li><b>无缓冲</b>：不缓存 FluidStack，每次调用现场读活数据（源随时被汲走 / 晋升）。</li>
 *   <li><b>只出不进</b>：{@link #fill} 恒 0 —— 守住「活桶是源的唯一种子工具」，与 §6.1 对称性一致。</li>
 *   <li><b>范围</b>：tank 数 = 当前<b>派生源</b>个数（权威资产表）；流动格不算（每 tick 由 BFS 重算）。</li>
 *   <li><b>末影箱不做</b>：流体数据在玩家附件里，没有 BE 可挂 ⇒ 管道无归属语义。</li>
 * </ul>
 *
 * <p>与红电接口同构：宽注册 + provider 四段判定链；实例内部自适应（无源 ⇒ 0 tank，永不 null 切换）。</p>
 */
public final class ContainerFluidHandler implements IFluidHandler {

    /** 一个源格对外的容量口径（mB）—— 单一真相在 {@link ContainerFluidData#SOURCE_MB}。 */
    public static final int SOURCE_MB = ContainerFluidData.SOURCE_MB;

    private final BlockEntity be;

    public ContainerFluidHandler(BlockEntity be) {
        this.be = be;
    }

    // ── 能力注册侧（框架入口见 LivingItem#onRegisterCapabilities）────────

    /**
     * 宽注册 provider 判定链（与红电 {@code ContainerEnergyStorage.resolveProvider} 同款四段）：
     * ① 随机战利品容器（未开箱）→ null；② 直接实现 {@link IFluidHandler} 的 BE → null（让位）；
     * ③ {@code level == null} → null，重入保护下查 {@code FluidHandler.BLOCK}：已有主人 → null；
     * ④ 返回实例（内部自适应 ⇒ 永不 null 切换，零失效隐患）。
     */
    public static IFluidHandler resolveProvider(BlockEntity be, @Nullable Direction side) {
        if (be instanceof RandomizableContainer rc && rc.getLootTable() != null) return null;
        if (be instanceof IFluidHandler) return null;              // 直接实现者让位
        Level level = be.getLevel();
        if (level == null) return null;
        if (DEFER_QUERY.get()) return null;                       // 重入保护：内层查询摘除自己
        DEFER_QUERY.set(true);
        try {
            var existing = level.getCapability(Capabilities.FluidHandler.BLOCK, be.getBlockPos(), side);
            if (existing != null) return null;                     // 已有主人 → 让位
        } finally {
            DEFER_QUERY.set(false);
        }
        return new ContainerFluidHandler(be);
    }

    private static final ThreadLocal<Boolean> DEFER_QUERY = ThreadLocal.withInitial(() -> false);

    // ── 活数据反查（BE → 容器 → 流体数据，与 tick 路径同源同键）────────

    /**
     * 取该 BE 对应容器的活流体数据（<b>只读 peek，不创建</b>）。
     *
     * <p>反查链 = {@link ContainerLivingItemHandler#resolveContextAt}（{@code ItemHandler.BLOCK}
     * 兼容面 + 双箱规范化 + 战利品跳过 ⇒ containerKey 与 tick 路径相同）
     * → {@code peekContainerData(ContainerDataKeys.FLUID)}。</p>
     *
     * @return 流体数据；非容器 / 无数据 / EMPTY 哨兵 ⇒ {@code null}
     */
    @Nullable
    public ContainerFluidData peekFluidData() {
        Level level = be.getLevel();
        if (level == null || level.isClientSide()) return null;   // 客户端不参与（也不许改源）
        TickableContainerContext ctx =
            ContainerLivingItemHandler.resolveContextAt(level, be.getBlockPos(), null);
        if (ctx == null) return null;
        ContainerFluidData data = ctx.peekContainerData(ContainerDataKeys.FLUID);
        return (data == null || data == ContainerFluidData.EMPTY) ? null : data;
    }

    /** 源格快照（按槽位升序 ⇒ tank 序号稳定）。 */
    private List<Source> sources() {
        ContainerFluidData data = peekFluidData();
        if (data == null) return List.of();
        List<Source> out = new ArrayList<>();
        data.getGeneratedSources().forEach((slot, type) -> out.add(new Source(slot, type)));
        out.sort(Comparator.comparingInt(Source::slot));
        return out;
    }

    /** 源格：槽位 + 流体类型。 */
    private record Source(int slot, FluidType type) {}

    private static FluidStack stackOf(FluidType type, int amount) {
        Fluid fluid = ContainerFluidData.representativeFluidOf(type);
        return fluid == null ? FluidStack.EMPTY : new FluidStack(fluid, amount);
    }

    // ── IFluidHandler ───────────────────────────────────────────

    @Override
    public int getTanks() {
        return sources().size();
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        ContainerFluidData data = peekFluidData();
        List<Source> list = sources();
        if (data == null || tank < 0 || tank >= list.size()) return FluidStack.EMPTY;
        // 报**余额**（部分抽取后 < 1000）—— 管道据此知道还能抽多少
        return stackOf(list.get(tank).type(), data.sourceRemaining(list.get(tank).slot()));
    }

    @Override
    public int getTankCapacity(int tank) {
        return SOURCE_MB;
    }

    /**
     * 「本 handler 是否<b>持有</b>该流体」= 该流体是否是某个源格里的流体。
     *
     * <p><b>不是</b>「能不能注入」—— 注入一律失败（{@link #fill} 恒 0）；这里如实回答
     * 「我这边有没有这种流体」，让按 {@code isFluidValid} 过滤的消费者不至于把整台机器判死。</p>
     */
    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        for (Source s : sources()) {
            FluidStack mine = stackOf(s.type(), 1);
            if (!mine.isEmpty() && mine.getFluid() == stack.getFluid()) return true;
        }
        return false;
    }

    /** 只出不进：恒 0（守住活桶的种子工具地位，见类注释）。 */
    @Override
    public int fill(FluidStack resource, FluidAction action) {
        return 0;
    }

    /** 按流体抽取：命中某个源 ⇒ 消耗该源并返回 {@code min(请求量, 1000)}；异种 ⇒ EMPTY。 */
    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (resource == null || resource.isEmpty()) return FluidStack.EMPTY;
        for (Source s : sources()) {
            FluidStack mine = stackOf(s.type(), 1);
            if (mine.isEmpty() || mine.getFluid() != resource.getFluid()) continue;
            return drainSlot(s, resource.getAmount(), action);
        }
        return FluidStack.EMPTY;
    }

    /** 不问流体的抽取：取槽位序第一个源。 */
    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        if (maxDrain <= 0) return FluidStack.EMPTY;
        for (Source s : sources()) {
            if (stackOf(s.type(), 1).isEmpty()) continue;   // 未注册流体：跳过
            return drainSlot(s, maxDrain, action);
        }
        return FluidStack.EMPTY;
    }

    /**
     * 按请求量抽取该源（**部分抽取**，2026-10-06 实测修正）：
     * 返回 {@code min(请求量, 该源余额)}，余额扣减；扣到 0 ⇒ 源消失。
     *
     * <p>⚠️ 原实现是「任意请求都吞整源、返回请求量」的<b>全有或全无</b>语义 ⇒ 慢管道
     * （如 100mB/t）每拍请求 100 却整源蒸发，几拍就把容器抽干（用户实测：瞬间抽完、且
     * 每次只拿到 100 不是 1000）。现在余额记在 {@link ContainerFluidData#sourceRemaining} 上
     * 并落盘 ⇒ 慢管道一滴滴抽，抽干才删源。</p>
     *
     * <p>{@code simulate()} 时只算不消耗（Create 的探测走这条路）。</p>
     */
    private FluidStack drainSlot(Source source, int requested, FluidAction action) {
        FluidStack probe = stackOf(source.type(), 1);
        if (probe.isEmpty()) return FluidStack.EMPTY;
        ContainerFluidData data = peekFluidData();
        if (data == null) return FluidStack.EMPTY;

        if (action.simulate()) {
            return new FluidStack(probe.getFluid(), Math.min(requested, data.sourceRemaining(source.slot())));
        }
        int taken = data.consumeSourceAmount(source.slot(), requested);
        if (taken <= 0) return FluidStack.EMPTY;
        be.setChanged();
        return new FluidStack(probe.getFluid(), taken);
    }
}
