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
| `ContainerLivingItemHandler` | 登记 | `CONTAINER_DATA` `LIVING_SLOT_ITEMS` `PENDING_RUNTIME_INVALIDATIONS` `POS_TO_CACHE_KEY` |
| `ContainerMenuInfoRequestPacket` | 登记 | `LAST_REQUEST` |
| `ContainerRuntimeCache` | 登记 | `dirtyContainers` `store` |
| `FailureThrottle` | ⚠️ 本类无清理入口 | `FAILURES` |
| `FluidFlowServerSync` | 登记 | `CLIENT_ACTIVE` |
| `ItemFormLiveViews` | 登记 | `VIEWS` |
| `LearnedLayouts` | ⚠️ 本类无清理入口 | `AMBIGUOUS` `LEARNED` |
| `LivingItemManager` | ⚠️ 本类无清理入口 | `APPLICABLE_CACHE` |
| `LivingMapEventHandler` | 自带停服事件 | `lastSentMetadata` |
| `LivingToolFakePlayerCache` | 登记 | `CACHE` |
| `LivingToolHostSync` | 登记 | `current` |
| `LivingToolRecorder` | 自带停服事件 | `LAST_RECORD_TICK` |
| `MapUpdateSkipHelper` | ⚠️ 本类无清理入口 | `cooldowns` `lastUpdateTick` |
| `StaticCacheRegistry` | 登记 / 自带登出事件 | `CLIENT` `SERVER` |
| `StructureMapDecorator` | ⚠️ 本类无清理入口 | `SCANNED_CHUNKS` |

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
| J2 | `POS_TO_CACHE_KEY` 只在 `entry(ctx)` 里建 ⇒ **只装活物品、没有容器级数据的容器没有位置索引** | `indexPositions` 的唯一调用点在 `entry(ctx)` 内 | 以为「玩家破坏方块一定能清」；实际破坏路径覆盖不到这类容器 ⇒ 同物品原地交换会继承旧进度（R-06 缺口 1） |
| J3 | `LIVING_SLOT_ITEMS` 与 `ContainerRuntimeCache` 是**瞬态、可重建、可随时丢弃**的；要落盘的数据**不得**放这里 | `D-container-20` · `ContainerRuntimeCache` 类头「生命周期契约」 | 又去给这两张表设计持久化 / 长 TTL / 存档格式（该方案已被明确否决过一次，代价是整套 codec） |
| J4 | `ContainerRuntimeCache.removeSlot` 删空内层槽位后**不删外层键** | `ContainerRuntimeCache#removeSlot`（无外层删除分支） | 以为作废即回收；空壳键会累积（R-06 缺口 3） |
| J5 | `ItemFormLiveViews#VIEWS` 是**惰性过期**：条目只在 `find()` 命中过期时才删 | `ItemFormLiveViews#find`（先查 `TTL_MS` 再 `remove`） | 以为「每 tick 重建 ⇒ 不泄漏」；实际背包停 tick 且再无人按该 UUID 取货时条目常驻到停服，并强引用第三方 `IItemHandler`（R-06 缺口 4） |
| J6 | **卸载事件里不得查世界 / 能力，但可以直接改 tick 线程的表**（主线程） | `D-container-22` · 推导链见 [event-threading.md](event-threading.md) §2 | 两个方向都错过：① 把它当异步线程，去写「只入队 + drain」的无谓改造（已作废一次）；② 在卸载事件里查能力 ⇒ 再入触发区块加载 |
| J7 | 跨维度广播表（`BeaconPlayerHostSync` / `BeaconChestHost` / `LivingToolHostSync`）的当 tick 快照**只能在 tick 头 `beginTick` 清，不能在逐维度 flush 里清** | flush 是 per-level 调用（`LivingItem#onServerTick` 各 flush 点）· `BroadcastDedup.java:41`「⚠️ 不是本维度的：用本维度会误删其它维度玩家的记录」 | 在第二个维度的 flush 里清空 ⇒ 第一个维度刚算出的记录被抹掉 ⇒ 联机玩家看不到对方 |
| J8 | `LearnedLayouts` **落盘**（`config/living_item/…json`） | 该类 load/save 路径 | 以为清内存就清掉了学习结果；测试要调 `clearForTest`，否则单例污染下一个用例 |
| J9 | 名单区显示「⚠️ 本类无清理入口」的**才算缺口**，目前三处：`FailureThrottle#FAILURES`、`MapUpdateSkipHelper#cooldowns` / `#lastUpdateTick` | 名单区「清理入口」列（机器推）+ 本类文件内无 `ServerStoppedEvent` 处理器 | 单机退出存档再进另一存档时旧记录仍在；`lastUpdateTick` 更实际：**tick 计数跨存档重置** ⇒ `current - last < 20` 会把新存档的地图更新误跳过，直到新 tick 追上旧值 |
| J10 | 清理钩子必须登记在**缓存类自己的文件**里（`StaticCacheRegistry.onServerStop` 出现在该类 static 块） | `StaticCacheRegistry` 类头约定 · 名单区「清理入口」列之所以能机器推，靠的就是这条约定 | 登记写在别处 ⇒ 名单把它判成「无清理入口」，读该类的人也看不到「会不会被清」；新表漏登记的失败模式正是这套机制要防的（`LivingToolPlayerSync` 一例见 §3） |
| J11 | `LivingItemManager#APPLICABLE_CACHE`、`LearnedLayouts#LEARNED/AMBIGUOUS` 显示「⚠️ 本类无清理入口」但**不是缺口** | 前者按 `Item` 类型有界且启动后基本只读（`LivingItemManager#APPLICABLE_CACHE` 声明处）；后者**故意落盘**（`config/living_item/…json`，见 J8） | 误当泄漏去加停服清理 ⇒ 反而把跨存档的学习结果清掉，或为一个不增长的缓存白加一层清理 |

---

## 3. 已判过、不是问题（防重复调查）

> 这一节的存在理由：读源码会产出**看着像 bug** 的结论（2026-11-01 的 A/B 实验里，
> 禁读文档组就报了一处）。写下来，下一个读源码的人就不必再调查一遍。

- **`LivingToolPlayerSync#lastSent` 有 `clear()` 但没有任何调用方** —— 不是漏登记：
  它每 tick 经 `BroadcastDedup.merge(lastSent, next, online)` 重建，`merge` 里
  `merged.keySet().retainAll(online)` 已淘汰离线玩家（`BroadcastDedup.java:43-47`）。
  ⇒ 无残留，不需要停服清理。
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
