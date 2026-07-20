package com.qiqi.li.living.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.qiqi.li.living.chest.LivingChestStackFlags;
import com.qiqi.li.living.chest.LivingChestStackHandler;
import com.qiqi.li.living.core.components.InternalStorageComponent;
import com.qiqi.li.living.function.LivingChestFunction;

/**
 * BlockItem Mixin — 活箱子方块放置时的物品自动填充。
 *
 * <h2>功能说明</h2>
 * <p>当玩家将装有物品的活箱子放置为方块时，自动将虚拟箱子中被消耗的那个 UUID
 * 对应的物品填充到新放置的实体箱子中，实现"虚拟存储 → 实体容器"的转换。</p>
 *
 * <h2>执行流程</h2>
 * <ol>
 *   <li><strong>HEAD 注入</strong>：检查是否为活箱子，捕获全部 UUID 列表，
 *       设置 {@link LivingChestStackFlags#BLOCK_PLACING_UUIDS} 标志
 *       阻止 {@code onShrink/onSetCount} 在放置期间重复处理 UUID</li>
 *   <li><strong>原版执行</strong>：BlockItem.place() 正常放置方块，
 *       内部调用 shrink() 消耗物品（生存模式）或保持不变（创造模式）</li>
 *   <li><strong>RETURN 注入</strong>：如果放置成功，从被消耗的 UUID（头部第一个）
 *       读取物品数据，填充到实体箱子中，然后调整活箱子的 UUID 列表</li>
 * </ol>
 *
 * <h2>游戏模式差异</h2>
 * <table>
 *   <tr><th>模式</th><th>物品消耗</th><th>UUID 处理</th><th>填充逻辑</th></tr>
 *   <tr><td>生存模式</td><td>消耗 1 个物品</td>
 *       <td>移除头部第 1 个 UUID，剩余 N-1 个</td>
 *       <td>从被移除的 UUID 中读取物品填充</td></tr>
 *   <tr><td>创造模式</td><td>不消耗物品</td>
 *       <td>UUID 列表不变</td>
 *       <td>从头部第一个 UUID 中读取物品填充（每次放置相同物品）</td></tr>
 * </table>
 *
 * <h2>安全策略</h2>
 * <ul>
 *   <li><strong>仅服务端执行</strong>：客户端直接跳过</li>
 *   <li><strong>UUID 只增不减</strong>：不删除磁盘文件，仅清空 NBT 引用</li>
 *   <li><strong>互斥保护</strong>：通过 BLOCK_PLACING_UUIDS 标志阻止
 *       onShrink/onSetCount 在放置期间修改 UUID</li>
 *   <li><strong>ThreadLocal 清理</strong>：RETURN 中确保 remove() 清理</li>
 * </ul>
 */
@Mixin(BlockItem.class)
public class BlockItemMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * HEAD 注入：在 BlockItem.place() 执行前捕获活箱子的 UUID 列表。
     *
     * <h3>为什么用 HEAD 而非 RETURN</h3>
     * <p>place() 内部会调用 shrink() 消耗物品，返回时物品已不可用。
     * 必须在 HEAD 中提前捕获 UUID 数据。</p>
     *
     * <h3>互斥标志</h3>
     * <p>设置 BLOCK_PLACING_UUIDS 标志，阻止 onShrink/onSetCount
     * 在 place() 内部调用时修改 UUID 列表。</p>
     *
     * @param context 方块放置上下文（包含玩家、世界、物品栈等）
     * @param cir 返回值回调（未使用）
     */
    @Inject(method = "place", at = @At("HEAD"))
    private void onPlaceHead(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        // 清理上一次可能残留的 BLOCK_PLACING_UUIDS
        LivingChestStackFlags.BLOCK_PLACING_UUIDS.remove();

        // 仅服务端处理
        Level level = context.getLevel();
        if (level.isClientSide()) return;

        ItemStack stack = context.getItemInHand();
        if (!LivingChestFunction.isLivingChest(stack)) return;

        List<UUID> uuids = LivingChestFunction.getUuids(stack);
        if (uuids.isEmpty()) return;

        LOGGER.info("[BlockItemMixin] captured {} uuids for block placement", uuids.size());
        LivingChestStackFlags.BLOCK_PLACING_UUIDS.set(uuids);
    }

    /**
     * RETURN 注入：在 BlockItem.place() 执行后，若放置成功则将物品填充到实体箱子。
     *
     * <h3>填充逻辑</h3>
     * <ol>
     *   <li>检查放置是否成功（SUCCESS 或 CONSUME）</li>
     *   <li>获取放置位置的方块实体（必须是 ChestBlockEntity）</li>
     *   <li>只从被消耗的那一个 UUID（头部第一个）中读取物品并填充到实体箱子</li>
     *   <li>根据游戏模式调整 UUID 列表</li>
     * </ol>
     *
     * <h3>UUID 调整策略</h3>
     * <table>
     *   <tr><th>场景</th><th>操作</th></tr>
     *   <tr><td>创造模式</td><td>UUID 列表不变（物品未被消耗）</td></tr>
     *   <tr><td>生存模式，最后一个物品</td><td>物品栈已空，无需操作</td></tr>
     *   <tr><td>生存模式，还有剩余</td><td>移除头部 1 个 UUID，保留后 N-1 个</td></tr>
     * </table>
     *
     * <h3>ThreadLocal 清理</h3>
     * <p>无论放置成功与否，都必须清理 BLOCK_PLACING_UUIDS 标志，
     * 避免泄漏到后续操作。</p>
     *
     * @param context 方块放置上下文
     * @param cir 返回值回调（包含放置结果）
     */
    @Inject(method = "place", at = @At("RETURN"))
    private void onPlaceReturn(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        List<UUID> uuids = LivingChestStackFlags.BLOCK_PLACING_UUIDS.get();
        LivingChestStackFlags.BLOCK_PLACING_UUIDS.remove();
        if (uuids == null || uuids.isEmpty()) return;

        // 检查放置是否成功
        InteractionResult result = cir.getReturnValue();
        if (!result.consumesAction()) {
            LOGGER.info("[BlockItemMixin] placement failed, skipping fill");
            return;
        }

        Level level = context.getLevel();
        if (level.isClientSide()) return;

        // 获取放置位置的方块实体
        // getClickedPos() 已内置 relative(clickedFace) 逻辑，无需再手动偏移
        BlockPos pos = context.getClickedPos();
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ChestBlockEntity chest)) {
            LOGGER.info("[BlockItemMixin] placed block is not a chest, skipping fill");
            return;
        }

        MinecraftServer server = level.getServer();
        if (server == null) return;

        // 判断游戏模式：创造模式不消耗物品，UUID 不应减少
        boolean isCreative = context.getPlayer() != null && context.getPlayer().isCreative();

        // 获取当前物品栈（place 执行后，生存模式已 shrink，创造模式未变）
        ItemStack stackInHand = context.getItemInHand();

        // 只取第一个 UUID（匹配 onShrink 的头部移除模式：subList(0, amount)）
        // 放置时消耗 1 个物品，对应消耗 1 个 UUID
        UUID placedUuid = uuids.get(0);

        InternalStorageComponent.WorldStorage storage = InternalStorageComponent.WorldStorage.get(server);
        Container chestContainer = chest;
        int totalTransferred = 0;

        // 只从被放置的那一个 UUID 中读取物品并填充到实体箱子
        List<ItemStack> chestItems = storage.getOrCreate(placedUuid, LivingChestFunction.CHEST_SLOTS);
        for (ItemStack item : chestItems) {
            if (item.isEmpty()) continue;

            boolean placed = false;
            for (int slot = 0; slot < chestContainer.getContainerSize(); slot++) {
                if (chestContainer.getItem(slot).isEmpty()) {
                    chestContainer.setItem(slot, item.copy());
                    totalTransferred += item.getCount();
                    placed = true;
                    break;
                }
                ItemStack slotItem = chestContainer.getItem(slot);
                if (ItemStack.isSameItemSameComponents(slotItem, item)) {
                    int space = slotItem.getMaxStackSize() - slotItem.getCount();
                    int toTransfer = Math.min(item.getCount(), space);
                    if (toTransfer > 0) {
                        slotItem.grow(toTransfer);
                        totalTransferred += toTransfer;
                        placed = true;
                        break;
                    }
                }
            }

            if (!placed) {
                LOGGER.warn("[BlockItemMixin] chest is full, {} items left in virtual storage",
                    item.getCount());
            }
        }

        // 调整 UUID 列表
        if (isCreative) {
            // 创造模式：不消耗物品，UUID 列表保持不变
            LOGGER.info("[BlockItemMixin] creative mode: UUID list unchanged, {} items transferred",
                totalTransferred);
        } else if (stackInHand.isEmpty() || !LivingChestFunction.isLivingChest(stackInHand)) {
            // 生存模式：最后一个物品被消耗，物品栈已不可用，无需清理
            LOGGER.info("[BlockItemMixin] survival mode, last item consumed: {} items transferred",
                totalTransferred);
        } else {
            // 生存模式：还有剩余物品，移除第一个 UUID
            // 剩余 UUID 列表 = 后 N-1 个（匹配 onShrink 的 subList(amount, oldCount) 模式）
            List<UUID> remaining = new ArrayList<>(uuids.subList(1, uuids.size()));
            LivingChestStackHandler.setUuidsUnsorted(stackInHand, remaining);
            LOGGER.info("[BlockItemMixin] survival mode: removed placed UUID, remaining={}, {} items transferred",
                remaining.size(), totalTransferred);
        }
    }
}