package com.qiqi.li.living.mixin;

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
import com.qiqi.li.living.core.components.InternalStorageComponent;
import com.qiqi.li.living.function.LivingChestFunction;

/**
 * BlockItem Mixin — 活箱子方块放置时的物品自动填充。
 *
 * <h2>功能说明</h2>
 * <p>当玩家将装有物品的活箱子放置为方块时，自动将虚拟箱子中的物品
 * 填充到新放置的实体箱子中，实现"虚拟存储 → 实体容器"的转换。</p>
 *
 * <h2>执行流程</h2>
 * <ol>
 *   <li><strong>HEAD 注入</strong>：检查是否为活箱子，捕获 UUID 列表，
 *       设置 {@link LivingChestStackFlags#BLOCK_PLACING_UUIDS} 标志
 *       阻止 {@code onShrink/onSetCount} 在放置期间重复处理 UUID</li>
 *   <li><strong>原版执行</strong>：BlockItem.place() 正常放置方块，
 *       内部调用 shrink() 消耗物品</li>
 *   <li><strong>RETURN 注入</strong>：如果放置成功，从捕获的 UUID 列表中
 *       读取物品数据，填充到实体箱子中，然后清空活箱子的虚拟存储</li>
 * </ol>
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
     *   <li>按 UUID 顺序遍历虚拟箱子，读取物品并填充到实体箱子中</li>
     *   <li>清空活箱子的虚拟存储数据</li>
     * </ol>
     *
     * <h3>填充策略</h3>
     * <p>按顺序填充到实体箱子的空槽位中。如果实体箱子已满，
     * 剩余物品不会丢失（它们仍然在虚拟箱子中），但 UUID 引用会被清空。
     * 作为安全措施，如果实体箱子无法容纳所有物品，会记录警告日志。</p>
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
        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ChestBlockEntity chest)) {
            LOGGER.info("[BlockItemMixin] placed block is not a chest, skipping fill");
            return;
        }

        MinecraftServer server = level.getServer();
        if (server == null) return;

        InternalStorageComponent.WorldStorage storage = InternalStorageComponent.WorldStorage.get(server);
        Container chestContainer = chest;
        int totalTransferred = 0;

        // 按 UUID 顺序遍历虚拟箱子，将物品填充到实体箱子
        for (UUID uuid : uuids) {
            List<ItemStack> chestItems = storage.getOrCreate(uuid, LivingChestFunction.CHEST_SLOTS);
            for (ItemStack item : chestItems) {
                if (item.isEmpty()) continue;

                // 尝试找到空槽位放置物品
                boolean placed = false;
                for (int slot = 0; slot < chestContainer.getContainerSize(); slot++) {
                    if (chestContainer.getItem(slot).isEmpty()) {
                        chestContainer.setItem(slot, item.copy());
                        totalTransferred += item.getCount();
                        placed = true;
                        break;
                    }
                    // 尝试与同类物品合并
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
        }

        // 清空活箱子的虚拟存储数据
        LivingChestFunction.clearStorage(context.getItemInHand());

        LOGGER.info("[BlockItemMixin] transferred {} items from {} virtual chests to placed chest",
            totalTransferred, uuids.size());
    }
}