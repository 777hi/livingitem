package com.qiqi.li.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.qiqi.li.client.gui.LivingButton;
import com.qiqi.li.living.function.LivingFurnaceFunction;
import com.qiqi.li.living.function.LivingHopperFunction;
import com.qiqi.li.living.LivingItemManager;
import com.qiqi.li.living.data.DirectionSlotsData;
import com.qiqi.li.living.data.DirectionTransferData;
import com.qiqi.li.living.data.LivingFurnaceData;
import com.qiqi.li.living.data.LivingHopperData;
import com.qiqi.li.living.core.model.Pos2D;
import com.qiqi.li.living.core.model.SlotMapping;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
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

        if (isFurnaceItem(carried)) {
            event.setCanceled(true);
            processFurnaceInput(upperChar, carried);
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

    /** 检查物品是否为活熔炉 */
    private static boolean isFurnaceItem(ItemStack stack) {
        return stack.is(net.minecraft.world.item.Items.FURNACE) &&
               LivingItemManager.isLivingItem(stack);
    }

    /**
     * 处理活熔炉的单键输入（SLOTS 模式）。
     *
     * 每个 WASD 键立即设置当前激活槽位的方向，然后自动切换到下一个槽位。
     * 例如：input→fuel→output→input...
     *
     * @param key 按键字符（W/A/S/D）
     * @param furnaceStack 光标上的活熔炉 ItemStack
     */
    private static void processFurnaceInput(char key, ItemStack furnaceStack) {
        Pos2D direction = keyToDirection(key);
        if (direction == null) {
            LOGGER.debug("Invalid furnace input key: {}", key);
            return;
        }

        LivingFurnaceData data = LivingItemManager.getFurnaceData(furnaceStack);
        DirectionSlotsData dir = data.direction();
        String[] slotNames = dir.getSlotNames();
        int activeIndex = dir.activeSlotIndex();
        if (slotNames.length == 0) {
            LOGGER.debug("No slot names in furnace direction data");
            return;
        }

        String activeSlotName = slotNames[activeIndex % slotNames.length];

        sendSlotDirectionPacket(activeSlotName, direction);

        LOGGER.debug("Updated furnace slot direction: {} ({}) = {}",
            activeSlotName, key, direction.getSymbol());
    }

    /** 发送槽位方向配置网络包到服务端 */
    private static void sendSlotDirectionPacket(String slotName, Pos2D direction) {
        PacketDistributor.sendToServer(new SlotDirectionPacket(
            LivingFurnaceFunction.ID, slotName, direction.x(), direction.y()));
    }

    /**
     * 输入会话 —— 管理一次 WASD 键入的生命周期。
     *
     * 生命周期：
     * 1. 创建会话（用户按下第一个有效键）
     * 2. 收集键入（最多 2 个键）
     * 3. 完成或超时
     *
     * 超时机制：
     * 2 秒内未完成输入则会话失效，避免残留的半输入状态。
     */
    private static class InputSession {
        private final StringBuilder keys = new StringBuilder();
        private final long startTime;

        /** 会话超时时间（毫秒） */
        private static final long TIMEOUT_MS = 2000;

        /** 最大键入数量（源方向 + 目标方向 = 2） */
        private static final int MAX_KEYS = 2;

        InputSession() {
            this.startTime = System.currentTimeMillis();
        }

        /** 追加一个键到会话 */
        void appendKey(char key) {
            if (keys.length() < MAX_KEYS) {
                keys.append(key);
            }
        }

        /** 检查会话是否已完成（已收集 2 个键） */
        boolean isComplete() {
            return keys.length() >= MAX_KEYS;
        }

        /** 检查会话是否已超时 */
        boolean isTimedOut() {
            return System.currentTimeMillis() - startTime > TIMEOUT_MS;
        }

        /** 获取原始输入字符串 */
        String getRawInput() {
            return keys.toString();
        }
    }
}