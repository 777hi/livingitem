package com.qiqi.li.living.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
 * <h2>五个核心职责</h2>
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
 *   <tr><td>5. 右键/中键拖拽</td><td>copyWithCount</td>
 *       <td>右键拖拽分发物品时按比例拆分 UUID，确保每个槽位的副本获得正确的 UUID 子集</td></tr>
 * </table>
 *
 * <h2>安全策略</h2>
 * <ul>
 *   <li><strong>仅服务端执行</strong>：所有 UUID 修改操作通过
 *       {@code ServerLifecycleHooks.getCurrentServer() != null} 检查，客户端调用直接跳过。</li>
 *   <li><strong>UUID 只增不减</strong>：任何自动缩减 UUID 列表的操作都被禁止。
 *       唯一删除 UUID 的路径是用户明确 toggle living off（{@code LivingChestFunction.dropAllItems}）。</li>
 *   <li><strong>split 期间互斥</strong>：split() 内部会调用 shrink()，通过
 *       {@link #PRE_SPLIT_UUIDS} 标志阻止 onShrink 在 split 期间重复处理。</li>
 *   <li><strong>线程安全</strong>：所有 ThreadLocal 变量在每次操作完成后立即清理，
 *       避免跨操作污染。单机模式下通过 {@code server.isSameThread()} 确保仅在服务端线程执行。</li>
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
 * <p>{@link #PENDING_TRANSFER} 的设计允许先执行的一方存储待转移数据，后执行的一方来消费。</p>
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
     * <h3>为什么用 ThreadLocal 标志而不是直接返回 true</h3>
     * <p>如果无条件返回 true，会导致掉落物、漏斗等世界交互也忽略 UUID 差异，
     * 可能造成不同活箱子意外合并。通过 {@link LivingChestStackFlags#ALLOW_STACK}
     * 标志，只在玩家 GUI 操作时启用跨 UUID 堆叠：</p>
     * <ul>
     *   <li>玩家拖拽物品 → 设置 ALLOW_STACK → 本方法返回 true → 允许堆叠</li>
     *   <li>掉落物落地 → ALLOW_STACK 为 null → 走原版逻辑 → 不同 UUID 不堆叠</li>
     *   <li>漏斗传输 → ALLOW_STACK 为 null → 走原版逻辑 → 不同 UUID 不堆叠</li>
     * </ul>
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
        if (LivingChestStackFlags.ALLOW_STACK.get() != null) {
            cir.setReturnValue(true);
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
     * <p>利用 {@link #PENDING_TRANSFER} 作为中转站：</p>
     * <ul>
     *   <li><strong>源堆清零</strong>（count → 0）：将 UUID 列表暂存到 PENDING_TRANSFER，
     *       等待目标堆的 setCount 来消费。UUID 不会被丢弃，只是暂存。</li>
     *   <li><strong>目标堆增长</strong>（count 增加）：检查 PENDING_TRANSFER 中是否有
     *       来自清零源堆的 UUID 等待转移。数量匹配则合并，不匹配则丢弃（表示异源操作）。</li>
     * </ul>
     *
     * <h3>与其他方法的互斥</h3>
     * <ul>
     *   <li>split 内部调用 setCount 时，{@link #PRE_SPLIT_UUIDS} 已设置，直接跳过本方法。</li>
     *   <li>grow/shrink 内部调用 setCount 时，grow-first 的 PENDING_TRANSFER.uuids 为 null，
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
        if (PRE_SPLIT_UUIDS.get() != null) return;

        // 方块放置期间调用 setCount 时，跳过
        if (LivingChestStackFlags.BLOCK_PLACING_UUIDS.get() != null) return;

        // 仅服务端处理
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        // 分支 1：源堆被清零（shift+点击全量转移）
        if (count == 0 && oldCount > 0) {
            List<UUID> uuids = LivingChestStackHandler.getUuids(self);
            if (!uuids.isEmpty() && uuids.size() == oldCount) {
                LOGGER.info("[onSetCount] source depleted: storing {} uuids", uuids.size());
                // 暂存 UUID 到 PENDING_TRANSFER，等待目标堆的 setCount 来消费
                PENDING_TRANSFER.set(new MergeTransfer(null, oldCount, new ArrayList<>(uuids)));
                // 清空自身的 UUID（源堆即将消失，UUID 已安全转移）
                LivingChestStackHandler.setUuids(self, List.of());
            }
        }
        // 分支 2：目标堆增长（shift+点击接收方）
        else if (count > oldCount) {
            MergeTransfer pending = PENDING_TRANSFER.get();
            if (pending != null && pending.uuids() != null && !pending.uuids().isEmpty()) {
                int delta = count - oldCount;
                if (pending.uuids().size() == delta) {
                    LOGGER.info("[onSetCount] target grew by {}: consuming {} uuids", delta, pending.uuids().size());
                    // 将暂存的 UUID 合并到当前堆
                    List<UUID> current = LivingChestStackHandler.getUuids(self);
                    List<UUID> merged = new ArrayList<>(current);
                    merged.addAll(pending.uuids());
                    LivingChestStackHandler.setUuids(self, merged);
                    PENDING_TRANSFER.remove();
                } else {
                    // 数量不匹配，说明不是同一个合并操作的双方，丢弃 PENDING_TRANSFER
                    LOGGER.info("[onSetCount] target grew by {}: pending size mismatch (pending={}), clearing",
                        delta, pending.uuids().size());
                    PENDING_TRANSFER.remove();
                }
            }
            // 分支 3：PENDING_TRANSFER.uuids 为 null（grow-first 模式），不消费，
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
     *   <li><strong>客户端调用</strong>：ServerLifecycleHooks 返回 null，HEAD 直接跳过，
     *       RETURN 的 PRE_SPLIT_UUIDS 为 null 也跳过，不产生副作用。</li>
     * </ul>
     *
     * <h3>UUID 安全保证</h3>
     * <p>拆分总数 = 原堆剩余 + 新堆获得 = 原始 UUID 总数，UUID 无丢失。</p>
     */

    /**
     * split() 前捕获的原始 UUID 快照。
     * 同时作为互斥标志：非 null 时 onShrink 和 onSetCount 会跳过处理。
     * 必须在 RETURN 中 remove() 清理，否则会泄漏到后续操作。
     */
    private static final ThreadLocal<List<UUID>> PRE_SPLIT_UUIDS = new ThreadLocal<>();

    /**
     * split 产出的新堆 UUID，供紧随其后的 grow() 合并使用。
     * 在 onSplitReturn 中设置，在 onGrow 中消费并移除。
     * 与 PENDING_TRANSFER 的区别：这个是专门给 split 后的 grow 用的，
     * 不会被 PENDING_TRANSFER 的通用逻辑干扰。
     */
    private static final ThreadLocal<List<UUID>> SPLIT_UUIDS_FOR_GROW = new ThreadLocal<>();

    @Inject(method = "split", at = @At("HEAD"))
    private void onSplitHead(int amount, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack self = (ItemStack)(Object)this;
        if (!isLivingChest(self)) return;

        // 仅服务端处理
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        // 清理上一次可能残留的 SPLIT_UUIDS_FOR_GROW
        SPLIT_UUIDS_FOR_GROW.remove();

        List<UUID> uuids = LivingChestStackHandler.getUuids(self);
        if (uuids.isEmpty()) return;

        LOGGER.info("[onSplitHead] count={}, amount={}, uuids={}", self.getCount(), amount, uuids);
        // 捕获原始 UUID 快照，同时阻止 onShrink/onSetCount 在 split 期间执行
        PRE_SPLIT_UUIDS.set(new ArrayList<>(uuids));
    }

    @Inject(method = "split", at = @At("RETURN"))
    private void onSplitReturn(int amount, CallbackInfoReturnable<ItemStack> cir) {
        // 取出 HEAD 中捕获的 UUID 快照并立即清理互斥标志
        List<UUID> uuids = PRE_SPLIT_UUIDS.get();
        PRE_SPLIT_UUIDS.remove();
        if (uuids == null) return;  // 客户端调用或非活箱子，跳过

        ItemStack original = (ItemStack)(Object)this;
        ItemStack newStack = cir.getReturnValue();

        // 拿起整堆（amount >= 总数量）时新堆可能为空，跳过
        if (newStack.isEmpty()) return;

        int originalCount = original.getCount();
        int newCount = newStack.getCount();

        LOGGER.info("[onSplitReturn] originalCount={}, newCount={}, uuids={}", originalCount, newCount, uuids);

        // 校验：拆分后的 count 之和应等于原始 UUID 数
        if (uuids.size() != originalCount + newCount) {
            LOGGER.warn("[onSplitReturn] size mismatch: uuids={}, expected={}", uuids.size(), originalCount + newCount);
            return;
        }

        // 按 newCount 的比例拆分 UUID 列表
        LivingChestStackHandler.SplitResult result = LivingChestStackHandler.splitUuidList(uuids, newCount);

        // 前 M 个 UUID 留给原堆，后 N 个 UUID 分配给新堆
        LivingChestStackHandler.setUuidsUnsorted(original, result.remain());
        LivingChestStackHandler.setUuidsUnsorted(newStack, result.split());
        LOGGER.info("[onSplitReturn] remain={}, split={}", result.remain(), result.split());

        // 将新堆的 UUID 存入 SPLIT_UUIDS_FOR_GROW，供紧随其后的 grow 消费
        if (!result.split().isEmpty()) {
            SPLIT_UUIDS_FOR_GROW.set(new ArrayList<>(result.split()));
        }
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
     *
     * <h3>字段含义</h3>
     * <ul>
     *   <li>{@code target}：grow-first 模式下 grow 的目标堆，其他模式为 null</li>
     *   <li>{@code amount}：转移的数量，用于校验 grow/shrink 的 amount 是否匹配</li>
     *   <li>{@code uuids}：待转移的 UUID 列表，null 表示 grow-first 等待 shrink 来填充</li>
     * </ul>
     */
    private record MergeTransfer(ItemStack target, int amount, List<UUID> uuids) {}

    /**
     * 线程局部存储，在 grow/shrink/setCount 之间传递待转移的 UUID 数据。
     * 每次操作完成后必须 remove() 清理，避免泄漏到无关操作。
     * 支持三种数据来源：grow-first、shrink-first、setCount 源堆清零。
     */
    private static final ThreadLocal<MergeTransfer> PENDING_TRANSFER = new ThreadLocal<>();

    /**
     * 拦截 grow()，与 shrink() 配对完成 UUID 转移。
     *
     * <h3>执行优先级（按顺序检查）</h3>
     * <ol>
     *   <li><strong>split 后 grow</strong>：检查 {@link #SPLIT_UUIDS_FOR_GROW}，
     *       如果有数据且数量匹配，直接合并到当前堆。这是 split 内部的 grow，
     *       不应该走 PENDING_TRANSFER 逻辑。</li>
     *   <li><strong>shrink-first 合并</strong>：检查 {@link #PENDING_TRANSFER}，
     *       如果 uuids 非 null（shrink 先执行留下的），直接合并并清理。</li>
     *   <li><strong>grow-first 合并</strong>：如果 PENDING_TRANSFER 为空或
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
        if (PRE_SPLIT_UUIDS.get() != null) {
            LOGGER.info("[onGrow] inside split, skipping");
            return;
        }

        // 仅服务端处理
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        // shrink 内部会调用 grow(-amount)，跳过负数调用
        if (amount <= 0) {
            LOGGER.info("[onGrow] amount<=0, skipping (internal shrink call)");
            return;
        }

        // 优先级 1：检查是否有 split 后待消费的 UUID
        List<UUID> splitUuids = SPLIT_UUIDS_FOR_GROW.get();
        if (splitUuids != null && splitUuids.size() == amount) {
            SPLIT_UUIDS_FOR_GROW.remove();
            LOGGER.info("[onGrow] consuming split UUIDs for grow: {}", splitUuids);
            List<UUID> current = LivingChestStackHandler.getUuids(self);
            List<UUID> merged = new ArrayList<>(current);
            merged.addAll(splitUuids);
            LivingChestStackHandler.setUuids(self, merged);
            return;
        }

        LOGGER.info("[onGrow] count={}, amount={}, uuids={}", self.getCount(), amount, LivingChestStackHandler.getUuids(self));

        MergeTransfer pending = PENDING_TRANSFER.get();
        // 优先级 2：shrink-first 合并 — PENDING_TRANSFER 中有待转移的 UUID
        if (pending != null && pending.uuids() != null) {
            if (pending.uuids().size() == amount) {
                LOGGER.info("[onGrow] shrink-first merge: merging {} uuids", pending.uuids().size());
                List<UUID> current = LivingChestStackHandler.getUuids(self);
                List<UUID> merged = new ArrayList<>(current);
                merged.addAll(pending.uuids());
                LivingChestStackHandler.setUuids(self, merged);
                PENDING_TRANSFER.remove();
                LOGGER.info("[onGrow] result: {}", merged);
            } else {
                // 数量不匹配，覆盖为 grow-first 模式（重新开始）
                LOGGER.info("[onGrow] shrink-first: pending size mismatch, overwriting (pending={}, amount={})", pending.uuids().size(), amount);
                PENDING_TRANSFER.set(new MergeTransfer(self, amount, null));
            }
            return;
        }
        // 优先级 3：grow-first — 存储当前 grow 信息，等待后续 shrink 来消费
        LOGGER.info("[onGrow] grow-first: storing pending transfer");
        PENDING_TRANSFER.set(new MergeTransfer(self, amount, null));
    }

    /**
     * 拦截 shrink()，与 grow() 配对完成 UUID 转移，并更新源堆的 UUID 列表。
     *
     * <h3>为什么用 HEAD 注入</h3>
     * <p>在 shrink 发生前读取旧状态，计算被移除的 UUID 尾部子列表，
     * 完成合并转移后立即更新源堆 UUID。使用 HEAD 而非 RETURN 是因为：</p>
     * <ul>
     *   <li>shrink 后 count 已变，RETURN 时读取的 UUID 列表可能已被其他操作修改</li>
     *   <li>HEAD 时所有状态都是确定的，可以直接计算并更新</li>
     * </ul>
     *
     * <h3>执行分支</h3>
     * <ol>
     *   <li><strong>PENDING_TRANSFER 存在且 uuids 为 null</strong>（grow-first）：
     *       将当前移除的 UUID 直接转移到 PENDING_TRANSFER 记录的 target 堆，
     *       然后清理 PENDING_TRANSFER。</li>
     *   <li><strong>PENDING_TRANSFER 不存在或 uuids 非 null</strong>（shrink-first）：
     *       将移除的 UUID 存入 PENDING_TRANSFER，等待后续 grow 来消费。</li>
     * </ol>
     *
     * <h3>源堆 UUID 更新</h3>
     * <p>无论哪种分支，shrink 后源堆的 UUID 都会被截断为前 newCount 个。
     * 被移除的 UUID 通过 PENDING_TRANSFER 或直接转移的方式交给目标堆，
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
        if (PRE_SPLIT_UUIDS.get() != null) {
            LOGGER.info("[onShrink] inside split, skipping");
            return;
        }

        // 方块放置期间调用 shrink 时，BLOCK_PLACING_UUIDS 已设置，跳过
        // 方块放置的逻辑由 BlockItemMixin 独立处理，不需要 onShrink 介入
        if (LivingChestStackFlags.BLOCK_PLACING_UUIDS.get() != null) {
            LOGGER.info("[onShrink] inside block placing, skipping");
            return;
        }

        // 仅服务端处理
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

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

        // 计算被移除的 UUID 尾部子列表（旧列表的后 amount 个）
        List<UUID> removedUuids = new ArrayList<>(myUuids.subList(newCount, oldCount));

        MergeTransfer pending = PENDING_TRANSFER.get();
        // 分支 1：grow-first — PENDING_TRANSFER 存在且 uuids 为 null
        if (pending != null && pending.uuids() == null) {
            if (pending.amount() == amount) {
                // 数量匹配，直接将 UUID 转移到 PENDING_TRANSFER 记录的 target 堆
                LOGGER.info("[onShrink] grow-first merge: transferring {} uuids to target", removedUuids.size());
                List<UUID> targetUuids = LivingChestStackHandler.getUuids(pending.target());
                List<UUID> merged = new ArrayList<>(targetUuids);
                merged.addAll(removedUuids);
                LivingChestStackHandler.setUuids(pending.target(), merged);
                PENDING_TRANSFER.remove();
                LOGGER.info("[onShrink] target result: {}", merged);
            } else {
                // 数量不匹配，覆盖为 shrink-first 模式
                LOGGER.info("[onShrink] grow-first: pending amount mismatch, overwriting (pending={}, amount={})", pending.amount(), amount);
                PENDING_TRANSFER.set(new MergeTransfer(null, amount, removedUuids));
            }
        }
        // 分支 2：shrink-first — PENDING_TRANSFER 不存在或已由其他 shrink 填充
        else {
            LOGGER.info("[onShrink] shrink-first: storing pending transfer");
            PENDING_TRANSFER.set(new MergeTransfer(null, amount, removedUuids));
        }

        // 更新源堆 UUID 为剩余的前 newCount 个
        if (newCount > 0) {
            List<UUID> remaining = new ArrayList<>(myUuids.subList(0, newCount));
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
     * <h3>为什么统一用拆分逻辑</h3>
     * <p>之前的实现尝试区分右键和中键拖拽，但中键拖拽的触发频率极低，
     * 且不容易在 Mixin 中准确区分。统一拆分的好处：</p>
     * <ul>
     *   <li>右键拖拽：拆分后原堆 shrink 同步减少，UUID 数与 count 一致</li>
     *   <li>中键拖拽：拆分后原堆 UUID 暂时变少，但 tick 检测到 count 不变
     *       → UUID 数少于 count → 创建新 UUID 补齐 → 结果正确</li>
     * </ul>
     *
     * <h3>安全边界</h3>
     * <ul>
     *   <li>{@code PRE_SPLIT_UUIDS != null}：split 内部调用，跳过</li>
     *   <li>{@code server.isSameThread()}：单机模式下确保仅在服务端线程执行</li>
     *   <li>{@code copy.isEmpty()}：副本为空，无需处理</li>
     *   <li>{@code originalUuids.isEmpty()}：无 UUID 可分配，跳过</li>
     *   <li>{@code count >= original.getCount()}：复制整堆（拿起操作），
     *       不是拖拽分发，跳过让 split 路径处理</li>
     * </ul>
     *
     * <h3>UUID 安全保证</h3>
     * <p>remain + split = originalUuids，UUID 总数不变，无丢失。</p>
     */
    @Inject(method = "copyWithCount", at = @At("RETURN"))
    private void onCopyWithCount(int count, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack original = (ItemStack)(Object)this;
        if (!isLivingChest(original)) return;

        // split 内部调用 copyWithCount 时，跳过
        if (PRE_SPLIT_UUIDS.get() != null) {
            return;
        }

        // 仅服务端线程处理（单机模式下 Render 线程也返回非 null server）
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) return;

        ItemStack copy = cir.getReturnValue();
        if (copy.isEmpty()) return;  // 空副本，跳过

        List<UUID> originalUuids = LivingChestStackHandler.getUuids(original);
        if (originalUuids.isEmpty()) return;  // 无 UUID 可分配

        // 复制整堆（count >= 原堆数量）不是拖拽分发，跳过让 split 路径处理
        if (count >= original.getCount()) {
            return;
        }

        // 按请求数量 count 拆分 UUID 列表：前 count 个分配给副本，剩余留给原堆
        LivingChestStackHandler.SplitResult result = LivingChestStackHandler.splitUuidList(originalUuids, count);
        LivingChestStackHandler.setUuidsUnsorted(original, result.remain());
        LivingChestStackHandler.setUuidsUnsorted(copy, result.split());
        LOGGER.info("[onCopyWithCount] count={}, originalUuids={}, remain={}, split={}",
            count, originalUuids, result.remain(), result.split());
    }
}