package com.qiqi.li.living.transfer;

import com.qiqi.li.living.container.ContainerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 传输目标接管策略 —— 「目标槽有特殊语义时替代通用传输」的注册式扩展点。
 *
 * <p>与 {@link SlotInteractions} 的分工：交互方程在货物准入 / 过滤门<b>之前</b>分发、
 * 只收裸 ItemStack（施肥类「改目标内容」语义）；本接口在两侧 Accessor 建好、
 * {@code canPlaceItem} 模拟<b>之后</b>分发（路由类「换目的地」语义，需要读写
 * Accessor 本体）。两者位置不可互换。</p>
 *
 * <p>注册：领域在自己的 {@code *Registration} 里调 {@link TransferTargetPolicies#register}。
 * 分发按注册顺序，首个 {@link #matches} 者接管。</p>
 */
public interface TransferTargetPolicy {

    /** 目标识别。同一谓词也用于源侧探测（{@link #onProbingTarget}）的匹配。 */
    boolean matches(ItemStack stack);

    /** 容器内接管的决策。 */
    enum Outcome {
        /** 无策略接管或策略放行 —— 调用方走通用传输 */
        CONTINUE,
        /** 被策略拒绝 —— 本轮不传输（源侧探测也不触发） */
        REJECT,
        /** 策略已处理（传输 / 路由登记完成）—— 调用方无需再操作 */
        HANDLED
    }

    /**
     * 容器内传输的目标接管。
     *
     * @return 决策；{@code CONTINUE} 表示回落通用传输
     */
    Outcome interceptInContainer(SlotAccessor source, SlotAccessor target,
                                 ContainerContext ctx, Level level,
                                 int sourceSlot, int targetSlot, int hostSlot,
                                 int stackSize, int maxTransfer);

    /**
     * 跨容器拉取的目标接管（目标在本容器、源在邻居容器）。
     *
     * @return false 表示已接管但本轮无可用源 —— 调用方<b>不</b>回落通用拉取
     */
    boolean interceptCrossContainerPull(ContainerContext containerCtx, Level level,
                                        IItemHandler neighborHandler, BlockPos neighborPos,
                                        ItemStack targetStack, int targetSlot,
                                        int stackSize, int maxTransfer,
                                        FilterData filterData, int hostSlot);

    /**
     * 源侧探测：容器内传输且<b>目标无任何策略接管</b>时，对匹配 sourceStack 的策略回调，
     * 用于源自身的传输伴随状态（如偏好类型设置）。REJECT 路径不会走到这里。
     */
    default void onProbingTarget(SlotAccessor source, ContainerContext ctx,
                                 int targetSlot, ItemStack targetStack) {
    }
}
