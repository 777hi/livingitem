# 活化规则配置指南

> **受众**：配置者（服务器主 / 整合包作者 / 想自定义的单机玩家）—— 不写一行 Java，
> 通过 JSON 控制「哪些物品可以被活化」。技术设计与论证见
> `docs/archive/activation-rule-design.md`（已归档）。

## 文件位置

| 文件 | 作用 |
|---|---|
| `assets/living_item/activation_rules.json`（模组 jar 内） | 内置规则 —— **不要改**，升级会被覆盖 |
| `config/living_item/activation_rules.json` | **你的规则**（只写差异，没改过就不存在此文件，手写即可） |

改完执行 `/livingitem activation reload` 立即生效（无需重启）。

## 活化的途径（via）—— 最重要的概念

「活化」有多个发起途径，规则只约束你指定途径：

| via | 含义 | 典型场景 |
|---|---|---|
| `player` | 玩家自己动手活化（活按钮 / 活化指令） | 黑名单管的就是这条 |
| `external` | 外部给予（任务奖励 / 命令 / 掉落） | **配置者自己的发放渠道，通常要放行** |
| `internal` | 模组内部产出（活锄头耕地等） | 模组功能，别碰 |

> ⚠️ **via 省略 = 仅 `player`**。
> 在 `default: deny`（白名单模式）下，`allow` 若不带 `via`，
> 任务奖励途径**不会命中**这条放行 —— 静默变拒绝。
> 要放行任务奖励必须显式写 `allow <target> external`。

## JSON 格式

```json
{
  "version": 1,
  "default": "allow",
  "rules": [
    { "item": "minecraft:bedrock",  "activate": "deny",  "via": ["player"] },
    { "tag": "#minecraft:swords",   "activate": "deny" },
    { "namespace": "cheatymod",     "activate": "deny" },
    { "item": "minecraft:chest",    "activate": "allow" }
  ],
  "options": {
    "denyUnclaimed": false
  }
}
```

| 字段 | 含义 |
|---|---|
| `default` | 无规则命中时的默认值：`allow`（黑名单模式）/ `deny`（白名单模式） |
| `rules[].item` | 单个物品 ID（`minecraft:chest`） |
| `rules[].tag` | 物品标签（`#minecraft:swords`，跨模组） |
| `rules[].namespace` | 整个 modid（禁掉某模组的全部物品） |
| `rules[].activate` | 对**活化**动作的处理：`allow` / `deny`（省略 = 取 `default`） |
| `rules[].deactivate` | 对**取消活化**动作的处理，与 `activate` 互相独立 |
| `rules[].via` | 作用于哪些途径：`player` / `external` / `internal`；省略 = 仅 `player` |
| `options.denyUnclaimed` | 开启后，「没有任何活物品功能认领的物品」也会被拒绝活化；**默认 `false`**（默认行为不可动 —— 显式开启才生效） |

### 匹配优先级：特异性优先

```
item（单个物品，最精确）> tag（一类）> namespace（整个模组，最粗）
同等精确时保持先到先得
```

⇒ **后加的精确 `allow` 能覆盖先前的宽泛 `deny`，不用关心书写顺序**。

## 指令

```
/livingitem activation reload            # 重载 JSON
/livingitem activation list              # 列出当前生效规则
/livingitem activation test              # 判定【手持】物品能否被活化（按途径分行显示）
/livingitem activation deny <目标>       # 禁止活化（<目标> = 物品ID / #标签）
/livingitem activation allow <目标>      # 放行（覆盖 deny）
/livingitem activation remove <目标>     # 移除规则
/livingitem activation deny-mod <modid>  # 禁止某模组全部物品（allow-mod / remove-mod 同理）
```

> ⭐ **不带参数 = 对手持物品生效**：拿着物品敲 `deny` 比手打 ID 方便。
> 指令增删的规则会**写回你的 JSON 文件**，与手改文件等效。
> `test` 改完配置后**必跑** —— 它按途径分行显示判定结果，不用猜。

## 已知限制

| 限制 | 说明 |
|---|---|
| 指令无法设置「取消活化」规则 | 只能手写 JSON 的 `deactivate` 字段；且「取消活化」机制本身尚未实现 |
| `deny` 不会清理已活化物品 | 只阻止未来活化（保护玩家已有进度） |
| 按钮不变灰 | 被拒时游戏内会提示原因，细节用 `test` 查 |
