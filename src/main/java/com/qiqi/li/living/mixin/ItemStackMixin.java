package com.qiqi.li.living.mixin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.chest.LivingChestStackFlags;
import com.qiqi.li.living.chest.LivingChestStackHandler;
import com.qiqi.li.living.function.LivingChestFunction;

/**
 * ItemStack Mixin — 活箱子（Living Chest）UUID 生命周期管理的中枢。
 *
 * <h2>背景</h2>
 * <p>每个活箱子（count=1）拥有一个独立的 UUID，对应磁盘上的一个虚拟箱子文件。
 * 当活箱子堆叠时（count=N），它持有 N 个 UUID，对应 N 个虚拟箱子。
 * 本 Mixin 的职责是确保在物品堆叠的所有操作中，UUID 列表始终与堆叠数保持同步，
 * 且 UUID 永远不会丢失（UUID 丢失 = 箱内物品永久无法找回）。</p>
 *
 * <h2>六个核心职责</h2>
 * <table>
 *   <tr><th>职责</th><th>拦截方法</th><th>说明</th></tr>
 *   <tr><td>1. 堆叠判定</td><td>isSameItemSameComponents</td>
 *       <td>让不同 UUID 的活箱子相互堆叠，通过 ThreadLocal 标志区分玩家 GUI 操作和世界交互</td></tr>
 *   <tr><td>2. 拆分 UUID 分配</td><td>split</td>
 *       <td>在服务端拦截 split()，按比例拆分 UUID 列表分配给原堆和新堆</td></tr>
 *   <tr><td>3. 合并 UUID 转移</td><td>grow + shrink</td>
 *       <td>通过 ThreadLocal 传递 UUID，兼容左键合并（先 grow 后 shrink）和右键/漏斗合并（先 shrink 后 grow）</td></tr>
 *   <tr><td>4. Shift+点击转移</td><td>setCount</td>
 *       <td>原版容器使用 setCount 而非 grow/shrink 进行物品转移，需要单独拦截</td></tr>
 * <tr><td>5. 右键/中键拖拽</td><td>copyWithCount</td>
 *       <td>右键拖拽分发物品时按比例拆分 UUID。创造模式 QUICK_CRAFT 不经过 shrink，
 *       直接在 copyWithCount 中完成拆分；CLONE 复制则清空副本 UUID 防止泄露。</td></tr>
 *   <tr><td>6. 创造模式防护</td><td>copyWithCount</td>
 *       <td>创造模式中键复制（ClickType.CLONE）时清空副本 UUID，防止 UUID 数量不可控膨胀</td></tr>
 * </table>
 *
 * <h2>安全策略</h2>
 * <ul>
 *   <li><strong>仅服务端执行</strong>：除创造模式防护外的 UUID 修改操作通过
 *       {@code ServerLifecycleHooks.getCurrentServer() != null} 检查，客户端调用直接跳过。</li>
 *   <li><strong>创造模式防护双路径</strong>：创造模式检测同时覆盖服务端和客户端线程，
 *       因为创造模式物品列表标签页的中键复制在客户端线程执行 copyWithCount。</li>
 *   <li><strong>UUID 只增不减</strong>：任何自动缩减 UUID 列表的操作都被禁止。
 *       唯一删除 UUID 的路径是用户明确 toggle living off（{@code LivingChestFunction.dropAllItems}）。</li>
 *   <li><strong>split 期间互斥</strong>：split() 内部会调用 shrink()，通过
 *       {@link #PRE_SPLIT_UUIDS} 标志阻止 onShrink 在 split 期间重复处理。</li>
 *   <li><strong>线程安全</strong>：所有 ThreadLocal 变量在每次操作完成后立即清理，
 *       避免跨操作污染。创造模式 INVENTORY 标签页的点击操作在客户端线程执行
 *       （{@code CreativeModeInventoryScreen.slotClicked} 直接调用 {@code inventoryMenu.clicked()}），
 *       因此 UUID 管理逻辑不再限制服务端线程，客户端和服务端线程均执行。</li>
 * </ul>
 *
 * <h2>合并时序兼容性</h2>
 * <p>不同操作触发的 grow/shrink 调用顺序不同：</p>
 * <ul>
 *   <li><strong>左键合并</strong>（拿起一堆放到另一堆上）：grow 先执行，shrink 后执行</li>
 *   <li><strong>右键合并</strong>（右键逐个放置）：shrink 先执行，grow 后执行</li>
 *   <li><strong>漏斗/投掷器</strong>：shrink 先执行，grow 后执行</li>
 *   <li><strong>split 后合并</strong>：split 内部调用 shrink，紧接着调用 grow</li>
 * </ul>
 * <p>{@link #LivingChestStackFlags.PENDING_TRANSFER} 的设计允许先执行的一方存储待转移数据，后执行的一方来消费。</p>
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 拦截 {@code ItemStack.isSameItemSameComponents()}，使不同 UUID 的活箱子可以相互堆叠。
     *
     * <h3>为什么需要这个拦截</h3>
     * <p>活箱子使用 DataComponent 存储 UUID 列表，不同堆叠的 UUID 列表内容不同，
     * 导致原版的 {@code isSameItemSameComponents} 判定它们为"不同"物品，无法堆叠。
     * 本拦截在玩家手动操作时忽略 UUID 差异，允许堆叠。</p>
     *
     * <h3>方案A：ALLOW_STACK 仅用于此堆叠判定</h3>
     * <p>ALLOW_STACK 的唯一作用就是控制本方法的返回值。其他所有 UUID 转移逻辑
     * （split/shrink/grow/setCount/copyWithCount）不再检查 ALLOW_STACK，
     * 确保漏斗、投掷器、模组管道等自动化系统也能正确处理 UUID 的合并与拆分。</p>
     *
     * <h3>为什么用 ThreadLocal 标志而不是直接返回 true</h3>
     * <p>如果无条件返回 true，会导致掉落物、漏斗等世界交互也忽略 UUID 差异，
     * 可能造成不同活箱子意外合并。通过 {@link LivingChestStackFlags#ALLOW_STACK}
     * 标志，只在玩家 GUI 操作时启用跨 UUID 堆叠：</p>
     * <ul>
     *   <li>玩家拖拽物品 → 设置 ALLOW_STACK → 本方法返回 true → 允许堆叠</li>
     *   <li>掉落物落地 → ALLOW_STACK 为 null → 走原版逻辑 → 不同 UUID 不堆叠</li>
     *   <li>漏斗传输 → ALLOW_STACK 为 null → 走原版逻辑 → 不同 UUID 不堆叠</li>
     * </ul>
     * <p>注意：漏斗传输同一种活箱子（相同 UUID 列表）时，原版判定为相同物品，
     * 会正常堆叠，此时 onGrow/onShrink 会正确处理 UUID 合并。</p>
     *
     * <h3>前置检查</h3>
     * <ul>
     *   <li>null 检查：防止空指针</li>
     *   <li>物品类型检查：不同类型物品不应堆叠</li>
     *   <li>活箱子检查：非活箱子走原版逻辑</li>
     * </ul>
     */
    @Inject(method = "isSameItemSameComponents", at = @At("HEAD"), cancellable = true)
    private static void onIsSameItemSameComponents(ItemStack stack, ItemStack other, CallbackInfoReturnable<Boolean> cir) {
        // 空指针防护
        if (stack == null || other == null) return;

        // 非活箱子不干预，走原版逻辑
        if (!isLivingChest(stack) || !isLivingChest(other)) {
            return;
        }

        // 不同类型物品不堆叠（防御性检查）
        if (stack.getItem() != other.getItem()) {
            cir.setReturnValue(false);
            return;
        }

        // 仅当玩家 GUI 操作时（ALLOW_STACK 已设置）才允许跨 UUID 堆叠
        // 但保留其他所有差异（如铁砧重命名、附魔等），只忽略 UUID 差异
        if (LivingChestStackFlags.ALLOW_STACK.get() != null) {
            // 创建副本并移除 LIVING_FUNCTION_DATA（包含 UUID 等活物品专属数据），
            // 然后用原版 isSameItemSameComponents 比较副本。
            // 副本不再是活箱子，因此不会触发本 Mixin 的递归拦截。
            // 这样自定义名称等差异仍会阻止堆叠，只有 UUID 差异被忽略。
            ItemStack copyA = stack.copy();
            ItemStack copyB = other.copy();
            LivingItemManager.clearLivingData(copyA);
            LivingItemManager.clearLivingData(copyB);
            cir.setReturnValue(ItemStack.isSameItemSameComponents(copyA, copyB));
        }
    }

    /**
     * 拦截 setCount()，处理 shift+点击快速移动和数字键交换时的 UUID 转移。
     *
     * <h3>为什么需要这个拦截</h3>
     * <p>原版 {@code AbstractContainerMenu.moveItemStackTo()} 和数字键交换物品时，
     * 直接使用 {@code setCount()} 而非 {@code grow()}/{@code shrink()}，
     * 导致 onGrow / onShrink 无法拦截，UUID 丢失。具体场景：</p>
     * <pre>
     *   // 全量合并：两个堆都用 setCount，grow 和 shrink 都不触发
     *   stack.setCount(0);               // 源堆清零
     *   itemstack.setCount(j);           // 目标堆增长
     *
     *   // 部分合并：shrink 触发，但 grow 用 setCount 替代，不触发 onGrow
     *   stack.shrink(k - itemstack.getCount());
     *   itemstack.setCount(k);           // 目标堆增长，但 onGrow 不触发
     * </pre>
     *
     * <h3>处理策略</h3>
     * <p>利用 {@link #LivingChestStackFlags.PENDING_TRANSFER} 作为中转站：</p>
     * <ul>
     *   <li><strong>源堆清零</strong>（count → 0）：将 UUID 列表暂存到 LivingChestStackFlags.PENDING_TRANSFER，
     *       等待目标堆的 setCount 来消费。UUID 不会被丢弃，只是暂存。</li>
     *   <li><strong>目标堆增长</strong>（count 增加）：检查 LivingChestStackFlags.PENDING_TRANSFER 中是否有
     *       来自清零源堆的 UUID 等待转移。数量匹配则合并，不匹配则丢弃（表示异源操作）。</li>
     * </ul>
     *
     * <h3>与其他方法的互斥</h3>
     * <ul>
     *   <li>split 内部调用 setCount 时，{@link #PRE_SPLIT_UUIDS} 已设置，直接跳过本方法。</li>
     *   <li>grow/shrink 内部调用 setCount 时，grow-first 的 LivingChestStackFlags.PENDING_TRANSFER.uuids 为 null，
     *       不会被本方法消费，留给 onShrink 处理。</li>
     * </ul>
     *
     * <h3>安全边界</h3>
     * <ul>
     *   <li>{@code oldCount == count}：无变化，跳过</li>
     *   <li>{@code count == 0 && oldCount > 0}：源堆耗尽，暂存 UUID</li>
     *   <li>{@code count > oldCount}：目标堆增长，尝试消费暂存的 UUID</li>
     *   <li>{@code count < oldCount && count > 0}：不处理（此时应走 shrink 路径）</li>
     * </ul>
     */
    @Inject(method = "setCount", at = @At("HEAD"))
    private void onSetCount(int count, CallbackInfo ci) {
        ItemStack self = (ItemStack)(Object)this;
        if (!isLivingChest(self)) return;

        int oldCount = self.getCount();
        if (oldCount == count) return;  // 无变化，跳过

        // split 内部调用 setCount 时，PRE_SPLIT_UUIDS 已设置，跳过
        if (LivingChestStackFlags.PRE_SPLIT_UUIDS.get() != null) return;

        // 方块放置期间调用 setCount 时，跳过
        if (LivingChestStackFlags.BLOCK_PLACING_UUIDS.get() != null) return;

        // 分支 1：源堆被清零（shift+点击全量转移）
        if (count == 0 && oldCount > 0) {
            List<UUID> uuids = LivingChestStackHandler.getUuids(self);
            if (!uuids.isEmpty() && uuids.size() == oldCount) {
                LOGGER.info("[onSetCount] source depleted: storing {} uuids", uuids.size());
                // 暂存 UUID 到 LivingChestStackFlags.PENDING_TRANSFER，等待目标堆的 setCount 来消费
                LivingChestStackFlags.PENDING_TRANSFER.set(new LivingChestStackFlags.MergeTransfer(null, oldCount, new ArrayList<>(uuids)));
                // 清空自身的 UUID（源堆即将消失，UUID 已安全转移）
                LivingChestStackHandler.setUuids(self, List.of());
            }
        }
        // 分支 2：目标堆增长（shift+点击接收方）
        else if (count > oldCount) {
            LivingChestStackFlags.MergeTransfer pending = LivingChestStackFlags.PENDING_TRANSFER.get();
            if (pending != null && pending.uuids() != null && !pending.uuids().isEmpty()) {
                int delta = count - oldCount;
                if (pending.uuids().size() == delta) {
                    LOGGER.info("[onSetCount] target grew by {}: consuming {} uuids", delta, pending.uuids().size());
                    mergeIntoTargetUpToCount(self, pending.uuids(), count, "onSetCount");
                    LivingChestStackFlags.PENDING_TRANSFER.remove();
                } else {
                    // 数量不匹配，说明不是同一个合并操作的双方，丢弃 LivingChestStackFlags.PENDING_TRANSFER
                    LOGGER.info("[onSetCount] target grew by {}: pending size mismatch (pending={}), clearing",
                        delta, pending.uuids().size());
                    LivingChestStackFlags.PENDING_TRANSFER.remove();
                }
            }
            // 分支 3：LivingChestStackFlags.PENDING_TRANSFER.uuids 为 null（grow-first 模式），不消费，
            // 留给后续 onShrink 处理
        }
    }

    /**
     * 拦截 split() 方法，在服务端按比例拆分 UUID 列表。
     *
     * <h3>为什么分 HEAD 和 RETURN 两步拦截</h3>
     * <p>split() 内部会调用 shrink()，而 onShrink 会修改 UUID 列表。
     * 如果在 RETURN 才读取 UUID，读到的已经是 onShrink 修改后的残缺数据。
     * 因此必须在 HEAD 捕获原始 UUID 快照，在 RETURN 用快照做拆分：</p>
     * <pre>
     *   split(amount) 执行流程：
     *   1. HEAD: 捕获原始 UUID 快照 → PRE_SPLIT_UUIDS
     *   2. 原版: 创建新堆，调整两个堆的 count
     *   3. 内部调用 shrink() → onShrink 被 PRE_SPLIT_UUIDS 阻断
     *   4. RETURN: 用快照拆分 UUID → 分配给两个堆
     * </pre>
     *
     * <h3>场景分支</h3>
     * <ul>
     *   <li><strong>真正拆分</strong>（amount < 总数量）：原堆保留前 M 个 UUID，新堆获得后 N 个 UUID。
     *       拆分后新堆的 UUID 存入 {@link #SPLIT_UUIDS_FOR_GROW}，供紧随其后的 grow 消费。</li>
     *   <li><strong>拿起整堆</strong>（amount >= 总数量）：原堆变为空，所有 UUID 跟随新堆。
     *       此时不算真正拆分，跳过处理，新堆自然保留全部 UUID。</li>
     *   <li><strong>客户端调用</strong>：HEAD 设置 PRE_SPLIT_UUIDS 标志（阻止 onCopyWithCount 在
     *       创造模式下误清 UUID），RETURN 正常执行 UUID 拆分（不再跳过），
     *       确保创造模式 INVENTORY 标签页的拆分操作也能正确分配 UUID。</li>
     * </ul>
     *
     * <h3>UUID 安全保证</h3>
     * <p>拆分总数 = 原堆剩余 + 新堆获得 = 原始 UUID 总数，UUID 无丢失。</p>
     */

    /**
     * 注意：PRE_SPLIT_UUIDS、SPLIT_UUIDS_FOR_GROW、LivingChestStackFlags.PENDING_TRANSFER 和 LivingChestStackFlags.MergeTransfer
     * 已移至 {@link LivingChestStackFlags} 统一管理。以下通过该类引用。
     */

    @Inject(method = "split", at = @At("HEAD"), cancellable = true)
    private void onSplitHead(int amount, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack self = (ItemStack)(Object)this;
        if (!isLivingChest(self)) return;

        List<UUID> uuids = LivingChestStackHandler.getUuids(self);

        // 始终设置 PRE_SPLIT_UUIDS 标志，阻止 split 内部的 onCopyWithCount/onShrink/onSetCount 执行。
        // 客户端线程也需要设置：创造模式 INVENTORY 标签页的 slotClicked 在客户端线程
        // 直接调用 inventoryMenu.clicked()，不经过服务端。若不设置，onCopyWithCount
        // 的 isCreativeMode() 会误判为"创造模式复制"从而清空 UUID。
        // onSplitReturn 中会无条件 remove() 清理此标志，不会泄漏。
        LivingChestStackFlags.PRE_SPLIT_UUIDS.set(new ArrayList<>(uuids));

        // 清理上一次可能残留的 SPLIT_UUIDS_FOR_GROW（客户端和服务端线程都需要清理）
        LivingChestStackFlags.SPLIT_UUIDS_FOR_GROW.remove();

        LOGGER.info("[onSplitHead] count={}, amount={}, uuids={}, uuidsEmpty={}, thread={}",
            self.getCount(), amount, uuids, uuids.isEmpty(),
            Thread.currentThread().getName());

        // 以下 UUID 拆分逻辑仅服务端线程处理
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) {
            LOGGER.info("[onSplitHead] not on server thread, skipping UUID split");
            return;
        }
    }

    @Inject(method = "split", at = @At("RETURN"))
    private void onSplitReturn(int amount, CallbackInfoReturnable<ItemStack> cir) {
        // 取出 HEAD 中捕获的 UUID 快照并立即清理互斥标志
        List<UUID> uuids = LivingChestStackFlags.PRE_SPLIT_UUIDS.get();
        LivingChestStackFlags.PRE_SPLIT_UUIDS.remove();
        if (uuids == null) return;  // 非活箱子，跳过

        ItemStack original = (ItemStack)(Object)this;
        ItemStack newStack = cir.getReturnValue();

        // 拿起整堆（amount >= 总数量）时新堆可能为空，跳过
        if (newStack.isEmpty()) return;

        int originalCount = original.getCount();
        int newCount = newStack.getCount();

        LOGGER.info("[onSplitReturn] originalCount={}, newCount={}, uuids={}",
            originalCount, newCount, uuids);

        // 校验：拆分后的 count 之和应等于原始 UUID 数
        if (uuids.size() != originalCount + newCount) {
            LOGGER.warn("[onSplitReturn] size mismatch: uuids={}, expected={}", uuids.size(), originalCount + newCount);
            return;
        }

        // 按 newCount 的比例拆分 UUID 列表
        LivingChestStackHandler.SplitResult result = LivingChestStackHandler.splitUuidList(uuids, newCount);

        // 新堆获得头部 UUID（活跃的，有物品），原堆保留尾部 UUID（预留的）
        LivingChestStackHandler.setUuidsUnsorted(original, result.remain());
        LivingChestStackHandler.setUuidsUnsorted(newStack, result.split());
        LOGGER.info("[onSplitReturn] remain={}, split={}", result.remain(), result.split());

        // 将新堆的 UUID 存入 SPLIT_UUIDS_FOR_GROW，供紧随其后的 grow 消费
        if (!result.split().isEmpty()) {
            LivingChestStackFlags.SPLIT_UUIDS_FOR_GROW.set(new ArrayList<>(result.split()));
        }
    }

    /**
     * 将一批 UUID 安全合并到目标堆，但绝不让目标堆的 UUID 数量超过预期堆叠数。
     *
     * <p>创造模式下某些复制/切换路径可能让目标堆在进入 grow/setCount 之前，
     * 已经携带了完整甚至偏多的 UUID 列表。此时如果再按常规 addAll，会把
     * UUID 数量推到超过堆叠数。这里统一做两层保护：</p>
     * <ul>
     *   <li>去重：相同 UUID 不重复追加</li>
     *   <li>限长：最多只补到 expectedCount 为止</li>
     * </ul>
     *
     * <p>注意：这里不会主动删除目标堆已有 UUID，只阻止“继续超量追加”。</p>
     * @return 实际合并的 UUID 数量；如果目标已有足够 UUID 导致完全跳过合并，返回 -1
     */
    private static int mergeIntoTargetUpToCount(ItemStack target, List<UUID> incoming, int expectedCount, String source) {
        if (incoming == null || incoming.isEmpty()) return 0;
        if (expectedCount <= 0) return -1;

        List<UUID> current = LivingChestStackHandler.getUuids(target);
        if (current.size() >= expectedCount) {
            LOGGER.warn("[{}] target already has {} uuids (expectedCount={}), skipping merge to avoid overflow",
                source, current.size(), expectedCount);
            return -1;
        }

        LinkedHashSet<UUID> mergedSet = new LinkedHashSet<>(current);
        int added = 0;
        for (UUID uuid : incoming) {
            if (mergedSet.size() >= expectedCount) {
                break;
            }
            if (mergedSet.add(uuid)) {
                added++;
            }
        }

        List<UUID> merged = new ArrayList<>(mergedSet);
        if (merged.size() > expectedCount) {
            merged = new ArrayList<>(merged.subList(0, expectedCount));
        }

        if (merged.size() < current.size() + incoming.size()) {
            LOGGER.warn("[{}] capped uuid merge: current={}, incoming={}, merged={}, expectedCount={}",
                source, current.size(), incoming.size(), merged.size(), expectedCount);
        }

        LivingChestStackHandler.setUuids(target, merged);
        return added;
    }

    private static boolean isLivingChest(ItemStack stack) {
        return com.qiqi.li.living.function.LivingChestFunction.isLivingChest(stack);
    }

    /**
     * 合并传输记录：在 grow() 和 shrink() 之间传递 UUID 数据。
     *
     * <h3>两种使用模式</h3>
     * <ul>
     *   <li><strong>grow-first</strong>（左键合并）：grow 先执行，存储
     *       {@code (target=目标堆, amount=增长量, uuids=null)}，
     *       shrink 后执行，发现 uuids 为 null，消费此记录并转移 UUID 到目标堆。</li>
     *   <li><strong>shrink-first</strong>（右键合并/漏斗）：shrink 先执行，存储
     *       {@code (target=null, amount=缩减量, uuids=被移除的UUID)}，
     *       grow 后执行，发现 uuids 非 null，直接消费并合并 UUID。</li>
     *   <li><strong>setCount 路径</strong>（shift+点击）：onSetCount 源堆清零时存储
     *       {@code (target=null, amount=oldCount, uuids=全部UUID)}，
     *       目标堆 onSetCount 增长时消费。</li>
     * </ul>
     /**
     * 注意：LivingChestStackFlags.MergeTransfer record 和 LivingChestStackFlags.PENDING_TRANSFER、PRE_SPLIT_UUIDS、
     * SPLIT_UUIDS_FOR_GROW 已移至 {@link LivingChestStackFlags} 统一管理。
     */

    /**
     * 拦截 grow()，与 shrink() 配对完成 UUID 转移。
     *
     * <h3>执行优先级（按顺序检查）</h3>
     * <ol>
     *   <li><strong>split 后 grow</strong>：检查 {@link #SPLIT_UUIDS_FOR_GROW}，
     *       如果有数据且数量匹配，直接合并到当前堆。这是 split 内部的 grow，
     *       不应该走 LivingChestStackFlags.PENDING_TRANSFER 逻辑。</li>
     *   <li><strong>shrink-first 合并</strong>：检查 {@link #LivingChestStackFlags.PENDING_TRANSFER}，
     *       如果 uuids 非 null（shrink 先执行留下的），直接合并并清理。</li>
     *   <li><strong>grow-first 合并</strong>：如果 LivingChestStackFlags.PENDING_TRANSFER 为空或
     *       uuids 为 null，存储当前 grow 信息，等待后续 shrink 来消费。</li>
     * </ol>
     *
     * <h3>为什么 amount<=0 时跳过</h3>
     * <p>原版 shrink 内部会调用 {@code grow(-amount)} 来减少数量。
     * 如果处理 amount<=0，会导致 shrink 内部调用 grow 时误触发 UUID 转移逻辑。</p>
     *
     * <h3>互斥保护</h3>
     * <p>PRE_SPLIT_UUIDS 非 null 时表示正在 split 内部，跳过所有处理。</p>
     */
    @Inject(method = "grow", at = @At("HEAD"))
    private void onGrow(int amount, CallbackInfo ci) {
        ItemStack self = (ItemStack)(Object)this;
        if (!isLivingChest(self)) return;

        // split 内部调用 grow 时，PRE_SPLIT_UUIDS 已设置，跳过
        if (LivingChestStackFlags.PRE_SPLIT_UUIDS.get() != null) {
            LOGGER.info("[onGrow] inside split, skipping");
            return;
        }

        // shrink 内部会调用 grow(-amount)，跳过负数调用
        if (amount <= 0) {
            LOGGER.info("[onGrow] amount<=0, skipping (internal shrink call)");
            return;
        }

        // 优先级 1：检查是否有 split 后待消费的 UUID
        List<UUID> splitUuids = LivingChestStackFlags.SPLIT_UUIDS_FOR_GROW.get();
        if (splitUuids != null && splitUuids.size() == amount) {
            LivingChestStackFlags.SPLIT_UUIDS_FOR_GROW.remove();
            LOGGER.info("[onGrow] consuming split UUIDs for grow: {}", splitUuids);
            mergeIntoTargetUpToCount(self, splitUuids, self.getCount() + amount, "onGrow-split");
            return;
        }

        LOGGER.info("[onGrow] count={}, amount={}, uuids={}", self.getCount(), amount, LivingChestStackHandler.getUuids(self));

        LivingChestStackFlags.MergeTransfer pending = LivingChestStackFlags.PENDING_TRANSFER.get();
        // 优先级 2：shrink-first 合并 — LivingChestStackFlags.PENDING_TRANSFER 中有待转移的 UUID
        if (pending != null && pending.uuids() != null) {
            if (pending.uuids().size() == amount) {
                LOGGER.info("[onGrow] shrink-first merge: merging {} uuids", pending.uuids().size());
                mergeIntoTargetUpToCount(self, pending.uuids(), self.getCount() + amount, "onGrow-shrink-first");
                LivingChestStackFlags.PENDING_TRANSFER.remove();
                LOGGER.info("[onGrow] merge applied safely");
            } else {
                // 数量不匹配，覆盖为 grow-first 模式（重新开始）
                LOGGER.info("[onGrow] shrink-first: pending size mismatch, overwriting (pending={}, amount={})", pending.uuids().size(), amount);
                LivingChestStackFlags.PENDING_TRANSFER.set(new LivingChestStackFlags.MergeTransfer(self, amount, null));
            }
            return;
        }
        // 优先级 3：grow-first — 存储当前 grow 信息，等待后续 shrink 来消费
        LOGGER.info("[onGrow] grow-first: storing pending transfer");
        LivingChestStackFlags.PENDING_TRANSFER.set(new LivingChestStackFlags.MergeTransfer(self, amount, null));
    }

    /**
     * 拦截 shrink()，与 grow() 配对完成 UUID 转移，并更新源堆的 UUID 列表。
     *
     * <h3>为什么用 HEAD 注入</h3>
     * <p>在 shrink 发生前读取旧状态，计算被移除的 UUID 头部子列表，
     * 完成合并转移后立即更新源堆 UUID。使用 HEAD 而非 RETURN 是因为：</p>
     * <ul>
     *   <li>shrink 后 count 已变，RETURN 时读取的 UUID 列表可能已被其他操作修改</li>
     *   <li>HEAD 时所有状态都是确定的，可以直接计算并更新</li>
     * </ul>
     *
     * <h3>执行分支</h3>
     * <ol>
     *   <li><strong>LivingChestStackFlags.PENDING_TRANSFER 存在且 uuids 为 null</strong>（grow-first）：
     *       将当前移除的 UUID 直接转移到 LivingChestStackFlags.PENDING_TRANSFER 记录的 target 堆，
     *       然后清理 LivingChestStackFlags.PENDING_TRANSFER。</li>
     *   <li><strong>LivingChestStackFlags.PENDING_TRANSFER 不存在或 uuids 非 null</strong>（shrink-first）：
     *       将移除的 UUID 存入 LivingChestStackFlags.PENDING_TRANSFER，等待后续 grow 来消费。</li>
     * </ol>
     *
     * <h3>源堆 UUID 更新</h3>
     * <p>无论哪种分支，shrink 后源堆的 UUID 都会被截断为前 newCount 个。
     * 被移除的 UUID 通过 LivingChestStackFlags.PENDING_TRANSFER 或直接转移的方式交给目标堆，
     * UUID 总数不变，无丢失。</p>
     *
     * <h3>安全边界</h3>
     * <ul>
     *   <li>{@code amount <= 0 || amount > oldCount}：无效缩减量，跳过</li>
     *   <li>{@code myUuids.isEmpty()}：无 UUID 可转移，跳过</li>
     *   <li>{@code myUuids.size() != oldCount}：UUID 数量与堆叠数不匹配，
     *       数据已不一致，跳过并打印警告</li>
     * </ul>
     */
    @Inject(method = "shrink", at = @At("HEAD"))
    private void onShrink(int amount, CallbackInfo ci) {
        ItemStack self = (ItemStack)(Object)this;
        if (!isLivingChest(self)) return;

        // split 内部调用 shrink 时，PRE_SPLIT_UUIDS 已设置，跳过
        if (LivingChestStackFlags.PRE_SPLIT_UUIDS.get() != null) {
            LOGGER.info("[onShrink] inside split, skipping");
            return;
        }

        // 方块放置期间调用 shrink 时，BLOCK_PLACING_UUIDS 已设置，跳过
        if (LivingChestStackFlags.BLOCK_PLACING_UUIDS.get() != null) {
            LOGGER.info("[onShrink] inside block placing, skipping");
            return;
        }

        int oldCount = self.getCount();
        // 无效缩减量：负数或超过当前数量
        if (amount <= 0 || amount > oldCount) return;
        int newCount = oldCount - amount;

        List<UUID> myUuids = LivingChestStackHandler.getUuids(self);
        LOGGER.info("[onShrink] oldCount={}, newCount={}, amount={}, uuids={}", oldCount, newCount, amount, myUuids);
        // 无 UUID 可转移
        if (myUuids.isEmpty()) return;
        // UUID 数量与堆叠数不匹配，数据已不一致，跳过
        if (myUuids.size() != oldCount) {
            LOGGER.warn("[onShrink] UUID count mismatch: uuids.size={}, oldCount={}", myUuids.size(), oldCount);
            return;
        }

        // 计算被移除的 UUID 头部子列表（旧列表的前 amount 个）
        List<UUID> removedUuids = new ArrayList<>(myUuids.subList(0, amount));

        LivingChestStackFlags.MergeTransfer pending = LivingChestStackFlags.PENDING_TRANSFER.get();
        boolean mergeSkipped = false;
        // 分支 1：grow-first — LivingChestStackFlags.PENDING_TRANSFER 存在且 uuids 为 null
        if (pending != null && pending.uuids() == null) {
            if (pending.amount() == amount) {
                LOGGER.info("[onShrink] grow-first merge: transferring {} uuids to target", removedUuids.size());
                int merged = mergeIntoTargetUpToCount(pending.target(), removedUuids, pending.target().getCount(), "onShrink-grow-first");
                if (merged < 0) {
                    mergeSkipped = true;
                    LOGGER.warn("[onShrink] grow-first merge skipped (target already has enough uuids), keeping source uuids intact");
                }
                LivingChestStackFlags.PENDING_TRANSFER.remove();
                LOGGER.info("[onShrink] target merge applied safely");
            } else {
                // 数量不匹配，覆盖为 shrink-first 模式
                LOGGER.info("[onShrink] grow-first: pending amount mismatch, overwriting (pending={}, amount={})", pending.amount(), amount);
                LivingChestStackFlags.PENDING_TRANSFER.set(new LivingChestStackFlags.MergeTransfer(null, amount, removedUuids));
            }
        }
        // 分支 2：shrink-first — LivingChestStackFlags.PENDING_TRANSFER 不存在或已由其他 shrink 填充
        else {
            LOGGER.info("[onShrink] shrink-first: storing pending transfer");
            LivingChestStackFlags.PENDING_TRANSFER.set(new LivingChestStackFlags.MergeTransfer(null, amount, removedUuids));
        }

        // 更新源堆 UUID 为剩余的后 newCount 个
        // 如果合并被跳过（目标已有足够 UUID），保留源堆的完整 UUID 列表，防止 UUID 丢失
        if (newCount > 0 && !mergeSkipped) {
            List<UUID> remaining = new ArrayList<>(myUuids.subList(amount, oldCount));
            LivingChestStackHandler.setUuidsUnsorted(self, remaining);
            LOGGER.info("[onShrink] source remaining: {}", remaining);
        }
    }

    /**
     * 拦截 copyWithCount()，在右键/中键拖拽分发物品时正确分配 UUID。
     *
     * <h3>两种拖拽的区别</h3>
     * <table>
     *   <tr><th>操作</th><th>光标堆叠变化</th><th>原版调用链</th><th>UUID 处理</th></tr>
     *   <tr><td>右键拖拽（生存/创造通用）</td><td>逐个减少</td>
     *       <td>copyWithCount → shrink（原堆减少）</td>
     *       <td>按比例拆分 UUID 给原堆和副本</td></tr>
     *   <tr><td>中键拖拽（创造复制）</td><td>不变</td>
     *       <td>copyWithCount（原堆不动）</td>
     *       <td>同样拆分，后续 tick 通过 grow 补齐原堆 UUID</td></tr>
     * </table>
     *
     * <h3>创造模式 QUICK_CRAFT 特殊处理</h3>
     * <p>创造模式下右键拖动（QUICK_CRAFT）的流程是：</p>
     * <pre>
     *   itemstack3 = getCarried().copy();  // 复制光标堆
     *   for each slot:
     *       slot.setByPlayer(itemstack3.copyWithCount(l));  // 直接分发，不经过 shrink
     *   itemstack3.setCount(k1);           // 手动设置剩余数量
     *   setCarried(itemstack3);            // 替换光标堆
     * </pre>
     * <p>QUICK_CRAFT 不经过 shrink，完全依赖 copyWithCount 完成 UUID 拆分。
     * 因此创造模式下检测到 IS_QUICK_CRAFT 时，跳过清空 UUID 的分支，
     * 直接走正常拆分逻辑：每次 copyWithCount 从 itemstack3 的 UUID 列表中
     * 切出 count 个分配给副本，剩余留给 itemstack3 供后续迭代继续拆分。</p>
     *
     * <h3>创造模式 CLONE 处理</h3>
     * <p>中键复制（ClickType.CLONE）不走 QUICK_CRAFT 路径，IS_QUICK_CRAFT 为 null，
     * 副本 UUID 被清空以防止 UUID 不可控膨胀。复制出的活箱子为"空壳"，
     * 后续 tick 检测到 count>0 且 UUID 为空时会自动创建新 UUID。</p>
     *
     * <h3>安全边界</h3>
     * <ul>
     *   <li>{@code PRE_SPLIT_UUIDS != null}：split 内部调用，跳过</li>
     *   <li>{@code copy.isEmpty()}：副本为空，无需处理</li>
     *   <li>{@code originalUuids.isEmpty()}：无 UUID 可分配，跳过</li>
     *   <li>{@code count >= original.getCount()}：复制整堆（拿起操作），
     *       不是拖拽分发，跳过让 split 路径处理</li>
     * </ul>
     *
     * <h3>UUID 安全保证</h3>
     * <p>remain + split = originalUuids，UUID 总数不变，无丢失。</p>
     */
    @Inject(method = "copyWithCount", at = @At("RETURN"), cancellable = true)
    private void onCopyWithCount(int count, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack original = (ItemStack)(Object)this;
        if (!isLivingChest(original)) return;

        // split 内部调用 copyWithCount 时，跳过
        if (LivingChestStackFlags.PRE_SPLIT_UUIDS.get() != null) {
            LOGGER.debug("[onCopyWithCount] Inside split, skipping");
            return;
        }

        ItemStack copy = cir.getReturnValue();
        if (copy.isEmpty()) {
            LOGGER.debug("[onCopyWithCount] Copy is empty, skipping");
            return;
        }

        List<UUID> originalUuids = LivingChestStackHandler.getUuids(original);
        if (originalUuids.isEmpty()) {
            LOGGER.debug("[onCopyWithCount] No UUIDs to allocate, skipping");
            return;
        }

        // 创造模式检测（客户端和服务端线程均需处理，因为创造模式 INVENTORY 标签页
        // 的点击操作在客户端线程执行 copyWithCount）
        if (isCreativeMode()) {
            // 分支 A：QUICK_CRAFT（右键拖动分发）— 走正常拆分逻辑。
            // QUICK_CRAFT 的流程是 copyWithCount 直接分发（不经过 shrink），
            // 因此必须在 copyWithCount 中完成 UUID 拆分，不能依赖 LivingChestStackFlags.PENDING_TRANSFER。
            if (LivingChestStackFlags.IS_QUICK_CRAFT.get() != null) {
                LOGGER.info("[onCopyWithCount] QUICK_CRAFT in creative mode: falling through to normal split");
                // 继续执行下面的正常拆分逻辑
            } else {
                // 分支 B：CLONE（中键复制）或其他创造模式复制 — 清空副本 UUID 防止泄露
                LivingChestStackHandler.setUuids(copy, List.of());
                LOGGER.info("[onCopyWithCount] Creative mode: clearing UUIDs from copy to prevent UUID leak");
                return;
            }
        }

        // 复制整堆（count >= 原堆数量）不是拖拽分发，跳过让 split 路径处理
        if (count >= original.getCount()) {
            LOGGER.debug("[onCopyWithCount] Copying entire stack (count >= original), skipping");
            return;
        }

        // 按请求数量 count 拆分 UUID 列表：前 count 个分配给副本，剩余留给原堆
        LivingChestStackHandler.SplitResult result = LivingChestStackHandler.splitUuidList(originalUuids, count);
        LivingChestStackHandler.setUuidsUnsorted(original, result.remain());
        LivingChestStackHandler.setUuidsUnsorted(copy, result.split());
        LOGGER.info("[onCopyWithCount] count={}, originalUuids={}, remain={}, split={}",
                count, originalUuids, result.remain(), result.split());
    }

    /**
     * 检测当前是否处于创造模式（服务端 + 客户端双路径）。
     *
     * <h3>为什么需要双路径</h3>
     * <p>创造模式中键复制（ClickType.CLONE）和右键拖动（ClickType.QUICK_CRAFT）
     * 均在客户端线程执行 copyWithCount，仅靠服务端检测无法覆盖。
     * 客户端路径使用 Minecraft.getInstance().player 作为兜底。</p>
     *
     * <h3>与 QUICK_CRAFT 的区分</h3>
     * <p>本方法仅判断是否创造模式，不区分操作类型。操作类型区分由
     * {@link LivingChestStackFlags#IS_QUICK_CRAFT} 在 {@code onCopyWithCount} 中处理：
     * QUICK_CRAFT 走正常拆分逻辑，CLONE 走清空副本 UUID 逻辑。</p>
     *
     * <h3>安全性</h3>
     * <ul>
     *   <li>服务端路径：遍历在线玩家，任一玩家为创造模式即返回 true</li>
     *   <li>客户端路径：仅检查本地玩家，try-catch 保护专用服务端环境</li>
     *   <li>不会误判：生存模式玩家不会触发 UUID 清理</li>
     * </ul>
     */
    private static boolean isCreativeMode() {
        // 服务端检测
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.isCreative()) return true;
            }
        }

        // 客户端检测（处理创造模式背包中键克隆，在客户端线程执行）
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null && mc.player.isCreative()) {
                return true;
            }
        } catch (Exception ignored) {
            // 专用服务端环境下 Minecraft 类不可用，忽略
        }

        return false;
    }
}