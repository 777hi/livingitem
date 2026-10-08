package com.qiqi.li.living.domain.water;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.SimpleContainerContext;
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
 *   <li><b>抽取 = 消耗整源</b>：drain 请求量 ≥ 1000mB 时给满 1000 并删源；<b>拿不满就不给</b>。
 *       等价「自动化汲走」；两源夹一格 + 抽中间 + 晋升补中间 ⇒ 有限速率的无限水（§10.3）。</li>
 *   <li><b>一源 = 一个 tank = 1000mB 整单位</b>：<b>无分数源、无余额</b>。源在本系统里到处是
 *       二进制语义（挤没 / 晋升 / 汲走 / 刷石机 / 黑曜石 / 落盘 / 渲染）⇒ 分数源会污染每条路径；
 *       自动化流体交互留给<b>专用流体活物品</b>（活涂蜡铜灯存电那套逻辑），不在此扩展。</li>
 *   <li><b>无缓冲</b>：不缓存 FluidStack，每次调用现场读活数据（源随时被汲走 / 晋升）。</li>
 *   <li><b>只出不进</b>：{@link #fill} 恒 0 —— 守住「活桶是源的唯一种子工具」，与 §6.1 对称性一致。</li>
 *   <li><b>范围</b>：tank 数 = 当前<b>派生源</b>个数（权威资产表）；流动格不算（每 tick 由 BFS 重算）。</li>
 *   <li><b>末影箱不做</b>：流体数据在玩家附件里，没有 BE 可挂 ⇒ 管道无归属语义。</li>
 * </ul>
 *
 * <p>与红电接口同构：宽注册 + provider 四段判定链；实例内部自适应（无源 ⇒ 0 tank，永不 null 切换）。</p>
 */
public final class ContainerFluidHandler implements IFluidHandler {

    /** 一个源格对外的容量口径（mB）—— 与 Create 的 tank 容量一致；源是**整源**单位（§10.3）。 */
    public static final int SOURCE_MB = 1000;

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

    // ── 容器级流体数据的取用（原 ContainerLivingItemHandler#getFluidData，2026-10-08 计划 ⑤ 移入）──

    /**
     * 取或创建容器级持久化流体数据（含 BE / Player 附件的<b>回填</b>）。
     *
     * <p>回填路径（LRU 驱逐 / 退出重进后首访）：① 先看 BE 附件的 {@code CONTAINER_FLUID_DATA}；
     * ② 玩家背包 / 末影箱无 BE 可挂 ⇒ 看 Player 附件的按容器键映射。</p>
     *
     * @return 流体数据；容器不支持容器级数据（无稳定键）时返回 {@code null}
     */
    public static ContainerFluidData getOrCreateFluidData(ContainerContext ctx) {
        ContainerFluidData existing = ctx.peekContainerData(ContainerFluidData.KEY);
        if (existing != null) return existing;

        if (ctx instanceof SimpleContainerContext simpleCtx) {
            for (BlockEntity be : simpleCtx.getAssociatedBlockEntities()) {
                ContainerFluidData persisted = be.getData(LivingComponents.CONTAINER_FLUID_DATA);
                if (persisted != null && persisted != ContainerFluidData.EMPTY && !persisted.isEmpty()) {
                    ContainerLivingItemHandler.putContainerData(ctx, ContainerFluidData.KEY, persisted);
                    return persisted;
                }
            }
        }

        // 玩家背包 / 末影箱（B.5 第三项）：无 BE 可挂 ⇒ 从 Player attachment 回填（按容器键）。
        // ⚠️ getData 可能返回 null（测试替身 / 附件未注册），必须判空。
        Player owner = ctx.getOwnerPlayer();
        if (owner != null) {
            String ownerKey = ctx.getContainerKey();
            if (ownerKey != null) {
                Map<String, ContainerFluidData> playerMap =
                    owner.getData(LivingComponents.CONTAINER_FLUID_DATA_PLAYER);
                ContainerFluidData persisted = playerMap == null ? null : playerMap.get(ownerKey);
                if (persisted != null && persisted != ContainerFluidData.EMPTY && !persisted.isEmpty()) {
                    ContainerLivingItemHandler.putContainerData(ctx, ContainerFluidData.KEY, persisted);
                    return persisted;
                }
            }
        }

        return ctx.getOrCreateContainerData(ContainerFluidData.KEY);
    }

    // ── 活数据反查（BE → 容器 → 流体数据，与 tick 路径同源同键）────────

    /**
     * 取该 BE 对应容器的活流体数据（<b>只读 peek，不创建</b>）。
     *
     * <p>反查链 = {@link ContainerLivingItemHandler#resolveContextAt}（{@code ItemHandler.BLOCK}
     * 兼容面 + 双箱规范化 + 战利品跳过 ⇒ containerKey 与 tick 路径相同）
     * → {@code peekContainerData(ContainerFluidData.KEY)}。</p>
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
        ContainerFluidData data = ctx.peekContainerData(ContainerFluidData.KEY);
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
        List<Source> list = sources();
        if (tank < 0 || tank >= list.size()) return FluidStack.EMPTY;
        return stackOf(list.get(tank).type(), SOURCE_MB);   // 整源：恒 1000mB（无分数源）
    }

    @Override
    public int getTankCapacity(int tank) {
        return SOURCE_MB;
    }

    /**
     * 「<b>该 tank</b> 是否持有该流体」（NeoForge 契约是<b>按 tank</b> 回答）。
     *
     * <p><b>不是</b>「能不能注入」—— 注入一律失败（{@link #fill} 恒 0）；这里如实回答
     * 「这一格源里是不是这种流体」，让按 {@code isFluidValid} 过滤的消费者不至于把整台机器判死。</p>
     *
     * <p>⚠️ 2026-10-07 收尾审查：此前<b>忽略 {@code tank}</b>、遍历所有源回答 ⇒
     * 按 tank 过滤的消费者会拿到跨 tank 的答复。现只判断该 tank 对应的源（越界 false）。</p>
     */
    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        List<Source> list = sources();
        if (tank < 0 || tank >= list.size()) return false;
        FluidStack mine = stackOf(list.get(tank).type(), 1);
        return !mine.isEmpty() && mine.getFluid() == stack.getFluid();
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
     * <b>整源抽取</b>（A 方案，2026-10-06 实测修正）：源是<b>整单位</b>资产 ——
     * 请求量 ≥ {@link #SOURCE_MB} 才给 1000mB 并删源；<b>拿不满 1000 就一分不给</b>（EMPTY）。
     *
     * <p>⚠️ 之前是「任意 ≥1mB 请求都吞整源、只返请求量」⇒ 慢管道（如 100mB/t）每拍蒸发一个源
     * 却只换回 100mB，几拍就把容器抽干（用户实测：瞬间抽空 + 每次不是 1000）。</p>
     *
     * <p><b>为什么不做部分抽取 / 无限源</b>（2026-10-06 用户拍板）：源在本系统里到处是
     * <b>二进制语义</b>（挤没 / 晋升 / 汲走 / 刷石机 / 黑曜石 / 落盘 / 渲染），
     * 引入「1000mB 的源只放得出 100mB」这种分数源会污染每一条路径；无限源（舀水不删源）
     * 则让源彻底失去「资产」意义。真正的自动化流体交互留给<b>专用流体活物品</b>
     * （如活涂蜡铜灯存电那套：自带缓冲与规则），不在通用源能力上做文章。</p>
     *
     * <p>{@code simulate()} 时只算不删源（Create 的探测走这条路）。</p>
     */
    private FluidStack drainSlot(Source source, int requested, FluidAction action) {
        FluidStack probe = stackOf(source.type(), 1);
        if (probe.isEmpty()) return FluidStack.EMPTY;
        if (requested < SOURCE_MB) return FluidStack.EMPTY;   // 拿不满整源 ⇒ 不给（不浪费源）
        if (action.simulate()) return new FluidStack(probe.getFluid(), SOURCE_MB);
        ContainerFluidData data = peekFluidData();
        if (data == null) return FluidStack.EMPTY;
        data.removeGeneratedSource(source.slot());             // 整源消耗（≡ 汲走，唯一正确的删源口径）
        be.setChanged();
        return new FluidStack(probe.getFluid(), SOURCE_MB);
    }
}
