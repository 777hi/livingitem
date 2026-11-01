# 服务端共享状态（参考）

> **性质**：横切视图 —— 回答「有哪些跨 tick 的 static 状态」（§1 名单，机器生成）和
> 「知道哪一条会让我少犯一次错」（§2 判断清单，手写带判据）。
> **形态（2026-11-01 重划）**：原先是「成员 × 内容 × 线程 × 建立 × 清理 × 持久化 × 备注」七列手写表格；
> 除成员名外每列都是对源码的复述，会被删 —— 理由与口径见
> [docs/README.md](../README.md)「该不该写：一条判据」。
> 🔴 **读法**：§2 每条后面都给了判据出处；**需要判据之外的细节就去读源码**。
> 这张表的存在不是让你停止验证的理由。
> **关联**：[event-threading.md](event-threading.md) ·
> [container-data-lifecycle.md](container-data-lifecycle.md) ·
> [living-item-infrastructure.md](../system-design/living-item-infrastructure.md) §3.5。

---

## 1. 名单与论域（生成区）

<!-- rosters:begin（本区由 tools/gen_rosters.py 从源码扫描生成；名单变化 = 改代码后重跑本脚本，勿手改本区） -->
| 类 | 清理入口（本类文件内可见的事实） | 跨 tick 可变 static 集合 |
|---|---|---|
| `BeaconChestHost` | 登记 | `activeLids` `current` `lidLastTick` |
| `BeaconPlayerHostSync` | 登记 | `current` |
| `ContainerChunkCache` | 登记 | `INSTANCE` |
| `ContainerLivingItemHandler` | 登记 | `CONTAINER_DATA` `LAST_SEEN` `LIVING_SLOT_ITEMS` `PENDING_RUNTIME_INVALIDATIONS` `POS_TO_CACHE_KEY` |
| `ContainerMenuInfoRequestPacket` | 登记 | `LAST_REQUEST` |
| `ContainerRuntimeCache` | 登记 | `dirtyContainers` `store` |
| `FailureThrottle` | 登记 | `FAILURES` |
| `FluidFlowServerSync` | 登记 | `CLIENT_ACTIVE` |
| `ItemFormLiveViews` | 登记 | `VIEWS` |
| `LearnedLayouts` | ⚠️ 本类无清理入口 | `AMBIGUOUS` `LEARNED` |
| `LivingItemManager` | ⚠️ 本类无清理入口 | `APPLICABLE_CACHE` |
| `LivingMapEventHandler` | 自带停服事件 | `lastSentMetadata` |
| `LivingToolFakePlayerCache` | 登记 | `CACHE` |
| `LivingToolHostSync` | 登记 | `current` |
| `LivingToolRecorder` | 自带停服事件 | `LAST_RECORD_TICK` |
| `MapUpdateSkipHelper` | 登记 | `cooldowns` `lastUpdateTick` |
| `StaticCacheRegistry` | 登记 / 自带登出事件 | `CLIENT` `SERVER` |
| `StructureMapDecorator` | ⚠️ 本类无清理入口 | `SCANNED_CHUNKS` |
| `TransferTargetPolicies` | ⚠️ 本类无清理入口 | `POLICIES` |

**论域**（本表覆盖范围的完整定义，超出论域的问题本表**不回答**）：

- 扫描根目录：`src/main/java/com/qiqi/li/{living,network}/`。
- 扫描形态：`static final` 的 `Map` / `Set` / `List` / `Queue` / `Deque` / `ConcurrentHashMap` / 名字以 `Cache` 结尾的类型；初始化式含 `Map.of` / `List.of` / `Set.of` / `Collections.unmodifiable*` / `Codec` / `StreamCodec` / `TypeToken` 的直接跳过。
- 按**类**排除（配置 / 启动期注册表 / 反射缓存 / 纯客户端 / 诊断，共 32 个，**这些类里的集合一律不收录**）：`ActivationRuleConfig`、`AvaritiaMenuResolver`、`BeaconHostClientCache`、`BeaconPlayerHostClientCache`、`ContainerCompatibilityConfig`、`ContainerContexts`、`ContainerDataKey`、`ContainerFluidData`、`ContainerHandoff`、`ContainerLikeItems`、`ContainerMonitor`、`ContainerRuleConfig`、`ContainerSnapshot`、`ContainerTickHooks`、`CropClassifier`、`EnderChannelClientCache`、`FluidFlowBehaviors`、`FluidTransformTable`、`InteractionPredicates`、`InteractionRegistry`、`InteractionRuleConfig`、`IrisBeamTagging`、`ItemFormContainers`、`LivingMapClientCache`、`LivingToolHostClientCache`、`LivingToolPlayerClientCache`、`MenuSlotOwners`、`PerfMetrics`、`RuntimeSegmentRegistry`、`SlotAccessorFactory`、`SlotInteractions`、`SophisticatedMenuResolver`。
- 按**字段**排除（共 1 个）：`LivingItemManager#FUNCTIONS`。
- **「清理入口」列只有三种取值，且只描述本类文件内看得见的事实**：`登记` = 走了 `StaticCacheRegistry` 统一分发；`自带停服事件` = 自己 `@SubscribeEvent` 处理 `ServerStoppedEvent`（效果相同，只是没走分发）；`⚠️ 本类无清理入口` = 本类文件里两种都看不见 —— **这一列不是「有没有泄漏」的结论**，泄漏判断见下方判断清单（那里带判据）。
- ⚠️ 因此「哪些表没有停服清理」这类全称问句，答案只在**上表 + 上列排除集**内成立；排除集里的类不受本表约束（它们的清理判据见下方判断清单）。
<!-- rosters:end -->

---

## 2. 判断清单（每条必须给判据；没有判据的判断不写在这里）

> 只收「**不知道就会做错事**」的条目。能从类自己文件里一眼读出来的，不写。

| # | 判断 | 判据（出处） | 不知道会做错什么 |
|---|---|---|---|
| J1 | `CONTAINER_DATA` 的 TTL 节拍是**每 1200 次 `processContext`**，不是每 N tick、也不是按墙钟 | `ContainerLivingItemHandler.java:90`（`CLEANUP_INTERVAL = 1200`）· `:624`（每次容器处理计数）· `:690-693` | 按「每 5 分钟扫一次」估算回收延迟会错：容器越多、扫得越勤（10k 容器约每 7 秒一轮，单容器存档可能几小时一轮） |
| J2 | 位置索引有**两个**建立点：`entry(ctx)`（容器级数据首建）与 `trackLivingSlots`（追踪条目首现，2026-11-01） ⇒ 只装活物品的容器玩家破坏也能清 | `ContainerLivingItemHandler#trackLivingSlots`（`previous == null` 分支）· 判据测试 `ContainerCacheSweepTest.livingOnlyContainer_...` | 修复前只装活物品、没有流体/红石/电力数据的容器**永远没索引** ⇒ 破坏也清不掉，同物品原地交换会继承旧进度。**残留限制**：位置只在「首现」时刷新 ⇒ 被活塞搬走的双箱可能留旧索引（与修复前同源，未新增风险） |
| J3 | `LIVING_SLOT_ITEMS` 与 `ContainerRuntimeCache` 是**瞬态、可重建、可随时丢弃**的；要落盘的数据**不得**放这里 | `D-container-20` · `ContainerRuntimeCache` 类头「生命周期契约」 | 又去给这两张表设计持久化 / 长 TTL / 存档格式（该方案已被明确否决过一次，代价是整套 codec） |
| J4 | `removeSlot` 删空内层后**连外层键一起删**（2026-11-01）；⚠️ 要断言这件事只能用 `holdsContainer(key)`，**不能用 `getSnapshot`** | `ContainerRuntimeCache#removeSlot`（空 ⇒ `store.remove`）· 判据测试 `ContainerCacheSweepTest.removeSlot_lastSlot_deletesOuterKey` | `getSnapshot` 对「无键」和「有键但空壳」都返回空 map ⇒ 用它断言等于没断言（这正是当初空壳能活下来的原因：没人看得见） |
| J5 | `ItemFormLiveViews#VIEWS` 有**两条**回收路：`find()` 命中过期的惰性删 + 容器层 sweep 的 `evictExpired(nowMs)` 主动删（2026-11-01） | `ItemFormLiveViews#find` / `#evictExpired` · 判据测试 `ItemFormLiveViewsTest.evictExpired_removesStaleEntriesWithoutAnyQuery` | 以为惰性过期够用 ⇒ 背包停 tick 且再无人按该 UUID 取货时条目常驻到停服，并强引用第三方 `IItemHandler`（没人查询就没人回收的「惰性回收」陷阱） |
| J6 | **卸载事件里不得查世界 / 能力，但可以直接改 tick 线程的表**（主线程） | `D-container-22` · 推导链见 [event-threading.md](event-threading.md) §2 | 两个方向都错过：① 把它当异步线程，去写「只入队 + drain」的无谓改造（已作废一次）；② 在卸载事件里查能力 ⇒ 再入触发区块加载 |
| J6 | **卸载事件里不得查世界 / 能力，但可以直接改 tick 线程的表**（主线程） | `D-container-22` · 推导链见 [event-threading.md](event-threading.md) §2 | 两个方向都错过：① 把它当异步线程，去写「只入队 + drain」的无谓改造（已作废一次）；② 在卸载事件里查能力 ⇒ 再入触发区块加载 |
| J7 | 跨维度广播表（`BeaconPlayerHostSync` / `BeaconChestHost` / `LivingToolHostSync`）的当 tick 快照**只能在 tick 头 `beginTick` 清，不能在逐维度 flush 里清** | flush 是 per-level 调用（`LivingItem#onServerTick` 各 flush 点）· `BroadcastDedup.java:41`「⚠️ 不是本维度的：用本维度会误删其它维度玩家的记录」 | 在第二个维度的 flush 里清空 ⇒ 第一个维度刚算出的记录被抹掉 ⇒ 联机玩家看不到对方 |
| J8 | `LearnedLayouts` **落盘**（`config/living_item/…json`） | 该类 load/save 路径 | 以为清内存就清掉了学习结果；测试要调 `clearForTest`，否则单例污染下一个用例 |
| J9 | 「哪些表没有停服清理」目前的答案是 **0 项**：`FailureThrottle` 与 `MapUpdateSkipHelper` 已在 2026-11-01 补登记；名单区剩下的三个 ⚠️ 都不是缺口（见 §3） | 两个类的 `static { StaticCacheRegistry.onServerStop(...) }` · 判据测试 `FailureThrottleTest.serverStopCleanup_isRegistered` / `MapUpdateSkipHelperTest.serverStopCleanup_isRegistered` | 换存档时的**漏报**与**永久跳过刷新**（不是内存问题）：旧存档的失败 key 会吃掉新存档的首条完整栈；`getTickCount()` 从 0 重来，旧 `cooldowns` / `lastUpdateTick` 让 `currentTick - lastTick < 20` 恒真 ⇒ 新存档里活地图刷新被永久跳过且无报错 |
| J10 | 清理钩子必须登记在**缓存类自己的文件**里（`StaticCacheRegistry.onServerStop` 出现在该类 static 块） | `StaticCacheRegistry` 类头约定 · 名单区「清理入口」列之所以能机器推，靠的就是这条约定 | 登记写在别处 ⇒ 名单把它判成「无清理入口」，读该类的人也看不到「会不会被清」；新表漏登记的失败模式正是这套机制要防的（`LivingToolPlayerSync` 一例见 §3） |
| J11 | `LivingItemManager#APPLICABLE_CACHE`、`LearnedLayouts#LEARNED/AMBIGUOUS` 显示「⚠️ 本类无清理入口」但**不是缺口** | 前者按 `Item` 类型有界且启动后基本只读（`LivingItemManager#APPLICABLE_CACHE` 声明处）；后者**故意落盘**（`config/living_item/…json`，见 J8） | 误当泄漏去加停服清理 ⇒ 反而把跨存档的学习结果清掉，或为一个不增长的缓存白加一层清理 |
| J12 | `LAST_SEEN` 的**盖章点是 `trackLivingSlots`**（每个进 `processContext` 的容器都盖，含已无活物品的）；回收口是 `sweepUnseenContainers`，判据 = 「消失满 5 分钟」 | `ContainerLivingItemHandler#trackLivingSlots` 首行盖章 · `#sweepUnseenContainers` · `LivingItem#onServerTick` 的调用点 · 判据测试 `ContainerCacheSweepTest` | ① 把盖章挪进「有活物品」分支 ⇒ 空容器被误判成消失，走两步就丢进度；② 回到逐形态挂六种事件（区块卸载 / 下线 / 实体移除 / 销毁 / 拾取 / 非玩家移除）⇒ 六条路径必漏其一，而那正是原病根。**「没再被看到」是六种成因的共同结果**，一个判据全覆盖 |

---

## 3. 已判过、不是问题（防重复调查）

> 这一节的存在理由：读源码会产出**看着像 bug** 的结论（2026-11-01 的 A/B 实验里，
> 禁读文档组就报了一处）。写下来，下一个读源码的人就不必再调查一遍。

- **`LivingToolPlayerSync#lastSent` 有 `clear()` 但没有任何调用方** —— 不是漏登记：
  它每 tick 经 `BroadcastDedup.merge(lastSent, next, online)` 重建，`merge` 里
  `merged.keySet().retainAll(online)` 已淘汰离线玩家（`BroadcastDedup.java:43-47`）。
  ⇒ 无残留，不需要停服清理。
- **`StructureMapDecorator#SCANNED_CHUNKS` 显示「⚠️ 本类无清理入口」** —— 实际有清：
  `LivingMapEventHandler.onServerStopped` 调 `StructureMapDecorator.reset()`，而 `reset()` 里
  `SCANNED_CHUNKS.clear()`（两个文件都在 `domain/map/`）。
  ⇒ **这是 J10 的盲区样本**：登记写在别的类，机器推出来的「清理入口」列就看不见它。
  读到 ⚠️ 先 grep 一次类名 + `reset`/`clear`，再决定要不要动手（本条即为此留档）。
- **「`ChunkEvent.Unload` 与 `flushPendingRescans` 存在跨线程竞态」** —— 不成立，
  Unload 在服务器主线程（`D-container-22`）。它曾是本表的一条「缺口」，2026-11-01 撤除。

---

## 4. 本表不回答

| 想问的 | 去哪 |
|---|---|
| 某个处理器在哪个线程、能碰什么 | [event-threading.md](event-threading.md) §0（三条硬规则）+ §2（判据链） |
| 某种**容器形态**的数据什么时候建 / 什么时候不清 | [container-data-lifecycle.md](container-data-lifecycle.md) §2 |
| 为什么是这四张表、为什么不放 DataComponent | 同上 §1（设计理由，不可从代码重推） |
| 配置 / 规则表 / 启动期注册表 / 客户端缓存 | 本表论域之外（名单区「按类排除」列），直接读对应类 |
