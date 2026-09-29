package com.qiqi.li.living.domain.tools;

import javax.annotation.Nullable;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import com.qiqi.li.living.transfer.LivingComponents;

/**
 * 活工具/活武器主人名字的<b>显示缓存</b>（{@code LIVING_TOOL_OWNER_NAME} 组件）。
 *
 * <p><b>它不是绑定数据</b> —— 绑定关系只看 {@code LIVING_TOOL_OWNER} 的 UUID
 * （{@code LivingItemManager#getToolOwner}）。名字在这里只回答一个问题：
 * <b>主人离线时，tooltip 显示什么</b>。</p>
 *
 * <p><b>写入时机</b>（都是服务端「能确认名字」的时刻）：</p>
 * <ul>
 *   <li>活化时（{@code LivingTagPacket}，绑定主人顺带记名）；</li>
 *   <li>回放遇到在线主人时（{@code syncOwnerAttributes} 返回主人 → {@link #refresh}）——
 *       玩家改名必然发生在线上，下一次回放即跟上。</li>
 * </ul>
 *
 * <p><b>读取</b>：tooltip 的兜底链为 实时解析（tab 列表 / 本地 UsernameCache）→
 * 本缓存 → 短 UUID（见 {@code OwnerNameResolver#displayName}）。
 * 缓存陈旧的代价只是「显示旧名」，绝不会「显示错人」，故允许陈旧。</p>
 */
public final class LivingToolOwnerName {

    private LivingToolOwnerName() {
    }

    /** 读取显示缓存；组件缺失 = 从未确认过名字。 */
    @Nullable
    public static String of(ItemStack stack) {
        return stack.get(LivingComponents.LIVING_TOOL_OWNER_NAME.value());
    }

    /** 写入显示缓存（{@code null} = 清除）。 */
    public static void set(ItemStack stack, @Nullable String name) {
        if (name == null) {
            stack.remove(LivingComponents.LIVING_TOOL_OWNER_NAME.value());
        } else {
            stack.set(LivingComponents.LIVING_TOOL_OWNER_NAME.value(), name);
        }
    }

    /**
     * 服务端刷新：主人在线时把「当前确认的名字」写进缓存。
     *
     * <p>⚠️ 名字没变时<b>不写</b> —— 本方法在每 tick 的回放路径上，避免无谓的组件脏写。</p>
     */
    public static void refresh(ItemStack stack, Player owner) {
        String current = owner.getName().getString();
        if (!current.equals(of(stack))) {
            set(stack, current);
        }
    }
}
