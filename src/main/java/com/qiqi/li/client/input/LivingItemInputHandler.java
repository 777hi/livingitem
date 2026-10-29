package com.qiqi.li.client.input;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
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
import com.qiqi.li.living.model.DirectionSymbols;
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
import com.qiqi.li.network.ToolMemoryClearPacket;
import com.qiqi.li.living.domain.tools.LivingToolMemory;
import com.qiqi.li.living.domain.tools.LivingToolRecorder;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

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
 * - 不设超时：会话存活由「光标是否仍在活按钮上」决定
 * - 2 个键输入完成后立即处理并发送网络包
 *
 * 输入通道（2026-10-29 起双通道）：
 * - 主通道 {@link #onKeyPressed}：GLFW 键码 → 方向字符。触屏 / 软键盘的屏幕按键只产生按键回调，
 *   字符回调不一定存在，故主通道必须走按键。
 * - 兜底 {@link #onCharTyped}：输入法提交的字符（无对应键码时才有意义）。
 *   同一次按键会先后到达两条通道，按 {@link #CHAR_DEDUPE_MS} 只消费一次。
 *
 * 通信流程：
 *   按键 / 字符 → feedDirectionInput() → InputSession 收集
 * → processInput() → 解析方向 → sendDirectionPacket()
 * → HopperDirectionPacket → 服务端 ServerPacketHandler
 */
@EventBusSubscriber(modid = LivingItem.MOD_ID, value = net.neoforged.api.distmarker.Dist.CLIENT)
public class LivingItemInputHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LivingItemInputHandler.class);

    /** 当前活跃的输入会话 */
    private static InputSession currentSession = null;
    private static DirectionSession directionSession = null;

    /** 方向键码 → 上次按下时刻，用于屏蔽 GLFW 的自动重复（不依赖按键释放事件）。 */
    private static final Map<Integer, Long> lastDirectionPressAt = new HashMap<>();

    /** 按键通道刚消费的方向字符；字符通道据此跳过同一次按键。 */
    private static char pendingCharKey;
    private static long pendingCharAt;

    /** 按键通道消费后，允许字符通道认定「同一次按键」的时间窗（毫秒）。 */
    private static final long CHAR_DEDUPE_MS = 300L;

    /** 同一方向键两次有效按下的最小间隔（毫秒）—— 低于此值视为自动重复。 */
    private static final long KEY_REPEAT_MS = 250L;

    /**
     * 断开连接时清空活地图元数据缓存，避免切换存档/服务器后残留旧 mapId 的中心坐标。
     */
    @SubscribeEvent
    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        LivingMapClientCache.clear();
    }

    /**
     * 左键空气 → 通知服务端清除记忆（{@code L13}）。
     *
     * <p>{@code LeftClickEmpty} <b>只在客户端触发</b>（NeoForge 注释：
     * "The server is not aware of when the client left clicks empty space,
     * you will need to tell the server yourself."），故必须由客户端发包。</p>
     *
     * <p>只在「确实有对应记忆」时才发包 —— 避免无意义的网络流量。</p>
     *
     * <p>⭐ 两条分支（判据不同，别合并）：</p>
     * <ul>
     *   <li>手持<b>活工具</b>且有挖掘记忆 ⇒ 清<b>挖掘</b>记忆</li>
     *   <li>手持<b>活武器</b>且有攻击记忆 ⇒ 清<b>攻击</b>记忆（这一刀挥空了，没打到怪）</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        ItemStack tool = mc.player.getMainHandItem();
        LivingToolMemory memory = LivingToolMemory.of(tool);

        if (LivingToolRecorder.isLivingTool(tool) && memory.hasDig()) {
            PacketDistributor.sendToServer(
                new ToolMemoryClearPacket(ToolMemoryClearPacket.KIND_DIG));
            return;
        }
        // ⭐ 活武器：挥空 = 这一刀没打到怪 ⇒ 清掉攻击记忆（回到"无记忆"⇒ 重新上环）
        if (LivingToolRecorder.isLivingWeapon(tool) && memory.hasAttack()) {
            PacketDistributor.sendToServer(
                new ToolMemoryClearPacket(ToolMemoryClearPacket.KIND_ATTACK));
        }
    }

    /**
     * 字符兜底通道：输入法提交的字符（没有对应键码时才有意义）。
     *
     * <p>与 {@link #onKeyPressed} 共用 {@link #shouldCaptureDirectionInput()} 判据；
     * 同一次物理按键会先走按键通道，再到这里 —— 命中 {@link #pendingCharKey} 时只吞事件、不重复计数。</p>
     */
    @SubscribeEvent
    public static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        if (!shouldCaptureDirectionInput()) return;

        char upperChar = Character.toUpperCase(normalizeChar(event.getCodePoint()));

        if (!isValidKey(upperChar)) return;

        if (pendingCharKey == upperChar && Util.getMillis() - pendingCharAt <= CHAR_DEDUPE_MS) {
            pendingCharKey = 0;
            event.setCanceled(true);
            return;
        }

        ItemStack carried = Minecraft.getInstance().player.containerMenu.getCarried();
        if (feedDirectionInput(upperChar, carried)) {
            event.setCanceled(true);
        }
    }

    // ==================== 按键屏蔽 + HUD（2026-10-10，九宫格方案） ====================

    /**
     * ⭐ 方向输入主通道：「光标持活物品 + 悬停活按钮」时，按键码 → 方向字符 → 会话。
     *
     * <p><b>为什么主通道是按键事件而不是字符事件</b>：{@code CharacterTyped} 只由 GLFW 的字符回调驱动，
     * 触屏 / 软键盘的屏幕按键只产生按键回调 ⇒ 只挂字符事件时，这类设备上方向配置完全无响应。</p>
     *
     * <p>同时承担屏蔽职责：GLFW 的按键事件<b>先于</b>字符事件 —— 等字符事件到来时，
     * Q 的丢弃 / E 的关界面已经发生。故在按键阶段直接 cancel，让这 8 个键在该场景下只服务于方向输入。</p>
     */
    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!shouldCaptureDirectionInput()) return;
        int keyCode = event.getKeyCode();
        char dirKey = directionCharForKeyCode(keyCode);
        if (dirKey == 0) return;
        // ⭐ 对角键只在目标功能声明支持时才屏蔽（supportsDiagonal 默认 false）——
        // 否则会无谓地吃掉 Q（丢弃）/ E（关界面）：不支持对角的活物品上，这两键应保持原版行为
        if (isDiagonalKeyCode(keyCode) && !targetSupportsDiagonal()) return;
        event.setCanceled(true);

        // 按住不放 ⇒ GLFW 持续发按下事件；间隔过短的只屏蔽、不重复计入会话。
        // 不依赖按键释放事件：部分触屏启动器只合成按下。
        long now = Util.getMillis();
        Long last = lastDirectionPressAt.get(keyCode);
        if (last != null && now - last < KEY_REPEAT_MS) return;
        lastDirectionPressAt.put(keyCode, now);

        ItemStack carried = Minecraft.getInstance().player.containerMenu.getCarried();
        if (!feedDirectionInput(dirKey, carried)) return;

        // 随后到达的同键字符事件由字符通道跳过（PC 上两条回调都触发）
        pendingCharKey = dirKey;
        pendingCharAt = now;
    }

    /**
     * 把一次方向输入喂进当前会话（两条通道共用）。
     *
     * @return true = 已消费（调用方应 cancel 事件）；false = 该输入不适用于当前物品
     */
    private static boolean feedDirectionInput(char key, ItemStack carried) {
        LivingItemFunction dirFunc = findDirectionFunction(carried);
        if (dirFunc instanceof HasDirection dir) {
            processDirectionInput(key, dirFunc.getFunctionId(), dir);
            return true;
        }

        if (!isHopperItem(carried)) return false;

        // 漏斗不支持对角槽位：对角键直接忽略（不进入会话、不 cancel）
        Pos2D hopperDir = keyToDirection(key);
        if (hopperDir == null || hopperDir.isDiagonal()) return false;

        if (currentSession == null) {
            currentSession = new InputSession();
            beginHud(true);
        }

        currentSession.appendKey(key);
        refreshHudTyped();

        if (currentSession.isComplete()) {
            processInput(currentSession.getRawInput(), carried);
            currentSession = null;
            activeHud = null;
        }
        return true;
    }

    /** 当前光标物品的方向功能是否声明支持对角槽位（漏斗不支持）。 */
    private static boolean targetSupportsDiagonal() {
        ItemStack carried = Minecraft.getInstance().player.containerMenu.getCarried();
        LivingItemFunction func = findDirectionFunction(carried);
        return func instanceof HasDirection dir && dir.supportsDiagonal();
    }

    /**
     * 活按钮拖动转发 —— 容器界面默认<b>不把拖动事件转发给子控件</b>。
     *
     * <p><b>为什么要这层</b>：{@code AbstractContainerScreen.mouseDragged} 整个重写了
     * {@code ContainerEventHandler} 的默认实现（焦点 + isDragging + 左键 ⇒ 转发给焦点控件），
     * 且<b>不调用 super</b>、恒返回 true —— 所以任何子控件都收不到 mouseDragged
     * （对比 {@code mouseReleased}：Forge 补丁调了 super，所以松手能到）。
     * 原版中需要拖动自己控件的容器界面（{@code CreativeModeInventoryScreen} 滚动条 /
     * {@code LoomScreen} / {@code BookEditScreen}）**也都各自重写 mouseDragged 自行处理**
     * —— 说明「拖动 = 槽位级手势、不转发控件」是容器界面的既定设计，不是漏写。</p>
     *
     * <p><b>为什么用事件而不是 mixin</b>：NeoForge 的 {@code MouseDragged.Pre} 在
     * {@code MouseHandler} 里、{@code screen.mouseDragged()} <b>之前</b>触发
     * （{@code ClientHooks.onScreenMouseDragPre}，取消即跳过原版调用），天然绕开那处重写，
     * 且是官方扩展点 —— 无需再动 {@code AbstractContainerScreenMixin}。</p>
     *
     * <p>只在按钮真正处于拖动手势时才取消事件（{@code mouseDragged} 返回 true）：
     * 手持物品时的原版槽位拖动（快速合成）不受影响。</p>
     */
    @SubscribeEvent
    public static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (event.getMouseButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) return;
        for (GuiEventListener child : screen.children()) {
            if (child instanceof LivingButton button
                && button.mouseDragged(event.getMouseX(), event.getMouseY(),
                                       event.getMouseButton(), event.getDragX(), event.getDragY())) {
                event.setCanceled(true);
                return;
            }
        }
    }

    /** 会话进行到一半时关闭界面 → 丢弃半输入状态（避免残留到下一个界面）。 */
    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        currentSession = null;
        directionSession = null;
        activeHud = null;
        lastDirectionPressAt.clear();
        pendingCharKey = 0;
    }

    /**
     * 方向输入的总前置条件 —— 按键拦截与字符输入<b>共用同一判据</b>（改一处即改两处）。
     */
    private static boolean shouldCaptureDirectionInput() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        if (!(mc.screen instanceof AbstractContainerScreen)) return false;
        if (mc.screen.getFocused() instanceof EditBox) return false; // 文本框（重命名等）输入不受影响
        if (!isLivingButtonHovered()) return false;
        ItemStack carried = mc.player.containerMenu.getCarried();
        if (carried.isEmpty() || !LivingItemManager.isLivingItem(carried)) return false;
        return findDirectionFunction(carried) != null || isHopperItem(carried);
    }

    // -------------------- 方向配置 HUD（悬停即显示） --------------------

    /**
     * HUD 的输入进度快照 —— 只承载「已输入」的符号列；槽位名与当前方向每帧从光标物品**实时读**
     * （避免快照过期）。null = 尚未开始输入（预览态）。
     *
     * @param typedSymbols 已输入的方向符号（与槽位顺序对齐）
     * @param hopperMode true = 活漏斗两键模式（源 → 目标）
     */
    public record DirectionHudState(List<String> typedSymbols, boolean hopperMode) {}

    private static DirectionHudState activeHud = null;

    /**
     * 方向配置 HUD 是否可见 —— 「悬停活按钮 + 光标持方向类活物品」即显示
     * （2026-10-10 起由「输入时显示」改为「**悬浮即显示**」：玩家不必先盲按一次才知道有这功能）。
     * 活按钮据此让位自己的 tooltip（两者同为 tooltip 会重叠）。
     */
    public static boolean isDirectionHudVisible() {
        return shouldCaptureDirectionInput();
    }

    /**
     * HUD 渲染行（null = 不显示）。预览态列出各槽位当前方向；输入态叠加进度与高亮
     * （已输入=绿、下一待输入=黄并带 ▶ 前缀、其余=灰）。
     */
    public static List<Component> getHudLines() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !shouldCaptureDirectionInput()) return null;
        ItemStack carried = mc.player.containerMenu.getCarried();
        LivingItemFunction func = findDirectionFunction(carried);
        boolean hopperMode = func == null;               // 能走到这里且无 HasDirection ⇒ 漏斗
        boolean diagonal;
        List<Component> slotNames = new ArrayList<>();
        List<String> currentSymbols = new ArrayList<>();
        if (hopperMode) {
            diagonal = false;
            slotNames.add(Component.translatable("hud.livingitem.hopper.source"));
            slotNames.add(Component.translatable("hud.livingitem.hopper.target"));
            DirectionTransferData dirData = LivingHopperFunction.readDirectionData(carried);
            currentSymbols.add(dirData != null ? dirData.sourceOffset().getSymbol() : "—");
            currentSymbols.add(dirData != null ? dirData.targetOffset().getSymbol() : "—");
        } else {
            HasDirection dir = (HasDirection) func;
            diagonal = dir.supportsDiagonal();
            for (String name : dir.getDirectionSlotNames()) {
                slotNames.add(Component.translatable("slot.livingitem." + name));
                Pos2D d = dir.getSlotDirection(carried, name);
                currentSymbols.add(d != null ? d.getSymbol() : "—");
            }
        }

        int total = slotNames.size();
        List<String> typed = activeHud != null ? activeHud.typedSymbols() : List.of();
        int done = Math.min(typed.size(), total);

        List<Component> lines = new ArrayList<>();
        if (activeHud == null) {                         // 预览态：不显示进度
            lines.add(Component.translatable(hopperMode
                ? "hud.livingitem.hopper.title_preview" : "hud.livingitem.direction.title_preview"));
        } else {
            lines.add(Component.translatable(hopperMode
                ? "hud.livingitem.hopper.title" : "hud.livingitem.direction.title", done, total));
        }
        for (int i = 0; i < total; i++) {
            boolean isDone = i < done;
            boolean isNext = i == done && done < total;
            String symbol = isDone ? typed.get(i) : currentSymbols.get(i);
            ChatFormatting color = isDone ? ChatFormatting.GREEN
                : isNext ? ChatFormatting.YELLOW : ChatFormatting.GRAY;
            Component nameComp = Component.literal(isNext ? "▶ " : "  ")
                .append(slotNames.get(i)).withStyle(color);
            // 符号走专用字体：对角箭头 ← 自绘字形，基本箭头 ← 原版（见 DirectionSymbols）
            Component symbolComp = DirectionSymbols.literal(symbol).withStyle(color);
            lines.add(Component.translatable("hud.livingitem.slot.line", nameComp, symbolComp));
        }
        String hintKey = hopperMode ? "hud.livingitem.hopper.hint"
            : diagonal ? "hud.livingitem.direction.hint" : "hud.livingitem.direction.hint_basic";
        lines.add(Component.translatable(hintKey).withStyle(ChatFormatting.GRAY));
        return lines;
    }

    private static void beginHud(boolean hopperMode) {
        activeHud = new DirectionHudState(new ArrayList<>(), hopperMode);
    }

    /** 会话追加按键后，刷新「已输入」符号列（两个会话类型共用）。 */
    private static void refreshHudTyped() {
        if (activeHud == null) return;
        String raw = directionSession != null ? directionSession.getRawInput()
            : currentSession != null ? currentSession.getRawInput() : "";
        List<String> typed = new ArrayList<>();
        for (char k : raw.toCharArray()) {
            Pos2D d = keyToDirection(k);
            typed.add(d != null ? d.getSymbol() : "?");
        }
        activeHud = new DirectionHudState(typed, activeHud.hopperMode());
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
     * 每 tick 检查会话存活：**鼠标离开活按钮即重置**（2026-10-10 起取代 3 秒超时——
     * 只要一直悬浮在按钮上就保持输入窗口，符合直觉，不再中途丢失半输入）。
     */
    @SubscribeEvent
    public static void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel)) return;
        if (currentSession == null && directionSession == null) return;
        if (isLivingButtonHovered()) return;
        LOGGER.debug("Direction session reset (cursor left the living button)");
        currentSession = null;
        directionSession = null;
        activeHud = null;
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
            // ⭐ 九宫格对角（2026-10-10）：键位 = 键盘左上角物理布局。
            // ⚠️ 不用 X（用户拍板：与 S 的「下」直觉冲突）——左下=Z、右下=C，提示文案同理不提 X
            case 'Q' -> Pos2D.UP_LEFT;
            case 'E' -> Pos2D.UP_RIGHT;
            case 'Z' -> Pos2D.DOWN_LEFT;
            case 'C' -> Pos2D.DOWN_RIGHT;
            default -> null;
        };
    }

    /**
     * 九宫格 8 键的 GLFW 键码 → 方向字符（0 = 非方向键）。
     *
     * <p>与 {@link #keyToDirection(char)} 一一对应；按键通道据此在不依赖字符回调的情况下解析方向。</p>
     */
    static char directionCharForKeyCode(int keyCode) {
        return switch (keyCode) {
            case GLFW.GLFW_KEY_W -> 'W';
            case GLFW.GLFW_KEY_S -> 'S';
            case GLFW.GLFW_KEY_A -> 'A';
            case GLFW.GLFW_KEY_D -> 'D';
            case GLFW.GLFW_KEY_Q -> 'Q';
            case GLFW.GLFW_KEY_E -> 'E';
            case GLFW.GLFW_KEY_Z -> 'Z';
            case GLFW.GLFW_KEY_C -> 'C';
            default -> 0;
        };
    }

    /** 全角字母（输入法提交态 U+FF21–FF3A / U+FF41–FF5A）归一化为半角 ASCII。 */
    static char normalizeChar(char c) {
        if (c >= '\uFF41' && c <= '\uFF5A') return (char) (c - 0xFF41 + 'a');
        if (c >= '\uFF21' && c <= '\uFF3A') return (char) (c - 0xFF21 + 'A');
        return c;
    }

    /** 4 个对角键（屏蔽需按能力放行，见 {@link #targetSupportsDiagonal()}）。 */
    private static boolean isDiagonalKeyCode(int keyCode) {
        return keyCode == GLFW.GLFW_KEY_Q || keyCode == GLFW.GLFW_KEY_E
            || keyCode == GLFW.GLFW_KEY_Z || keyCode == GLFW.GLFW_KEY_C;
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
        // 对角槽位是声明式能力（supportsDiagonal 默认 false）：未声明 ⇒ 忽略对角键
        if (direction.isDiagonal() && !dirFunc.supportsDiagonal()) return;

        if (dirFunc.getDirectionKeyCount() == 1) {
            sendSlotDirectionPacket(functionId, dirFunc.getDirectionSlotNames()[0], direction);
            return;
        }

        if (directionSession == null) {
            directionSession = new DirectionSession(dirFunc.getDirectionKeyCount());
            beginHud(false);
        }
        directionSession.appendKey(key);
        refreshHudTyped();

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
        activeHud = null;
    }

    /** 发送槽位方向配置网络包到服务端 */
    private static void sendSlotDirectionPacket(String functionId, String slotName, Pos2D direction) {
        PacketDistributor.sendToServer(new SlotDirectionPacket(
            functionId, slotName, direction.x(), direction.y()));
    }

    /**
     * 通用方向输入会话 —— 管理多键方向输入的生命周期。
     *
     * <p>适用于任意需要多键配向的活物品（如活熔炉 3 键）。
     * <b>不设超时</b>：存活由「鼠标是否仍在活按钮上」决定（{@link #onLevelTick}）。</p>
     */
    private static class DirectionSession {
        private final StringBuilder keys = new StringBuilder();
        private final int maxKeys;

        DirectionSession(int maxKeys) {
            this.maxKeys = maxKeys;
        }

        void appendKey(char key) {
            if (keys.length() < maxKeys) {
                keys.append(key);
            }
        }

        boolean isComplete() {
            return keys.length() >= maxKeys;
        }

        String getRawInput() {
            return keys.toString();
        }
    }

    /**
     * 活漏斗输入会话 —— 管理 2 键 WASD 输入的生命周期。
     *
     * <p>输入规则：第 1 个键 = 源方向，第 2 个键 = 目标方向；示例 "WD" = 上传下。
     * <b>不支持对角</b>（漏斗的槽位映射只有 4 个基本方向）。
     * <b>不设超时</b>：存活由「鼠标是否仍在活按钮上」决定。</p>
     */
    private static class InputSession {
        private final StringBuilder keys = new StringBuilder();

        private static final int MAX_KEYS = 2;

        void appendKey(char key) {
            if (keys.length() < MAX_KEYS) {
                keys.append(key);
            }
        }

        boolean isComplete() {
            return keys.length() >= MAX_KEYS;
        }

        String getRawInput() {
            return keys.toString();
        }
    }
}