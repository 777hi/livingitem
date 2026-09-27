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
│   ├── reload / list
│   └── test
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

## 约定

- **语言文件**：`container` / `activation` 子树走 `command.livingitem.*`（zh_cn + en_us）；
  `debug` 子树输出**刻意保持英文硬编码**（排障工具，输出同时进日志）—— 不是待本地化遗漏。
- **权限**：全部 `hasPermission(2)`。曾考虑给 debug 子树单独提权限，因会锁死单人调试而暂缓。
- **新增指令**：按领域放进对应命令类并挂到 `/livingitem` 的对应子树，
  **不新建第二命令根**（`/living_monitor` 的教训）。
- **文档侧**：新增 / 改名指令后，本文只更新导航项，语法细节留在对应技术文档，
  然后跑 `python tools/doc_check.py`（第 8 项会校验命令是否真实存在）。
