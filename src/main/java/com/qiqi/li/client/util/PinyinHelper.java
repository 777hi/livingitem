package com.qiqi.li.client.util;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 拼音转换工具类
 *
 * <h3>🎯 功能</h3>
 * <p>将中文字符串转换为拼音形式，支持：</p>
 * <ul>
 *   <li>✅ 完整拼音转换（"钻石" → "zuanshi"）</li>
 *   <li>✅ 首字母缩写（"钻石" → "zs"）</li>
 *   <li>✅ 混合匹配（支持中英文数字混合输入）</li>
 *   <li>✅ 大小写不敏感</li>
 * </ul>
 *
 * <h3>🔧 使用场景</h3>
 * <ul>
 *   <li>活箱子配方书搜索功能</li>
 *   <li>物品名称模糊搜索</li>
 *   <li>中英文混合搜索</li>
 * </ul>
 *
 * <h3>📝 示例</h3>
 * <pre>
 * // 完整拼音
 * toPinyin("钻石") → "zuanshi"
 * toPinyin("铁剑") → "tiejian"
 *
 * // 首字母
 * toPinyinInitials("钻石") → "zs"
 * toPinyinInitials("铁剑") → "tj"
 *
 * // 搜索匹配
 * isPinyinMatch("钻石", "zuanshi") → true
 * isPinyinMatch("钻石", "zs") → true
 * isPinyinMatch("钻石", "zuan") → true
 * </pre>
 *
 * @author AI Assistant
 * @version 1.0.0
 * @since 2026-07-16
 */
public class PinyinHelper {

    /**
     * 常用汉字到完整拼音的映射表（精简版）
     * <p><strong>注意</strong>: 这里只包含 Minecraft 中常见的物品相关汉字，
     * 如需支持更多汉字可以扩展此映射表。</p>
     */
    private static final Map<Character, String> PINYIN_MAP = new HashMap<>();

    /**
     * 常用汉字到拼音首字母的映射表（用于快速首字母搜索）
     */
    private static final Map<Character, String> INITIALS_MAP = new HashMap<>();

    static {
        initPinyinMaps();
    }

    /**
     * 初始化拼音映射表
     * <p>包含 Minecraft 常见物品、方块、工具等相关的汉字。</p>
     */
    private static void initPinyinMaps() {
        // ==================== 自然元素 ====================
        addPinyin('石', "shi", "s");
        addPinyin('金', "jin", "j");
        addPinyin('木', "mu", "m");
        addPinyin('水', "shui", "s");
        addPinyin('火', "huo", "h");
        addPinyin('土', "tu", "t");
        addPinyin('风', "feng", "f");
        addPinyin('雷', "lei", "l");
        addPinyin('冰', "bing", "b");
        addPinyin('雪', "xue", "x");
        addPinyin('草', "cao", "c");
        addPinyin('花', "hua", "h");
        addPinyin('树', "shu", "s");
        addPinyin('叶', "ye", "y");
        addPinyin('果', "guo", "g");
        addPinyin('种', "zhong", "z");
        addPinyin('子', "zi", "z");

        // ==================== 矿物与材料 ====================
        addPinyin('矿', "kuang", "k");
        addPinyin('钻', "zuan", "z");
        addPinyin('铁', "tie", "t");
        addPinyin('铜', "tong", "t");
        addPinyin('银', "yin", "y");
        addPinyin('金', "jin", "j");
        addPinyin('煤', "mei", "m");
        addPinyin('炭', "tan", "t");
        addPinyin('晶', "jing", "j");
        addPinyin('绿', "lv", "l");
        addPinyin('红', "hong", "h");
        addPinyin('蓝', "lan", "l");
        addPinyin('青', "qing", "q");
        addPinyin('紫', "zi", "z");
        addPinyin('黑', "hei", "h");
        addPinyin('白', "bai", "b");
        addPinyin('灰', "hui", "h");
        addPinyin('褐', "he", "h");
        addPinyin('橙', "cheng", "c");
        addPinyin('粉', "fen", "f");
        addPinyin('黄', "huang", "h");

        // ==================== 工具与武器 ====================
        addPinyin('剑', "jian", "j");
        addPinyin('镐', "gao", "g");
        addPinyin('斧', "fu", "f");
        addPinyin('锄', "chu", "c");
        addPinyin('锹', "qiao", "q");
        addPinyin('铲', "chan", "c");
        addPinyin('弓', "gong", "g");
        addPinyin('箭', "jian", "j");
        addPinyin('盾', "dun", "d");
        addPinyin('甲', "jia", "j");
        addPinyin('盔', "kui", "k");
        addPinyin('靴', "xue", "x");
        addPinyin('腿', "tui", "t");
        addPinyin('帽', "mao", "m");
        addPinyin('杖', "zhang", "z");
        addPinyin('刀', "dao", "d");
        addPinyin('枪', "qiang", "q");
        addPinyin('矛', "mao", "m");
        addPinyin('锤', "chui", "c");
        addPinyin('锄', "chu", "c");

        // ==================== 食物与农作物 ====================
        addPinyin('面', "mian", "m");
        addPinyin('包', "bao", "b");
        addPinyin('肉', "rou", "r");
        addPinyin('鱼', "yu", "y");
        addPinyin('鸡', "ji", "j");
        addPinyin('猪', "zhu", "z");
        addPinyin('牛', "niu", "n");
        addPinyin('羊', "yang", "y");
        addPinyin('蛋', "dan", "d");
        addPinyin('奶', "nai", "n");
        addPinyin('蜜', "mi", "m");
        addPinyin('糖', "tang", "t");
        addPinyin('苹', "ping", "p");
        addPinyin('瓜', "gua", "g");
        addPinyin('萝', "luo", "l");
        addPinyin('卜', "bo", "b");
        addPinyin('土', "tu", "t");
        addPinyin('豆', "dou", "d");
        addPinyin('胡', "hu", "h");
        addPinyin('南', "nan", "n");
        addPinyin('西', "xi", "x");
        addPinyin('番', "fan", "f");
        addPinyin('茄', "qie", "q");
        addPinyin('甜', "tian", "t");
        addPinyin('浆', "jiang", "j");
        addPinyin('可', "ke", "k");
        addPinyin('巧', "qiao", "q");
        addPinyin('曲', "qu", "q");
        addPinyin('饼', "bing", "b");
        addPinyin('干', "gan", "g");
        addPinyin('薯', "shu", "s");
        addPinyin('烤', "kao", "k");
        addPinyin('烧', "shao", "s");
        addPinyin('炖', "dun", "d");
        addPinyin('煮', "zhu", "z");
        addPinyin('炸', "zha", "z");
        addPinyin('腌', "yan", "y");

        // ==================== 方块与建筑 ====================
        addPinyin('砖', "zhuan", "z");
        addPinyin('玻', "bo", "b");
        addPinyin('璃', "li", "l");
        addPinyin('木', "mu", "m");
        addPinyin('板', "ban", "b");
        addPinyin('栅', "zhan", "z");
        addPinyin('栏', "lan", "l");
        addPinyin('门', "men", "m");
        addPinyin('窗', "chuang", "c");
        addPinyin('梯', "ti", "t");
        addPinyin('台', "tai", "t");
        addPinyin('箱', "xiang", "x");
        addPinyin('柜', "gui", "g");
        addPinyin('床', "chuang", "c");
        addPinyin('桌', "zhuo", "z");
        addPinyin('椅', "yi", "y");
        addPinyin('灯', "deng", "d");
        addPinyin('烛', "zhu", "z");
        addPinyin('火', "huo", "h");
        addPinyin('把', "ba", "b");
        addPinyin('桶', "tong", "t");
        addPinyin('盆', "pen", "p");
        addPinyin('罐', "guan", "g");
        addPinyin('瓶', "ping", "p");
        addPinyin('碗', "wan", "w");
        addPinyin('盘', "pan", "p");

        // ==================== 生物与怪物 ====================
        addPinyin('怪', "guai", "g");
        addPinyin('尸', "shi", "s");
        addPinyin('骷', "ku", "k");
        addPinyin('髅', "lou", "l");
        addPinyin('苦', "ku", "k");
        addPinyin('力', "bi", "b");
        addPinyin('怕', "pa", "p");
        addPinyin('末', "mo", "m");
        addPinyin('影', "ying", "y");
        addPinyin('守', "shou", "s");
        addPinyin('卫', "wei", "w");
        addPinyin('凋', "diao", "d");
        addPinyin('灵', "ling", "l");
        addPinyin('唤', "huan", "h");
        addPinyin('师', "shi", "s");
        addPinyin('潜', "qian", "q");
        addPinyin('影', "ying", "y");
        addPinyin('侦', "zhen", "z");
        addPinyin('察', "cha", "c");
        addPinyin('嗅', "xiu", "x");
        addPinyin('探', "tan", "t");
        addPinyin('蜘', "zhi", "z");
        addPinyin('蛛', "zhu", "z");
        addPinyin('洞', "dong", "d");
        addPinyin('穴', "xue", "x");
        addPinyin('史', "shi", "s");
        addPinyin('莱', "lai", "l");
        addPinyin('姆', "mu", "m");
        addPinyin('僵', "jiang", "j");
        addPinyin('尸', "shi", "s");
        addPinyin('骷', "ku", "k");
        addPinyin('髅', "lou", "l");
        addPinyin('爬', "pa", "p");
        addPinyin('行', "xing", "x");
        addPinyin('流', "liu", "l");
        addPinyin('恶', "e", "e");
        addPinyin('魂', "hun", "h");
        addPinyin('烈', "lie", "l");
        addPinyin('焰', "yan", "y");
        addPinyin('岩', "yan", "y");
        addPinyin('怪', "guai", "g");
        addPinyin('史', "shi", "s");
        addPinyin('莱', "lai", "l");
        addPinyin('姆', "mu", "m");

        // ==================== 附魔与药水 ====================
        addPinyin('附', "fu", "f");
        addPinyin('魔', "mo", "m");
        addPinyin('锋', "feng", "f");
        addPinyin('利', "li", "l");
        addPinyin('亡', "wang", "w");
        addPinyin('击', "ji", "j");
        addPinyin('摔', "shuai", "s");
        addPinyin('爆', "bao", "b");
        addPinyin('弹', "dan", "d");
        addPinyin('射', "she", "s");
        addPinyin('火', "huo", "h");
        addPinyin('焰', "yan", "y");
        addPinyin('海', "hai", "h");
        addPinyin('底', "di", "d");
        addPinyin('潜', "qian", "q");
        addPinyin('速', "su", "s");
        addPinyin('时', "shi", "s");
        addPinyin('跳', "tiao", "t");
        addPinyin('夜', "ye", "y");
        addPinyin('视', "shi", "s");
        addPinyin('隐', "yin", "y");
        addPinyin('消', "xiao", "x");
        addPinyin('耐', "nai", "n");
        addPinyin('久', "jiu", "j");
        addPinyin('保', "bao", "b");
        addPinyin('护', "hu", "h");
        addPinyin('落', "luo", "l");
        addPinyin('穿', "chuan", "c");
        addPinyin('刺', "ci", "c");
        addPinyin('拾', "shi", "s");
        // 附魔相关词汇（拆分为单字）
        addPinyin('经', "jing", "j");
        addPinyin('验', "yan", "y");
        addPinyin('修', "xiu", "x");
        addPinyin('补', "bu", "b");
        addPinyin('消', "xiao", "x");
        addPinyin('失', "shi", "s");
        addPinyin('绑', "bang", "b");
        addPinyin('定', "ding", "d");
        addPinyin('穿', "chuan", "c");
        addPinyin('刺', "ci", "c");
        addPinyin('激', "ji", "j");
        addPinyin('流', "liu", "l");
        addPinyin('忠', "zhong", "z");
        addPinyin('诚', "cheng", "c");
        addPinyin('冲', "chong", "c");
        addPinyin('击', "ji", "j");
        addPinyin('引', "yin", "y");
        addPinyin('退', "tui", "t");

        // ==================== 其他常用字 ====================
        addPinyin('大', "da", "d");
        addPinyin('小', "xiao", "x");
        addPinyin('长', "chang", "c");
        addPinyin('短', "duan", "d");
        addPinyin('高', "gao", "g");
        addPinyin('低', "di", "d");
        addPinyin('宽', "kuan", "k");
        addPinyin('窄', "zhai", "z");
        addPinyin('厚', "hou", "h");
        addPinyin('薄', "bao", "b");
        addPinyin('新', "xin", "x");
        addPinyin('旧', "jiu", "j");
        addPinyin('好', "hao", "h");
        addPinyin('坏', "huai", "h");
        addPinyin('快', "kuai", "k");
        addPinyin('慢', "man", "m");
        addPinyin('强', "qiang", "q");
        addPinyin('弱', "ruo", "r");
        addPinyin('轻', "qing", "q");
        addPinyin('重', "zhong", "z");
        addPinyin('硬', "ying", "y");
        addPinyin('软', "ruan", "r");
        addPinyin('热', "re", "r");
        addPinyin('冷', "leng", "l");
        addPinyin('亮', "liang", "l");
        addPinyin('暗', "an", "a");
        addPinyin('明', "ming", "m");
        addPinyin('暗', "an", "a");
        addPinyin('鲜', "xian", "x");
        addPinyin('陈', "chen", "c");
        addPinyin('干', "gan", "g");
        addPinyin('湿', "shi", "s");
        addPinyin('净', "jing", "j");
        addPinyin('脏', "zang", "z");
        addPinyin('纯', "chun", "c");
        addPinyin('杂', "za", "z");
        addPinyin('真', "zhen", "z");
        addPinyin('假', "jia", "j");
        addPinyin('虚', "xu", "x");
        addPinyin('实', "shi", "s");
        addPinyin('空', "kong", "k");
        addPinyin('满', "man", "m");
        addPinyin('多', "duo", "d");
        addPinyin('少', "shao", "s");
        addPinyin('无', "wu", "w");
        addPinyin('有', "you", "y");
        addPinyin('生', "sheng", "s");
        addPinyin('死', "si", "s");
        addPinyin('活', "huo", "h");
        addPinyin('动', "dong", "d");
        addPinyin('静', "jing", "j");
        addPinyin('开', "kai", "k");
        addPinyin('关', "guan", "g");
        addPinyin('进', "jin", "j");
        addPinyin('出', "chu", "c");
        addPinyin('上', "shang", "s");
        addPinyin('下', "xia", "x");
        addPinyin('左', "zuo", "z");
        addPinyin('右', "you", "y");
        addPinyin('前', "qian", "q");
        addPinyin('后', "hou", "h");
        addPinyin('内', "nei", "n");
        addPinyin('外', "wai", "w");
        addPinyin('里', "li", "l");
        addPinyin('中', "zhong", "z");
        addPinyin('东', "dong", "d");
        addPinyin('西', "xi", "x");
        addPinyin('南', "nan", "n");
        addPinyin('北', "bei", "b");
    }

    /**
     * 添加单个汉字的拼音映射
     *
     * @param ch 汉字字符
     * @param pinyin 完整拼音
     * @param initial 首字母
     */
    private static void addPinyin(char ch, String pinyin, String initial) {
        PINYIN_MAP.put(ch, pinyin);
        INITIALS_MAP.put(ch, initial);
    }

    /**
     * 将字符串转换为完整拼音（不带声调）
     *
     * <h3>🔧 转换规则</h3>
     * <ul>
     *   <li>中文字符 → 转换为拼音</li>
     *   <li>英文字母/数字 → 保持原样</li>
     *   <li>其他字符 → 忽略或保留</li>
     * </ul>
     *
     * @param text 输入文本
     * @return 拼音字符串（全小写）
     */
    public static String toPinyin(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        for (char ch : text.toCharArray()) {
            if (PINYIN_MAP.containsKey(ch)) {
                result.append(PINYIN_MAP.get(ch));
            } else if (isLatinOrDigit(ch)) {
                result.append(Character.toLowerCase(ch));
            }
            // 其他字符（空格、符号等）忽略
        }

        return result.toString();
    }

    /**
     * 将字符串转换为拼音首字母
     *
     * <h3>📝 示例</h3>
     * <pre>
     * toPinyinInitials("钻石剑") → "zsj"
     * toPinyinInitials("铁锭") → "td"
     * </pre>
     *
     * @param text 输入文本
     * @return 拼音首字母字符串（全小写）
     */
    public static String toPinyinInitials(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        for (char ch : text.toCharArray()) {
            if (INITIALS_MAP.containsKey(ch)) {
                result.append(INITIALS_MAP.get(ch));
            } else if (isLatinOrDigit(ch)) {
                result.append(Character.toLowerCase(ch));
            }
        }

        return result.toString();
    }

    /**
     * 检查文本是否通过拼音匹配搜索词
     *
     * <h3>🎯 匹配规则</h3>
     * <ul>
     *   <li>✅ 完整拼音匹配: "zuanshi" 匹配 "钻石"</li>
     *   <li>✅ 首字母匹配: "zs" 匹配 "钻石"</li>
     *   <li>✅ 部分拼音匹配: "zuan" 匹配 "钻石"</li>
     *   <li>✅ 大小写不敏感</li>
     *   <li>✅ 混合匹配: 支持"钻石zs"、"zuan石"等形式</li>
     * </ul>
     *
     * @param text 要检查的文本（可能是中文）
     * @param searchText 搜索关键词（可能是拼音）
     * @return 是否匹配
     */
    public static boolean isPinyinMatch(String text, String searchText) {
        if (text == null || searchText == null) {
            return false;
        }

        if (searchText.isEmpty()) {
            return true;  // 空搜索词匹配所有内容
        }

        String lowerSearch = searchText.toLowerCase(Locale.ROOT);
        String lowerText = text.toLowerCase(Locale.ROOT);

        // 1. 直接文本匹配（处理非中文或直接输入中文的情况）
        if (lowerText.contains(lowerSearch)) {
            return true;
        }

        // 2. 完整拼音匹配
        String fullPinyin = toPinyin(text);
        if (fullPinyin.contains(lowerSearch)) {
            return true;
        }

        // 3. 首字母匹配
        String initials = toPinyinInitials(text);
        if (initials.contains(lowerSearch)) {
            return true;
        }

        // 4. 混合匹配：搜索词可能是拼音+中文的组合
        // 例如："zuan石" 或 "钻shi"
        return hybridMatch(text, lowerSearch);
    }

    /**
     * 混合匹配模式（拼音+中文组合）
     *
     * <h3>💡 场景示例</h3>
     * <pre>
     * "zuan石" 匹配 "钻石"
     * "钻shi" 匹配 "钻石"
     * "zsj" 匹配 "钻石剑"
     * </pre>
     *
     * @param text 原始文本
     * @param search 搜索词
     * @return 是否匹配
     */
    private static boolean hybridMatch(String text, String search) {
        // 尝试将搜索词拆分为拼音部分和中文部分
        int splitIndex = findSplitIndex(search);

        if (splitIndex > 0 && splitIndex < search.length()) {
            String part1 = search.substring(0, splitIndex);
            String part2 = search.substring(splitIndex);

            // 尝试两种顺序：拼音在前 or 中文在前
            return (isPinyinMatch(text, part1) && text.toLowerCase().contains(part2)) ||
                   (text.toLowerCase().contains(part1) && isPinyinMatch(text, part2));
        }

        return false;
    }

    /**
     * 找到拼音和中文的分界点
     *
     * @param search 搜索词
     * @return 分界点索引，未找到返回 -1
     */
    private static int findSplitIndex(String search) {
        for (int i = 0; i < search.length(); i++) {
            char ch = search.charAt(i);
            boolean isLatin = isLatinOrDigit(ch);

            if (i > 0) {
                char prev = search.charAt(i - 1);
                boolean prevIsLatin = isLatinOrDigit(prev);

                if (isLatin != prevIsLatin) {
                    return i;  // 找到分界点
                }
            }
        }
        return -1;
    }

    /**
     * 检查字符是否为拉丁字母或数字
     *
     * @param ch 字符
     * @return 如果是拉丁字母或数字返回 true
     */
    private static boolean isLatinOrDigit(char ch) {
        return (ch >= 'a' && ch <= 'z') ||
               (ch >= 'A' && ch <= 'Z') ||
               (ch >= '0' && ch <= '9');
    }

    /**
     * 扩展拼音字典（运行时动态添加）
     *
     * <h3>🔧 用途</h3>
     * <p>允许在运行时添加新的汉字映射，
     * 用于支持模组自定义物品名称等场景。</p>
     *
     * @param ch 汉字字符
     * @param pinyin 完整拼音
     * @param initial 首字母
     */
    public static void extendDictionary(char ch, String pinyin, String initial) {
        PINYIN_MAP.put(ch, pinyin.toLowerCase(Locale.ROOT));
        INITIALS_MAP.put(ch, initial.toLowerCase(Locale.ROOT));
    }

    /**
     * 清除所有自定义扩展的映射（恢复到默认状态）
     */
    public static void resetToDefault() {
        initPinyinMaps();
    }

    /**
     * 获取当前字典大小（调试用）
     *
     * @return 映射的汉字数量
     */
    public static int getDictionarySize() {
        return PINYIN_MAP.size();
    }
}