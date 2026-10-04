# 待办池（TODO）

> **中间层**：暂缓但不想丢的事项，按触发时机分组。做掉就删，不定案、不沉淀。
> 流体侧的机制类挂起项以 [idea.md](../idea.md) 任务队列为准，此处不重复。

---

## A. static 缓存生命周期（2026-10-04 跨存档残留事故的后续）

> 背景：单机「退存档 → 进另一存档」不重启 JVM，static 字段跨存档存活；
> 流体侧两个缓存（`CLIENT_ACTIVE` / `FluidFlowClientCache`）因此漏了清理挂钩，
> 引发「渲染跨存档残留 + 源复活」两连（changelog 2026-10-04）。当前存量已审干净，
> 这两条是防复发的机制化动作。

### A1. 成文规则：新增 static 缓存 ⇒ 必须挂清理

- **做什么**：在「排查铁律」（AGENTS.md）或 `living-item-infrastructure.md` 加一条——
  新增任何 static 缓存，必须同时挂 `ServerStopped`（服务端）/
  `ClientPlayerNetworkEvent.LoggingOut`（客户端）清理；「记得做」变成「有检查点」。
- **出处**：`CLIENT_ACTIVE` 加缓存时忘了挂钩（现行犯），同类教训 Q3「测试也是调用者」
  也是「靠记性」失败。
- **触发时机**：下一次新增 static 缓存之前。

### A2. 缓存清理注册表收口（框架侧，治本）

- **做什么**：把散落 `LivingItem.onServerStopped` 与各客户端角落的清理调用收编成
  一张「缓存清理注册表」——`ServerStopped` / `LoggingOut` 遍历执行，新缓存登记一行
  即自动覆盖。与 Q6「收编」同哲学：把「记得做」变成「结构保证做了」。
- **底稿**：`onServerStopped` 现有清单（EnderChannel / ChunkCache / ContainerData /
  ExplosionLedger / FakePlayer / ToolHostSync / FluidSync 边沿集）就是注册表的第一批条目。
- **触发时机**：建议与 A1 一起，或在第 3 个漏挂事故前（两次已是现行）。

---

## B. 其它挂起项（指针，勿在此展开）

- 末影箱汲/倒：等框架侧 `ContainerContexts` 末影箱分支（`idea.md` §〇 F3 行）
- 岩浆/模组流体接入 + 跨流体交互策略拍板（`idea.md` §〇 队列 + 第 5 条）
- 满活桶对世界放水（应复用原版 `emptyContents`）+ 满桶对世界无反馈 UX（`idea.md` §〇.7）
- 游戏实测清单：漏斗自动化活锁、多流体同屏渲染、创造模式汲/倒（`idea.md` §〇 F4 行）
