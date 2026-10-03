package com.qiqi.li.living.container;

import java.util.List;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 可 tick 的容器上下文 —— 在 {@link ContainerContext} 的只读能力之上，
 * 补上 <b>tick 生命周期</b>相关的方法。
 *
 * <h3>为什么要单开一个接口</h3>
 * <p>{@link ContainerContext} 是<b>只读能力</b>接口（读槽位 / 同步 / 身份），被功能类广泛依赖。
 * 把 tick 生命周期混进去会破坏它的语义，而且所有实现类都被迫实现这些方法。</p>
 *
 * <h3>为什么掉落物容器也要实现</h3>
 * <p>{@link ItemEntityContainerContext}（掉落物形态）确实没有关联方块实体、也没有玩家背包，
 * 但它的 {@link #getAssociatedBlockEntities()} 返回<b>空列表</b>、{@link #getInventory()}
 * 返回 <b>null</b> —— 这是<b>事实陈述</b>，不是妥协。调用方据此自然跳过红石 / 流体 / 应力 /
 * 相位快照等容器级处理，效果与旧实现里「{@code instanceof} 不匹配就跳过」完全一致。</p>
 *
 * <p>由 {@link ContainerLivingItemHandler#processContext} 作为参数类型使用，
 * 用来消掉原先散落各处的 {@code instanceof SimpleContainerContext} 向下转型。</p>
 */
public interface TickableContainerContext extends ContainerContext {

    /** 绑定本 tick 的 {@link TickContext}（传 {@code null} 表示解绑）。 */
    void setTickContext(TickContext tick);

    /** 把本 tick 累积的脏槽刷新到客户端。 */
    void flushDirtySlots();

    /** 本容器关联的方块实体；玩家背包 / 掉落物场景返回<b>空列表</b>。 */
    List<BlockEntity> getAssociatedBlockEntities();

    /** 本容器关联的玩家背包；方块容器 / 掉落物场景返回 <b>null</b>。 */
    Inventory getInventory();
}
