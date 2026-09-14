## 核对结论

我上一条回复的内容，文档大部分已有：规则语义 + 设计意图（living-hopper-tech.md §2.4.2）、链式双层继承 + 穿透递归 + 防环 + 隔离边界 + mode 保留（§2.4.6，含新旧架构对比）、优先级田忌赛马（§2.4.3）、回写与不落盘（我们前几轮已补）。

**唯一缺口：快照的新鲜度机制**——「黑白名单如何保持与容器现状同步」（跨 tick 缓存按修订计数复用、内容签名兜底失效、组件变更显式 bump）。漏斗文档 §2.4.2 只有一句「下一 tick 自愈」带过，基础设施文档 §8.6 只写到 visited 防环，缓存与失效完全没讲。

## 改动（纯文档，两处）

1. **[living-item-infrastructure.md](docs/system-design/living-item-infrastructure.md) §8.6** 末尾补「快照缓存与失效（修订计数）」小节：
   - `TickContext.getSnapshot()` 懒构建 + `getCachedSnapshot` 按修订计数跨 tick 复用
   - 两条 bump 路径：内容签名兜底（`syncContentRevision`，逐槽「物品 id + 数量」，任何物品变动打破缓存）+ 组件变更显式 bump（`syncSlotToClients`/`setItem`）
   - 结论：过滤链/连接图始终反映容器现状；漏斗搬去新容器首 tick 重建自愈
2. **[living-hopper-tech.md](docs/tech/living-hopper-tech.md) §2.4.2** 在自愈句后补一句机制指向：快照按修订计数跨 tick 缓存、失效即重建，详见基础设施文档 §8.6

## 验证

纯文档改动，无需跑测试。