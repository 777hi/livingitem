package com.qiqi.li.living.container;

import java.util.ArrayList;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 末影箱容器上下文 —— 玩家末影箱（{@code PlayerEnderChestContainer}）既不是方块实体、
 * 也不是玩家背包的一部分，需要独立的容器上下文。
 *
 * <p>容器键 = {@code player_&lt;uuid&gt;_ender_chest}（玩家作用域 UUID，无 BE ⇒ 无键漂移），
 * 与玩家背包的键（{@code player_&lt;uuid&gt;}）区分。1a-2 删 hashCode 第三档时漏算了本调用者
 * （空 positions + null inventory 触发「缺稳定身份」异常，crash-2026-10-04）⇒ 改走
 * {@link SimpleContainerContext} 的<b>显式稳定键构造器</b>。</p>
 *
 * <p><b>为何独立成顶层类</b>：tick 主链路（{@code ContainerLivingItemHandler.processEnderChest}）
 * 与菜单槽位反查（{@link ContainerContexts#resolve}，F-1 末影箱分支）都要构造它 —— 留在
 * God class 里会让共享内核反向依赖 {@code ContainerLivingItemHandler}。</p>
 *
 * <p>可见性：public —— F-1 末影箱分支后跨包使用（流体快照同步
 * {@code FluidFlowServerSync} 需要 {@link #getOwner()} 直发渲染数据）；
 * 回归测试同包直接构造复现 crash-2026-10-04 的崩溃路径。</p>
 */
public class EnderChestContainerContext extends SimpleContainerContext {

    private final String enderChestKey;
    private final Player player;

    EnderChestContainerContext(IItemHandler handler, Player player, Level level) {
        super(handler, null, new ArrayList<>(), new ArrayList<>(), level,
            "player_" + player.getStringUUID() + "_ender_chest");
        this.player = player;
        this.enderChestKey = "player_" + player.getStringUUID() + "_ender_chest";
    }

    /** 持有玩家（流体快照直发用；inventory 为 null，无法从 getInventory() 反查）。 */
    public Player getOwner() {
        return player;
    }

    @Override
    public String getContainerKey() {
        return enderChestKey;
    }

    /** 所属玩家 —— B.5 落盘需要 owner（inventory 为 null，无法从 {@code getInventory()} 反查）。 */
    @Override
    public Player getOwnerPlayer() {
        return player;
    }
}
