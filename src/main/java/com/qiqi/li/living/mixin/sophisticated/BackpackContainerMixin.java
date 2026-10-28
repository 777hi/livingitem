package com.qiqi.li.living.mixin.sophisticated;

import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.qiqi.li.living.compat.sophisticated.SophisticatedBackpackSource;
import com.qiqi.li.logging.ModLog;

/**
 * 精妙背包「<b>背包本体槽被同步成非背包</b>」时的客户端崩溃（2026-10-28，真机日志驱动）。
 *
 * <h3>上游缺陷</h3>
 * <p>{@code BackpackWrapper#cacheSlotNumbers()} 里有一句硬转型，<b>不判 instanceof</b>：</p>
 * <pre>{@code
 * BackpackItem backpackItem = (BackpackItem) backpackStack.getItem();   // 空栈 ⇒ CCE
 * }</pre>
 * <p>所以只要背包本体那一格被同步成空栈，客户端就会
 * {@code AirItem cannot be cast to BackpackItem} 断线。上游自己知道这个状态：
 * {@code StorageContainerMenuBase#broadcastChanges} 开头就用
 * {@code hasSomethingMessedWithStorage()} 把关（不是背包 ⇒ {@code closeContainer()}），
 * <b>但那条保险只覆盖原版同步路径</b>；任何模组手发的
 * {@code ClientboundContainerSetSlotPacket} 都能绕过它。</p>
 *
 * <h3>为什么补在 {@code onStorageItemStackChanged} 而不是上游的转型点</h3>
 * <ul>
 *   <li>它是「背包本体槽变了」的<b>唯一入口</b>（{@code setItem} 判 {@code storageItemSlotNumber}
 *       之后调它），补在这里等于给所有调用方补判空；</li>
 *   <li>{@code void} 方法 ⇒ {@code CallbackInfo} 就够，不需要为了 {@code setReturnValue}
 *       去引用上游的 {@code IBackpackWrapper} 返回类型；</li>
 *   <li>不用 {@code @Shadow} 上游的私有字段/方法 ⇒ 精妙改内部结构时不会连带失效
 *       （{@code @Shadow} 缺失是<b>应用期硬错误</b>，而本类要求的是可降级）。</li>
 * </ul>
 *
 * <p>⚠️ 取消的语义是「<b>别把这个栈塞给 wrapper</b>」，不是「假装没发生」：
 * wrapper 继续指向上一份有效背包，界面由菜单方自己关
 * （{@code hasSomethingMessedWithStorage}）。</p>
 *
 * <p>⚠️ 判据走 {@link SophisticatedBackpackSource#isDefinitelyNotBackpack}：
 * <b>只在能确定它不是背包时</b>才动手（空栈恒确定；反射没解析出来时不动手，
 * 退回上游行为）—— 宁可漏拦，也不能把正常更新全拦掉。</p>
 */
@Mixin(targets = "net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer")
public abstract class BackpackContainerMixin {

    @Inject(method = "onStorageItemStackChanged", at = @At("HEAD"),
        cancellable = true, require = 0, remap = false)
    private void livingItem$ignoreNonBackpackStack(ItemStack stack, CallbackInfo ci) {
        if (!SophisticatedBackpackSource.isDefinitelyNotBackpack(stack)) return;
        // 留痕（I-C6）：被拦下来的是「背包本体没了」，不是正常刷新
        ModLog.MIXIN.debug("[精妙联动] 背包本体槽同步来一个非背包栈（{}），跳过上游硬转型",
            stack == null || stack.isEmpty() ? "空栈" : stack.getItem());
        ci.cancel();
    }
}
