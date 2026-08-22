package com.qiqi.li.client.input;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.qiqi.li.client.gui.LivingButton;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.HasDirection;
import com.qiqi.li.living.domain.hopper.LivingHopperFunction;
import com.qiqi.li.living.domain.hopper.DirectionTransferData;
import com.qiqi.li.living.domain.map.LivingMapClientCache;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.model.SlotMapping;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import com.qiqi.li.LivingItem;
import com.qiqi.li.network.HopperDirectionPacket;
import com.qiqi.li.network.SlotDirectionPacket;

/**
 * 活物品客户端输入处理器 —— 处理 WASD 键入配置活漏斗传输方向。
 *
 * 功能概述：
 * 在容器 GUI 中，将活漏斗拿起悬停在活按钮上方时，
 * 通过 WASD 键入来改变活漏斗的传输方向。
 *
 * 输入规则：
 * - W = 上方（UP）
 * - A = 左方（LEFT）
 * - S = 下方（DOWN）
 * - D = 右方（RIGHT）
 * - 需要输入 2 个键：第 1 个 = 源方向，第 2 个 = 目标方向
 * - 示例："WD" = 上传下，"AD" = 左传右
 *
 * 前置条件（全部满足才处理输入）：
 * 1. 当前屏幕是容器 GUI（AbstractContainerScreen）
 * 2. 鼠标悬停在活按钮（LivingButton）上
 * 3. 光标上持有活漏斗
 * 4. 输入的字符是有效的 WASD 键
 *
 * 输入会话机制（InputSession）：
 * - 每次输入创建一个会话，最多接受 2 个键
 * - 超时时间 2 秒，超时后会话自动失效
 * - 2 个键输入完成后立即处理并发送网络包
 *
 * 通信流程：
 *   WASD 键入 → onCharTyped() → InputSession 收集
 * → processInput() → 解析方向 → sendDirectionPacket()
 * → HopperDirectionPacket → 服务端 ServerPacketHandler
 */
@EventBusSubscriber(modid = LivingItem.MOD_ID, value = net.neoforged.api.distmarker.Dist.CLIENT)
public class LivingItemInputHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LivingItemInputHandler.class);

    /** 当前活跃的输入会话 */
    private static InputSession currentSession = null;
    private static DirectionSession directionSession = null;

    /**
     * 断开连接时清空活地图元数据缓存，避免切换存档/服务器后残留旧 mapId 的中心坐标。
     */
    @SubscribeEvent
    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        LivingMapClientCache.clear();
    }

    /**
     * 监听字符输入事件。
     *
     * 检查前置条件后，将有效的 WASD 键加入当前输入会话。
     * 会话完成后（2 个键），解析方向并发送网络包。
     */
    @SubscribeEvent
    public static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (!(mc.screen instanceof AbstractContainerScreen)) return;

        if (!isLivingButtonHovered()) return;

        ItemStack carried = mc.player.containerMenu.getCarried();
        if (carried.isEmpty()) return;

        if (!LivingItemManager.isLivingItem(carried)) return;

        char typedChar = event.getCodePoint();
        char upperChar = Character.toUpperCase(typedChar);

        if (!isValidKey(upperChar)) return;

        LivingItemFunction dirFunc = findDirectionFunction(carried);
        if (dirFunc instanceof HasDirection dir) {
            event.setCanceled(true);
            processDirectionInput(upperChar, dirFunc.getFunctionId(), dir);
            return;
        }

        if (!isHopperItem(carried)) return;

        event.setCanceled(true);

        if (currentSession == null) {
            currentSession = new InputSession();
        }

        currentSession.appendKey(upperChar);

        if (currentSession.isComplete()) {
            processInput(currentSession.getRawInput(), carried);
            currentSession = null;
        }
    }

    /** 检查鼠标是否悬停在活按钮上 */
    private static boolean isLivingButtonHovered() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) return false;

        for (GuiEventListener child : mc.screen.children()) {
            if (child instanceof LivingButton button && button.isHovered()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 监听世界 tick 事件，检查输入会话是否超时。
     *
     * 超时后会话自动失效，用户需要重新开始输入。
     */
    @SubscribeEvent
    public static void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel)) return;
        if (currentSession != null && currentSession.isTimedOut()) {
            LOGGER.debug("Input session timed out: {}", currentSession.getRawInput());
            currentSession = null;
        }
        if (directionSession != null && directionSession.isTimedOut()) {
            LOGGER.debug("Direction session timed out: {}", directionSession.getRawInput());
            directionSession = null;
        }
    }

    /**
     * 处理完整的输入序列。
     *
     * 1. 通过 LivingHopperFunction.readDirectionState() 读取当前方向状态
     * 2. 通过 DirectionModeComponent 解析输入
     * 3. 获取解析后的 SlotMapping
     * 4. 发送网络包到服务端
     *
     * @param rawInput 原始输入字符串（如 "WD"、"AD"）
     * @param hopperStack 光标上的活漏斗 ItemStack
     */
    private static void processInput(String rawInput, ItemStack hopperStack) {
        DirectionTransferData dirData = LivingHopperFunction.readDirectionData(hopperStack);
        if (dirData == null) {
            LOGGER.debug("Failed to read direction data from hopper");
            return;
        }

        Pos2D source = dirData.sourceOffset();
        Pos2D target = dirData.targetOffset();

        if (rawInput.length() >= 1) {
            Pos2D newSource = keyToDirection(rawInput.charAt(0));
            if (newSource != null) source = newSource;
        }
        if (rawInput.length() >= 2) {
            Pos2D newTarget = keyToDirection(rawInput.charAt(1));
            if (newTarget != null) target = newTarget;
        }

        SlotMapping mapping = SlotMapping.fromDirections(source, target);
        if (mapping == null) {
            LOGGER.debug("No mapping resolved from input: {}", rawInput);
            return;
        }

        sendDirectionPacket(mapping);

        LOGGER.debug("Updated hopper direction: {} -> {}", rawInput, mapping.displayName());
    }

    private static Pos2D keyToDirection(char key) {
        return switch (Character.toUpperCase(key)) {
            case 'W' -> Pos2D.UP;
            case 'S' -> Pos2D.DOWN;
            case 'A' -> Pos2D.LEFT;
            case 'D' -> Pos2D.RIGHT;
            default -> null;
        };
    }

    private static boolean isValidKey(char key) {
        return keyToDirection(key) != null;
    }

    /** 发送方向配置网络包到服务端 */
    private static void sendDirectionPacket(SlotMapping mapping) {
        PacketDistributor.sendToServer(new HopperDirectionPacket(mapping.toNBT()));
    }

    /** 检查物品是否为活漏斗 */
    private static boolean isHopperItem(ItemStack stack) {
        return stack.is(net.minecraft.world.item.Items.HOPPER) &&
               LivingItemManager.isLivingItem(stack);
    }

    /** 查找支持方向配置的活物品功能 */
    private static LivingItemFunction findDirectionFunction(ItemStack stack) {
        for (LivingItemFunction func : LivingItemManager.getApplicableFunctions(stack)) {
            if (func instanceof HasDirection) {
                return func;
            }
        }
        return null;
    }

    /**
     * 通用方向输入处理 —— 自动适配 1 键或多键输入。
     *
     * 1 键模式：立即发送（活红石火把）
     * 多键模式：使用 DirectionSession 收集，完成后统一发送（活熔炉）
     */
    private static void processDirectionInput(char key, String functionId, HasDirection dirFunc) {
        Pos2D direction = keyToDirection(key);
        if (direction == null) return;

        if (dirFunc.getDirectionKeyCount() == 1) {
            sendSlotDirectionPacket(functionId, dirFunc.getDirectionSlotNames()[0], direction);
            return;
        }

        if (directionSession == null) {
            directionSession = new DirectionSession(dirFunc.getDirectionKeyCount());
        }
        directionSession.appendKey(key);

        if (directionSession.isComplete()) {
            processDirectionSession(directionSession, functionId, dirFunc);
            directionSession = null;
        }
    }

    /** 完成多键方向输入后，依次发送每个槽位的方向包 */
    private static void processDirectionSession(DirectionSession session, String functionId, HasDirection dirFunc) {
        String[] slotNames = dirFunc.getDirectionSlotNames();
        String keys = session.getRawInput();

        for (int i = 0; i < slotNames.length && i < keys.length(); i++) {
            Pos2D direction = keyToDirection(keys.charAt(i));
            if (direction != null) {
                sendSlotDirectionPacket(functionId, slotNames[i], direction);
            }
        }

        LOGGER.debug("Updated direction: {} total={}", keys, slotNames.length);
    }

    /** 发送槽位方向配置网络包到服务端 */
    private static void sendSlotDirectionPacket(String functionId, String slotName, Pos2D direction) {
        PacketDistributor.sendToServer(new SlotDirectionPacket(
            functionId, slotName, direction.x(), direction.y()));
    }

    /**
     * 通用方向输入会话 —— 管理多键 WASD 输入的生命周期。
     *
     * 适用于任意需要多键配向的活物品（如活熔炉 3 键）。
     * 超时后会话自动失效，避免残留的半输入状态。
     */
    private static class DirectionSession {
        private final StringBuilder keys = new StringBuilder();
        private final long startTime;
        private final int maxKeys;

        private static final long TIMEOUT_MS = 3000;

        DirectionSession(int maxKeys) {
            this.maxKeys = maxKeys;
            this.startTime = System.currentTimeMillis();
        }

        void appendKey(char key) {
            if (keys.length() < maxKeys) {
                keys.append(key);
            }
        }

        boolean isComplete() {
            return keys.length() >= maxKeys;
        }

        boolean isTimedOut() {
            return System.currentTimeMillis() - startTime > TIMEOUT_MS;
        }

        String getRawInput() {
            return keys.toString();
        }
    }

    /**
     * 活漏斗输入会话 —— 管理 2 键 WASD 输入的生命周期。
     *
     * 输入规则：
     * - 第 1 个键：源方向
     * - 第 2 个键：目标方向
     * - 示例："WD" = 上传下
     *
     * 超时机制：2 秒内未完成输入则会话失效。
     */
    private static class InputSession {
        private final StringBuilder keys = new StringBuilder();
        private final long startTime;

        private static final long TIMEOUT_MS = 2000;
        private static final int MAX_KEYS = 2;

        InputSession() {
            this.startTime = System.currentTimeMillis();
        }

        void appendKey(char key) {
            if (keys.length() < MAX_KEYS) {
                keys.append(key);
            }
        }

        boolean isComplete() {
            return keys.length() >= MAX_KEYS;
        }

        boolean isTimedOut() {
            return System.currentTimeMillis() - startTime > TIMEOUT_MS;
        }

        String getRawInput() {
            return keys.toString();
        }
    }
}