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
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

/**
 * 活按钮位置布局 —— 按 <b>Screen 类名</b>分别持久化按钮位置。
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
 * { "version": 1, "buttons": { "net.minecraft...ShulkerBoxScreen": { "x": 12, "y": -8 } } }
 * </pre>
 *
 * <p><b>失败语义（沉默即缺陷）</b>：版本不识别 / 坏值 / 缺字段 ⇒ WARN 并退回默认位置，
 * 绝不崩游戏。</p>
 */
public final class LivingButtonLayout {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int VERSION = 1;
    private static final String FILE_NAME = "button_layout.json";

    /** screenId → {x, y}（相对 GUI 左上角的偏移）。 */
    private static final Map<String, int[]> POSITIONS = new HashMap<>();
    private static boolean loaded = false;

    private LivingButtonLayout() {}

    /** 用 Screen 类名做键 —— 每个界面类型各存一份位置。 */
    public static String screenId(Screen screen) {
        return screen.getClass().getName();
    }

    /** 读取该界面的按钮偏移；无记录 ⇒ 返回传入的默认值。 */
    public static int[] resolve(Screen screen, int defaultX, int defaultY) {
        ensureLoaded();
        int[] pos = POSITIONS.get(screenId(screen));
        return pos != null ? new int[]{pos[0], pos[1]} : new int[]{defaultX, defaultY};
    }

    /** 记录并立即写盘（拖动结束时调用；频率极低）。 */
    public static void setPosition(String screenId, int x, int y) {
        ensureLoaded();
        POSITIONS.put(screenId, new int[]{x, y});
        save();
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
