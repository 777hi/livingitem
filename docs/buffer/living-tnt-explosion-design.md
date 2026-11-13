# 活TNT爆炸规模化设计（living tnt explosion scale design）

> **状态**：设计稿（设计已确认，供实施与验收）—— 保留规模化论证与决策来源，不是完成报告。
>
> 用户已确认整体方案并授权实施；实现与自动化守卫已落地，游戏内性能与表现待验。
> 自动化结果以全量测试快照为准，不宣称实机通过，也不包含提交授权。
> 批准的约束见 [TNT tech §4.6](../tech/living-tnt-tech.md#46-规模化定案与验收边界d-tnt-03)
> / `D-tnt-03`。本稿暂保留论证来源，待代码与验证复核后再按文档规约分流，不能仅凭互引认定已搬完。

---

## 0. 为什么会有这份文档

缘起（2026-10-13）：用户提问「100 亿个活 TNT 的爆炸威力和性能影响」，前提是**可以容纳无限物品的容器**。
逐层量化后发现：**堆叠上限 21 亿（`Integer.MAX_VALUE`）的多槽容器是真实存在的**（抽屉类存储模组），
「塞满活 TNT」不是幻想场景，而是这类整合包里的极限工况。本文记录：

1. **改造前量化**——爆炸链上的四项风险 + 求和溢出事故，保留可复算推导；其中时间 / 对象大小仅估算，不是性能实测；
2. **批准设计**——SUPER 改为惰性覆盖谓词 + 纪元水印，拆掉覆盖面积级表示与建档工作；
3. 被否选项及理由、用户选择、验收守卫与未来场景边界。

**不承诺成本全程与 N 无关**：已加载候选、保留条目、被拒任务、实体查询和实际破坏仍会产生工作；
位图族自定义半径入口仍存在，不声称全部模式有固定总内存上界。

> ✅ 两处外部依赖均已读源码核实：水印落地方式见 §7.1（NeoForge 官方 chunk 附件 API）；
> W1 的实体查询成本见 §7.2（**非墙**——原版是「X 列 + 有序集区间查询」，本文此前的立方级预估是错的）。

## 1. 改造前量化（论证来源，不作当前实现断言）

> 本节对应全模式位图账本的旧表示法；求和溢出已修，SUPER 去位图是本轮批准的改变。
> 算术阈值可以按公式复算；耗时、对象大小与 JVM 分配量是模型估算，不能当实测或时限保证。

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

> `ExplosionComponent` 已改 long 求和 + 计数异常守卫，验证入口
> `ExplosionComponentCountTest`；本节保留原事故。规模化设计与整体实施已由用户确认，
> 不因保留旧分析而重新把 S1 或整体授权列为待决。

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

结论：只补算术类型不能消除巨型 SUPER 的面积级队列和循环；需要更换覆盖表示（§2）。
目标是让 SUPER 不再按理论覆盖网格分配 / 建档，不是让整条爆炸链或实际世界破坏成本与 N 无关。

## 2. 定稿设计（主路径）

### 2.0 核心原则

**SUPER 不记理论覆盖网格，只在已加载候选上求覆盖谓词。**

```
SUPER 条目 = 几何参数 + 持久正 epoch，无位图 / 剩余区块计数
once       = 每区块 1 个 long 水印，存 NeoForge 官方持久附件
候选       = 原版可见 holder 视图 / 自然 Load → FIFO → tick 检查 FULL 就绪
```

参数载荷不随理论覆盖面积增长；实际内存与工作仍依赖候选、存活条目和瞬态降级任务，
不能由此推出“64 TNT 与巨型 TNT 同成本”或固定 tick 时间。

### 2.1 表示法选择

| # | 改动 | 约束 |
|---|---|---|
| D1 | **SUPER 去位图** | 参数构造、日志、保存 / 读取、候选调度均不算 `side²`、不遍历范围网格 |
| D2 | **复用原版可见 holder 视图** | `ChunkMap` protected `getChunks()` 仅增加窄 AT；不新增独立 loaded 登记表，候选不等于 FULL 可用 |
| D3 | **实体查询优化不纳入本轮** | 保留伤害 / 击退与查询链；只因源码成本不是立方级，不能断言实机一定毫秒级 |
| D4 | **有预算的候选推进** | FIFO，FULL 就绪 / 尚未就绪 / 已卸载三态；每维度每 tick 32 个完成坐标整轮，不新增任意硬 backlog 上限 |
| D5 | **纪元水印防重放** | 旧水印固定一次，混合条目快照按原始登记序，整轮成功后才提交最大已应用 SUPER epoch |

**位图族保留**：NORMAL/HIGH_YIELD 仍有独立完成位图，自动收敛，不读水印。
默认 `baseRadius=4.0` **加上**模式阈值才有 ≤961 bit、16 个 long（128 B 裸载荷）的界限；
现有自定义半径 API 可超出它。本轮不把默认界限宣称为所有输入界限，不添加人为半径截断。
检查半径、中心、网格边长 / 面积、索引、区块坐标与数组字数的安全算术，非法输入在消耗前拒绝。

### 2.2 纪元水印与混合爆炸

```
epoch   = 每维度 SavedData 持久正数单调计数器，空账本不重置，耗尽拒绝而非回绕
W(chunk)= 区块官方附件 long，缺失 = 0；检查不写默认附件
before  = 本轮固定旧 W
snapshot= 混合模式条目快照，保持原始登记顺序
对 snapshot 逐项：
    NORMAL/HIGH_YIELD：以各自位图判断本区块是否未处理
    SUPER：affects(entry, chunk) 且 entry.epoch > before 才应用
所有本轮工作成功后：
    W = max(before, 本轮已应用的 SUPER epoch)
```

水印只属于 SUPER，位图族既不读也不更新它。条目次序不能被模式分组替代：
**SUPER 先清再 NORMAL 并非“无害”**，会吞掉原始登记顺序下 NORMAL 本应产出的掉落。
固定旧 W 解决的是较小 epoch 在同轮被较大 epoch 提前挡住的问题，不意味着可以乱序改世界。
同 tick 新登记 / 重入必须保留下轮候选；不能在出队时覆盖掉新工作，也不能插入当前快照。

| 场景 | 验收判据 |
|---|---|
| 同区块多发 SUPER，或 NORMAL → SUPER → HIGH_YIELD 混合 | 保持原始登记序，各自完成判据独立；全轮成功后才提交水印 |
| 同坑重建后引爆新 SUPER | 新 epoch 大于旧 W，仍应破坏重建内容 |
| 炸后重建、卸载再加载但无新爆炸 | 已处理 SUPER 被 W 挡住，不再次清除重建内容 |
| 同 tick 新爆炸 / 再入 | 新条目留给下一快照，候选不能丢失 |
| 正常保存再加载 | 参数、计数器、位图与水印保持往返语义；需守卫，不等于硬杀恢复保证 |
| 容量逐出 | 只能逐出最旧 SUPER，未完成普通位图不能被删，见 §2.5 |

### 2.3 失败与水印提交

**先 apply，整轮成功后写 W。** 异常退出不提前推进水印，但已经发生的方块修改不会自动回滚；
不能用“SUPER 清空幂等”概括 NORMAL 掉落、混合模式或跨文件崩溃恢复。持久化边界见 §2.6。

### 2.4 状态边界

| 结构 | 批准设计 |
|---|---|
| 持久条目 | NORMAL/HIGH_YIELD 参数 + 位图；SUPER 参数 + epoch；共享容量 |
| 区块水印 | 实际提交过的区块有官方 long 附件，未处理区块不因只读检查附着默认值 |
| 已加载视图 | 复用 `ChunkMap.getChunks()`，不复制成新登记表 |
| 候选队列 | FIFO 坐标，未就绪重试、卸载丢弃，后续自然加载由保留条目补触发 |
| 被拒 SUPER | 拒绝瞬间 loaded-only 瞬态任务，不持久化、不加入未来 Load、不随目标重载续炸 |

### 2.5 条目退役与容量边界（用户确认）

**SUPER 不按完成度退役，也不设时间过期**：没有覆盖完成账，不能因当前 loaded 全部处理完
就推断理论范围完成。NORMAL/HIGH_YIELD 仍按位图完成退役。

共享持久容量 **4096**，位图族满时拒绝；新 SUPER 满时只逐出**最旧 SUPER**，不删普通位图。
逐出须告警，旧条目尚未兑现的区域失去后续义务，已炸内容不回滚；不能叫“无损回收”。
这与改造前“满即拒绝新条目”不同，不能把逐出说成此前现行策略。

没有 SUPER 可逐出（全位图占满）时拒绝新 SUPER，走有预算的**瞬态 loaded-only 降级**：
只保留拒绝瞬间已加载的目标，不加入未来 Load；目标卸载后丢该目标，不因重载补炸。
与正常账本共享每维度每 tick 32 个完成坐标整轮的预算，不持久化，不枚举巨大覆盖网格，
执行完 / 失效后回收，服务端停止时清理。NORMAL/HIGH_YIELD 既有同步 loaded-only 降级不变。

不加任意硬 backlog 上限。4096 约束的是持久条目，不是全部任务、TNT 数量或服务端总内存；
实际检查成本还依赖已加载候选、保留条目与被拒任务，必须测量而非宣称固定耗时。

### 2.6 回档与持久化一致性边界（纠正原讨论）

**一致的完整回档本身不会破坏 epoch 语义**：账本、计数器、区块内容与水印一起恢复到同一
一致快照；新爆炸的 epoch 继续大于该快照里的既有 epoch。原讨论「完整回档会必然漏炸」不成立。

**风险是部分恢复或不一致快照**：只回退账本/计数器而保留较新的区块水印，会让新条目 epoch
不大于旧水印而被跳过；只恢复区块或外部重置水印，也可能令保留的旧爆炸再次作用于恢复内容。
本方案不增加回档迁移、自动重编号或外部编辑修复机制；恢复操作必须保持这些数据的一致性。

进程硬杀、部分回档和 I/O 失败都可能进入不一致保存窗口；SavedData 与区块 NBT 由不同
文件异步保存，**不构成跨文件事务**。§2.3 的先 apply 后水印是必要操作顺序，不证明崩溃原子性。
正常保存 / 读取往返预期须有测试守卫，不可外推为任意崩溃后的断点恢复保证。

## 3. 批准的实施边界与验收次序

S1 已修，不重做；整体方案已获用户确认与继续实施授权。授权不包含提交，也不代表验收完成。
首批不改威力公式、阈值、锚点、引信、掉落、伤害、形状或现有光照链；复用 `ExplosionLedger`
与 `applyToChunk`，不加新框架、登记表或扫描器。

| 批次 | 内容 | 验收约束 |
|---|---|---|
| A：表示与准入 | SUPER 参数路径去位图；位图族安全算术与消费前校验 | 构造、日志、保存 / 读取不暗算 SUPER 面积；默认与自定义半径均有守卫 |
| B：调度闭环 | 原版 protected `getChunks()` 窄 AT，FIFO / FULL 三态，epoch 附件，容量与拒绝瞬态路径 | 去位图与防重放同批；32 完成坐标预算共享，原始混合序与旧水印整轮提交，未来 Load / 重载不扩展拒绝任务 |
| C：验收 | 定向回归、全量 `--rerun`、六项门禁、正常保存往返与游戏内测量 | 不以结构断言替代实机 TPS / 光照 / 掉落验证，不宣称跨文件崩溃事务 |

完整约束正文归 [TNT tech §4.6](../tech/living-tnt-tech.md#46-规模化定案与验收边界d-tnt-03)。
队列只在 tick 内改世界，事件仅提交候选；同 tick 新登记与重入必须保留下轮工作。
实体查询重写、整块空预扫描与嵌套爆炸优化不属于本轮，性能证据不足时不顺手加机制。

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

## 5. 验收守卫（要求，不是通过报告）

**定向回归应覆盖**：long 求和与消费前拒绝；默认 / 自定义半径位图安全算术；SUPER
参数、日志、保存 / 读取不分配覆盖结构；epoch 正数持久、空账本不重置；FIFO、FULL 三态
与每维度共享 32 完成坐标预算；固定旧水印、混合原始登记序、整轮成功提交；同 tick 新登记、
重入及异常退出；4096 / 最旧 SUPER 逐出、不逐普通位图；被拒 SUPER 的拒绝瞬间 loaded-only、
不加入未来 Load、不随目标重载补炸；完成 / 失效 / 停服运行态清理；正常保存往返。

既有 `ExplosionParamsTest` / `ExplosionLedgerTest` 位图语义守卫应保留，
`ExplosionComponentCountTest` / `ExplosionComponentLightTest` 继续分别守计数与光照接线。
不把异常 applier 模拟等同于真实硬杀或跨文件 I/O 事务测试，不在文档填测试条数。

**待游戏内验证**：大堆叠点火与持续探索的真实成本、混合档掉落顺序、未就绪 FULL 补炸、
重建后卸载 / 重载不再清空、新 epoch 再炸、跨维度 / 正常重启续炸、容量降级 / 换存档清理、
光照与客户端重建。测量后才记录 TPS / 毫秒结论，不要求或承诺“点火 tick 不掉 TPS”。

## 6. 文档分流约束

批准约束已写入 TNT tech §4.6、基础设施 §3.2.2、`D-tnt-03` 与 TODO TNT 需求入口；
本稿保留旧量化 / 否决 / 官方 API 查证来源。**未完成代码与验证复核前，不宣称文档搬迁终态**；
不把互引当完成证据。验收后按 `docs/README.md` 与 buffer 索引维护判据分流，索引由协调者更新。
旧求和事故在 §1.4，不能再登记成未修任务；测试全集 / 共享状态 / 事件线程索引不在本稿手填。

## 7. 三项选择（用户已确认，随实施生效）

| 项 | 选择 | 约束与理由 |
|---|---|---|
| MAX_ENTRIES | **上调至 4096** | 降低批量点火时过早逐出的概率；它是条目保留上限，不保证零遗漏。内存与检查耗时须测量，不能将约 100 B/条的估算当 JVM 实测值 |
| 「整块已空则跳过」 | **不新增区块级预扫描守卫** | 水印负责防重放；为判断空而先扫一遍不属于必要正确性工作。保留既有清空函数内部的 section 空判，不删原有局部优化 |
| 嵌套爆炸跳过优化 | **首批不做** | 不新增同心/包含关系索引或逐条比较；先守住多爆炸不会被水印漏掉的正确性，只有 profile 证明重叠处理是热点后才另议 |

三项选择与整体实施边界均已获用户确认；§3 / §5 是交付与验收约束，不是待授权清单。
未验证的性能与游戏表现仍留待测，不能将设计确认写成验收通过。

### 7.1 已核实：水印落地方式 = NeoForge 官方 chunk 数据附件 API（2026-10-13）

**结论：不需要 mixin。** NeoForge 21.1.219 的 `ChunkAccess` 直接实现 `IAttachmentHolder`
（`libs/src/neoforge-21.1.219-merged/net/minecraft/world/level/chunk/ChunkAccess.java:60`），
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
        .serialize(Codec.LONG).build());

// 水印分支伪代码；实际遍历混合条目快照并保持原始登记顺序。
Long stored = chunk.getExistingDataOrNull(TNT_EPOCH.get());
long before = stored != null ? stored : 0L;
long after = before;
for (var entry : entriesSnapshotInRegistrationOrder) {
    if (entry.isBitmapFamily()) {
        applyIfBitmapPending(entry, chunk); // 成功后置位；不读写 SUPER 水印
    } else if (entry.epoch() > before && affects(entry, chunk)) {
        apply(entry, chunk);               // 失败则不提交本轮水印
        after = Math.max(after, entry.epoch());
    }
}
if (after > before) chunk.setData(TNT_EPOCH.get(), after);
```

**两个附注**：① 本附件不配 sync handler ⇒ 纯服务端数据，零网络成本
（chunk 附件同步入口 `LevelChunk.java:687` 不会被触发）；② 序列化只发生在真正处理过的区块
⇒ 存档增量 = 每区块 1 个 long（`neoforge:attachments` 下，NBT 压缩后更低），与 §8 预估一致。

### 7.2 已核实：W1 不是墙 —— 原版实体查询是「X 列 + 有序集区间查询」（2026-10-13）

**结论：`level.getEntities(null, 巨型AABB)` 的成本随盒宽线性增长（O(√N × log S)），不随体积立方增长。**
此前本文预估的「≈10¹⁴ 次网格迭代、小时级冻结」**是错的**——那假设了 3D 全网格遍历。

调用链（全部已读源码，`libs/src/neoforge-21.1.219-merged/`）：

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

⇒ 实体查询不因巨型 AABB 遍历不存在的三维体积，但成本仍随盒宽与实体索引规模变化。
表中耗时为操作量估算，不是实机数据；D3 本轮不做，只有测出真实热点才另议。

⚠️ **教训留痕**：本条差点凭「AABB ⇒ section 网格」的直觉写成小时级冻结，并据此排了修单优先级——
**量级断言必须读源码**（与 §1.1「全部可复算」的要求同源）。

## 8. 未来场景预演

| 场景 | 定稿设计下的表现 |
|---|---|
| 21 亿堆叠 × 上百槽（N ~10¹¹–10¹²，半径 1,300–2,600 km） | SUPER 无范围位图或范围级建档；实体查询、候选 / 条目检查与实际破坏仍有成本，需测量 |
| 几百个抽屉同时归零（连环爆） | 持久条目共享 4096；满时优先逐出最旧 SUPER，全位图时新 SUPER 仅做拒绝瞬间 loaded-only 的预算降级；不承诺零遗漏 |
| 跨维度 | 每维度账本 / epoch / 候选预算独立，水印随该维度区块；不新增 loaded 登记表 |
| 活红石批量点火 | 计数仍需扫描槽位；SUPER 建档不枚举理论覆盖网格，不声称整个点火 O(1) |
| 存档体积 | SUPER 参数载荷不随面积增长；水印只写实际处理区块，NBT 键名 / 对象开销不能仅算 8～9 B；位图族自定义半径另计 |
| 物理下限（诚实项） | 巨型覆盖只在自然观测到的区块兑现；全探索的区域文件与破坏 / 同步成本不会被参数化账本消除 |
