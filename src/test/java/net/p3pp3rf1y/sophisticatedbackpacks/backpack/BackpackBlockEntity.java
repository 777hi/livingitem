package net.p3pp3rf1y.sophisticatedbackpacks.backpack;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;

/**
 * 测试替身：精妙背包<b>方块实体</b>（放置态，2026-10-24）。
 *
 * <p>只为 {@code SophisticatedBackpackSource#columnsOfBlockEntity} 的反射链服务：
 * 一个同形方法 {@link #getBackpackWrapper()}（真实实现就是 {@code return backpackWrapper;}，
 * 返回 BE 上的缓存字段）。</p>
 *
 * <p>⚠️ <b>type / state 必须都给真的</b>：{@code BlockEntity} 构造器会
 * {@code validateBlockState()}（{@code type.isValid(state)}），传 null 直接 NPE。
 * 这里借原版箱子的类型 + 状态 —— 列数反射链根本不看它们，只要构造过得去。</p>
 */
public class BackpackBlockEntity extends BlockEntity {

    private final IBackpackWrapper wrapper;

    public BackpackBlockEntity(IBackpackWrapper wrapper) {
        super(BlockEntityType.CHEST, BlockPos.ZERO, (BlockState) Blocks.CHEST.defaultBlockState());
        this.wrapper = wrapper;
    }

    public IBackpackWrapper getBackpackWrapper() {
        return wrapper;
    }
}
