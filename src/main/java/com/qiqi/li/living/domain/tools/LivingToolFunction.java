package com.qiqi.li.living.domain.tools;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.container.TickContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 活工具功能 —— 把记忆回放挂进现有的容器 tick 管线。
 *
 * <p>只要活工具处在被扫描的容器里（箱子 / 玩家背包 …），
 * {@code ContainerLivingItemHandler} 每 tick 就会把活工具分组后调到这里，
 * 无需新增任何扫描通道（掉落物形态另需 {@code L-f} 的通道）。</p>
 *
 * <p>射线起点（{@code L14}）：
 * <ul>
 *   <li>方块容器 → <b>容器方块中心</b>（{@code L14=a}）</li>
 *   <li>玩家背包 → <b>玩家眼睛</b>（{@code L3=a}，与录制端一致）</li>
 * </ul>
 * 记忆存的是"这条线本身"，宿主迁移后换个起点重放，语义不变。</p>
 */
public class LivingToolFunction implements LivingItemFunction {

    public static final String ID = "living_tool";

    @Override
    public boolean canApply(ItemStack stack) {
        return LivingToolRecorder.isLivingTool(stack);
    }

    @Override
    public String getFunctionId() {
        return ID;
    }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        if (level.isClientSide) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        Vec3 origin = resolveOrigin(context);
        if (origin == null) {
            return;
        }

        // 宿主自身的方块（大箱子两格）——射线检测前要先把起点移出去，否则会挖掉容器自己
        Set<BlockPos> hostBlocks = new HashSet<>(context.getAssociatedBlockPositions());
        BlockPos hostPos = context.getBlockPos();
        if (hostPos != null) {
            hostBlocks.add(hostPos);
        }

        long now = level.getGameTime();

        for (SlotEntry entry : entries) {
            ItemStack tool = entry.stack();
            LivingToolMemory memory = LivingItemManager.getToolMemory(tool);
            if (memory.isEmpty()) {
                continue;
            }

            // 挖掘记忆（左键行为）
            ItemStack afterDig = LivingToolReplay.replayDig(tool, memory.dig(), origin, hostBlocks, serverLevel, now);
            if (afterDig != null) {
                writeBack(context, entry.slotIndex(), tool, afterDig);
                continue;
            }

            // 交互记忆（右键行为）—— 与挖掘互斥，避免同一 tick 双写
            ItemStack afterUse = LivingToolReplay.replayUse(tool, memory.use(), origin, hostBlocks, serverLevel);
            if (afterUse != null) {
                writeBack(context, entry.slotIndex(), tool, afterUse);
            }
        }
    }

    /**
     * 解析射线起点。
     *
     * <p>⚠️ {@code ContainerContext} 目前没有 {@code getOwnerPlayer()}（架构盘点 §2.2 ③），
     * 故此处按既有惯例用 {@code instanceof SimpleContainerContext} 取背包。</p>
     */
    @Nullable
    private static Vec3 resolveOrigin(ContainerContext context) {
        BlockPos pos = context.getBlockPos();
        if (pos != null) {
            // L14 = a：容器方块中心
            return Vec3.atCenterOf(pos);
        }
        if (context instanceof SimpleContainerContext simple && simple.getInventory() != null) {
            // L3 = a：与录制端一致，用玩家眼睛位置
            return simple.getInventory().player.getEyePosition();
        }
        return null;
    }

    /**
     * 把挖掘后的工具写回容器。
     *
     * <p>破坏会扣耐久，甚至可能因 {@code F3} 损坏为空 —— 必须写回并触发同步，
     * 否则客户端看到的还是旧耐久。</p>
     */
    private static void writeBack(ContainerContext context, int slot, ItemStack before, ItemStack after) {
        if (ItemStack.matches(before, after)) {
            return;
        }
        context.setItem(slot, after);
        context.syncSlotToClients(slot, after);
    }
}
