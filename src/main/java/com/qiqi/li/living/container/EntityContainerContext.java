package com.qiqi.li.living.container;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;

/**
 * 实体容器上下文 —— 把「装着物品的实体」包装成容器（2026-10-21）。
 *
 * <h3>覆盖对象（白名单见 {@code LivingItem#processEntityContainers}）</h3>
 * <ul>
 *   <li>箱子船 / 箱子矿车 / 漏斗矿车（{@link ContainerEntity}，1.21 接口 = {@code Container} + {@code MenuProvider}）</li>
 *   <li>带箱驴 / 骡（{@link AbstractHorse#hasChest()}）</li>
 * </ul>
 *
 * <h3>为什么继承 {@link SimpleContainerContext} 而不是新写</h3>
 * <p>实体容器与方块容器在 tick 管线里<b>只需要三处不同</b>：身份键（{@code entity_<uuid>}）、
 * 持久宿主（实体附件而非 BE 附件）、方块性（它不是方块）。其余全部相同 ——
 * 列数启发式、槽位读写、脏槽同步、容器级数据 peek/create 都是复用。</p>
 *
 * <h3>三个关键决策</h3>
 * <ol>
 *   <li><b>键只依赖 UUID</b>（不掺位置）：实体每 tick 移动，位置键会漂 ⇒ 熔炉进度被洗掉。
 *      位置每 tick 由 {@code positions} 快照提供，而 ctx <b>每 tick 新建</b>（与掉落物 ctx
 *      同范式）⇒ 快照即当前 tick，不存在陈旧问题。详见设计稿
 *       {@code docs/buffer/container-menu-introspection-topic.md} §3。</li>
 *   <li><b>{@link #getBlockPos()} 返回 {@code null}</b>：语义上「实体容器不是方块容器」
 *       （同 {@link ItemEntityContainerContext} 的意图标识）。这同时防了一个真 bug——
 *       船恰好停在箱子上方时，{@code getBlockEntity(船位置)} 会拿到地形里的箱子 ⇒
 *       活信标误判「有盖容器宿主」而把光束漂在下面的箱子上。返回 null 后信标走
 *       「非方块宿主 ⇒ 需玩家宿主 ⇒ 实体无 owner ⇒ 不开光」的空路径，与
 *       {@code LidBlockEntity} 判据的本意一致（GUI 外的世界存在感）。</li>
 *   <li><b>列数先按实体类型 id 查规则表</b>（与 BE 容器的 {@code findContainerRule} 同语义，
 *       将来玩家可用指令校准模组容器实体），查不到再走父类三级（Tier1 无 BE 跳过 /
 *       Tier2 无方块跳过 / <b>Tier3 启发式</b>）。
 *       四类实体容器的启发式已实测全对：27→9、5→5、15→5。</li>
 * </ol>
 *
 * <p>⚠️ <b>能力面</b>：NeoForge 显式给 {@code CHEST_BOAT}/{@code CHEST_MINECART}/
 * {@code HOPPER_MINECART} 注册 {@code ItemHandler.ENTITY}（{@code CapabilityHooks}），
 * 驴靠 {@code AbstractHorse} 的 fallback ⇒ 四类全有。构造时查不到能力直接抛
 * （符合 I-C7「不得静默继续」——白名单与注册面应当一致，不一致就是真 bug）。</p>
 */
public class EntityContainerContext extends SimpleContainerContext {

    private final Entity entity;

    public EntityContainerContext(Entity entity, ServerLevel level) {
        super(handlerOf(entity), null,
            List.of(entity.blockPosition()),    // 位置快照：本 tick 的实时位置
            List.of(),                          // 无方块实体
            (Level) level,
            "entity_" + entity.getUUID());      // 显式稳定键（父类 buildContainerKey 三条都不满足）
        this.entity = entity;
    }

    /** 被包装的实体（viewer 匹配 / 数据宿主 / 位置反查都用它）。 */
    public Entity getEntity() {
        return entity;
    }

    /**
     * 容器级持久数据的宿主 = <b>实体本身</b>（流体 / 电力相位附件）。
     *
     * <p>BE 与 Entity 同为 {@link IAttachmentHolder} ⇒ 领域侧 writeback 不问类型，
     * 只遍历宿主列表。实体附件持久化（NeoForge 明示 "written, and read from disc"）⇒
     * 船划到无人区块停 tick、区块卸载再回来，附件里的数据还在。</p>
     */
    @Override
    public List<IAttachmentHolder> getDataHosts() {
        return List.of(entity);
    }

    /**
     * 恒为 {@code null} —— 实体容器<b>不是方块容器</b>（见类注释决策 ②：
     * 防船停箱子上被信标误判；语义与 {@link ItemEntityContainerContext} 的
     * 「{@code getBlockPos()} 默认返回 null」一致）。
     *
     * <p>覆写而不是沿用父类（父类返回 positions 首元素）—— 父类那个返回值对
     * 「方块容器」是正确的，对本类不是。</p>
     */
    @Override
    public BlockPos getBlockPos() {
        return null;
    }

    /**
     * 列数：直接走父类三级 —— Tier1（规则）对无 BE 的容器天然跳过、Tier2（原版类型表）
     * 无方块可查也跳过 ⇒ 落到 <b>Tier3 启发式</b>，而四类实体的启发式实测全对
     * （27→9、5→5、15→5，靠 {@code resolveColumns} 的候选宽度顺序）。
     *
     * <p>⚠️ <b>刻意不加「按实体类型 id 查规则表」</b>（2026-10-21 评估后放弃）：
     * ① 没有消费方 —— /livingitem container register 只认方块容器，玩家现在根本无法
     * 校准实体容器；② 会让本类 import transfer 包，违反分层（L1→L2，棘轮 +1）；
     * ③ 「预留半截能力」正是项目反复踩的坑（能力面存在 ≠ 该有）。
     * 等 register 支持实体时再加，届时查询逻辑放 {@code SimpleContainerContext} 的既有钩子上。</p>
     */
    @Override
    public int getWidth() {
        return super.getWidth();
    }

    private static IItemHandler handlerOf(Entity entity) {
        IItemHandler handler = entity.getCapability(Capabilities.ItemHandler.ENTITY);
        if (handler == null) {
            throw new IllegalStateException("实体 " + entity.getType()
                + " 无 ItemHandler.ENTITY 能力 —— 白名单与 NeoForge 注册面不一致（I-C7：不得静默继续）");
        }
        return handler;
    }
}
