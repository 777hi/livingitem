package com.qiqi.li.living.domain.water;

import java.util.HashMap;
import java.util.Map;

import com.qiqi.li.living.components.LivingComponents;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.ContainerTickHook;
import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.container.TickableContainerContext;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 流体数据的写回钩子（水领域）—— 见 {@link ContainerTickHook}。
 *
 * <p>原逻辑硬编码在 {@code ContainerLivingItemHandler#writebackBlockEntities}（2026-10-08
 * 计划 ⑤ 移入本领域）：把容器级流体数据落盘到 BE 附件；玩家背包 / 末影箱无 BE 可挂
 * ⇒ 落到 Player 附件（按容器键）。</p>
 */
public final class ContainerFluidWriteback implements ContainerTickHook {

    /**
     * tick 开始：确保容器的流体数据已创建（含 BE / Player 附件回填）。
     *
     * <p>原在 {@code TickContext} 构造里调 {@code ContainerLivingItemHandler#getFluidData}，
     * 2026-10-08 计划 ⑤ 移入本领域 —— 时机不变（钩子在紧随构造的 {@code setTickContext} 触发）。</p>
     */
    @Override
    public void onTickStart(ContainerContext ctx, TickContext tick) {
        if (ctx instanceof SimpleContainerContext) {
            ContainerFluidHandler.getOrCreateFluidData(ctx);
        }
    }

    @Override
    public void onWriteback(TickableContainerContext ctx, TickContext tick) {
        ContainerFluidData fluidData = tick.data(ContainerFluidData.KEY);
        if (fluidData == null) return;

        if (!fluidData.isEmpty()) {
            for (BlockEntity be : ctx.getAssociatedBlockEntities()) {
                be.setData(LivingComponents.CONTAINER_FLUID_DATA.value(), fluidData);
            }
        }

        // 玩家背包 / 末影箱（B.5 第三项）：无 BE 可挂 ⇒ 落到 Player attachment（按容器键）。
        // 一个玩家有背包 + 末影箱两个容器 ⇒ 读-改-写一份 map（Codec 解码得到不可变 map，先复制）。
        // ⚠️ getData 可能返回 null（测试替身 / 附件未注册），必须判空。
        Player owner = ownerPlayer(ctx);
        if (owner != null) {
            String ownerKey = ctx.getContainerKey();
            if (ownerKey != null) {
                Map<String, ContainerFluidData> current =
                    owner.getData(LivingComponents.CONTAINER_FLUID_DATA_PLAYER);
                Map<String, ContainerFluidData> persistedMap =
                    current != null ? new HashMap<>(current) : new HashMap<>();
                if (fluidData.isEmpty()) {
                    persistedMap.remove(ownerKey);
                } else {
                    persistedMap.put(ownerKey, fluidData);
                }
                owner.setData(LivingComponents.CONTAINER_FLUID_DATA_PLAYER.value(), persistedMap);
            }
        }

        if (fluidData.isEmpty()) {
            // ⚠️ BE 附件同样要清（对齐玩家路径的 persistedMap.remove）：
            // 非空期间最后一次写回会把源留在附件里 ⇒ 不清则重进存档从附件回填复活
            // （汲走的源跨存档残留，2026-10-04 游戏实测）。EMPTY 序列化为空表，
            // 加载端 !isEmpty() 守卫会跳过 ⇒ 不会复活。
            for (BlockEntity be : ctx.getAssociatedBlockEntities()) {
                be.setData(LivingComponents.CONTAINER_FLUID_DATA.value(), ContainerFluidData.EMPTY);
            }
            ContainerLivingItemHandler.removeContainerData(ctx, ContainerFluidData.KEY);
        }
    }

    /**
     * 取容器的「所属玩家」—— 仅玩家背包 / 末影箱有；方块容器返回 {@code null}。
     *
     * <p>走 {@link ContainerContext#getOwnerPlayer()}（2026-10-08 计划 ⑤ 新增的契约方法）⇒
     * 本领域不必 {@code instanceof} 具体实现类。</p>
     */
    static Player ownerPlayer(ContainerContext ctx) {
        return ctx.getOwnerPlayer();
    }
}
