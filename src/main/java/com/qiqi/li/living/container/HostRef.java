package com.qiqi.li.living.container;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;

/**
 * <b>渲染宿主引用</b> —— 任何「要在世界里画东西」的通道共用的宿主身份。
 *
 * <h3>它回答什么</h3>
 * <p>只回答「宿主<b>是谁 / 在哪</b>」，<b>不回答「画在哪个点」</b>（与 {@link ContainerAnchor}
 * 同一条口径：参考物，不是坐标）。取点归渲染方（眼睛 / 方块中心 / 实体中心），
 * 见 {@code client/render/HostOrigin}。</p>
 *
 * <h3>为什么需要 {@code hostSlot}（2026-10-26）</h3>
 * <p><b>一个宿主能装多个容器</b>（玩家身上放两个活背包）。只按宿主做键，两次上报会撞成
 * 同一条记录、后写覆盖先写 ⇒ 客户端只画一个。槽位是它们的唯一区分：
 * <b>一个槽位只能放一件物品</b>。</p>
 *
 * <p>⚠️ <b>不能用容器 UUID 代替</b>：创造模式中键复制的两个背包<b>共享同一个 UUID</b>
 * （组件被一起复制），而它们必然在不同槽位。</p>
 *
 * <p>⚠️ 这是<b>宿主容器</b>的槽位，不是容器内部的槽位 —— 后者是另一个维度。</p>
 */
public record HostRef(Kind kind, @Nullable BlockPos pos, @Nullable UUID entityId, int hostSlot) {

    /** 宿主种类 —— 决定渲染方怎么把它变成世界坐标。 */
    public enum Kind {
        /** 方块容器（箱子 / 桶 / 大箱半格…）。{@code pos} 有效。 */
        BLOCK,
        /** 玩家宿主（玩家背包 / 物品形态容器背在玩家身上）。{@code entityId} 是玩家 UUID。 */
        PLAYER,
        /** 实体宿主（船 / 矿车 / 掉落物 / 物品形态容器在实体容器里）。{@code entityId} 是实体 UUID。 */
        ENTITY
    }

    /** 方块容器宿主（容器自己就是方块，没有"住在宿主第几格"）。 */
    public static HostRef ofBlock(BlockPos pos) {
        return ofBlock(pos, -1);
    }

    /**
     * 方块宿主 + 槽位 —— <b>物品形态容器住在方块容器里</b>时用（背包放在箱子里）。
     *
     * <p>⚠️ 这个重载不是多余的：同一个箱子里放两个活背包，只按方块位置做键
     * 仍然会撞（一个箱子一个 pos）⇒ 后写覆盖先写。槽位才是区分。</p>
     */
    public static HostRef ofBlock(BlockPos pos, int hostSlot) {
        return new HostRef(Kind.BLOCK, pos.immutable(), null, hostSlot);
    }

    public static HostRef ofPlayer(UUID playerId, int hostSlot) {
        return new HostRef(Kind.PLAYER, null, playerId, hostSlot);
    }

    public static HostRef ofEntity(UUID entityId, int hostSlot) {
        return new HostRef(Kind.ENTITY, null, entityId, hostSlot);
    }

    /**
     * 渲染去重用的稳定字符串。
     *
     * <p>⚠️ 不能用 {@code hashCode()}：碰撞会让两个不同宿主共用同一个 key，
     * 渲染器按 key 去重时其中一个会被跳过（静默少画一个）。这里拼的是
     * 全部区分字段，碰撞只可能来自真正的相等。</p>
     */
    public String renderKey() {
        return kind + ":" + (pos != null ? pos.asLong() : entityId) + ":" + hostSlot;
    }
}
