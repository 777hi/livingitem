package com.qiqi.li.living.container;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.ItemStack;
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

    /**
     * 马鞍槽偏移（2026-10-21 真机修正）—— {@code AbstractHorse} 的 inventory 布局是
     * <b>鞍在最前</b>：索引 0 = 马鞍（装备槽），索引 {@code 1..} 才是箱子格
     * （原版 {@code HorseInventoryMenu} 的 {@code new Slot(horseContainer, 0, …)} 是鞍槽，
     * 箱子格 {@code 1 + l + k * columns}；常量 {@code AbstractHorse.INV_SLOT_SADDLE = 0}）。
     *
     * <p>⚠️ 不排除它的后果（真机实测「驴被识别成 8×2」的完整因果）：16 槽（15 箱 + 1 鞍）
     * 直接喂启发式 ⇒ {@code 16 % 8 = 0} ⇒ 8 列；且槽 0（鞍）被当普通存储格 ⇒
     * 往那格倒水会得到玩家取不出的幽灵物品（GUI 只渲染鞍）。</p>
     */
    private static final int SADDLE_OFFSET = 1;

    private final Entity entity;
    /** 跳过前置装备槽后的“首个存储格”在 handler 里的索引（马 = 1，船/矿车 = 0）。 */
    private final int storageOffset;

    public EntityContainerContext(Entity entity, ServerLevel level) {
        super(handlerOf(entity), null,
            List.of(entity.blockPosition()),    // 位置快照：本 tick 的实时位置
            List.of(),                          // 无方块实体
            (Level) level,
            "entity_" + entity.getUUID());      // 显式稳定键（父类 buildContainerKey 三条都不满足）
        this.entity = entity;
        this.storageOffset = entity instanceof AbstractHorse ? SADDLE_OFFSET : 0;
    }

    /** 被包装的实体（viewer 匹配 / 数据宿主 / 位置反查都用它）。 */
    public Entity getEntity() {
        return entity;
    }

    /**
     * 容器口径的槽位数 —— <b>只数存储格</b>：带箱驴 16 槽去掉鞍槽 = 15（5×3）。
     *
     * <p>船/矿车无装备槽 ⇒ 等于 handler 槽位数（27 / 5）。</p>
     */
    @Override
    public int getSize() {
        return super.getSize() - storageOffset;
    }

    /**
     * 读存储格（逻辑槽位 → handler 物理索引，跳过鞍槽）。
     *
     * <p>委托 {@code super} 而不是直接调 handler：父类的越界判据是
     * {@code 0 <= slot < handler.getSlots()}，加偏移后天然落在存储区。</p>
     */
    @Override
    public ItemStack getItem(int logicalSlot) {
        return super.getItem(logicalSlot + storageOffset);
    }

    /** 写存储格（同 {@link #getItem} 的偏移；clamp/脏标记/同步全走父类既有路径）。 */
    @Override
    public void setItem(int logicalSlot, ItemStack stack) {
        super.setItem(logicalSlot + storageOffset, stack);
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
     * 列数：马/驴/骡直接问原版（{@code getInventoryColumns()} = 5），其余走父类三级。
     *
     * <p>⭐ <b>这是「原版给了 API 就用」</b>，与曾放弃的「查规则表」是两回事：那次放弃是
     * 因为规则表这条路没有消费方（register 不认实体容器）；而 {@code getInventoryColumns()}
     * 是原版对“马格布局”的权威回答，比启发式硬。启发式拿 16 槽会算 8 列
     * （{@code 16 % 8 = 0}）—— 真机实测的错。</p>
     */
    @Override
    public int getWidth() {
        if (entity instanceof AbstractHorse horse) {
            return horse.getInventoryColumns();
        }
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
