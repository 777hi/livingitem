package com.qiqi.li.client.gui;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

/**
 * 活按钮位置布局 —— 按 <b>Screen 类名 + GUI 尺寸</b>分别持久化按钮位置。
 *
 * <p>思路参考 TrashSlot，只取骨架（**类名做键 + 客户端本地 JSON**）；
 * 它的吸附 / 碰撞 / 显示开关三者**不做**（2026-10-10 用户拍板）。</p>
 *
 * <p><b>坐标语义</b>：相对 GUI 左上角（{@code getGuiLeft()}/{@code getGuiTop()}）的偏移 ——
 * 界面位置随窗口缩放 / 分辨率变化时按钮跟着走，不会漂到屏幕别处。</p>
 *
 * <p><b>存储</b>：{@code config/living_item/button_layout.json}（**每个玩家自己的**，
 * 多人服互不影响；首次拖动才生成文件）：</p>
 *
 * <pre>
 * { "version": 2, "buttons": { "net.minecraft.client.gui.screens.inventory.ChestScreen#176x166": { "x": 12, "y": -8 } } }
 * </pre>
 *
 * <p><b>失败语义（沉默即缺陷）</b>：版本不识别 / 坏值 / 缺字段 ⇒ WARN 并退回默认位置，
 * 绝不崩游戏。</p>
 */
public final class LivingButtonLayout {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int VERSION = 2;
    private static final String FILE_NAME = "button_layout.json";

    /** screenId → {x, y}（相对 GUI 左上角的偏移）。 */
    private static final Map<String, int[]> POSITIONS = new HashMap<>();
    private static boolean loaded = false;

    private LivingButtonLayout() {}

    /**
     * 用 <b>Screen 类名 + GUI 尺寸</b>做键 —— 每个「界面类型 × 布局」各存一份位置。
     *
     * <p>⚠️ <b>为什么必须带尺寸</b>：原版<b>单箱与大箱是同一个 {@code ChestScreen} 类</b>
     * （只有 {@code ChestMenu} 的槽位数不同）⇒ 只用类名会让两者<b>共享同一份位置</b>，
     * 而两者 GUI 高度差 56px（166 / 222）⇒ 拖过其中一个会破坏另一个的布局。</p>
     */
    public static String screenId(Screen screen, int width, int height) {
        return screen.getClass().getName() + "#" + width + "x" + height;
    }

    /** 读取该界面的按钮偏移；无记录 ⇒ 返回传入的默认值。 */
    public static int[] resolve(Screen screen, int width, int height, int defaultX, int defaultY) {
        ensureLoaded();
        int[] pos = POSITIONS.get(screenId(screen, width, height));
        return pos != null ? new int[]{pos[0], pos[1]} : new int[]{defaultX, defaultY};
    }

    /** 记录并立即写盘（拖动结束时调用；频率极低）。 */
    public static void setPosition(String screenId, int x, int y) {
        ensureLoaded();
        POSITIONS.put(screenId, new int[]{x, y});
        save();
    }

    /**
     * 把按钮的**屏幕坐标**夹进屏幕内。
     *
     * <p>⚠️ <b>为什么应用保存位置时也要夹</b>：拖动时的夹取只管**当时那一次**。
     * 玩家之后**缩小窗口 / 调大 GUI scale** ⇒ 逻辑屏幕变小，而保存的偏移是**相对 GUI** 的
     * ⇒ 换算出的屏幕坐标可能落到屏幕外（按钮"消失"）。</p>
     *
     * <p>只夹**显示位置**、**不回写配置** —— 恢复窗口后按钮应回到原位。</p>
     */
    public static int[] clampToScreen(int x, int y, int screenWidth, int screenHeight,
                                      int buttonWidth, int buttonHeight) {
        return new int[]{
            Mth.clamp(x, 0, Math.max(0, screenWidth - buttonWidth)),
            Mth.clamp(y, 0, Math.max(0, screenHeight - buttonHeight))
        };
    }

    // ==================== 按钮默认位（2026-10-13，生存背包启用） ====================

    /**
     * 原版 {@code InventoryScreen} 小人渲染矩形 —— {@code renderBg} 对
     * {@code renderEntityInInventoryFollowsMouse} 的写死实参：
     * (leftPos+26, topPos+8) → (leftPos+75, topPos+78)，49×70。
     * 按钮默认位与射线微调（ToolRayTuningClicks 调用点）共用，勿在调用点重复写魔法数。
     */
    public static final int INVENTORY_PREVIEW_X1 = 26;
    public static final int INVENTORY_PREVIEW_Y1 = 8;
    public static final int INVENTORY_PREVIEW_X2 = 75;
    public static final int INVENTORY_PREVIEW_Y2 = 78;

    /**
     * 按钮默认位（相对 GUI 左上角），按界面类型分流：
     * <ul>
     *   <li><b>生存背包</b>：小人框底居中、底对齐 —— x = 26 + (49-16)/2 = 42，
     *       y = 78 - 8 = 70。框内无槽位（盔甲列 x8-24、副手 (77,62)、合成格 x98+ 全在框外，
     *       已核对 {@code InventoryMenu} 全部槽位坐标）。</li>
     *   <li><b>其余容器</b>：GUI 底部中央（chest 原默认位，保持不变）。</li>
     * </ul>
     *
     * @param playerInventory true = 生存背包（InventoryScreen）
     */
    public static int[] defaultOffset(boolean playerInventory, int imageWidth, int imageHeight) {
        if (playerInventory) {
            return new int[]{
                INVENTORY_PREVIEW_X1
                    + (INVENTORY_PREVIEW_X2 - INVENTORY_PREVIEW_X1 - LivingButton.WIDTH) / 2,
                INVENTORY_PREVIEW_Y2 - LivingButton.HEIGHT
            };
        }
        return new int[]{(imageWidth - 16) / 2, imageHeight - 94};
    }

    private static Path configFile() {
        return FMLPaths.CONFIGDIR.get().resolve("living_item").resolve(FILE_NAME);
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path file = configFile();
        if (!Files.exists(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                LOGGER.warn("[living] 按钮布局文件不是对象，忽略：{}", file);
                return;
            }
            JsonObject obj = root.getAsJsonObject();
            int version = obj.has("version") ? obj.get("version").getAsInt() : -1;
            if (version != VERSION) {
                LOGGER.warn("[living] 按钮布局版本不识别（{}），忽略：{}", version, file);
                return;
            }
            JsonObject buttons = obj.has("buttons") && obj.get("buttons").isJsonObject()
                ? obj.getAsJsonObject("buttons") : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : buttons.entrySet()) {
                try {
                    JsonObject pos = entry.getValue().getAsJsonObject();
                    POSITIONS.put(entry.getKey(),
                        new int[]{pos.get("x").getAsInt(), pos.get("y").getAsInt()});
                } catch (Exception badEntry) {
                    LOGGER.warn("[living] 按钮布局条目坏值，跳过：{}", entry.getKey());
                }
            }
        } catch (Exception ex) {
            LOGGER.warn("[living] 按钮布局加载失败，使用默认位置：{}", ex.toString());
        }
    }

    private static void save() {
        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            JsonObject buttons = new JsonObject();
            for (Map.Entry<String, int[]> entry : POSITIONS.entrySet()) {
                JsonObject pos = new JsonObject();
                pos.addProperty("x", entry.getValue()[0]);
                pos.addProperty("y", entry.getValue()[1]);
                buttons.add(entry.getKey(), pos);
            }
            JsonObject root = new JsonObject();
            root.addProperty("version", VERSION);
            root.add("buttons", buttons);
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write(root.toString());
            }
        } catch (IOException ex) {
            LOGGER.warn("[living] 按钮布局保存失败：{}", ex.toString());
        }
    }
}
