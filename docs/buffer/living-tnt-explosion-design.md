# 活TNT爆炸规模化设计（living tnt explosion scale design）

*创建: 2026-10-13 · 状态: **未定案** —— 量化分析已完成 · 主设计已定稿（惰性谓词 + 纪元水印）·
**待拍板 3 项见 §7** · 两项外部依赖已读源码核实（§7.1 水印 API / §7.2 W1 非墙）· 代码未动*

---

## 0. 为什么会有这份文档

缘起（2026-10-13）：用户提问「100 亿个活 TNT 的爆炸威力和性能影响」，前提是**可以容纳无限物品的容器**。
逐层量化后发现：**堆叠上限 21 亿（`Integer.MAX_VALUE`）的多槽容器是真实存在的**（抽屉类存储模组），
「塞满活 TNT」不是幻想场景，而是这类整合包里的极限工况。本文记录：

1. **现状量化**——爆炸链上的四道墙 + 一个今天就中的真 bug（求和溢出吞物品），全部给出可复算推导；
2. **定稿设计**——把爆炸从「事件 + 记账」降级为「法则 + 惰性求值」，使服务端成本**与 TNT 数量 N 无关**；
3. 被否选项及理由、待拍板项、测试守卫、文档跟进清单、未来场景预演。

> ✅ 两处外部依赖均已读源码核实：水印落地方式见 §7.1（NeoForge 官方 chunk 附件 API）；
> W1 的实体查询成本见 §7.2（**非墙**——原版是「X 列 + 有序集区间查询」，本文此前的立方级预估是错的）。

## 1. 现状量化（全部可复算）

### 1.1 公式链（`ExplosionComponent` / `ExplosionParams`）

```
半径        r = baseRadius × √N        baseRadius = 4.0
chunkRadius = ⌈r / 16⌉                （区块 = 16×16 方块）
side        = 2 × chunkRadius + 1      （受影响区块是方形网格）
chunkCount  = side²                    （位图长度 = 方格网区块总数）
圆内区块    ≈ π × chunkRadius²         （affects() 判据 = 区块 AABB ∩ 球，只判水平）
破坏方块    = 圆内区块 × 98,304        （SUPER 整区块清空；区块 = 16×16×384）
```

关键标度（大 N 时）：**chunkCount ≈ N/4，圆内区块 ≈ 0.196N，位图 ≈ N/32 字节**——
破坏链上每个结构都是 **O(N)**，这是全部问题的根。

### 1.2 四道墙（按点火流程顺序）

| # | 墙 | 位置 | 标度 | 触发量级 |
|---|---|---|---|---|
| W1 | 实体伤害巨型 AABB | `ExplosionComponent.applyExplosionDamage`：`level.getEntities(null, center ± r)` | **O(√N × log S)**（已源码核实 = **非墙**，§7.2） | 100 亿 ≈ 毫秒级；荒谬级（N=10¹³）≈ 0.3 s 卡顿 |
| W2 | pendingChecks 突发注入 | `ExplosionLedger.schedule` 第二循环把圆内**每一个**区块塞进待检查队列 | 常驻 ≈ 0.196N 个 `ChunkPos`（~24 B + 哈希表） | 10⁸ ≈ 1 GB；10¹⁰ ≈ 47 GB 对象 + 表 ⇒ **OOM** |
| W3 | schedule 双循环 | `ExplosionLedger.schedule` 两遍 `chunkCount`，每 bit 分配一个 `ChunkPos` | ≈ N/2 次迭代 + 分配 | 10¹⁰ = 50 亿次 / ~120 GB 垃圾 / 1–2 分钟主线程冻结 |
| W4 | int 溢出 | `ExplosionParams.chunkCount = side²`（int 平方） | — | N > **8,588,840,976** ⇒ 回绕成负 ⇒ `new long[负]` ⇒ NegativeArraySizeException |

W2/W3 都发生在 W4 **之前或之中**（W1 经源码核实不是墙，§7.2）⇒ 修 W4 只是拆掉最后一道警铃（见 §4「只改 long」）。

### 1.3 W4 的精确阈值（复算）

```
side² ≤ 2³¹−1 = 2,147,483,647
  ⟺ side ≤ 46,340        （46,340² = 2,147,395,600 ✓；46,341² = 2,147,488,281 超）
  ⟺ chunkRadius ≤ 23,169
  ⟺ r ≤ 370,704 格
  ⟺ 4√N ≤ 370,704
  ⟺ N ≤ 92,676² = 8,588,840,976   （85.89 亿）
```

**安全值 8,588,840,976 与崩溃值 8,588,840,977 只差 1 个 TNT**（+1 ⇒ side = 46,341 ⇒
chunkCount = 2,147,488,281 回绕为 −2,147,479,015）。若溢出不存在，该档位图 = 312.5 MB/条
（还要进 SavedData 落盘），schedule 冻结 1–2 分钟——**溢出在这件事上是一道意外的保险丝**：
fail-fast 秒崩比资源耗尽死得干净。

### 1.4 求和溢出：今天就中的真 bug（🔴 优先修）

`ExplosionComponent.ignite`（`154:158`）：

```java
int totalTntCount = 0;
for (...) { totalTntCount += stack.getCount();      // 单堆叠上限 2,147,483,647
           containerCtx.setItem(i, ItemStack.EMPTY); }   // 物品先被清空
if (totalTntCount <= 0) return false;               // 回绕成负 ⇒ 直接返回
```

**容器内活 TNT 总和 > 2,147,483,647 即回绕**（两槽满栈 = 4,294,967,294 就够）：

- **偶数个满栈槽** ⇒ 回绕成小负数 ⇒ 早退 ⇒ **物品已在同一循环清空 ⇒ TNT 凭空消失、零爆炸、零日志**；
- **奇数个满栈槽** ⇒ 回绕成 ≈2³¹ 的正数 ⇒ 通过 ⇒ 半径 ≈ 185,364 格（185 km）的巨型爆炸照常发动
  （随后死于 W2/W3；注意此时半径是**回绕值**算出的，比真实 N 应有的半径小得多）。

这是 `oversized-stack-audit.md` 🔴「物品凭空消失」家族的**第二例**（第一例 = 活熔炉燃料消耗，已修），
并推翻该审计「正常模组容器（≤几万）不会触发」的前提——**两个满栈格就是日常**。

### 1.5 真实量级（N = 槽数 × 2,147,483,647）

| 容器 | N | 半径 | 圆内区块 | 破坏方块 | 类比 |
|---|---|---|---|---|---|
| 54 槽满栈 | 1.16×10¹¹ | 1,362 km | 227.7 亿 | 2.24×10¹⁵ | 边长 131 km 的立方体 |
| 200 槽满栈 | 4.29×10¹¹ | 2,621 km | 843 亿 | 8.29×10¹⁵ | 边长 202 km 的立方体；≈ 世界边界内总区块的 0.6% |
| （参照）100 亿 | 10¹⁰ | 400 km | 19.6 亿 | 1.93×10¹⁴ | 边长 57.8 km 的立方体 |

结论：**此量级没有任何就地补丁能救**——每个 O(N) 结构同时死亡。唯一能满足「极限场景」的设计
是把全部 O(N) 结构拆掉，使成本与 N 无关（§2）。

## 2. 定稿设计（主路径）

### 2.0 核心原则

**「世界只在被观测的地方演化」推到极致——连「哪些区块要炸」都不记账。**
爆炸从「事件 + 记账」降级为「法则 + 惰性求值」：

```
条目   = 纯参数（圆心 / 半径 / 模式 / epoch），~100 B，O(1)
破坏   = (条目参数, 区块内容) 的纯函数，在区块「被观测」时求值
once   = 每区块 1 个 long 水印（存区块 NBT），与条目数无关
```

同一套代码流畅处理 64 个和 4×10¹¹ 个 TNT——每 tick 成本、每区块状态、条目内存全部与 N 无关。

### 2.1 五个改动（定稿值）

| # | 改动 | 拆哪面墙 | 要点 |
|---|---|---|---|
| D1 | **SUPER 条目无状态化（去位图）** | W2/W3/W4 | 位图索引（`chunkCount`/`bitIndex`/`chunkAt`）整体退出 SUPER 路径 ⇒ int 溢出**结构性不可达**；long 化退化为防御纵深（§4） |
| D2 | **已加载区块登记表**（load/unload 钩子维护，每维度一张） | W2 | SUPER 的待办 = 登记表 ∩ 球内，**有界于已加载区块数（几百）**；「未加载即丢弃」从此安全（重载必重触发）⇒ `ExplosionLedger` javadoc 里「丢弃 vs carryOver」的纠结整个消失 |
| D3 | **实体伤害只查已加载区块（可选优化）** | （W1 已核实非墙 ⇒ 降级） | 语义等价：实体只存在于已加载 section；改为遍历登记表 ∩ 球体 + 逐区块小盒查询，伤害/击退公式不动。原版实现本身已毫秒级（§7.2）⇒ **不阻塞主设计，可延后** |
| D4 | **schedule() 从 O(chunkCount) 降到 O(1) + O(已加载)** | W3 | 删掉两遍 21 亿循环与每 bit 的 `ChunkPos` 分配；建档 tick 亚毫秒 |
| D5 | **纪元水印管 once** | （语义正确性） | 每区块 1 个 long：`W(区块) = 该区块吃到的最大 epoch`；加载/清扫时 `affects 且 epoch > W ⇒ 应用，全部应用完最后写 W`。存储 = **NeoForge 官方 chunk 数据附件**（已核实，§7.1）⇒ 无需 mixin |

**位图族保留不动**：NORMAL/HIGH_YIELD（`SUPER_EXPLOSION_THRESHOLD = 3456` 天然把 chunkCount
压在 ≤961 ⇒ `long[16]`、int 安全、自动收敛、逐字节可复现）。模式阈值就是表示法的分界线。

### 2.2 纪元水印与多重爆炸（定稿值）

```
epoch   = SavedData 里的持久单调计数器，每次 schedule +1
          （⚠️ 不用 gameTime：/time set 倒流会让水印永远挡住新条目）
W(区块) = 该区块 NBT 里的 1 个 long，0 = 无水印（旧存档 / 外部工具改过）
触发判据（区块加载 与 schedule 清扫已加载区，两条路同一判据）：
    对每个存活条目 E：affects(E, chunk) 且 E.epoch > W(chunk) ⇒ 应用
    全部应用完，最后写 W = 本趟应用过的最大 epoch
```

**为什么多重爆炸天然成立**（三条性质）：

1. SUPER/HIGH_YIELD 无掉落、无 RNG、逐区块幂等（`vanillaDrops` 仅对 NORMAL 有意义）⇒
   终态 = 各命中条目破坏集合的**并**，与应用顺序无关；
2. 水印只挡「重放」，不挡「新爆炸」——新条目 epoch 必大于所有存量；
3. W 只与**旧值**比较、循环结束后才写 ⇒ 乱序迭代也不会「小的后爆挡住大的先爆」。

**场景矩阵**（全部推演过）：

| 场景 | 结果 |
|---|---|
| 两发 SUPER 同心大小重叠 / 部分重叠 | 各清各自圆内；重叠区清一次（第二次 no-op）✔ |
| 同一坑二次引爆（玩家重建后再炸） | 新 epoch > 旧水印 ⇒ 重新清空 ✔（新爆炸是新事件） |
| SUPER × 位图族（NORMAL/HIGH_YIELD） | 位图族自走 `isDone` 不读水印；SUPER 先清 ⇒ 它们后来应用 = 空气上雕花、零掉落 ⇒ 无害 ✔ |
| 同 tick 两容器同时归零 | 两个条目（epoch 连续）；同 epoch 也无害（并集同态）✔ |
| 重启 / 退出重进 | epoch 与条目在 SavedData，水印在区块 NBT ⇒ 断点续炸 ✔ |
| 旧区块无水印 | 按未处理清一次（可接受；alpha 阶段不做旧数据兼容，铁律 6）✔ |
| 条目被 MAX_ENTRIES 逐出 | 只被它覆盖的未探索区永远不炸（复用现有降级语义 + WARN）；**不会错炸** ✔ |

### 2.3 崩溃窗口（写反=永久漏炸）

**先 apply 后写 W**：崩溃 ⇒ 重载时重清一次（幂等无害）。
先写后清 ⇒ 水印在但区块没清 ⇒ 该区块永远漏炸。

### 2.4 每区块 / 每条目状态盘点

| 结构 | 现状 | 定稿后 |
|---|---|---|
| 条目 | 参数 + 位图（SUPER 无上界，312 MB @100 亿） | 纯参数 ~100 B |
| 区块 | 无（靠位图间接记） | 1 个 long（仅真正处理过的区块才写） |
| 待办队列 | 圆内全部区块（0.196N） | 已加载 ∩ 球内（几百） |
| 已加载视图 | 无 | 登记表（同时供 D3 实体伤害用） |

## 3. 修单顺序（按依赖排，S1 可立即独立执行）

| # | 步骤 | 依赖 | 说明 |
|---|---|---|---|
| S1 | **求和 `int totalTntCount` → long** | 无 | 今天的真 bug（§1.4）；补「两满栈槽点火 ⇒ 物品不丢 + 爆炸按 long 求和发生」测试 |
| S2 | **LoadedChunkRegistry**（load/unload 钩子，按维度；服务端停止清空） | 无 | D2 的地基 + D3 的数据源；项目已有 `StaticCacheRegistry` 登记模式与 `ExplosionLedger.onChunkLoaded` 钩子可挂 |
| S3 | **SUPER 条目无状态化 + 双触发**（D1/D4） | S2 | 位图族路径一行不动；`schedule` 改 O(1) + 扫登记表 |
| S4 | **实体伤害改登记表遍历**（D3，**可选**） | S2 | 语义等价论证见 §2.1-D3；伤害公式逐字不动。W1 核实为非墙 ⇒ 本步骤可延后或不做 |
| S5 | **水印落地**（D5） | S3 | NeoForge chunk 数据附件（官方 API 已核实，§7.1）：`DeferredRegister(NeoForgeRegistries.ATTACHMENT_TYPES)` 注册带 `Codec.LONG` 序列化的 `AttachmentType<Long>`；**读取用 `getExistingDataOrNull`**（无副作用，不给未处理区块附着默认值）、写入用 `setData`（自动 `setUnsaved`） |
| S6 | **long/断言防御纵深** | S3 | 位图族 ≤961 天然安全；加断言防「将来把 HIGH_YIELD 并进位图族还不设限」 |

## 4. 被否掉的选项（含理由）

| 选项 | 否的理由 |
|---|---|
| **只把 int 改 long** | 只拆 W4，且把「秒崩」变成「先 W3 冻结 1–2 分钟、再 W2 OOM」——溢出是保险丝；对 < 8.59×10⁹ 区间的体验**零改善**（W2/W3 先响，W1 已核实非墙）；漏改 `load()` 的 `int bit` 循环还会把崩溃从点火时推迟成**下次开服时** AIOOBE；`new long[...]` 长度强转又是一处静默截断点 |
| 位图改 Roaring 压缩 | 治内存不治冻结（schedule 仍是 N/2 循环、pendingChecks 仍是 0.196N）；引入新依赖 + SavedData 序列化复杂度 |
| 硬截断半径（超限拒绝/截断） | 违反规则 4「威力 ∝ 堆叠数」契约；不解决中当量的 O(N) 循环。只能当 fail-safe，不能当设计 |
| LOD 分层破坏（远处粗炸） | 违反 2026-09-22 立的不变量「同一场爆炸，无论区块什么时候加载，结果一致」——项目刚为这个打过仗 |
| 条目限时过期 | 留下两个缺口：过期后未探索区永远不炸 + 过期前仍是重放窗口。零新机制但语义残 |
| structure_void 哨兵（自造水印） | 一个哨兵记不住多个爆炸 ⇒ 重叠爆炸互相遮挡（后到的 SUPER 被前一爆炸哨兵挡掉）；技术方块污染 palette；Creative 可挖掉。**官方附件 API 已确认（§7.1）后此备选已无必要** |
| 异步/多线程破坏 | 原版世界编辑主线程独占，核心链路不适用 |

## 5. 测试守卫

**单测可守**：

- 求和：两满栈槽 ⇒ 点火后物品不丢、爆炸按 long 求和发生；奇数满栈槽不再早退（S1）
- 无状态 SUPER：条目不含位图（结构性断言）；schedule 成本不随 N 增长（大 N 建档在毫秒级）
- 水印规则：炸后改建 ⇒ 卸载 ⇒ 重载 ⇒ **改建保留**；未探索区首次加载即清；重叠爆炸
  （大包小 / 小后爆 / 同坑二爆）终态 = 并集；epoch 跨重启持久化
- 登记表：加载即触发、卸载安全丢弃、重载重触发（「丢弃安全」的新不变量）
- 崩溃窗口：apply 后写 W 的顺序（可用 applier 抛异常模拟崩溃 ⇒ 重放幂等）

**游戏内验证**（同 `ExplosionComponentLightTest` 的口径）：

- （S4 落地时）巨型半径下实体伤害只碰已加载区块；伤害数值不变（贴脸 7r+1）
- 21 亿堆叠抽屉整箱点火：点火 tick 不掉 TPS、破坏前线随探索推进
- 跨维度 / 重启续炸 / 多爆重叠的目视一致性

**既有用例**：`ExplosionParamsTest` / `ExplosionLedgerTest` 的位图语义用例**全部保留**
（NORMAL/HIGH_YIELD 路径不动）——它们守的正是「≤961 位图族」。

## 6. 文档跟进清单

- [ ] `living-tnt-tech.md` §4.3 重写「待炸账本」一节（位图族 / 水印族分界 + epoch 规则）；
      §0 能力清单与规则表届时补「威力 ∝ 堆叠数（N 无上界）」的边界说明
- [ ] `living-item-infrastructure.md` §3.2.2 补「惰性谓词 + 纪元水印」的推导（现文档只推到分帧账本）
- [ ] `oversized-stack-audit.md` 新增活 TNT 求和溢出条目（🔴），修订「≤几万不会触发」前提
- [ ] `ExplosionLedger.MAX_ENTRIES` 字段注释（「每条最大约 130 字节位图」的前提已变）
- [ ] changelog + AGENTS 进展条目（**落地时**补，现在不补）
- [ ] TODO.md「活TNT」节（已随本文建立）

## 7. 待拍板（3 项）

1. **MAX_ENTRIES 新上限**：条目缩到 ~100 B 后，256 是否上调（如 4096 = 400 KB）？
2. **「整块已空则跳过」守卫**：区块重载重放（位图族与 SUPER 都有此浪费）要不要做廉价守卫。
3. **嵌套爆炸跳过优化**：同圆心、半径更大、epoch 更新的条目已应用 ⇒ 跳过旧条目（纯省 CPU）。

### 7.1 已核实：水印落地方式 = NeoForge 官方 chunk 数据附件 API（2026-10-13）

**结论：不需要 mixin。** NeoForge 21.1.249 的 `ChunkAccess` 直接实现 `IAttachmentHolder`
（`libs/src/neoforge-21.1.249-merged/net/minecraft/world/level/chunk/ChunkAccess.java:60`），
附件是**区块实例上的字段**（同文件 `:495`），序列化已接进原版 `ChunkSerializer`：

| 事实 | 证据（可复算） |
|---|---|
| chunk 即附件 Holder（`hasData` / `getData` / `getExistingDataOrNull` / `setData` / `removeData`） | `ChunkAccess.java:494-530` |
| 写 NBT：键 `neoforge:attachments`；无序列化附件时返回 null ⇒ **未处理区块零增量** | `AttachmentHolder.java:27`（键名）/ `:122-144`（serializeAttachments）/ `ChunkSerializer.java:401`（写入点） |
| 读 NBT：区块加载时反序列化 ⇒ **`ChunkEvent.Load` 触发时水印已可读** | `ChunkSerializer.java:214-215` → `ChunkAccess.readAttachmentsFromNBT`（`:550`） |
| `setData` 自动 `setUnsaved(true)` ⇒ 写水印即脏标记，无需手工维护 | `ChunkAccess.java:518-523` |
| ⚠️ `getData` 首次访问会**附着默认值** ⇒ 检查路径必须用 `getExistingDataOrNull`（无副作用） | `AttachmentHolder.java:74-93` |
| 只有带 serializer 的附件才持久化 ⇒ 注册时必须给 `Codec.LONG` | `AttachmentHolder.java:130`（`type.serializer != null` 才写） |
| 彩蛋：ProtoChunk→LevelChunk 晋升时附件被拷贝（生成期数据不丢） | `LevelChunk.java:142`（`copyChunkAttachmentsOnPromotion`） |

**落地写法（S5）**：

```java
// 注册（同项目 DeferredRegister 惯例）
public static final Supplier<AttachmentType<Long>> TNT_EPOCH = ATTACHMENT_TYPES.register(
    "tnt_explosion_epoch", () -> AttachmentType.builder(() -> 0L)
        .serialize(Codec.LONG.fieldOf("epoch")).build());

// 检查（无副作用，不给未处理区块附着默认值）
Long w = chunk.getExistingDataOrNull(TNT_EPOCH.get());
if (w != null && entry.epoch() <= w) continue;   // 已吃过这场（或更新的）爆炸

// 应用后写水印（自动 setUnsaved；先 apply 后写，崩溃窗口见 §2.3）
chunk.setData(TNT_EPOCH.get(), Math.max(w != null ? w : 0L, entry.epoch()));
```

**两个附注**：① 本附件不配 sync handler ⇒ 纯服务端数据，零网络成本
（chunk 附件同步入口 `LevelChunk.java:687` 不会被触发）；② 序列化只发生在真正处理过的区块
⇒ 存档增量 = 每区块 1 个 long（`neoforge:attachments` 下，NBT 压缩后更低），与 §8 预估一致。

### 7.2 已核实：W1 不是墙 —— 原版实体查询是「X 列 + 有序集区间查询」（2026-10-13）

**结论：`level.getEntities(null, 巨型AABB)` 的成本随盒宽线性增长（O(√N × log S)），不随体积立方增长。**
此前本文预估的「≈10¹⁴ 次网格迭代、小时级冻结」**是错的**——那假设了 3D 全网格遍历。

调用链（全部已读源码，`libs/src/neoforge-21.1.249-merged/`）：

```
Level.getEntities            Level.java:903-906         （收集进 List）
  → LevelEntityGetterAdapter.get(AABB)   LevelEntityGetterAdapter.java:41-43
    → EntitySectionStorage.getEntities    EntitySectionStorage.java:114-116
      → forEachAccessibleNonEmptySection  EntitySectionStorage.java:35-64   ← 关键实现
```

```java
for (int l1 = j; l1 <= i1; l1++) {                    // 只遍历 X 列：盒宽/16 + 1 次
    LongIterator it = sectionIds.subSet(              // 有序集区间查询：只摸「已存在」的 section
        SectionPos.asLong(l1, 0, 0), SectionPos.asLong(l1, -1, -1) + 1).iterator();
    while (it.hasNext()) {
        long k2 = it.nextLong();
        if (SectionPos.y(k2) >= k && ... <= j1 && SectionPos.z(k2) >= l && ... <= k1) {
            ... consumer.accept(sections.get(k2)) ...   // 存在的 section 才被访问
        }
    }
}
```

两个关键点：

1. **`SectionPos.asLong` 把 X 放在最高位**（`SectionPos.java:210-214`：`X<<42 | Z<<20 | Y`）⇒
   `subSet` 区间 `[x<<42, (x+1)<<42)` 恰好圈出**同一 X 列的全部 section**；
2. **`sectionIds` 是已存在 section 的有序集**（`EntitySectionStorage.java:28`，`LongAVLTreeSet`）
   ⇒ 空列只付一次失败的树查找 O(log S)，不存在的 3D 网格点**完全不被访问**。

量化（S = 全维度实体 section 数，典型几百到几千，log₂S ≈ 10–14）：

| 场景 | X 列数 | 量级 |
|---|---|---|
| 100 亿（r = 400,000） | 50,001 | ~10⁶ 次基本操作 ⇒ **毫秒级** |
| 200 满栈槽（r = 2,621 km） | 327,651 | ~5×10⁶ ⇒ **几十毫秒** |
| 荒谬级（N = 2.1×10¹³，r = 18,300 km） | 2.29×10⁶ | ~3×10⁷ ⇒ **0.1–0.3 s 卡顿**（仍非冻结） |

⇒ **D3 从「拆墙」降级为「可选优化」**；流程里唯一的 √N 项在毫秒~几十毫秒量级，可接受。

⚠️ **教训留痕**：本条差点凭「AABB ⇒ section 网格」的直觉写成小时级冻结，并据此排了修单优先级——
**量级断言必须读源码**（与 §1.1「全部可复算」的要求同源）。

## 8. 未来场景预演

| 场景 | 定稿设计下的表现 |
|---|---|
| 21 亿堆叠 × 上百槽（N ~10¹¹–10¹²，半径 1,300–2,600 km） | 点火 tick O(1)；破坏前线 = 探索前线；每区块 1 long；条目 100 B |
| 几百个抽屉同时归零（连环爆） | 条目 ≤ MAX_ENTRIES，超出走降级（只炸已加载 + WARN），不错炸 |
| 跨维度 | 账本 / 登记表 / epoch 计数器按维度；水印随区块（区块本属单一维度） |
| 活红石批量点火 | 点火侧 O(槽位) 与爆炸规模彻底解耦 |
| 存档体积 | 只给真正处理过的区块 +9 B（NBT 压缩后更低）；不再有 312 MB/条的 SavedData |
| 物理下限（诚实项） | 843 亿区块只有被观测的才消失；全探完是数十 PB 区域文件——那是玩家行为的函数，不是服务器义务 |
