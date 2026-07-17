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

import com.qiqi.li.living.LivingChestStackHandler;
import com.qiqi.li.living.LivingChestStackFlags;

/**
 * ItemStack Mixin - 处理活箱子的堆叠判定、拆分/合并 UUID 分配。
 *
 * 三个核心职责：
 * 1. 堆叠判定（isSameItemSameComponents）：
 *    对活箱子忽略 UUID 差异，使不同 UUID 的活箱子也可堆叠。
 * 2. 拆分 UUID 分配（split）：
 *    在服务端拦截 split()，即时将 UUID 列表按比例分配。
 * 3. 合并 UUID 转移（grow / shrink）：
 *    拦截 grow() 和 shrink()，通过 ThreadLocal 自动协调 UUID 转移，
 *    兼容左键合并（先 grow 后 shrink）和右键/漏斗合并（先 shrink 后 grow）。
 *
 * 安全策略：
 *   所有 UUID 修改操作仅在服务端执行（ServerLifecycleHooks.getCurrentServer() != null），
 *   客户端调用不会产生任何副作用。
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 拦截 isSameItemSameComponents 静态方法。
     * 仅在玩家 GUI 操作时允许活箱子跨 UUID 堆叠。
     * 掉落物、漏斗等世界交互不触发，按原版逻辑处理（不同 NBT 无法堆叠）。
     */
    @Inject(method = "isSameItemSameComponents", at = @At("HEAD"), cancellable = true)
    private static void onIsSameItemSameComponents(ItemStack stack, ItemStack other, CallbackInfoReturnable<Boolean> cir) {
        if (stack == null || other == null) return;

        if (!isLivingChest(stack) || !isLivingChest(other)) {
            return;
        }

        if (stack.getItem() != other.getItem()) {
            cir.setReturnValue(false);
            return;
        }

        if (LivingChestStackFlags.ALLOW_STACK.get() != null) {
            cir.setReturnValue(true);
        }
    }

    /**
     * 拦截 split() 方法，在服务端即时分配 UUID。
     *
     * 场景分析：
     * - 真正拆分（amount < 总数量）：原堆保留前 M 个 UUID，新堆获得后 N 个 UUID
     * - 拿起整堆（amount >= 总数量）：原堆变为空，跳过处理，新堆保留全部 UUID
     * - 客户端调用：ServerLifecycleHooks 返回 null，跳过，不产生副作用
     *
     * 注意：split() 内部调用 shrink() 会触发 onShrink，因此必须在 HEAD 捕获原始 UUID，
     * 在 RETURN 用捕获的值做拆分，避免 onShrink 修改后的数据干扰校验。
     */

    /** 在 split() 调用前捕获原始 UUID，同时阻止 onShrink 在 split 期间执行 */
    private static final ThreadLocal<List<UUID>> PRE_SPLIT_UUIDS = new ThreadLocal<>();

    /** split 产出的 newStack 的 UUID，供紧随其后的 grow() 合并使用 */
    private static final ThreadLocal<List<UUID>> SPLIT_UUIDS_FOR_GROW = new ThreadLocal<>();

    @Inject(method = "split", at = @At("HEAD"))
    private void onSplitHead(int amount, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack self = (ItemStack)(Object)this;
        if (!isLivingChest(self)) return;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        SPLIT_UUIDS_FOR_GROW.remove();

        List<UUID> uuids = LivingChestStackHandler.getUuids(self);
        if (uuids.isEmpty()) return;

        LOGGER.info("[onSplitHead] count={}, amount={}, uuids={}", self.getCount(), amount, uuids);
        PRE_SPLIT_UUIDS.set(new ArrayList<>(uuids));
    }

    @Inject(method = "split", at = @At("RETURN"))
    private void onSplitReturn(int amount, CallbackInfoReturnable<ItemStack> cir) {
        List<UUID> uuids = PRE_SPLIT_UUIDS.get();
        PRE_SPLIT_UUIDS.remove();
        if (uuids == null) return;

        ItemStack original = (ItemStack)(Object)this;
        ItemStack newStack = cir.getReturnValue();

        if (newStack.isEmpty()) return;

        int originalCount = original.getCount();
        int newCount = newStack.getCount();

        LOGGER.info("[onSplitReturn] originalCount={}, newCount={}, uuids={}", originalCount, newCount, uuids);

        if (uuids.size() != originalCount + newCount) {
            LOGGER.warn("[onSplitReturn] size mismatch: uuids={}, expected={}", uuids.size(), originalCount + newCount);
            return;
        }

        LivingChestStackHandler.SplitResult result = LivingChestStackHandler.splitUuidList(uuids, newCount);

        LivingChestStackHandler.setUuids(original, result.remain());
        LivingChestStackHandler.setUuids(newStack, result.split());
        LOGGER.info("[onSplitReturn] remain={}, split={}", result.remain(), result.split());

        if (!result.split().isEmpty()) {
            SPLIT_UUIDS_FOR_GROW.set(new ArrayList<>(result.split()));
        }
    }

    private static boolean isLivingChest(ItemStack stack) {
        return com.qiqi.li.living.LivingChestFunction.isLivingChest(stack);
    }

    /**
     * 合并传输记录：用于 grow() 和 shrink() 之间协调 UUID 转移。
     * grow 先执行时：target=目标堆, amount=增长量, uuids=null
     * shrink 先执行时：target=null, amount=缩减量, uuids=被移除的 UUID
     */
    private record MergeTransfer(ItemStack target, int amount, List<UUID> uuids) {}

    private static final ThreadLocal<MergeTransfer> PENDING_TRANSFER = new ThreadLocal<>();

    /**
     * 拦截 grow()，与 shrink() 配对完成 UUID 转移。
     *
     * 左键合并场景：grow 先执行 → 存储待转移记录 → shrink 来消费
     * 右键/漏斗场景：shrink 先执行 → 存储被移除的 UUID → grow 来消费
     */
    @Inject(method = "grow", at = @At("HEAD"))
    private void onGrow(int amount, CallbackInfo ci) {
        ItemStack self = (ItemStack)(Object)this;
        if (!isLivingChest(self)) return;

        if (PRE_SPLIT_UUIDS.get() != null) {
            LOGGER.info("[onGrow] inside split, skipping");
            return;
        }

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        if (amount <= 0) {
            LOGGER.info("[onGrow] amount<=0, skipping (internal shrink call)");
            return;
        }

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
                LOGGER.info("[onGrow] shrink-first: pending size mismatch, overwriting (pending={}, amount={})", pending.uuids().size(), amount);
                PENDING_TRANSFER.set(new MergeTransfer(self, amount, null));
            }
            return;
        }
        LOGGER.info("[onGrow] grow-first: storing pending transfer");
        PENDING_TRANSFER.set(new MergeTransfer(self, amount, null));
    }

    /**
     * 拦截 shrink()，与 grow() 配对完成 UUID 转移。
     * 使用 HEAD 注入：在 shrink 发生前读取旧状态，计算被移除的 UUID，
     * 完成合并转移后立即更新源堆 UUID，避免 RETURN 注入的不稳定性。
     */
    @Inject(method = "shrink", at = @At("HEAD"))
    private void onShrink(int amount, CallbackInfo ci) {
        ItemStack self = (ItemStack)(Object)this;
        if (!isLivingChest(self)) return;

        if (PRE_SPLIT_UUIDS.get() != null) {
            LOGGER.info("[onShrink] inside split, skipping");
            return;
        }

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        int oldCount = self.getCount();
        if (amount <= 0 || amount > oldCount) return;
        int newCount = oldCount - amount;

        List<UUID> myUuids = LivingChestStackHandler.getUuids(self);
        LOGGER.info("[onShrink] oldCount={}, newCount={}, amount={}, uuids={}", oldCount, newCount, amount, myUuids);
        if (myUuids.isEmpty()) return;
        if (myUuids.size() != oldCount) {
            LOGGER.warn("[onShrink] UUID count mismatch: uuids.size={}, oldCount={}", myUuids.size(), oldCount);
            return;
        }

        List<UUID> removedUuids = new ArrayList<>(myUuids.subList(newCount, oldCount));

        MergeTransfer pending = PENDING_TRANSFER.get();
        if (pending != null && pending.uuids() == null) {
            if (pending.amount() == amount) {
                LOGGER.info("[onShrink] grow-first merge: transferring {} uuids to target", removedUuids.size());
                List<UUID> targetUuids = LivingChestStackHandler.getUuids(pending.target());
                List<UUID> merged = new ArrayList<>(targetUuids);
                merged.addAll(removedUuids);
                LivingChestStackHandler.setUuids(pending.target(), merged);
                PENDING_TRANSFER.remove();
                LOGGER.info("[onShrink] target result: {}", merged);
            } else {
                LOGGER.info("[onShrink] grow-first: pending amount mismatch, overwriting (pending={}, amount={})", pending.amount(), amount);
                PENDING_TRANSFER.set(new MergeTransfer(null, amount, removedUuids));
            }
        } else {
            LOGGER.info("[onShrink] shrink-first: storing pending transfer");
            PENDING_TRANSFER.set(new MergeTransfer(null, amount, removedUuids));
        }

        if (newCount > 0) {
            List<UUID> remaining = new ArrayList<>(myUuids.subList(0, newCount));
            LivingChestStackHandler.setUuids(self, remaining);
            LOGGER.info("[onShrink] source remaining: {}", remaining);
        }
    }

    /**
     * 拦截 copyWithCount()，在右键拖拽分发等场景中正确拆分 UUID。
     * 右键拖拽使用 copyWithCount 而非 split，因此 onSplitHead/onSplitReturn 不触发。
     * 当活箱子有多个 UUID 且 copy 数量小于原数量时，将 UUID 拆分为两部分。
     */
    @Inject(method = "copyWithCount", at = @At("RETURN"))
    private void onCopyWithCount(int count, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack original = (ItemStack)(Object)this;
        if (!isLivingChest(original)) return;

        if (PRE_SPLIT_UUIDS.get() != null) {
            return;
        }

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        ItemStack copy = cir.getReturnValue();
        if (copy.isEmpty()) return;

        List<UUID> originalUuids = LivingChestStackHandler.getUuids(original);
        if (originalUuids.isEmpty()) return;

        if (count >= original.getCount()) {
            return;
        }

        LivingChestStackHandler.SplitResult result = LivingChestStackHandler.splitUuidList(originalUuids, count);
        LivingChestStackHandler.setUuids(original, result.remain());
        LivingChestStackHandler.setUuids(copy, result.split());
        LOGGER.info("[onCopyWithCount] count={}, originalUuids={}, remain={}, split={}",
            count, originalUuids, result.remain(), result.split());
    }
}