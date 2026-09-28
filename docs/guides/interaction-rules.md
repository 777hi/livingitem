# 交互规则配置指南

> **受众**：配置者 —— 不写一行 Java，通过 JSON 新增/覆盖活物品的 GUI 交互规则。
> 技术设计与论证见 `docs/archive/interaction-rule-design.md`（已归档）。

## ⚠️ 先弄清这是什么

交互规则约束的是**容器界面内的操作**：玩家手拿「光标物品」、悬停在「槽位物品」上点击时，
触发的动作。**槽位物品必须是活物品**。

它**不是**「世界里右键方块触发什么」—— 那是另一套体系，本规则管不到。

## 文件位置

| 文件 | 作用 |
|---|---|
| `assets/living_item/interaction_rules.json`（模组 jar 内） | 内置规则（13 条）—— **也是格式示范，照抄即可** |
| `config/living_item/interaction_rules.json` | **你的规则**（只写差异；没写过就不存在，手写即可） |

改完执行 `/livingitem interaction reload` 生效；`/livingitem interaction list`
列出当前全部生效规则（标注内置 / 玩家来源）。

## JSON 格式

```json
{
  "version": 1,
  "rules": [
    { "id": "my_ignite",  "target": "minecraft:tnt",  "trigger": "minecraft:blaze_powder",
      "button": "right", "action": "ignite" },
    { "id": "my_till",    "target": "minecraft:dirt", "button": "right",
      "action": "till_to_farmland", "predicate": "can_till" }
  ],
  "removed": ["tnt_ignite"]
}
```

| 字段 | 必填 | 含义 |
|---|---|---|
| `id` | ✅ | 规则的唯一名字。与内置规则同名 = **覆盖**它；写进顶层 `removed` = 删除它 |
| `target` | ✅ | 槽位物品（必须是活物品）：物品 ID 或 `#tag` |
| `trigger` | ❌ | 光标物品：物品 ID 或 `#tag`；**省略 = 任意光标** |
| `button` | ✅ | `left` / `right` / `middle` |
| `action` | ✅ | 动作 ID —— **只能引用模组已有的动作**（见下表），不能创造新行为 |
| `onRelease` | ❌ | `true` = 松开鼠标时触发（默认按下触发） |
| `predicate` | ❌ | 附加过滤条件（`can_till` = 光标须是锄头、`can_plant` = 光标须是可种植种子） |

**匹配规则**：精确触发的条目优先于通配（`trigger` 省略的）条目 ——
「骨粉催熟」不会被「任意光标种植」抢走，与配置顺序无关。

**内置的全部动作**（`action` 可选值，共 9 个）：
`ignite`（点燃）· `ignite_carried`（反向点燃）· `button_press`（按钮按压）·
`lever_toggle`（拉杆）· `repeater_cycle`（中继器调档）· `comparator_toggle`（比较器切换）·
`till_to_farmland`（耕地）· `plant_crop`（种植）· `bonemeal`（催熟）

> ⭐ **安全边界**：`action` 只能引用上面这些已有动作 —— 你能把已有行为接到新的
> 物品组合上，**不能**让活物品做出模组没写过的行为。

## 写错的后果（都会 WARN 到日志，不会崩游戏）

| 错误 | 后果 |
|---|---|
| 物品 ID 拼错 / 不存在 | 该条规则被跳过 |
| `action` 拼错 | 该条规则被跳过 |
| `predicate` 引用未注册的 ID | 该条规则被跳过 |
| 字段名拼错（如 `buttton`） | 该字段被忽略（日志有 WARN） |
| **tag 拼错**（如 `#minecraft:button`） | ⚠️ **加载期发现不了** —— 规则生效但永不匹配；tag 名以原版 `data/minecraft/tags/item/` 为准 |

## 增量语义

你的文件只写差异：追加新规则（新 `id`）、覆盖内置（同 `id`）、删除内置（`removed` 列表）。
删掉你的文件 = 恢复内置规则。模组升级不会冲掉你的配置。
