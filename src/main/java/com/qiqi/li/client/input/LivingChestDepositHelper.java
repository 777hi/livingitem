package com.qiqi.li.client.input;

import javax.annotation.Nullable;

import com.qiqi.li.client.util.LivingChestTabState;
import com.qiqi.li.living.domain.chest.LivingChestFunction;
import com.qiqi.li.network.LivingChestAccessPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 活箱子「Shift+左键 存放」分支（客户端）。
 *
 * <p>存在理由：这条判据原先在 {@code AbstractContainerScreenMixin} 与 {@code InventoryScreenMixin}
 * 各写一份，连「NBT 序列化 + 发包」那三行也是复制的 ⇒ 两处会各自漂移
 * （2026-11-01 复查时它们就已经一份少了守卫）。判据与动作收在一个地方，两个界面天然一致。</p>
 *
 * <p><b>触发手势</b>：配方书「活箱子」标签处于激活态 + 配方书面板可见时，
 * 对<b>非活箱子</b>的槽位物品按 Shift+左键 ⇒ 把该槽位物品存进手持/选中的活箱子。
 * 槽位里是活箱子本身时不存放（那是「取/放的容器」，不是待收纳的内容物）。</p>
 *
 * <p>⚠️ 调用方在 {@code mouseClicked} 的 HEAD 阶段拦截成功后，必须同时置
 * {@code skipNextRelease = true} —— 原版的放置/交换发生在<b>释放</b>阶段，
 * 不置位则一次按压会在释放时被再执行一次（见
 * {@code AbstractContainerScreenMixin#skipNextRelease} 的字段说明）。</p>
 */
public final class LivingChestDepositHelper {

    private LivingChestDepositHelper() {}

    /**
     * 本次按压是否应当触发存放。
     *
     * @param button     GLFW 按钮码（只有左键存放）
     * @param shiftDown  当前是否按住 Shift
     * @param tabActive  配方书「活箱子」标签是否激活（{@link LivingChestTabState}）
     * @param bookVisible 配方书面板是否可见 —— 不可见时标签根本没画，不能认领这块屏幕区域
     */
    public static boolean shouldDeposit(@Nullable Slot slot, int button, boolean shiftDown,
                                        boolean tabActive, boolean bookVisible) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_1 || !shiftDown || !tabActive || !bookVisible) {
            return false;
        }
        if (slot == null || !slot.hasItem()) {
            return false;
        }
        // 活箱子是容器，不是待收纳的内容物
        return !LivingChestFunction.isLivingChest(slot.getItem());
    }

    /** 发存放包（按<b>整栈</b>数量，服务端侧再按活箱子剩余空间截断）。 */
    public static void sendDeposit(Slot slot) {
        ItemStack stack = slot.getItem();
        CompoundTag itemTag = (CompoundTag) stack.saveOptional(
            Minecraft.getInstance().player.registryAccess());
        PacketDistributor.sendToServer(new LivingChestAccessPacket(
            LivingChestAccessPacket.DEPOSIT_SLOT, itemTag, stack.getCount()));
    }
}
