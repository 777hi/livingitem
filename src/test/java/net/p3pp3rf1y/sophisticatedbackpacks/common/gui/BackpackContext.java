package net.p3pp3rf1y.sophisticatedbackpacks.common.gui;

import net.minecraft.world.entity.player.Player;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;

/**
 * 测试替身：精妙的<b>菜单上下文</b>（2026-10-23）。
 *
 * <p>它就是「锚点」：知道 GUI 正在显示哪个背包（{@link #getBackpackWrapper}）。
 * 真实的 {@code BackpackContext.Item} 靠 {@code handlerName + backpackSlotIndex} 定位，
 * 替身直接持有一个包装器，效果等同。</p>
 */
public class BackpackContext {

    private final IBackpackWrapper wrapper;

    public BackpackContext(IBackpackWrapper wrapper) {
        this.wrapper = wrapper;
    }

    public IBackpackWrapper getBackpackWrapper(Player player) {
        return wrapper;
    }
}
