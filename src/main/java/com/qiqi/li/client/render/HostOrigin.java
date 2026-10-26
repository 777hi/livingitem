package com.qiqi.li.client.render;

import javax.annotation.Nullable;

import com.qiqi.li.living.container.HostRef;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 「宿主 ⇒ 渲染参考点」的<b>唯一口径</b>（2026-10-26）。
 *
 * <h3>为什么要有它</h3>
 * <p>这个换算原本有三份各写一遍：本机背包用 {@code getEyePosition(partialTick)}、
 * 其它玩家用 {@code getEyePosition(partialTick)}、物品形态容器用 {@code position()}。
 * 第三份写错了两件事 —— <b>取的是脚底</b>（{@code position()} 是 AABB 最小角）且
 * <b>没插值</b>（每 tick 才更新一次）⇒ 模型从脚踝出来、走动时一顿一顿。</p>
 *
 * <p>⇒ 收成一处后，「新接一条渲染通道」不必再各自决定"取哪个点"。</p>
 *
 * <h3>三种宿主的点</h3>
 * <table>
 *   <caption>取点口径</caption>
 *   <tr><th>宿主</th><th>点</th><th>为什么</th></tr>
 *   <tr><td>玩家</td><td><b>眼睛</b></td><td>与"本机背包里的工具"同一处，物品形态与普通形态不跳位</td></tr>
 *   <tr><td>方块</td><td>方块中心</td><td>方块不会动，不需要插值</td></tr>
 *   <tr><td>实体</td><td>实体中心（半高）</td><td>船 / 矿车 / 掉落物画在身体中部</td></tr>
 * </table>
 *
 * <p>⚠️ <b>必须带 {@code partialTick}</b>：渲染是每帧、位置是每 tick 更新，
 * 不插值就是每 50ms 瞬移一次。这是"移动不平滑"的唯一成因。</p>
 *
 * <p>⚠️ 返回 {@code null} 表示宿主此刻不可见（玩家不在本维度 / 实体已卸载），
 * 调用方应当跳过该条目 —— <b>不要退化成画在原点</b>。</p>
 *
 * <p>⚠️ 信标光束<b>不在这里</b>：它从<b>头顶面</b>出（还要跟随头部旋转），
 * 与"眼睛"是两个点，那个差异是对的（`D-container-15`：框架只答在哪，取点归功能）。</p>
 */
public final class HostOrigin {

    private HostOrigin() {
    }

    /** 宿主引用 → 世界坐标；{@code null} = 此刻不可见。 */
    @Nullable
    public static Vec3 of(ClientLevel level, HostRef host, float partialTick) {
        return switch (host.kind()) {
            case BLOCK -> forBlock(host.pos());
            case PLAYER -> {
                Player player = level.getPlayerByUUID(host.entityId());
                yield player != null ? forPlayer(player, partialTick) : null;
            }
            case ENTITY -> {
                Entity found = null;
                for (Entity entity : level.entitiesForRendering()) {
                    if (entity.getUUID().equals(host.entityId())) {
                        found = entity;
                        break;
                    }
                }
                yield found != null ? forEntity(found, partialTick) : null;
            }
        };
    }

    /** 玩家宿主：<b>眼睛</b>（带插值）。本机 / 远程 / 物品形态三条路径共用。 */
    public static Vec3 forPlayer(Player player, float partialTick) {
        return player.getEyePosition(partialTick);
    }

    /**
     * 宿主如果是玩家，返回那个玩家 —— 作为<b>射线微调 / 朝向跟随</b>的 bearer。
     *
     * <p>⚠️ 与 {@link #of} 分开是为了让「谁是操作者」有唯一答案（2026-10-27）：
     * 微调 / 跟随 / 背后环都问这个，<b>不要再用「ctx 有没有 inventory」判断</b> ——
     * 物品形态容器的 inventory 恒 null，但背在身上时操作者就是宿主玩家。</p>
     *
     * @return 非玩家宿主返回 {@code null}（方块 / 实体宿主没有"身体"可作基准）
     */
    @Nullable
    public static Player playerOf(ClientLevel level, HostRef host) {
        return host.kind() == HostRef.Kind.PLAYER ? level.getPlayerByUUID(host.entityId()) : null;
    }

    /** 方块宿主：方块中心（方块不会动，无插值）。 */
    public static Vec3 forBlock(BlockPos pos) {
        return Vec3.atCenterOf(pos);
    }

    /** 实体宿主：实体中心（半高，带插值）。 */
    public static Vec3 forEntity(Entity entity, float partialTick) {
        return entity.getPosition(partialTick).add(0.0, entity.getBbHeight() * 0.5, 0.0);
    }
}
