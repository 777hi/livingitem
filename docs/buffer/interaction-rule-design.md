> **⛔ 未定案（设计稿）** —— 本文是 [open-plan.md](open-plan.md) §4 D2 的施工设计，
> 尚未实施。schema 一旦随发布对外就很难再改，动代码前 §6 的问题必须先拍板。
> 实施后：§2 并入稳定层，本文按 [README](../README.md) §1 搬迁或归档。

---

## 0. 目的

把「活物品交互规则」从 Java 代码迁到 JSON，让**整合包作者不写一行 Java**
就能新增/覆盖活物品的交互规则 —— 与 D1（活化规则）同一受众、同一价值主张。

**为什么现在做**：A1 已把规则按域下放（D2 的前置条件，open-plan §4 当时写明
「A1 本身就是 D2 的准备工作」）；且 `ContainerRuleConfig` / `ActivationRuleConfig`
两套同构模式已经跑通，照抄即可，无新设计风险。

---

## 1. 现状盘点（数字来源见 §7）

| 项 | 数量 | 位置 |
|---|---|---|
| 交互规则（`new InteractionEntry`） | 源码 **18 处** | farmland 3 / tnt 2 / redstone 13 |
| ↑ 运行时实际条数 | **多于 18** | `till_to_farmland` 按 `Tillables` 循环注册，每个可耕物品一条 |
| 交互处理器（`registerHandler`） | **9 个** actionId | ignite / ignite_carried / button_press / lever_toggle / repeater_cycle / comparator_toggle / till_to_farmland / plant_crop / bonemeal |
| Java 谓词（`triggerFilter`） | **仅 1 个** | `Tillables::canTillWith`（farmland） |

### 1.1 一个立刻能拿到的收益：红石域 13 条同构规则

`RedstoneRegistration` 把 **13 种按钮逐个列出**，每条只差物品 ID、动作完全相同：

```java
InteractionRegistry.register(new InteractionEntry(Items.STONE_BUTTON, null, 1, "button_press"));
InteractionRegistry.register(new InteractionEntry(Items.POLISHED_BLACKSTONE_BUTTON, null, 1, "button_press"));
// … 共 13 行
```

而原版现成有 `#minecraft:buttons` tag —— **target 支持 tag 后，13 行变 1 行**。
这 13 行正是「规则该数据化」的最实证（规则本来就是数据，用 Java 枚举物品是绕远）。

---

## 2. 设计

### 2.1 语义边界：这是【GUI 内】的交互，不是世界交互 ⚠️

`InteractionRegistry.findInteraction(trigger, target, button, onRelease)` 的两个
参数是**光标物品**与**悬浮槽位物品**（见该类 javadoc「GUI交互注册表」）——
即玩家在**容器界面里**，手拿 trigger 悬停在 target 上点击时触发。
且匹配前提是 **target 必须是活物品**（精确条目的 trigger 也必须是活物品）。

⇒ 整合包作者能表达的是「在活物品的 GUI 里，这样两种物品的组合 → 这样一个动作」，
**不是**「世界里右键方块触发什么」。后者的规则体系（方块交互）本文不涉及。

### 2.2 JSON schema（草案）

```json
{
  "version": 1,
  "rules": [
    { "target": "minecraft:tnt", "trigger": "minecraft:flint_and_steel",
      "button": "right", "action": "ignite" },

    { "target": "#minecraft:buttons", "button": "right", "action": "button_press" },

    { "target": "minecraft:farmland", "button": "right", "action": "plant_crop" },

    { "target": "minecraft:farmland", "button": "right", "action": "till_to_farmland",
      "predicate": "can_till" }
  ]
}
```

| 字段 | 必填 | 含义 |
|---|---|---|
| `target` | ✅ | 槽位物品：物品 ID 或 `#tag`（D1 同款写法） |
| `trigger` | ❌ | 光标物品：物品 ID 或 `#tag`；**省略 = 通配**（任意光标） |
| `button` | ✅ | `left` / `right` / `middle`（不用裸数字 —— 对配置者不友好） |
| `action` | ✅ | **必须引用已注册的 handler actionId**（见 §2.4 安全边界） |
| `onRelease` | ❌ | 默认 `false` |
| `predicate` | ❌ | ID 化谓词（§2.3）；省略 = 无额外过滤 |

**解析失败一律 WARN + 跳过，不崩**（照抄两套前例）；且必须补
**未知字段 WARN**（`{"buttton": …}` 拼错静默变默认值 —— `ContainerRuleConfig`
目前没有的检测，open-plan §5 原则 4 要求新 JSON 必须补上）。

### 2.3 谓词 ID 化（工作量很小：全项目只有 1 个谓词）

```java
/** 谓词注册表：JSON 里写 ID，Java 里查表。各域注册自己的谓词。 */
public final class InteractionPredicates {
    private static final Map<String, BiPredicate<ItemStack, ItemStack>> PREDICATES = new HashMap<>();
    public static void register(String id, BiPredicate<ItemStack, ItemStack> p) { ... }
    public static BiPredicate<ItemStack, ItemStack> get(String id) { ... }   // null = JSON 引用了未知谓词 ⇒ WARN
}
```

- `FarmlandRegistration` 注册 `"can_till" -> Tillables::canTillWith`
- 玩家 JSON 只能引用**已注册**的谓词 ID —— 引用未知 ID 时 WARN 并丢弃该规则
  （沉默即缺陷）

### 2.4 安全边界：玩家不能创造新行为（天然成立，写明即可）

`action` 只能引用**已有 handler 的 actionId**，而 handler 是模组代码 ——
⇒ 玩家 JSON 能做的仅仅是「把已有动作接到新的物品组合上」，
**不能**让活物品做出模组没写过的行为。无需额外权限系统。

加载后的玩家规则走同一个 `InteractionRegistry.register()` 入口
⇒ **自动继承既有匹配语义**（精确 > 通配的两趟匹配 ——
2026-09-13 踩坑记录见 `InteractionRegistry.findInteraction` javadoc），零新逻辑。

### 2.5 加载与三层来源（照抄，不发明第二套）

完全复用 `ActivationRuleConfig` 的结构（其本身抄自 `ContainerRuleConfig`）：

```
内置规则（模组 jar 内 JSON）→ 玩家规则（config/living_item/interaction_rules.json 差异）→ REMOVED_IDS（防复活）
```

- **增量语义**：玩家文件只存差异，不回写内置副本（升级不丢新内置规则）
- `load()` 幂等，reload 反映磁盘现状
- 显式 UTF-8

---

## 3. 指令（待建，随实施一起做）

最小集（**两条均待建**，随实施一起注册）：`/livingitem interaction reload`（重载玩家 JSON）+ `/livingitem interaction list`（列出全部生效规则，标注来源：内置 / 玩家）。
**不做 add/remove** —— 交互规则是四元组，指令拼参数比改 JSON 更繁琐，
与活化规则（二元组，指令顺手）不同。若实施时发现需要再补。

---

## 4. 测试计划

照抄 `ActivationRuleConfigTest` 的口径（14 项），覆盖：
三层优先级 / 增量语义 / reload 幂等 / 坏值与未知字段 WARN 跳过 / tag 匹配
（tag 路径照例 `@Disabled` —— 单测环境不加载 item tags）/ 谓词 ID 未知时丢弃 /
`action` 引用未注册 handler 时丢弃。

---

## 5. 不做的事

| 不做 | 理由 |
|---|---|
| handler 数据化（玩家写逻辑） | handler 是代码 —— 让 JSON 执行任意逻辑等于开放 RCE；且 §2.4 已足够 |
| 世界交互（右键方块）规则化 | 是另一套体系（`use` 事件链），与本 GUI 交互表无关；等真实需求 |
| 通配符 / 正则 target | tag 已覆盖「一类物品」；通配是想象中的需求 |
| 与活化规则共用一个 JSON 文件 | 两个体系字段结构完全不同，合一个文件徒增嵌套 |

---

## 6. 待决问题（拍板前不动代码）

| # | 问题 | 选项 | 倾向 |
|---|---|---|---|
| **Q-D2-1** | 现有 18 条 Java 规则要不要**整体迁移**成内置 JSON？ | ① 全迁（红石 13 条用 `#minecraft:buttons` 压成 1 条）② Java 保留、玩家规则走 JSON（两来源并存） | ✅ **已定：①**（2026-09-28 用户拍板）。论证两轮：先倾向① → 被「②保留编译期检查、开发摩擦更小」说服 → **核实 `ContainerRuleConfig` 的内置规则本就是 jar 内 JSON 资源**（`assets/living_item/container_rules.json`，line 34/142）后回到① —— 否则本体系将成为三套配置里**唯一内置不走 JSON 的异类**，模式一致性本身就是单一真相。风险（内置 JSON 写错物品 ID）的三层兜底：加载期 WARN+丢弃、**启动测试断言每条内置规则有效**（把编译期检查平移成测试期检查，须落实进 §4）、文档系统（复算命令 + 本稿 + 将来的 addon-guide）。用户补充的拍板理由：项目有完善的文档系统，JSON 不需要怕。内置 JSON 同时是整合包作者的**格式示范**（照抄即可），这是 Java 形态给不了的教学价值。止损条件：若实施后发现改内置规则频繁出错（测试兜不住），重新评估。迁移时逐条对照行为不变 |
| **Q-D2-2** | `target` / `trigger` 支不支持 `namespace` 通配？ | 支持 / 只做 item+tag | **只做 item+tag**（第一版）—— target 必须是活物品（§2.1），「按模组批量接动作」的场景存疑；要时再加，与 D1 的兼容成本为零 |
| **Q-D2-3** | 谓词注册表放哪？ | ① `interaction/` 包独立类 ② 挂在 `InteractionRegistry` 上 | **①** —— Registry 已有两个职责，别再塞 |
| **Q-D2-4** | 玩家规则命中优先级：低于内置，还是同等？ | ① 同表混排（继承精确>通配）② 玩家必压内置 | **①** —— 走同一个 ENTRIES 列表天然如此；② 会破坏「精确 > 通配」这条**已踩过坑**的语义（2026-09-13） |

---

## 7. 复算命令

```bash
rg -c "new InteractionEntry\(" src/main/java        # 源码 18 处（farmland 循环展开后运行时更多）
rg -c "registerHandler\(\"" src/main/java           # 9 个 actionId
rg -n "triggerFilter" src/main/java/com/qiqi/li     # 谓词仅 Tillables::canTillWith 1 处
rg -n "两趟|通配" src/main/java/com/qiqi/li/living/interaction/InteractionRegistry.java
python tools/doc_check.py
```
