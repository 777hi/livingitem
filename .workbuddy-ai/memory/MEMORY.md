# 项目长期记忆（Living Item Mod）

## 纹理约定
- 涂蜡铜灯图标 = 未涂蜡对应图标 + 外圈黄色边框。边框 60 像素，颜色 (232,160,62,255)，四种锈蚀等级（copper/exposed/weathered/oxidized）边框掩码完全一致。发光版（lit）同理：内部取未涂蜡发光图、外圈填黄框。
- item 纹理均为 16×16 PNG（含 P 调色板与 RGBA 两种，均有效）。

## 环境
- Python 隔离 venv：`C:/Users/AI-777hi/.workbuddy-ai/binaries/python/envs/default`（已装 Pillow 12.3.0），处理图片用其 `Scripts/python.exe`。

## 配方书材料表（stackedContents）注入约定
- `RecipeBookComponent` 重建 `stackedContents` 只有两条路径：`initVisuals()`（开界面/切可见性）和
  `updateStackedContents()`（背包变动/点槽位）。**改这个 Mixin 时两条都要注入**，漏 `initVisuals`
  就会出现"刚打开界面活箱子材料不识别，做点什么才恢复"。
- 其余 `updateCollections` 调用点（切标签/搜索/筛选/recipesUpdated）只复用、不重建，无需注入。
- **内容变化后的刷新靠原版链路，不要再加轮询/指纹/缓存机制**（2026-09-05 加过又被用户要求移除）：
  服务端改 CONTAINER → `AbstractContainerMenu.triggerSlotListeners` 用 `ItemStack.matches` 判定
  → 发包 → 客户端 `Inventory.setItem` → `timesChanged++` → 客户端 tick 走路径 B。1–3 tick，比任何
  轮询都快。前提已验证：`ItemStackMixin` 只改了 `isSameItemSameComponents` 和 `getTooltipImage`，
  **没动 `matches`**。
- **保持无状态是这个 Mixin 正确的原因** —— 需要引入状态字段的优化方案，先质疑它是否真有必要。

## 电力层架构（v3 铜块网络）
- `tickContainerData` 按 (TopoKey, rep, channelIdx) 组件遍历：同氧化级连通块内多台发电机共享同一张边集，每组件仅锚点（最小铜块槽位 rep）跑一次 `runBfs`，其余 `ChannelState.copyFrom` 锚点通道，`accountEnergy` 仍逐机调用（各自 pref）。
- **关键不变量**：`ChannelState.onPhaseEvent(event, pref)` 中 pref 完全不被使用——域只按 `event.period()` 分桶，pref 仅在读时 `bestFactor/bestDomain(pref)` 选域。故同网络内多机共享同一 ChannelState 实例安全。
- `ChannelState.copyFrom` 为深拷贝（domains 逐 PhaseDomain 复制 offsets/lastEventTick），须保持深拷贝——改 ChannelState 字段时同步改 copyFrom。
- 跨 tick 持久化：`SimpleContainerContext` 把 powerData/redstoneData 存进 `ContainerLivingItemHandler` 静态缓存（按 containerKey）；`FakeContainerContext` 不持久化，驱动多 tick 逻辑须用 SimpleContainerContext。
- 测试 seam：`ContainerRedstoneData.setEdgeForTest/setPrevEdgeForTest` 供电力层单测注入边信号驱动 `tickContainerData`（绕过完整传播）。
