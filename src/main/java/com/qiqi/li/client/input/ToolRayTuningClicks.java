package com.qiqi.li.client.input;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import org.lwjgl.glfw.GLFW;

import com.qiqi.li.living.domain.tools.LivingToolMemory;
import com.qiqi.li.living.domain.tools.LivingToolRecorder;
import com.qiqi.li.network.ToolRayTuningPacket;

import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 背包界面<b>点击玩家小人</b>的射线微调交互（客户端侧命中判定，2026-09-30）。
 *
 * <h3>交互口径（用户定）</h3>
 * <ul>
 *   <li><b>左键</b>：按小人渲染矩形的<b>竖向分区</b>判定 ——
 *       上段（脑袋）= 清除起点锚点回默认眼睛；中段 = 躯干中心；下段 = 躯干底部。</li>
 *   <li><b>右键</b>：小人区域任意位置 = 切换朝向跟随（开/关，与具体部位无关）。</li>
 *   <li>配置对象 = <b>选中槽位（主手）</b>的物品，且必须是<b>有记忆</b>的活工具/活武器
 *       （被动模式没有射线，不配置；服务端二次校验，客户端判据一致只为省一个无效包）。</li>
 *   <li><b>光标必须为空</b> —— 配置与光标无关，持物点击容易误触。</li>
 * </ul>
 *
 * <h3>命中判定取向</h3>
 * <p>小人会随鼠标转头且有缩放，<b>不做精确 3D 拾取</b>：按调用方传入的渲染矩形做竖向三分
 * （约 头 0~30% / 躯干中心 30~55% / 躯干底部+腿 55~100%，按模型 2m 身高与躯干 0.75~1.5 推得）。
 * 分区精度对「选哪个锚点」完全够用，且不依赖小人当前朝向。</p>
 */
public final class ToolRayTuningClicks {

    private ToolRayTuningClicks() {
    }

    /**
     * 尝试处理一次对小人区域的点击。
     *
     * @param rx1..ry2 小人渲染矩形（与原版 renderEntityInInventoryFollowsMouse 的裁剪矩形一致）
     * @param carriedEmpty 当前 GUI 光标是否为空
     * @return true = 点击已被消费（调用方应取消原版行为）
     */
    public static boolean handle(double mouseX, double mouseY, int button,
                                 double rx1, double ry1, double rx2, double ry2,
                                 boolean carriedEmpty) {
        if (mouseX < rx1 || mouseX > rx2 || mouseY < ry1 || mouseY > ry2) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !carriedEmpty) {
            return false;
        }

        ItemStack selected = mc.player.getMainHandItem();
        if (!LivingToolRecorder.isLivingToolOrWeapon(selected)
            || LivingToolMemory.of(selected).isEmpty()) {
            return false;
        }

        int action;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            double frac = (mouseY - ry1) / (ry2 - ry1);
            if (frac < 0.30) {
                action = ToolRayTuningPacket.ACTION_CLEAR_ANCHOR;      // 脑袋 = 重置回眼睛
            } else if (frac < 0.55) {
                action = ToolRayTuningPacket.ACTION_ANCHOR_TORSO_CENTER;
            } else {
                action = ToolRayTuningPacket.ACTION_ANCHOR_TORSO_BOTTOM;
            }
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            action = ToolRayTuningPacket.ACTION_TOGGLE_FOLLOW;
        } else {
            return false;
        }

        PacketDistributor.sendToServer(new ToolRayTuningPacket(action));
        return true;
    }
}
