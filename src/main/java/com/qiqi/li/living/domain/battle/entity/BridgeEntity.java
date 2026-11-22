package com.qiqi.li.living.domain.battle.entity;

import com.qiqi.li.living.domain.battle.ReconcilePlan;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.util.Mth;

/**
 * 桥实体 —— 活物品在竞技场的化身（§2.1）：无 AI、硬墙、不落盘，容器是唯一真相源。
 *
 * <p>它只是棋子的<b>身体</b>，不是棋子的身份：桥可以随时 discard / 重建（自愈），
 * 棋子身份在容器名册（{@link ReconcilePlan.BridgeRef}）。皮（物品模型）与层数走
 * SynchedEntityData 自动同步客户端。</p>
 */
public class BridgeEntity extends LivingEntity {

    /** 剩余完整层数（= count，1~64）。 */
    public static final EntityDataAccessor<Integer> DATA_COUNT =
        SynchedEntityData.defineId(BridgeEntity.class, EntityDataSerializers.INT);
    /** 皮栈（物品模型渲染用；数量恒 1，层数见 DATA_COUNT）。 */
    public static final EntityDataAccessor<ItemStack> DATA_SKIN =
        SynchedEntityData.defineId(BridgeEntity.class, EntityDataSerializers.ITEM_STACK);

    /** 服务端事件归属键（battleKey）；客户端不读。非持久实体随生灭，无需持久化。 */
    private String battleKey;
    /** 服务端：所属逻辑槽位（事件定位 + 位置校准）。 */
    private int slot = -1;

    public BridgeEntity(EntityType<? extends BridgeEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_COUNT, 1);
        builder.define(DATA_SKIN, ItemStack.EMPTY);
    }

    /** 硬墙身份（船先例）：bounding box 进其它实体移动解算。 */
    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    /** 不可被挤走（船为 true 是要能被玩家推，桥不要）。 */
    @Override
    public boolean isPushable() {
        return false;
    }

    /** 不落盘：区块保存/卸载不写入 ⇒ 桥自愈重建零回滚逻辑。 */
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    /** 高度随层数（史莱姆范式：可覆写点是 getDefaultDimensions，getDimensions 是 final）。宽恒 1。 */
    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        return EntityDimensions.scalable(1.0F, countLayer());
    }

    @Override
    public net.minecraft.world.entity.HumanoidArm getMainArm() {
        return net.minecraft.world.entity.HumanoidArm.RIGHT;
    }

    public int countLayer() {
        return Math.max(1, this.entityData.get(DATA_COUNT));
    }

    /** 部署 / 掉层联动：层数 + 皮一起刷新（hitbox 与渲染同步变）。 */
    public void setPiece(int count, ItemStack skin) {
        this.entityData.set(DATA_COUNT, Mth.clamp(count, 1, 64));
        this.entityData.set(DATA_SKIN, skin.isEmpty() ? ItemStack.EMPTY : skin.copyWithCount(1));
        this.refreshDimensions();
    }

    public ItemStack skin() {
        return this.entityData.get(DATA_SKIN);
    }

    public String battleKey() {
        return battleKey;
    }

    public void setBattleKey(String battleKey) {
        this.battleKey = battleKey;
    }

    public int slot() {
        return slot;
    }

    public void setSlot(int slot) {
        this.slot = slot;
    }

    /** 桥不穿装备（ArmorStand 同款 no-op）。 */
    @Override
    public void setItemSlot(net.minecraft.world.entity.EquipmentSlot slot, ItemStack stack) {
        // no-op
    }

    @Override
    public Iterable<ItemStack> getArmorSlots() {
        return java.util.List.of();
    }

    @Override
    public ItemStack getItemBySlot(net.minecraft.world.entity.EquipmentSlot slot) {
        return ItemStack.EMPTY;
    }
}
