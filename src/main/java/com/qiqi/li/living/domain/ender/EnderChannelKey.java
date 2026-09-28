package com.qiqi.li.living.domain.ender;

import com.qiqi.li.living.container.ContainerContext;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 活末影箱频道键 —— (归属玩家, 堆叠数) 复合键。
 *
 * <h3>设计语义</h3>
 * <p>频道键是<b>命名空间</b>，不是<b>权限</b>。绑定信息存放在物品的 DataComponent 中并跟随物品流转，
 * 因此「谁持有活末影箱，谁就能接入该物品指向的频道」—— 不存在持有者鉴权。
 * 玩家 A 持有绑定了玩家 B 的活末影箱，同样可以读写 B 的专属频道。</p>
 *
 * <h3>三种形态</h3>
 * <ul>
 *   <li>{@code (null, N)} —— <b>公共频道 N</b>：未绑定玩家的活末影箱，堆叠数即频道号</li>
 *   <li>{@code (uuid, 1)} —— <b>直连模式</b>：绑定玩家且堆叠数为 1，直接读写玩家末影箱背包，不参与路由表</li>
 *   <li>{@code (uuid, N≥2)} —— <b>专属频道 N</b>：绑定玩家且堆叠数 ≥ 2，走路由表但与其他玩家/公共频道隔离</li>
 * </ul>
 *
 * <p>注意：本类只描述「频道归属」这一维度，直连与否由 {@link LivingEnderChestAccessor#isDirectMode()}
 * 结合绑定状态与堆叠数另行判定，因为直连模式下频道键不参与路由。</p>
 *
 * @param owner 绑定的玩家 UUID，{@code null} 表示公共频道
 * @param count 活末影箱物品的堆叠数
 */
public record EnderChannelKey(@Nullable UUID owner, int count) {

    /**
     * 判定是否为直连模式（绑定玩家且堆叠数为 1）。
     *
     * <p>直连模式下活末影箱直接读写玩家末影箱背包，完全绕过 {@link EnderChannelRegistry}，
     * 因此该频道键不参与任何路由表操作。</p>
     */
    public boolean isDirect() {
        return owner != null && count == 1;
    }

    /** 是否为公共频道（无绑定玩家）。 */
    public boolean isPublic() {
        return owner == null;
    }

    /** 是否为玩家专属频道（有绑定玩家且堆叠数 ≥ 2）。 */
    public boolean isPrivateChannel() {
        return owner != null && count >= 2;
    }

    /** 构造公共频道键。 */
    public static EnderChannelKey publicChannel(int count) {
        return new EnderChannelKey(null, count);
    }

    /** 构造玩家专属频道键。 */
    public static EnderChannelKey playerChannel(UUID owner, int count) {
        return new EnderChannelKey(owner, count);
    }

    /**
     * 从活末影箱物品栈解析频道键。
     *
     * <p>绑定玩家 UUID 从 {@code LivingEnderChestData} 的 DataComponent 读取，
     * 客户端同样可读（DataComponent 随物品同步），因此服务端与客户端能各自独立算出一致的键，
     * 无需通过网络协议传递键的计算方式。</p>
     *
     * @param stack 活末影箱物品栈（调用方需确保 {@link LivingEnderChestFunction#isLivingEnderChest} 为真）
     */
    public static EnderChannelKey of(ItemStack stack) {
        UUID bound = LivingEnderChestFunction.getBoundPlayerUuid(stack);
        return new EnderChannelKey(bound, stack.getCount());
    }

    /**
     * 批量解析：将活跃末影箱槽位集合映射为「槽位 → 当前频道键」。
     *
     * <p>供 {@link EnderChannelRegistry#validateRoutes} 校验末影箱的频道键是否发生变化
     * （拆分/合并堆叠会导致模式切换，旧频道下的路由必须清理）。</p>
     *
     * <p><b>null 与空集的语义不同</b>，必须严格区分：</p>
     * <ul>
     *   <li>{@code slots == null} → 返回 {@code null}，表示「跳过目标检查」（调用方未提供该信息）</li>
     *   <li>{@code slots} 为空集 → 返回空 Map，表示「容器内确实没有活末影箱」，目标检查应判定全部失效</li>
     * </ul>
     *
     * @param ctx   容器上下文，用于读取槽位物品
     * @param slots 活跃的活末影箱槽位集合，null 表示跳过检查
     * @return 槽位 → 频道键的映射，slots 为 null 时返回 null
     */
    public static @Nullable Map<Integer, EnderChannelKey> ofSlots(ContainerContext ctx,
                                                                   @Nullable Collection<Integer> slots) {
        if (ctx == null || slots == null) return null;
        if (slots.isEmpty()) return Map.of();

        Map<Integer, EnderChannelKey> result = new HashMap<>(slots.size());
        for (int slot : slots) {
            ItemStack stack = ctx.getItem(slot);
            if (stack.isEmpty()) continue;
            result.put(slot, of(stack));
        }
        return result;
    }
}
