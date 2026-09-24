package com.qiqi.li.living.domain.tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ItemEntityContainerContext;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.network.LivingToolHostPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
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
        // ⭐ 活【工具】与活【武器】共用这一个 function（两者行为差异在 tick 里按记忆类型分派）。
        //    判据取 LivingToolRecorder#isLivingToolOrWeapon —— 单一来源，别在这里另写一遍。
        return LivingToolRecorder.isLivingToolOrWeapon(stack);
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
        // 注：掉落物形态这里刻意【不】把"起点所在格"当宿主跳过 ——
        // 起点可能落在半砖 / 台阶这类非满高方块所在格的上半空气部分，
        // 那是"格子级"的误判，由 scanForTarget 的【形状求交】精确排除，
        // 直接跳过整格反而会漏掉那格里真正的目标。

        long now = level.getGameTime();

        // K2：登记「本容器有活工具」，供客户端同步（容器形态的射线可视化 / 未来的悬浮渲染）。
        // 只在【方块容器】形态上报 —— 玩家背包与掉落物客户端本来就知道，无需同步。
        if (hostPos != null) {
            List<LivingToolHostPacket.ToolRay> tools = new ArrayList<>(entries.size());
            for (SlotEntry entry : entries) {
                // ⭐ 只同步「打没打中」这个布尔（L48）—— 目标坐标不传：客户端画的是
                //    记忆射线本身（恒定），传坐标反而会让射线跟着目标跳。
                //    下面 replayDig / replayUse 会再算一次：扫描很便宜（步进 0.1、几十次
                //    getBlockState），换来的是回放 API 保持不变、且两侧判据只有一份实现。
                LivingToolMemory memory = LivingItemManager.getToolMemory(entry.stack());
                tools.add(new LivingToolHostPacket.ToolRay(
                    entry.stack(),
                    LivingToolReplay.resolveDigTarget(memory.dig(), origin, hostBlocks, serverLevel) != null,
                    LivingToolReplay.resolveUseTarget(memory.use(), origin, hostBlocks, serverLevel) != null));
            }
            LivingToolHostSync.report(hostPos, tools);
        }

        for (SlotEntry entry : entries) {
            ItemStack tool = entry.stack();
            LivingToolMemory memory = LivingItemManager.getToolMemory(tool);
            if (memory.isEmpty()) {
                continue;
            }

            // ⭐ 记录本 tick 开始时的"动画状态"（下面用于检测翻转）
            LivingToolProgress progressBefore = LivingItemManager.getToolProgress(tool);
            LivingToolAction actionBefore = LivingItemManager.getToolLastAction(tool);

            // ⭐ S2「射线决定」+【双记忆共存】（方案 C，用户 2026-09-24 定）：
            //
            //   有 attack 记忆时，先看射线上有没有怪：
            //     有怪 → 冷却满则打；冷却中则【等待】（不转去挖方块）
            //     没怪 → 转去按 dig / use 记忆干活
            //
            //   ⇒ 于是活斧子（既是工具又是武器）能「**有怪打怪、没怪挖矿**」，
            //     两条记忆都真正有效 —— 而不像早先那样"有 attack 就再也不挖了"。
            boolean handled = false;
            if (memory.hasAttack()) {
                LivingToolReplay.AttackResult result = LivingToolReplay.replayAttack(
                    tool, memory.attack(), origin, hostBlocks, serverLevel, now);
                if (result.outcome() == LivingToolReplay.Outcome.ATTACKED) {
                    writeBack(context, entry.slotIndex(), tool, result.tool());
                    handled = true;
                } else if (result.outcome() == LivingToolReplay.Outcome.COOLING) {
                    handled = true;   // 专心等冷却 —— 别跑去挖方块，否则观感像"三心二意"
                }
                // NO_TARGET ⇒ 落到下面，转去挖掘 / 交互
            }

            if (!handled) {
                // 挖掘记忆（左键行为）
                ItemStack afterDig = LivingToolReplay.replayDig(tool, memory.dig(), origin, hostBlocks, serverLevel, now);
                if (afterDig != null) {
                    writeBack(context, entry.slotIndex(), tool, afterDig);
                } else {
                    // 交互记忆（右键行为）—— 与挖掘互斥，避免同一 tick 双写
                    ItemStack afterUse = LivingToolReplay.replayUse(tool, memory.use(), origin, hostBlocks, serverLevel);
                    if (afterUse != null) {
                        writeBack(context, entry.slotIndex(), tool, afterUse);
                    }
                }
            }

            // ⭐ 动画状态（挖掘进度 / 瞬时动作）的【翻转】必须额外同步一次 —— 见 syncStateFlip 的说明
            syncStateFlip(context, entry.slotIndex(), tool, progressBefore, actionBefore);
        }
    }

    /**
     * 活工具 tooltip —— 显示「模式 + 记忆内容」。
     *
     * <p>⭐ <b>为什么这个 tooltip 重要</b>：记忆是<b>隐形</b>的（手持才画射线、容器里还要 F3+B），
     * 而 {@code A3} 之后<b>"有没有记忆"直接决定这把工具是哪种模式</b>：</p>
     * <pre>
     *   无记忆 → 辅助模式（在背包里帮玩家挖）
     *   有记忆 → 自主模式（自己按记忆干活）
     * </pre>
     * <p>不说清楚的话，玩家根本不知道手上这把是「帮手」还是「工人」。</p>
     */
    @Override
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        LivingToolMemory memory = LivingItemManager.getToolMemory(stack);

        tooltipAdder.accept(Component.empty());
        tooltipAdder.accept(Component.translatable(memory.isEmpty()
            ? "tooltip.livingitem.tool.mode.assist"
            : "tooltip.livingitem.tool.mode.auto"));

        addRayLine(tooltipAdder, "tooltip.livingitem.tool.dig", memory.dig());
        addRayLine(tooltipAdder, "tooltip.livingitem.tool.use", memory.use());
    }

    /** 一行记忆信息：距离恒有，类型约束（蹲下录的）才附加。 */
    private static void addRayLine(Consumer<Component> tooltipAdder, String key,
                                   @Nullable LivingToolMemory.RayMemory ray) {
        if (ray == null) {
            return;
        }
        Component line = Component.translatable(key, String.format("%.1f", ray.offset().length()));
        if (ray.block() != null) {
            line = line.copy().append(
                Component.translatable("tooltip.livingitem.tool.limited", ray.block().getName()));
        }
        tooltipAdder.accept(line);
    }

    /**
     * 解析射线起点 —— 三种宿主各有一条规则（{@code L3} / {@code L14}）。
     *
     * <table>
     *   <tr><th>宿主</th><th>起点</th><th>依据</th></tr>
     *   <tr><td>方块容器（箱子…）</td><td>容器方块中心</td><td>{@code L14=a}</td></tr>
     *   <tr><td>玩家背包</td><td>玩家眼睛</td><td>{@code L3=a}（与录制端一致）</td></tr>
     *   <tr><td>掉落物</td><td>实体位置</td><td>{@code L14=d}</td></tr>
     * </table>
     *
     * <p>记忆存的是"这条线本身"（完整偏移向量），换个起点重放，语义不变。</p>
     *
     * <p>⚠️ {@code ContainerContext} 目前没有 {@code getOwnerPlayer()}（架构盘点 §2.2 ③），
     * 故此处按既有惯例用 {@code instanceof} 判具体实现。</p>
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
        if (context instanceof ItemEntityContainerContext itemCtx) {
            // L14 = d：掉落物【碰撞箱中心】—— 用 position() 会落在脚下方块里（见 rayOrigin 说明）
            return ItemEntityContainerContext.rayOrigin(itemCtx.getEntity());
        }
        return null;
    }

    /**
     * 把挖掘后的工具写回容器。
     *
     * <p>破坏会扣耐久，甚至可能因 {@code F3} 损坏为空 —— 必须写回并触发同步，
     * 否则客户端看到的还是旧耐久。</p>
     */
    /**
     * 动画状态的<b>翻转</b>（挖掘进度 / 瞬时动作发生变化）时同步一次。
     *
     * <p>⭐ <b>为什么必须单独补这一步</b>：{@link #writeBack} 开头有
     * {@code ItemStack.matches(before, after)} 短路，而
     * <b>{@code PatchedDataComponentMap.equals()} 检测不到自定义组件的变化</b>（项目已知坑）——
     * 于是「只改了 {@code LIVING_TOOL_PROGRESS} / {@code LIVING_TOOL_LAST_ACTION}」时
     * {@code matches} 返回 {@code true}，直接 {@code return}，<b>永远不同步</b>。</p>
     *
     * <p>各形态表现不同，正是取决于有没有兜底通道：</p>
     * <table>
     *   <tr><th>形态</th><th>兜底通道</th><th>不补这步会怎样</th></tr>
     *   <tr><td>方块容器</td><td>{@code K2} 每 tick 全量同步</td><td>看得到（侥幸）</td></tr>
     *   <tr><td>玩家背包</td><td>原版 {@code broadcastChanges()}</td><td>看得到（侥幸）</td></tr>
     *   <tr><td><b>掉落物</b></td><td><b>无</b></td><td>❌ <b>永远看不到"开始挖 / 停挖 / 刚交互"</b> ⇒ 模型没动画</td></tr>
     * </table>
     *
     * <p>两个组件都是<b>翻转</b>语义（挖掘期间进度内容不变；动作 tick 每次不同），
     * 故不会每 tick 重复发。</p>
     */
    private static void syncStateFlip(ContainerContext context, int slot, ItemStack tool,
                                      @Nullable LivingToolProgress progressBefore,
                                      @Nullable LivingToolAction actionBefore) {
        boolean changed = !Objects.equals(progressBefore, LivingItemManager.getToolProgress(tool))
            || !Objects.equals(actionBefore, LivingItemManager.getToolLastAction(tool));
        if (changed) {
            context.syncSlotToClients(slot, tool);
        }
    }

    private static void writeBack(ContainerContext context, int slot, ItemStack before, ItemStack after) {
        if (ItemStack.matches(before, after)) {
            return;
        }
        context.setItem(slot, after);
        context.syncSlotToClients(slot, after);
    }
}
