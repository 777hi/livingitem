package com.qiqi.li.living.core.interaction;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;

/**
 * GUI交互处理器接口 —— 服务端执行交互逻辑。
 *
 * 每种交互动作（如点燃、熄灭、远程引爆等）实现此接口，
 * 通过 {@link InteractionRegistry#registerHandler} 注册。
 *
 * 当服务端收到 {@link com.qiqi.li.network.GuiInteractionPacket} 后，
 * 通过 actionId 查找对应的处理器并调用 handle()。
 *
 * 实现规范：
 *   - 处理器应该是无状态的（所有状态从 slot/player 获取）
 *   - handle() 内部需要自行验证创造/生存模式的差异
 *   - 处理完成后应调用 menu.broadcastChanges() 同步数据
 *
 * 使用示例：
 * <pre>
 * // 注册点燃处理器
 * InteractionRegistry.registerHandler("ignite", new IgniteHandler());
 * </pre>
 */
public interface InteractionHandler {

    /**
     * 处理GUI交互。
     *
     * @param player 发起交互的服务端玩家
     * @param targetSlot 目标物品所在的槽位
     */
    void handle(ServerPlayer player, Slot targetSlot);
}