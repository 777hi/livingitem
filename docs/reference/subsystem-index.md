# 子系统索引（快照 · 完整版）

> ⚠️ **这是快照，以各子系统文档为准。** 本表原在 `AGENTS.md`「子系统索引」，
> 2026-10-06 **降级**到此（入口只留族级路由 + 子系统直链）。
>
> **新会话不必加载本文件** —— 只有需要「某子系统到底做什么」的**细节**时才读。
> 入口的路由已保证能直达每篇文档。

| 子系统 | 概述 | 详细文档 |
|--------|------|----------|
| **活TNT** | 引信倒计时 + 爆炸，威力随数量缩放，三模式（普通/大当量/超级爆炸）；破坏按区块分帧 + 待炸账本 | [living-tnt-tech.md](../tech/living-tnt-tech.md) |
| **活流体**（原「活水桶」） | 容器级流体引擎（`ContainerFluidData`：派生源/蔓延/晋升/挤没/转化/推动，`LivingFluidFunction` 自维持驱动）+ 行为分档 + 转化表；**活桶**（桶家族 × 活标记）是系统的激活器/载体（类比红电的活铜块们），子系统关系见 §1；**活熔岩** = 接入的第二条会流动流体（引擎零改动）：焚毁/源诞生 + 前沿反应·刷石机，见 §3.7 | [docs/tech/living-fluid-tech.md](../tech/living-fluid-tech.md) |
| **活熔炉** | 配方匹配 + 燃料消耗 + 方向槽位配置 | [living-furnace-tech.md](../tech/living-furnace-tech.md) |
| **活漏斗** | TransferPipeline 统一传输 + 黑白名单 + 跨容器 + WASD 配置 | [living-hopper-tech.md](../tech/living-hopper-tech.md) |
| **活箱子** | 堆叠倍增 + UUID 映射 + LRU 缓存 + 磁盘持久化 | [living-chest-tech.md](../tech/living-chest-tech.md) |
| **活末影箱** | 路由模式（共享黑板）+ 直连模式（绑定玩家末影箱） | [living-ender-chest-tech.md](../tech/living-ender-chest-tech.md) |
| **活水车** | 力矩计算 + 应力叠加/抵消 + Create 软依赖 | [living-water-wheel-tech.md](../tech/living-water-wheel-tech.md) |
| **活地图传送** | 三种场景 + UV 精确传送 + 跨维度 + 载具 + Sable 飞艇 | [living-map-ender-pearl-tech.md](../tech/living-map-ender-pearl-tech.md) |
| **活耕地** | GUI 交互获取/种植/骨粉 + **放置回世界模拟右键种植** + 世界轴节拍生长 + round-robin 逐项产出 + 双槽渲染 | [living-farmland-tech.md](../tech/living-farmland-tech.md) §3.5 / §8 |
| **活工具**（镐/斧/铲/锄） | **记忆玩家操作行为**（左键挖掘 / 右键交互；**活斧子还含攻击记忆 —— 三类记忆齐全**）→ 以宿主为原点沿射线回放；FakePlayer 模拟完整操作 + 逐格扫描黑名单 | [living-tool-tech.md](../tech/living-tool-tech.md)（设计池见 [living-tool-design.md](../buffer/living-tool-design.md) §3.12） |
| **活武器**（剑/斧/重锤） | **不实现攻击逻辑，只「代玩家出手」**：攻击记忆（射线）+ `fake.attack()` 走原版管线；攻击缩放 per-weapon 注入（受控 tick 驱动）。**仅近战** | [living-weapon-tech.md](../tech/living-weapon-tech.md)（设计池见 [living-weapon-design.md](../buffer/living-weapon-design.md)，含蓄力型设计稿指引） |
| **活红石** | 红石信号传播 + BFS 算法 + 反相器 + 堆叠数影响 | [living-redstone-tech.md](../tech/living-redstone-tech.md) |
| **活涂蜡铜块（红电发电）** | 双因子感应发电 + 事件驱动记账 + RE/FE 单位制 | [living-power-tech.md](../tech/living-power-tech.md) |
| **活打火石** | 交互触发器，无 tick 逻辑 | [living-flint-and-steel-tech.md](../tech/living-flint-and-steel-tech.md) |
| **GUI交互** | 声明式规则 + 统一拦截 + 创造模式兼容 | [gui-interaction-system.md](../system-design/gui-interaction-system.md) |
| **图标系统** | 三层架构 + 声明式配置 + 上下文切换 | [icon-system.md](../system-design/icon-system.md) |
| **Tooltip 系统** | 双层渲染架构 + 运行时缓存同步 + 显示口径（窗口均值/mFE） | [tooltip-system.md](../system-design/tooltip-system.md) |
| **红电架构演进** | SensorPort 感知端口 + 事件流/元件接口化路线（信号层⇄电力层解耦） | [redstone-evolution-roadmap.md](../buffer/redstone-evolution-roadmap.md) |
| **红电不变量测试** | 35 条可执行不变量（七组） + 四层测试方案（属性测试/场景生成/蜕变/运行时监控） | [power-invariants.md](../system-design/power-invariants.md) |
| **超大堆叠审计** | 模组容器堆叠上限 > 64 场景下全部活物品的表现评级（202 处调用点） | [oversized-stack-audit.md](../system-design/oversized-stack-audit.md) |
| **基础设施** | 容器抽象 + 发现缓存 + SlotAccessor + 性能监控 | [living-item-infrastructure.md](../system-design/living-item-infrastructure.md) |
| **多方块容器身份解析** | 边界带「槽位↔容器」解析共享内核（`ContainerContexts`）+ 大箱 `CompoundContainer` 匹配不变量 + 两套槽位体系探针 | [container-identity.md](../system-design/container-identity.md) |
| **数据模型** | DataComponent 体系 + 新旧架构对比 + 设计决策 | [data-model.md](../system-design/data-model.md) |
| **单元测试** | FML 测试环境配置 + 测试替身 + 可测性边界 | [unit-testing.md](../guides/unit-testing.md) |
| **活TNT测试说明** | **分两区**：群友版（`T-01`~`T-13`，肉眼观察引爆现象，含**铁箱子触发的超级爆炸**）+ 作者自测（`A-01`~`A-12`，需日志/TPS/跑图） | [living-tnt-testing.md](../guides/living-tnt-testing.md) |
| **框架重构** | HasDirection + HasContainerData 接口化设计 | [framework-refactoring.md](../archive/framework-refactoring.md) |
| **框架层对标（未定案）** | 🆚 对标 AnvilCraft + Cataclysm 的框架层：可学 5 条（Mixin 外移 / 注册期事件 / reload 钩子 / 确定性排序 / nullness）+ 待拍板 1 条（规则 JSON 迁 `data/`）+ 明确不抄清单。**动架构改向前先读它** | [framework-benchmark.md](../buffer/framework-benchmark.md) |
