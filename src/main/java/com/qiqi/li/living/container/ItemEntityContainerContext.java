package com.qiqi.li.living.container;

import java.util.List;

import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 掉落物容器上下文 —— 把一个 {@link ItemEntity} 包装成只有 <b>1 个槽位</b>的容器。
 *
 * <p>目的：让「掉落物形态」的活物品复用现成的容器 tick 管线
 * （{@code ContainerLivingItemHandler#processContext}），而不是另写一条平行的回放逻辑。
 * 管线里所有 {@code instanceof SimpleContainerContext} 的分支（红石 / 流体 / 应力 / 相位快照 /
 * tooltip 同步）对掉落物都<b>自动跳过</b>，正是我们想要的结果。</p>
 *
 * <h3>意图标识</h3>
 * <ul>
 *   <li>{@link #getBlockPos()} 默认返回 {@code null} —— 掉落物不是方块容器。
 *       回放侧据此走「实体位置作射线起点」的分支（{@code L14=d}）。</li>
 *   <li>{@link #getLevel()} 返回实体所在世界，供跨容器 / 权限判定使用。</li>
 * </ul>
 *
 * <h3>⚠️ 同步为什么要"先置空再写回"</h3>
 * {@code ItemEntity} 的物品走 {@link SynchedEntityData#set}，而它内部按 {@code equals} 判重。
 * 自定义 DataComponent 的变更不一定能被 {@code equals} 检出（与容器侧
 * {@link ContainerSync} 注释同一原因），于是"置空 → 写回"用来<b>保证一定被标脏</b>；
 * 两次赋值发生在同一 tick、客户端只会收到最终值，无副作用。
 *
 * @see SimpleContainerContext
 */
public class ItemEntityContainerContext implements ContainerContext {

    /** 单栈容器只有这一个槽位。 */
    public static final int SINGLE_SLOT = 0;

    private final ItemEntity entity;
    private final ServerLevel level;
    private final String containerKey;

    public ItemEntityContainerContext(ItemEntity entity, ServerLevel level) {
        this.entity = entity;
        this.level = level;
        this.containerKey = "item_entity_" + entity.getUUID();
    }

    /** 被包装的掉落物实体（回放侧据此取射线起点，{@code L14=d}）。 */
    public ItemEntity getEntity() {
        return entity;
    }

    /**
     * 掉落物形态的射线起点（{@code L14=d}，2026-09-19 修订）。
     *
     * <p>取<b>碰撞箱中心</b>，<b>不是</b> {@link ItemEntity#position()}。</p>
     *
     * <p>⚠️ <b>为什么不能直接用 {@code position()}</b>：{@code Entity#position()} 返回的是
     * 碰撞箱的<b>底部</b>（包围盒是 {@code y} 到 {@code y + height}）。掉落物落地后，
     * 这个点正好<b>贴着脚下方块的上表面</b> —— 而回放的逐格扫描从 {@code t=0} 开始，
     * 第一个格子就命中"所站的那块方块"，表现就是<b>射线永远指向底面、只挖脚下的方块</b>。
     * 抬到中心（{@code +0.125} 格）即可离开方块边界。</p>
     *
     * <p>服务端回放与客户端渲染<b>共用此方法</b>，保证"画出来的线"和"实际挖的地方"一致。</p>
     */
    public static Vec3 rayOrigin(ItemEntity entity) {
        return entity.getBoundingBox().getCenter();
    }

    @Override
    public int getSize() {
        return 1;
    }

    @Override
    public ItemStack getItem(int logicalSlot) {
        return logicalSlot == SINGLE_SLOT ? entity.getItem() : ItemStack.EMPTY;
    }

    @Override
    public void setItem(int logicalSlot, ItemStack stack) {
        if (logicalSlot == SINGLE_SLOT) {
            entity.setItem(stack);
        }
    }

    @Override
    public int getMaxStackSize() {
        return 64;
    }

    @Override
    public String getContainerKey() {
        return containerKey;
    }

    @Override
    public String getStableKey(int logicalSlot, String functionId) {
        return containerKey + "#" + logicalSlot + "#" + functionId;
    }

    @Override
    public Level getLevel() {
        return level;
    }

    /**
     * 把物品的新状态推给能看到这个掉落物的客户端。
     *
     * <p>容器形态走 {@code ClientboundContainerSetSlotPacket}，掉落物没有容器菜单，
     * 只能改实体的同步数据 —— 见类注释里"先置空再写回"的原因。</p>
     */
    @Override
    public void syncSlotToClients(int logicalSlot, ItemStack stack) {
        if (logicalSlot != SINGLE_SLOT || entity.isRemoved()) {
            return;
        }

        entity.setItem(ItemStack.EMPTY);
        entity.setItem(stack);

        // 与原版 ServerEntity#sendChanges 同款：只在真正脏了时才打包发送
        List<SynchedEntityData.DataValue<?>> packed = entity.getEntityData().packDirty();
        if (packed != null) {
            level.getChunkSource().broadcastAndSend(entity,
                new ClientboundSetEntityDataPacket(entity.getId(), packed));
        }
    }
}
