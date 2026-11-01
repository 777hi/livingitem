package com.qiqi.li.living.domain.power;
import com.qiqi.li.living.domain.power.LivingWaxedBulbData;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.energy.IEnergyStorage;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.util.PerItemLedger;

/**
 * <b>活</b>涂蜡铜灯物品能量接口 —— 通用电池（双向，§3.6 v17.5）。
 *
 * <p>每盏等量充/放（q ± Δ/count，向下取整），拆分/合并/搬运天然守恒。
 * 充电受每盏容量 C 限制；无出身论——外部充的电与红电发的电混存不分来源。</p>
 *
 * <p>⚠️ <b>仅「已活化」的涂蜡铜灯能拿到本接口</b>（2026-10-09 收紧）。准入判据在
 * {@link #of(ItemStack)}：未活化返回 {@code null}，由 {@code LivingItem} 的 capability
 * provider 直接透传。原因：capability 按<b>原版物品</b>注册 ⇒ 未活化灯也会被问到；
 * 不判则外部 mod 能对「原版方块」充放电，绕过「活化 = 进入能量系统」的门槛，
 * 且与容器路径（{@link BulbBank#isBulb} 要求 {@code isLivingItem}）不一致。</p>
 *
 * <p>另：取消活化会清空电量组件（{@code clearLivingData} 按各功能的
 * {@code getOwnedComponentTypes()} 逐个移除）⇒ <b>不存在「带电的未活化灯」这一状态</b>，
 * 因此收紧不会丢任何数据。</p>
 */
public class BulbItemEnergyStorage implements IEnergyStorage {

    /**
     * 取某栈的能量接口 —— <b>准入判据的唯一归属地</b>。
     *
     * <p>供 {@code LivingItem} 的 {@code Capabilities.EnergyStorage.ITEM} provider 直接透传：
     * 返回 {@code null} 表示「这个栈不提供能量能力」（NeoForge 约定 = 无该能力）。</p>
     *
     * @return 已活化的涂蜡铜灯 → 实例；否则 {@code null}
     */
    public static BulbItemEnergyStorage of(ItemStack stack) {
        return LivingItemManager.isLivingItem(stack) ? new BulbItemEnergyStorage(stack) : null;
    }

    private final ItemStack stack;

    public BulbItemEnergyStorage(ItemStack stack) {
        this.stack = stack;
    }

    private long chargeMilliFe() {
        return LivingWaxedBulbData.of(stack).chargeMilliFe();
    }

    private void setChargeMilliFe(long chargeMilliFe) {
        LivingWaxedBulbData.set(stack,
            LivingWaxedBulbData.of(stack).withChargeMilliFe(chargeMilliFe));
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        if (toExtract <= 0) return 0;
        int count = stack.getCount();
        long availableFe = chargeMilliFe() * count / 1000L;   // floor：尘（<1FE）不参与交付
        if (availableFe <= 0) return 0;
        int takeFe = (int) Math.min(toExtract, availableFe);
        if (takeFe <= 0) return 0;

        // ceil 口径（2026-11-02 对齐流体排出侧实测教训）：交付 = takeFe 整 FE 分毫不差
        // （floor 版随请求缩水：499 请求实得 498），每盏扣 ⌈takeFe×1000 / count⌉
        // ⇒ 实扣 ≥ 交付（损耗向）。残余尘 <1FE 留在账上（不销毁）——
        // getEnergyStored 报 floor ⇒ 外部视图一致（尘隐身），无卡死。
        var wd = PerItemLedger.withdrawCeil(chargeMilliFe(), count, (long) takeFe * 1000L);
        if (!simulate) {
            setChargeMilliFe(wd.newQ());   // newQ = 0 ⇒ 默认值移除 = 空灯
        }
        return takeFe;
    }

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        if (toReceive <= 0) return 0;
        // 算法收归 BulbBank（2026-10-09 power 收口 步骤 2 第 3 步）：单堆时「按剩余容量比例
        // 分配」退化为「全部」，与容器充电同源 ⇒ 不再各自维护一份 perLamp 计算。
        // ⚠️ 口径 = ANY_MOVEMENT（不量化、不做零头回收）；返回值沿用本接口的
        // 「实充 ceil」口径（见下），故取 distributedMilliFe() 而非 deposit 的声明值。
        // ⚠️ BulbBank.of 刻意不过滤 isBulb —— 本 capability 按原版物品注册（含未活化灯）。
        BulbBank bank = BulbBank.of(stack);
        bank.deposit((long) toReceive * 1000L, simulate, BulbBank.FePolicy.ANY_MOVEMENT);
        // 2026-09-09 口径修正（保留）：返回「实充的 ceil」（= 支付方将扣的账），实充
        // perLamp×count 可能比请求少 count−1 mFE（按盏向下取整残余）——报账 ≥ 实充，
        // 差额损耗向（防往返凭空造电，与 ContainerEnergyStorage.receive 同族修复，
        // 见 RoundTripConservationIT）。
        return (int) Math.min(toReceive, (bank.distributedMilliFe() + 999L) / 1000L);
    }

    @Override
    public int getEnergyStored() {
        // int 收窄 clamp（与 ContainerEnergyStorage 同口径）：超大堆叠（count ≥ 2,148 × 1M FE）
        // 真值越 int 公约 → clamp 语义「至少 21.4 亿 FE」，内部 long 账本无损
        return (int) Math.min(chargeMilliFe() * stack.getCount() / 1000L, Integer.MAX_VALUE);
    }

    @Override
    public int getMaxEnergyStored() {
        return (int) Math.min(PowerMath.BULB_UNIT_CAPACITY_MFE * stack.getCount() / 1000L,
            Integer.MAX_VALUE);
    }

    @Override
    public boolean canExtract() {
        return true;
    }

    @Override
    public boolean canReceive() {
        return true;   // 通用电池：双向开放（§3.6 v17.5）
    }
}