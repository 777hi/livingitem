<!-- markdownlint-disable -->

# 指令清单（快照）

> **快照文档**：会漂移，**以代码为准**（三个命令类：`LivingItemContainerCommand` /
> `LivingItemActivationCommand` / `ContainerMonitorCommand`，各挂各的子树）。
> 整理约定见文末。

唯一命令根：**`/livingitem`**（权限 ≥2）。2026-09-27 整理：`/living_monitor` 已迁入
`/livingitem debug`，对外只保留一个根。

```
/livingitem
├── container                          # 容器大小兼容规则（JSON：assets + config/living_item/）
│   ├── register <columns> [size] [id] #   校准当前看向的容器（游戏内实测后登记）
│   ├── inspect                        #   查看当前看向容器的解析参数
│   ├── list                           #   列出全部已注册规则
│   ├── remove <containerId>           #   移除一条
│   ├── reload                         #   从配置文件重新加载
│   └── export                         #   导出全量生效规则（发给模组作者合并内置）
├── activation                         # 活化规则（JSON：assets + config/living_item/）
│   ├── reload                         #   改完 JSON 生效
│   ├── list                           #   概况（条数 / default / denyUnclaimed）
│   └── test                           #   判定手持物品，按 player/external/internal 分行显示
└── debug                              # 开发者排障（输出刻意英文，不走语言文件）
    ├── monitor on|off|status          #   容器监控开关（检测复制/丢失，日志入 logs/）
    ├── dump_inventory                 #   打印自身背包（标记 [L]=活物品）
    └── cache                          #   各维度区块缓存 / loaded 区块统计
```

## 约定

- **语言文件**：`container` / `activation` 子树走 `command.livingitem.*`（zh_cn + en_us）；
  `debug` 子树输出**刻意保持英文硬编码**（排障工具，输出同时进日志）—— 不是待本地化遗漏。
- **权限**：全部 `hasPermission(2)`。曾考虑给 debug 子树单独提权限，因会锁死单人调试而暂缓。
- **新增指令**：按领域放进对应命令类并挂到 `/livingitem` 对应子树；
  不新建第二命令根（`/living_monitor` 的教训）。
