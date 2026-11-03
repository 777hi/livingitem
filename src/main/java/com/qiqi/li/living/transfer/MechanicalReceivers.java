package com.qiqi.li.living.transfer;

import java.util.ArrayList;
import java.util.List;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.model.Pos2D;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 中立机械接收端注册表与分发器。
 *
 * <p>领域在模组 common setup 阶段注册自己的 {@link Receiver}；注册顺序即匹配优先级，
 * 同一实现类只保留首个实例。共享传输层只负责校验地址并提供源槽物品本体的原始访问器，
 * 具体接收规则与目标写入由注册领域拥有。</p>
 */
public final class MechanicalReceivers {

    private static final List<Receiver> RECEIVERS = new ArrayList<>();

    private MechanicalReceivers() {}

    /** 接收端扩展点；首个 {@link #matches(ItemStack)} 命中的实现独占本次请求。 */
    public interface Receiver {
        boolean matches(ItemStack receiverStack);

        /** @return 实际接收数量；分发器会限制在初始源栈数量内 */
        int receive(Request request);
    }

    /**
     * 接收请求的不可变视图。
     *
     * @param bodySource 精确源槽的原始物品访问器；不会展开活跃存储物品的内部内容
     */
    public record Request(
            ContainerContext context,
            Level level,
            TickContext tick,
            int sourceSlot,
            int receiverSlot,
            Pos2D pushDirection,
            SlotAccessor bodySource) {
    }

    /** 注册接收端；按实现类去重，首次注册者保留其优先级与实例。 */
    public static void register(Receiver receiver) {
        for (Receiver registered : RECEIVERS) {
            if (registered.getClass() == receiver.getClass()) return;
        }
        RECEIVERS.add(receiver);
    }

    /** 是否有接收端声明匹配该物品；只作角色探测，不执行接收。 */
    public static boolean matches(ItemStack receiverStack) {
        if (receiverStack == null || receiverStack.isEmpty()) return false;
        for (Receiver receiver : RECEIVERS) {
            if (receiver.matches(receiverStack)) return true;
        }
        return false;
    }

    /**
     * 分发一次接收请求。首个匹配者拥有请求，即使它返回 0 也不回落到后续实现。
     *
     * @return 实际接收数量，范围为 {@code 0..分发前源栈数量}
     */
    public static int receive(ContainerContext context, Level level, TickContext tick,
                              int sourceSlot, int receiverSlot, Pos2D pushDirection) {
        if (context == null
                || !context.isValidSlot(sourceSlot)
                || !context.isValidSlot(receiverSlot)
                || sourceSlot == receiverSlot
                || !context.supportsExactWrite(sourceSlot)) {
            return 0;
        }

        ItemStack sourceStack = context.getItem(sourceSlot);
        if (sourceStack.isEmpty()) return 0;

        ItemStack receiverStack = context.getItem(receiverSlot);
        int initialCount = sourceStack.getCount();
        SlotAccessor bodySource = new BodySlotAccessor(context, sourceSlot);
        Request request = new Request(context, level, tick, sourceSlot, receiverSlot,
            pushDirection, bodySource);

        for (Receiver receiver : RECEIVERS) {
            if (!receiver.matches(receiverStack)) continue;
            return Math.max(0, Math.min(receiver.receive(request), initialCount));
        }
        return 0;
    }

    /** 测试专用：当前注册表快照（不改内容）。 */
    static List<Receiver> snapshotForTest() {
        return new ArrayList<>(RECEIVERS);
    }

    /** 测试专用：替换注册表内容（隔离 / 恢复，仍按实现类去重）。 */
    static void replaceForTest(List<Receiver> receivers) {
        RECEIVERS.clear();
        for (Receiver receiver : receivers) {
            register(receiver);
        }
    }
}
