package com.qiqi.li.living.transfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.qiqi.li.living.container.ContainerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 传输目标策略注册表 + 统一分发器 —— 与 {@link SlotInteractions} 同构。
 *
 * <p>调用点只有两处，且与具体策略无关（{@code TransferPipeline.executeInContainer}
 * 末段、{@code CrossContainerTransfer.pullFromNeighbor} 的目标分派）——
 * 新增策略只注册一条，调用点不再改动。</p>
 */
public final class TransferTargetPolicies {

    /** 注册的策略（按注册顺序匹配，先注册者优先——同一目标不应注册多条）。 */
    private static final List<TransferTargetPolicy> POLICIES =
        Collections.synchronizedList(new ArrayList<>());

    private TransferTargetPolicies() {}

    /** 注册一条目标策略（由领域在 {@code *Registration} 里调用）。 */
    public static void register(TransferTargetPolicy policy) {
        POLICIES.add(policy);
    }

    /** 测试专用：当前注册表快照（不改内容）。 */
    static List<TransferTargetPolicy> snapshotForTest() {
        return new ArrayList<>(POLICIES);
    }

    /** 测试专用：替换注册表内容（隔离 / 恢复，避免静态表跨测试污染）。 */
    static void replaceForTest(List<TransferTargetPolicy> policies) {
        POLICIES.clear();
        POLICIES.addAll(policies);
    }

    /**
     * 容器内分发：目标被某策略接管则委托之；无接管时先触发源侧探测
     * （{@code onProbingTarget}），再回落通用传输。
     */
    public static TransferTargetPolicy.Outcome interceptInContainer(
            SlotAccessor source, ItemStack sourceStack,
            SlotAccessor target, ItemStack targetStack,
            ContainerContext ctx, Level level,
            int sourceSlot, int targetSlot, int hostSlot,
            int stackSize, int maxTransfer) {
        for (TransferTargetPolicy policy : POLICIES) {
            if (policy.matches(targetStack)) {
                return policy.interceptInContainer(source, target, ctx, level,
                    sourceSlot, targetSlot, hostSlot, stackSize, maxTransfer);
            }
        }
        for (TransferTargetPolicy policy : POLICIES) {
            if (policy.matches(sourceStack)) {
                policy.onProbingTarget(source, ctx, targetSlot, targetStack);
            }
        }
        return TransferTargetPolicy.Outcome.CONTINUE;
    }

    /**
     * 跨容器拉取分发。
     *
     * @return {@code null} = 无策略接管（调用方走通用拉取）；
     *         非 null = 已接管，值为接管结果（false = 本轮无可用源）
     */
    public static Boolean interceptCrossContainerPull(
            ContainerContext containerCtx, Level level,
            IItemHandler neighborHandler, BlockPos neighborPos,
            ItemStack targetStack, int targetSlot,
            int stackSize, int maxTransfer,
            FilterData filterData, int hostSlot) {
        for (TransferTargetPolicy policy : POLICIES) {
            if (policy.matches(targetStack)) {
                return policy.interceptCrossContainerPull(containerCtx, level,
                    neighborHandler, neighborPos, targetStack, targetSlot,
                    stackSize, maxTransfer, filterData, hostSlot);
            }
        }
        return null;
    }
}
