<!-- markdownlint-disable -->

# 指令清单（导航）

> 📄 **本文只做导航**：回答「有哪些指令、各自负责什么、完整用法去哪看」。
> **命令名与语法以代码为准** —— 三个命令类：`LivingItemContainerCommand` /
> `LivingItemActivationCommand` / `ContainerMonitorCommand`，各挂各的子树
> （由 `doc_check` 第 8 项校验本文引用是否真实存在）。
>
> 2026-09-27 整理：`/living_monitor` 已迁入 `/livingitem debug`，**对外只保留一个命令根**。

唯一命令根：**`/livingitem`**（权限 ≥2）

```
/livingitem
├── container                      # 容器大小兼容规则
│   ├── register / inspect
│   ├── list / remove
│   └── reload / export
├── activation                     # 活化规则
│   ├── reload / list / test
│   ├── deny   <目标> [via]        # 禁止
│   ├── allow  <目标> [via]        # 放行
│   └── remove <目标>              # 删除规则
└── debug                          # 开发者排障
    ├── monitor on|off|status
    ├── dump_inventory
    └── cache
```

| 子树 | 负责什么 | 完整用法 |
|---|---|---|
| `container` | 校准 / 管理第三方容器的槽位布局（含导出给模组作者合并进内置资源） | [`guides/container-compatibility.md`](../guides/container-compatibility.md) |
| `activation` | 控制哪些物品可被活化（禁玩家自己活化、放行任务奖励等途径） | [`buffer/activation-rule-design.md`](../buffer/activation-rule-design.md) |
| `debug` | 容器监控开关、背包转储、区块缓存统计（排障工具） | [`guides/container-monitor.md`](../guides/container-monitor.md) |

## activation 的「目标」语法（三种颗粒度）

| 写法 | 含义 | 例子 |
|---|---|---|
| `minecraft:chest` | 单个物品 | `deny minecraft:chest` |
| `#minecraft:swords` | 物品标签（**跨模组**） | `deny #minecraft:swords` |
| `somemod` | 整个命名空间（某模组的全部物品） | `deny-mod somemod` |
| **不带参数** | **手持物品**（主手 → 副手） | `deny` |

> 不带参数的写法不接受 `via`（无参数可带），一律取 `via` 缺省值（仅 `player`）；
> 要精细控制途径就用完整的 `deny <target> <via>`。

> ⚠️ **已知缺口**：以上动词都只能设置「**活化**」动作 —— **没有任何指令能设置
> 「取消活化」的规则**（只能手写 JSON 的 `deactivate` 字段）。
> 详见 [`activation-rule-design.md`](../buffer/activation-rule-design.md) §11.1。

**Tab 补全全部由平台提供**：`target` 用原版 `ResourceOrTagKeyArgument`（列举物品 ID 与 `#标签`），
`*-mod` 用 NeoForge `ModIdArgument`（列举已加载模组）。

> ⚠️ **不要用 `StringArgumentType` 接目标**（2026-09-28 修掉的 bug）：
> 它走 `StringReader.readUnquotedString()`，允许字符集**不含** `:` `#` `@` ——
> `minecraft:chest` 只解析出 `minecraft`、`#minecraft:swords` 与 `@somemod` 解析成**空串**，
> 随后 Brigadier 抛「Expected whitespace to end one argument, but found trailing data」，命令执行不到。
> 更隐蔽的是手写 `.suggests(...)` 补全**不经过** Brigadier 解析，
> 于是表现为「Tab 能列出候选、回车却注册失败」——补全在骗人。
> 同理 `@` 在原版是目标选择器保留前缀（`@a`/`@p`/…），语义上也不该复用。

### 匹配优先级：特异性优先

```
item（1 个物品，最精确）> tag（一类）> namespace（整个模组，最粗）
同等精确保持先到先得（数组顺序）
```

⇒ **后加的精确 `allow` 能覆盖先前的宽泛 `deny`，不用关心顺序**。

> ⚠️ **但 `via` 必须对齐** —— 这是最容易踩的坑：
> `via` 省略 = **仅 `player`**。若 `deny` 覆盖了 `external`（任务奖励）而 `allow` 没带，
> 则任务奖励途径**根本不会命中那条 `allow`**。
> 在 `default: allow` 下看不出问题（落回默认仍是放行），
> 但在 **`default: deny`（白名单模式）下会静默变成拒绝**。
> ⇒ 要放行任务奖励，必须显式写 `allow <target> external`。
（若按纯顺序匹配，指令追加的规则永远排在后面，「禁了 #swords 再单独放行一把剑」就表达不出来。）

## 约定

- **语言文件**：`container` / `activation` 子树走 `command.livingitem.*`（zh_cn + en_us）；
  `debug` 子树输出**刻意保持英文硬编码**（排障工具，输出同时进日志）—— 不是待本地化遗漏。
- **权限**：全部 `hasPermission(2)`。曾考虑给 debug 子树单独提权限，因会锁死单人调试而暂缓。
- **新增指令**：按领域放进对应命令类并挂到 `/livingitem` 的对应子树，
  **不新建第二命令根**（`/living_monitor` 的教训）。
- **文档侧**：新增 / 改名指令后，本文只更新导航项，语法细节留在对应技术文档，
  然后跑 `python tools/doc_check.py`（第 8 项会校验命令是否真实存在）。
