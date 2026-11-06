<!-- markdownlint-disable -->

# 活海绵（流体存储 / 流体容器物品）

> **文档版本**: v1.0（2026-11-02 自[设计稿](../archive/活海绵-design.md)升格——三段实施 +
> ⑭ 容器级能力 + 排出口径修复全部落地、实机两轮验证通过）
> **状态**: **已实施**（设计 2026-10-29 立稿 / 2026-11-01~02 四邻制与形态定案并三段落地）
> **定位**: 容器级水源的**移动式储液罐**——流体物流链「生成（活水源）→ 运输 → **存储（活海绵）**
> → 消费」中的存储一环；**旧活流体管道能力的接棒者**（⑭：管道直连放了活海绵的容器）。
> **关联**: [living-fluid-tech.md](living-fluid-tech.md)（引擎口径以其为准）·
> [living-power-tech.md](living-power-tech.md)（记账内核两域同源）·
> [设计历程留痕](../archive/活海绵-design.md)（拍板过程 / 推翻记录 / 表示法定理 / 横切表）
> **实现入口**: `living/domain/sponge/` —— 关键类：`LivingSpongeData`（{fluid, mmB} 组件）·
> `LivingSpongeFunction`（吸收 tick + tooltip）· `SpongeItemFluidHandler`（ITEM 能力面）·
> `SpongeContainerFluidHandler`（⑭ 容器级 BLOCK 能力）· `LivingSpongeInteractSupport/Handlers`
> （GUI 汲）· `SpongeComponents` / `SpongeRegistration`（注册）；
> 共享内核 `living/util/PerItemLedger`（均分记账，power/sponge 两域同源）·
> `living/api/FluidSourceLedger`（源账本契约）· `living/util/RepresentativeFluids`
> （FluidType→Fluid 映射）；客户端 `client/render/LivingSpongeFluidDecorator` +
> `client/icon/LivingIconRegistry`（海绵 spec）。

---

## §0 玩法定义（定稿）

> **性质**：本节「规则」是**契约**（违反即 bug），不是实现细节。
> **原始需求（用户原话）**：「后面直接出专门的流体相关的活物品再说，就像活涂蜡铜灯存电一样的
> 逻辑」（2026-10-06）·「流体容器方向优先」（2026-10-29）·「上下左右好点，可控，语义也更清晰」
> （2026-11-01）·「数据没有了就没有了」·「原先活流体的流体能力不需要了，由活海绵实现该能力」
> （2026-11-02）。

**语义**：活海绵 = **流体容器物品**。吸进容器水网里任意已支持的活流体源，流体随物品走
（丢地上、送人、塞末影箱都带着），对外暴露流体能力、能被模组设备与管道充放。
原版海绵机制作为**风味**保留：贴身吸水、下界不自干（见规则 11）。

**获取**：海绵 + 活按钮（活化无门槛，`activation_rules.json` default allow）。

**核心循环**：活桶倒水成源 → 水网蔓延 / 晋升 → **活海绵**按上下左右四邻直吸（任意已注册流体源）→
存量累积（每枚上限 64,000mB，堆总量 × count）→ 输出三选一：模组设备 / 储罐对物品充放（主通道）·
桶 GUI 汲（每桶 1000mB）· 滴水石锥滴回水网成源（**跨物品，随活滴水石锥另立项**）。
⑭ 之后容器本身也是储液点：管道直连「放了活海绵的容器」抽 / 灌。

**规则（定稿，违反即 bug）**：

| # | 规则 | 为什么 |
|---|---|---|
| 1 | **吸收任意已注册流体源**（水 / 岩浆 / 模组注册流体；「已注册」= `FluidFlowBehaviors` 认识） | 流体容器方向优先；原版「只吸水」显式偏离 |
| 2 | **只吸派生源，流动格不吸** | 流动格是每拍重算的产物，不是资产；账本契约只含源（结构保证） |
| 3 | **吸收沿上下左右四邻**（⑦）：每拍只看 4 个直接邻居，逐格独立判定——可吸源才吸，流动格 / 干槽 / 活物品格 / 异种源只是「该格跳过」，**不挡其他邻居**；斜对角与更远的水靠晋升回灌「爬」到嘴边 | 贴身制可控、语义清晰（2026-11-01 拍板取代「BFS 一圈圈」）；无 BFS、无深度、无新增列数义务 |
| 4 | **逐源原子吸收**（⑪）：每拍按固定方向序（上→下→左→右）逐个查邻居，可吸源逐个吸；**快满判据** = 每枚剩余 ≥ floor(1000mB / count) 才吸，第一个装不下的源出现即本拍停 | 贴近原版「碰到水就吸」；不吸半源、不丢大块（损耗界不破） |
| 5 | **单一流体**：非空时不吸收 / 不被充入异种流体；换流体 = 先清空；空海绵按方向序取首个可吸流体 | 对齐引擎「已占格不被异种覆盖」；单 `{fluid}` 组件的算术必然 |
| 6 | **守恒律扩展**：水的形态 = 桶里 / **海绵里** / 容器源里 / 世界里，互斥；**定点 mmB 计量**（1/1000 mB，总量 = mmB × count 精确） | 海绵 = 物品携带形态；细量子是均分守恒的前提（表示法定理见设计历程 §3.3） |
| 7 | **能力收口**：容器水网（generatedSources）**永不暴露能力**；流体能力的承载物 = 活海绵 | 分数源污染（挤没 / 晋升 / 刷石机）整类问题从根上消失 |
| 8 | **均分充放**：对堆的任何 ±D 都按枚均分（floor），残余 ≤ (count−1) × 0.001mB 不可感知 | 同栈共享同一份组件 ⇒ 均分是算术必然（不是语义选择） |
| 9 | **取消活化清空 {fluid, mmB}**（`getOwnedComponentTypes()` 声明） | 不存在「带流体的未活化海绵」——活化资产不外流 |
| 10 | **删源账本即时**（吸收 / 泵）：`removeGeneratedSource` 即时移出账本（同拍后 tick 的海绵不再看见）；实际层（CA / 渲染 / 反应）由流体域 `demoteOrphanSources` 在**同一 tick** 按目标层裁决（降级为流动格），不再是「下一拍」 | 账本延迟 = 同拍多枚海绵双吃同一源（守恒被破）；引擎实查即 `map.remove`；2026-11-07 修「源网络僵尸源 ⇒ 黑曜石」 |
| 11 | **下界不自干**（⑬，反转 2026-10-29 拍板）：存储态不查 `ultraWarm`——蒸发只属「放进世界」的世界暴露事件（原版湿海绵放置 / 桶倒水，原生路径自处理）；**倒水成源也不做 parity**（TODO 活流体 #11） | 湿海绵**物品**在背包 / 箱子里过下界同样不自干（原版对照）；存储态自干杀玩法（手提水箱 / 下界机器供水 / 水·岩浆双向贸易） |
| 12 | **报账红线**：fill 返回 ≥ 实充；drain / 汲**交付 = 决定交付的整单位，分毫不差且不超请求**（实扣 ≥ 交付）；多堆只续抽剩余请求，异种不扣；任何取整偏差只许**损耗向** | 支付方按返回值扣账——报账 < 实充 = 凭空造（灯泡 2026-09-09 教训）；floor 排出 = 三连炸（§12） |
| 13 | **湿态可堆叠**：同 fluid 同 mmB 可合并 / 异则不合并；总量 = mmB × count，拆堆复制组件天然守恒 | 灯泡同款堆叠语义 |
| 14 | **物品 id 不换**（⑧）：湿 / 干 = 组件派生态 + 图标变体（转化时机 = 写入边沿：首笔写入显湿、归零翻干）；**熔炼配方不做**、放置不拦截（⑨：活标记 + 流体随 consume 消失不可恢复，「数据没有了就没有了」） | 形态切换丢活标记 + 转化点覆盖不全（外部灌入发生在模组机器槽里） |
| 15 | **容器级能力**（⑭）：管道直连「放了活海绵的容器」抽 / 灌——**暴露的是海绵存量（封闭缓冲），源网络仍不暴露**（规则 7 不破）；存在性 = 有活海绵（湿或干，**干 = 空罐可灌装**；未活化不暴露） | 旧活流体管道角色的接棒；§10.4 教训（恒发实例把管道自身判成储液）直接继承 |

**能力清单（全部已实现 ✅）**：吸收（四邻 / 每拍 ≤4 源 / 任意已注册流体 / 每枚 64,000mB）·
存量组件（{fluid, mmB} / 堆叠守恒）· 记账内核（`PerItemLedger` 两域同源）·
物品流体能力（一堆一罐）· 容器级能力（⑭）· GUI 汲 · 下界不自干（反向守卫）。

**验证入口**：[§9 横切检查表与守卫映射](#§9-横切检查表与守卫映射)；
测试文件树见 [test-map.md](../reference/test-map.md)（sponge 域四个测试类 + `living/util/PerItemLedgerTest`）。

---

## §1 数据模型（LivingSpongeData）

per-item DataComponent `LivingSpongeData`（**record：`fluid: FluidType` + `mmB: long`**）：

- **定点 mmB = 1/1000 mB**——均分守恒需要细于外部接口的量子（灯泡 mFE 同款）；
- **每枚容量 64,000 mB = 6.4×10⁷ mmB**（= 64 源当量，储量锚）；堆总量 = mmB × count
  ≤ 4.1×10⁹ > int ⇒ **账本 long**；
- **干/湿不变量**：`mmB > 0 ⇔ fluid != null`——record **紧凑构造器归一**（无流体或非正存量
  ⇒ 干态），幽灵湿海绵在类型层面不可能；存量 = 默认值时组件移除（干海绵正常堆叠）；
- **CODEC**：FluidType 注册 id 字符串（`ContainerFluidData` 先例）；**未知流体 id 归干态**
  （I-C6 同款宁损勿鬼 + WARN 留痕——模组被移除后读旧档不产幽灵湿海绵）；
- **读写**走 `LivingItemManager.getData/setData`（等于默认值时移除）；
- ⚠️ **单次构造纪律**：干海绵首口定流体 + 入账必须**一步到位**
  （`new LivingSpongeData(fluid, newQ)`）——两步 wither 的中间态 `(fluid, 0)` 会被不变量
  归一回干态 = **首口蒸发**（守恒事故，吸收测试按住过，见 §12）。

---

## §2 吸收（每水节拍；四邻直吸 + 逐源原子）

1. **节拍门**：`level.getGameTime() % 5 == 0`（水节拍锁相心跳，跨存档天然安全——
   活耕地「生长节拍」同款；**不用 tick counter**，免吃换存档残留坑）；
2. **源账本**：`tick.getSourceLedger()`（契约层 `FluidSourceLedger`，水领域注册 resolver）
   ⇒ sponge 域不 import water 域（R3 零边）；
3. **四邻扫描**：`ContainerContext.resolveNeighbor(slot, dir, size, width)`（活耕地湿润 BFS
   同款算子；width 走 `ContainerLayoutResolver` 统一链）——越界返回 −1 视同干槽；
4. **逐格独立判定**：可吸源 = 派生源 且（空 ∨ 同流体）；异种 / 流动 / 干槽只跳过自己；
5. **逐源原子**：`share = 10⁶ / count`（floor，count ≤ 64 恒 > 0）；`mmB + share > MAX` ⇒ 本拍停；
   吸 = `removeSource(neighbor)`（账本即时删）+ 单次构造入账；
6. **吞吐由晋升回灌决定**：吸掉 → 回灌流动（1 拍）→ ≥2 邻源晋升（1 拍）→ 约第 3 拍再被吸。
   稳态 ≈3 拍一轮、每轮 ≤4 源（64,000mB ≈ 12s 装满）；**count 放大容量不放大速率**；
7. **农场攻防 = 贴身战**：距离 ≥2 即安全；「两源夹一格」贴着海绵 = 手动版无限水农场；
   隔离合法形态 = 中途回灌格凑不齐 ≥2 邻源（单源细流停在流动格）。

---

## §3 记账内核（PerItemLedger——power / sponge 两域同源）

`living/util/PerItemLedger`（L1，`WaxedCopperFamily` 上移同款判据）。纯函数、无 IO 无扫描：

- `distribute(q, count, cap, delta)` —— **充入**：均分 floor + 容量防御 + 完整步进保护；
  `accepted = per × count`（**记账与写入同源**——按堆写入按 1 计账 = 凭空造 count 倍，
  灯泡 2026-09-09 教训沉淀为类型契约）；
- `withdrawCeil(q, count, delta)` —— **排出（唯一口径）**：交付 = 决定交付的整单位，每枚扣
  ⌈delta / count⌉，实扣 ≥ 交付（损耗向）。⚠️ **floor 排出 = 三连炸**（2026-11-02 实测）：
  交付量随请求逐次缩水 + SIMULATE ≠ EXECUTE + 尾差沉成「显示 1 抽不出」的卡死尘
  （详见 §12）；内核同状态同请求的模拟 / 执行计算一致；BLOCK 的写入条件见 §4.2；
- 残余尘（< 1 外部单位）**对 mB/FE 世界隐身、留账继续累积**——不是死账，永不超过 1 单位；
- 灯泡的跨堆零头回收（回收优先 / `MAX_LEFTOVER_PASSES`）是「发电全局灌」特有机制，
  留在 `BulbBank`；单栈场景余量恒 < count ⇒ 恒走保守丢弃；
- `BulbBank.deposit` 逐堆分配已改调内核（行为等价，`RoundTripConservationIT` 回归守）。

---

## §4 能力面

### 4.1 物品能力（SpongeItemFluidHandler，ITEM）

- **一堆一罐**（⑩，Jade 观感好）：`getTanks() = 1`，tank 0 = {fluid, floor(堆总量/1000) mB}
  （报量 floor，尘不虚报），容量 = 64,000 × count；
- 充 = 内核 `distribute`；排 = `withdrawCeil`；报账口径规则 12；
- **准入**：`of()` 判 `isLivingItem`——未活化返回 null（capability 按原版物品注册，不判则
  外部 mod 给原版海绵充水绕过活化门槛，灯泡 2026-10-09 收紧同款）；
- `getContainer()` 返回 handler 持有的传入栈（provider 每次查询现建）；ITEM handler 仍只负责
  修改这份栈，不负责找到外部槽并写回。BLOCK 调用方传副本时，写槽责任在 §4.2 的提交协议；
- 注册：`LivingItem#onRegisterCapabilities` → `registerItem(Capabilities.FluidHandler.ITEM, …)`。

### 4.2 容器级能力（SpongeContainerFluidHandler，BLOCK，⑭）

管道直连「放了活海绵的容器」抽 / 灌——旧活流体管道角色的接棒：

- **暴露的是海绵存量（封闭缓冲），源网络仍不暴露**（规则 7 对 generatedSources 成立）；
  抽 / 灌的计算委托 `SpongeItemFluidHandler`（均分内核 + 报账口径单源复用），
  **写槽与确认交付由 BLOCK 面负责**，不依赖 `getItem` 恰好返回活引用；
- **罐模型**：每个活海绵堆 = 一罐（逻辑槽位稳定序）；**干海绵 = 空罐**（0 内容、容量在位 ⇒
  管道可灌装空弹匣）。每次操作只扫描一次，得到「逻辑槽号 + 复制栈」列表；不缓存罐列表，
  也不在同一操作内重复扫描或更换 handler；
- **写入准入**：fill / drain 只处理 `supportsItemComponentWrite(slot)` 为 true 的槽；
  不支持者在 **SIMULATE 也贡献 0**，但罐的存在性、展示及容量不因此消失。
  null side 仍是原有无方向上下文，不新增方向限制；也不得借它绕过模组只读能力；
- **复制 → 提交 → 报账**：SIMULATE 只在复制栈上计算，不写槽；EXECUTE 在复制栈上执行
  ITEM handler 充放，再用扫描时的 `before` 与变更后的 `after` 调
  `commitItemComponents`。**成功后**才累计该堆 fill 接收量 / drain 交付量；提交 false
  即停，返回此前已提交的部分（首堆失败则 0 / EMPTY）。精确恢复的边界见
  [api-contract §1.8](../system-design/api-contract.md#18-槽位物品组件提交2026-11-06-生效)；
- **模拟与执行的边界**：同请求同结果只在**状态稳定且声明支持的直接写入器有效**时成立。
  错误声明支持的 no-op / 只读写入器可能模拟出正量、执行却为 0；不把潜在可充放量当已交付量，
  也不承诺违约后端普遍原子恢复；
- **抽取聚合**：每堆只请求剩余量，交付合计不超请求；不指名抽取先模拟选定首个可抽流体，
  后续只抽同种，异种不扣也不阻断后面的同种罐；返回实际存量的代表流体，不复制请求身份。
- **存在性 = 有活海绵（湿或干）**：无活海绵 ⇒ provider 返回 null（§10.4 教训：恒发实例
  把管道自身判成储液，整张网络断在假 tank 上）；未活化海绵不暴露（活化门槛）；
- **存在性边沿**：`EDGE` 标志（`ContainerDataKey.persistent`，**跨 tick 内存态，不落盘**）+
  `ContainerTickHooks` 钩子（`onTickStart` 扫描比对）⇒ 存在性翻转时逐关联位置
  `invalidateCapabilities`，每个边沿只通知一次；**不因存量、fill / drain 或干湿变化通知**。
  标志随容器而生灭，寿命以 [统一回收契约](../reference/container-data-lifecycle.md) §2 为准；
  旧清理丢标志导致 Create 抽取停摆的源码推演与实机对照见 §12.1。
- 注册：BLOCK 宽注册（全部 BE 类型），provider 内部按存在性发实例。
- **客户端存在性探针**（2026-11-06）：客户端没有容器级数据，但 Jade 在客户端
  `WailaTickHandler → shouldRequestData → CommonProxy.hasDefaultFluidStorage` 用「能力非 null」
  决定是否发请求包 ⇒ 闸门不过时服务端 `appendServerData` 根本没机会下发真实罐内容。
  客户端 provider 因此返回零罐只读 `NO_OP`；服务端仍是存在性与读数的唯一权威。

---

## §5 GUI 汲（`living_sponge_draw`）

桶点海绵槽：堆总量 ≥1000mB 且与桶兼容 ⇒ 均分扣除（**ceil 口径**：每枚
−⌈10⁶/count⌉ mmB，超额 ≤ count−1 mmB 损耗向——floor 版会让桶凭空得到差额）+ 桶灌满 1000mB。

- **只做汲**（2026-11-01 拍板）：「桶 → 海绵」无直灌——路径 = 桶倒成源 → 下一拍被吸；
- 活桶被汲时**保留活标记**（填充是我们自己写栈，不走丢标记的原版 swap）；
- 创造模式对齐原版：光标桶直接灌满、不复制产物；
- 链路：客户端 `GuiInteractionHelper` 海绵分支（{fluid, mmB} 是 networkSynchronized 组件
  ⇒ 客户端直接精确判定，无需流体快照）→ `GuiInteractionPacket` → 服务端
  `LivingSpongeInteractHandlers.DRAW` **权威重验** → `LivingSpongeInteractSupport.drawFromSponge`；
- 读写的全部是**海绵物品组件**——不碰容器流体账本，无需 `ContainerContexts` 反查。

---

## §6 时钟归属（统一时钟，fluid tech §3.6）

| 操作 | 归类 | 说明 |
|---|---|---|
| 吸收 | **生长类**（按水的节拍） | 吞吐由「吸掉 → 回灌 → 晋升」循环决定；节拍门 = gameTime 锁相 5t |
| 删源生效 | 账本即时 / 实际层同拍对账 | 与汲走同层（规则 10）；流体域 `demoteOrphanSources` 同 tick 撤源身份，慢流体源网络不再留僵尸源 |
| 物品能力充放 | **即时** | 玩家 / 设备显式操作不等节拍 |
| GUI 汲 | **即时** | 显式操作 |

---

## §7 图标与反馈（活物品图标系统，几乎全走声明式）

| 反馈 | 数据 | 通道 | 覆盖 |
|---|---|---|---|
| 干 / 湿辨识 | `mmB > 0` | **模型变体**：`wet` / `dry` 两个三行 JSON parent 到 `minecraft:block/wet_sponge` / `sponge`——不随包分发纹理、跟随玩家材质包 | 全 GUI（含 JEI——模型层不受装饰器管线限制）；手持 / 掉落物回退原版外观 |
| 所存流体种类 | 组件 `fluid` | **流体渲染叠加**（`LivingSpongeFluidDecorator`）：still 贴图全幅 α0.55（源格同款，半透保留湿态底图）、染色取 `IClientFluidTypeExtensions`（模组流体零特判）、**只表种类不编码量**、z 抬 200 返回 true 交还 NeoForge 重置 | 装饰器管线场景；JEI 不显示（干湿变体仍在） |
| 量与容量 | 组件堆总量 | **tooltip**（`LivingSpongeFunction.addToTooltip`）：流体名（`FluidType.descriptionId`）× 堆总量 / **堆容量 64,000 × count mB** —— 分子分母同取堆口径，与能力面 `getFluidInTank` / `getTankCapacity`、铜灯电量行一致 | 全场景 |

- 「活」的辨识靠**状态发生的时刻**：原版海绵永远不会在物品栏里自己变湿，活海绵会
  （铜灯同款逻辑）；干的活海绵与原版海绵静态同形 = unlit 态代价，靠 tooltip 辨识；
- 湿 / 干翻转的 GUI 实时性白拿 2026-10-31「组件翻转」同步通道（`syncItemFormSlot`）。

---

## §8 守恒防线（灯泡前车之鉴 + 本域实测）

> 活涂蜡铜灯在与外部电力 mod 的传输中踩过多轮电量不守恒。海绵完整继承同款机器，
> 并在实测中补上了灯泡没踩全的「缩水」方向。

### 8.1 灯泡的坑 → 海绵防线

| 灯泡的坑 | 海绵防线 |
|---|---|
| 零头记账 × count 倍 ⇒ 每 tick 凭空造电 | 内核「记账与写入同源」：accepted = per × count |
| 报账 < 实充 ⇒ 往返造电 | 规则 12：充报 ceil、排交付整单位（实扣 ≥ 交付） |
| 比例分配 long 溢出 ⇒ 10 亿轮慢 tick | 账本 long + 份额走 `PowerMath.mulDivFloor`（充入）|
| 三份分配算法口径漂移 | 内核只此一份（两域同源） |

### 8.2 海绵主动防的陷阱

| # | 陷阱 | 防线 |
|---|---|---|
| 1 | handler 缓存旧 ItemStack / 把读取当写回 | ITEM 只修改传入栈；BLOCK 每操作现扫复制栈，以组件提交确认写回（§4.2） |
| 2 | SIMULATE 与实做分叉 | 内核同状态同请求计算一致；BLOCK 还要求有效的受支持写入器，提交失败不报交付（§4.2） |
| 3 | 返回值 ≠ 实充/实扣 | fill 报 ≥ 实充；drain 交付整单位（实扣 ≥ 交付） |
| 4 | count 乘法悄悄进公式 | 内核统一处理；手写 `* count` 即违规 |
| 5 | 零 / 负请求 | 首行守卫返回 0 / EMPTY |
| 6 | FluidStack 附加组件丢失 | v1 已知限制（接受）：池 = 纯类型 + 量 |
| 7 | 组件 equals / hashCode | record（FluidType 恒等比较）天然合规 |
| 8 | **floor 排出三连炸**（2026-11-02 实测）：交付随请求逐次缩水 + SIMULATE ≠ EXECUTE + 卡死尘销毁 | **排出侧唯一口径 = ceil**：交付整单位分毫不差、尘隐身留账；守卫 ⑩（实测场景全生命周期）+ ⑪（确定性） |
| 9 | **tooltip 容量口径漂移**（2026-11-06 实测）：分子按堆总量 ×count、分母写死每枚 64,000 mB ⇒ count=2 装满显示「128000 / 64000」 | **分子分母同取堆口径**：分母一律走 `totalCapacityMmB(count)`，与能力面 `getTankCapacity` / 铜灯电量行同构；守卫 `LivingSpongeTooltipTest`（含逐字比对能力面） |
| 10 | **Jade 客户端取数闸门被关死**（2026-11-06 实测）：10-31 能力存在性收口把 `level.isClientSide() → null` 写进 provider；Jade 客户端 `shouldRequestData` 判「无能力」⇒ 不发请求包 ⇒ 服务端读数再准也不显示 | 客户端只发**只读零值存在性探针**（海绵零罐 / 铜灯 0 FE 且不可充放），服务端 provider 仍是存在性与读数的唯一权威；守卫两域 `resolveProvider` 客户端测试 |
| 11 | **源网络僵尸源**（2026-11-07 实测）：海绵吸走岩浆源后 tooltip 立即变，但该格仍以 `isSource=true` 渲染一小会，遇活水流出黑曜石 | 根因在流体域双层不闭环：`removeSource` 只删账本，`flows` 靠下一拍被动收敛，邻源 + 岩浆节拍门会把它顶成僵尸源；修法 = `demoteOrphanSources` 同拍按目标层降级。守卫 `ContainerFluidDataTest` ㊴b/㊴c（旧代码 2 项红） |

**充入侧对偶检查（同日）**：报账 ceil 封顶请求 ⇒ 无缩水；count ≤ 64 ⇒ per ≥ 15 ⇒ 无报 0 死区；
simulate/execute 同源确定。唯一残留毛刺：存量带尘时 content 恒差 1 mB 到 capacity
（表示法定理固有）——以 fill 返回值为准的管道不受影响（守卫 ⑬ 全域扫描 + ⑭ 收敛性钉死）。

### 8.3 守卫测试（红线）

**`SpongeFluidRoundTripTest`**——净迁移无凭空产生 + 逐步损耗有界 + 拆分恒等式 +
往返 ×25 零漂移 + 实测场景全生命周期。🔴 **不许为了让它过而改它**——测试红 = 实现有 bug。

---

## §9 横切检查表与守卫映射

### 9.1 生命周期表（模板 §1.1）

| 状态 | 存在哪 | 物品移走时 | 取消活化时 | 换槽位时 | 容器卸载时 |
|---|---|---|---|---|---|
| 所存流体 `{fluid, mmB}` | DataComponent | **保留**（跟物品走） | **清空**（规则 9） | 保留 | 保留 |
| 吸收节拍 | **无存储**——每拍从 `gameTime` 现算边沿 | — | — | — | — |

**1.1-b 状态粒度**：`{fluid, mmB}` = per-item；`generatedSources` = 容器级（fluid 域资产，
海绵只是消费者）；⑭ `EDGE` 标志 = 容器级跨 tick 内存态，不落盘；其回收按
[container-data-lifecycle.md](../reference/container-data-lifecycle.md) §2，不以海绵存量决定寿命。

### 9.2 准入表（模板 §1.2）

海绵是**存储型物品，无专属槽位**。风险扫描：原版海绵无燃料值、无酿造用途 ⇒ 熔炉 /
酿造台天然安全；模组机器把 `sponge` 当耗材的配方不在把守范围（该枚份额随物品离开 =
损耗向）。`isLivingItem` 准入只管能力面。

### 9.3 容器能力横切（模板 §1.4）

| # | 判据 | 答 |
|---|---|---|
| ① | 要读相邻槽吗 | **是**（四邻）——走既有算子 `resolveNeighbor` + 统一链，无新增列数义务 |
| ② | 有容器级持久数据吗 | 吸收侧无（组件 + fluid 域既有账本）；⑭ `EDGE` 只需跨 tick 内存态，不落盘；生命周期见 [统一回收契约](../reference/container-data-lifecycle.md) §2 |

### 9.4 规则 → 守卫映射

| 规则 | 守卫 |
|---|---|
| 1 任意已注册流体 | 吸收单测：水 / 岩浆源各一 |
| 2 只吸源不吸流动格 | 实现结构保证：账本契约只含派生源 |
| 3 四邻逐格独立 | 吸收单测：异种跳过不挡其他邻居；斜对角不吸 |
| 4 逐源原子 + 快满 | 吸收单测：近满停拍、腾地方后恢复 |
| 5 单一流体 | 吸收 + 能力单测：异种拒绝 |
| 6 定点 mmB | `SpongeFluidRoundTripTest` + `LivingSpongeDataTest` |
| 7 能力收口 | `ContainerFluidHandlerTest` 已随退役删除；能力准入单测（未活化返回 null） |
| 8 均分充放 | RoundTripTest 穿插拆堆 / 合并 |
| 9 取消活化清空 | 框架 `clearLivingData` 守卫 + `ComponentOwnershipTest`（allFunctions 含海绵） |
| 10 账本即时删 | 吸收单测：同拍双海绵不双吃；`ContainerFluidDataTest` ㊴b/㊴c：源网络删源同拍撤源身份、水贴邻不出黑曜石 |
| 11 下界不自干 | **反向守卫**：ultraWarm mock 下照常吸收且所存完好（AbsorbTest ⑩） |
| 12 报账红线 | RoundTripTest 红线断言（净迁移无凭空产生）+ ⑪ 确定性 |
| 13 湿态可堆叠 | RoundTripTest 堆叠 / 拆分守恒 + CODEC 往返 |
| ⑩ 一堆一罐 | 能力单测：getTanks=1 / 报量 floor / 容量 ×count |
| ⑭ 容器级能力 | `SpongeContainerCapabilityTest` 6 项（存在性 / 罐映射 / 抽 / 灌 / 边沿精确一次） |
| GUI 汲 | 汲单测（复用 `LivingBucketInteractSupport` 测试模式，ceil 口径） |

---

## §10 测试地图（sponge 域 5 个测试类 + 共享内核）

| 测试类 | 守什么 |
|---|---|
| `LivingSpongeDataTest`（7 项） | 组件形状契约：干/湿不变量归一 / 容量 long 域 / CODEC 湿·干·岩浆往返 / 未知流体降级 / wither 不可变 |
| `LivingSpongeAbsorbTest`（10 项） | 吸收行为：一拍净邻 / 节拍门锁相 / 异种跳过不挡 / 方向序取首流体 / 快满停拍恢复 / 均分损耗有界 / 账本即时删不双吃 / 斜对角非邻居 / ultraWarm 反向守卫 / processContext 冒烟 |
| `SpongeFluidRoundTripTest`（12 项） | **守恒主战场**：附录算例 / 拆分恒等式 / 往返零漂移 / 净迁移无凭空产生 / 溢出拒收 / 异种拒绝 / 零负请求 / 尘量行为 / 一堆一罐 / 7 堆实测场景 / simulate=execute / fill 全域扫描与尘区收敛 |
| `SpongeContainerCapabilityTest`（6 项） | 容器级能力：存在性（干 = 空罐）/ 罐映射槽位序 / 指名罐抽 / 跨堆续抽 / 多罐灌装 / 边沿精确一次 |
| `LivingSpongeTooltipTest`（4 项，2026-11-06） | tooltip 堆口径：单枚 64000/64000 / 2·7·64 堆分子分母同乘 count 且与 `getFluidInTank`·`getTankCapacity` 逐字一致 / 半满余量 / 干态无内容行 |
| `PerItemLedgerTest`（6 项，living/util） | 均分内核：floor 记账同源 / 整步保护 / 容量防御 / 满堆不动 / 退化输入 / 损耗 ≤ count−1 |

---

## §11 原版对齐清单

| 原版检查 / 行为 | 本实现 | 处置 |
|---|---|---|
| 海绵只吸水 | 吸收任意已注册流体源 | ⚠️ 显式偏离（流体容器方向优先） |
| 一次吸 64 格（穿水 BFS 深度 6） | 64,000mB/枚 储量锚；四邻贴身直吸 | ⚠️ 偏离（吸收范围收窄，靠晋升回灌慢慢喂） |
| 穿水不穿空气 | 四邻（非邻居 = 吸不到） | ⚠️ 偏离收窄（「隔空吸不到」风味保留） |
| 吸流动水 | 只吸源 | ⚠️ 偏离（容器流动格无独立量） |
| 放置瞬间全吸 | 每拍四邻直吸（晋升回灌限速） | ⚠️ 偏离（贴身战） |
| 湿海绵 = 独立方块 | 组件派生态 + 图标变体 | ⚠️ 偏离（id 不换） |
| 湿海绵放置下界自干 | **存储态不自干**（蒸发只属世界暴露事件） | ⚠️ 有意取舍（⑬） |
| 熔炼兑现 1 桶 | 不做熔炼配方；GUI 汲继承粒度（1000mB/次） | ⚠️ 偏离 |
| 原版无「取出」 | 能力面 + GUI 汲 + 泵（待滴水石锥） | ⚠️ 偏离（立身之本） |
| 干海绵可放置 | 放置不拦截（活标记 + 流体随 consume 消失） | ⚠️ 有意取舍（⑨） |

---

## §12 踩坑实录

1. **首口蒸发（守恒事故，吸收测试按住）**：干海绵首口若走「`withFluid` → `withMmB`」两步
   wither，中间态 `(fluid, 0)` 会被干/湿不变量归一回干态 ⇒ 源被吃、一滴不存。
   **修**：fluid 与新 mmB **单次构造**同步到位（吸收 / fill 同款纪律，代码注释有标记）。
2. **floor 排出三连炸（实测：7 堆存 1 桶后机械动力管道抽不出）**：交付量随请求逐次缩水
   （999 请求实交 998）+ SIMULATE ≠ EXECUTE + 尾差沉成「显示 1mB 抽不出」的卡死尘 +
   空返回销毁 994mmB。**修**：排出侧唯一口径 = ceil（§3）。充入侧对偶检查无同类 bug。
3. **电力层同族**：`BulbItemEnergyStorage.extractEnergy` 同患 floor 缩水 + 双重尘销毁，
   同款 `withdrawCeil` 修复；容器级 `ContainerEnergyStorage.extract` 本来就是 ceil
   （2026-09-09 守恒战争产物）——当时只修了「造电」方向，「缩水」方向漏掉了。
4. **测试桩要求**：容器级能力的单测需 `level.getCapability` + `level.getBlockState`
   双打桩才能走通 `resolveContextAt`（石头 = 非双箱路径）。

### 12.1 Create 抽取停摆：通告标志被旧清理误删（2026-11-05）

**实机观测与推演分开记**：修复前用户确认，在出现抽取异常的海绵容器里加**活涂蜡铜灯**后，
Create 管道可正常抽取；**2026-11-06 使用修复包、不放铜灯复测，用户确认停抽问题解决**。
运行时的能力失效次数与 Create provider 内部引用没有被直接观测，下面内部链路仍属源码推演。

- **本模组侧根因**：旧类型心跳清理只认 `ContainerDataLifecycle`；只剩 Boolean `EDGE` 的
  活跃聚合条目也会被整条删掉。下次 `tickEdge` 读到 null，当成尚未通告的 false，
  海绵仍在就再次触发能力失效。灯泡使电力账本持续更新，旧清理因此保留整条目，`EDGE` 也免遭误删；
  **不能靠放灯泡作为修法**。统一修复约束见 [container-data-lifecycle.md](../reference/container-data-lifecycle.md) §2/§4。
- **Create 6.0.10 源码链**：`FlowSource.FluidHandler.manageSource` 的失效回调清掉自身的 handler cache，
  并使 `ICapabilityProvider.BlockCapabilityCacheProvider` 永久返回 null；下一轮源管理可建新 provider。
  但 `FluidNetwork.tick` **只在 `source == null` 时取 supplier**，已持有的非 null 旧 provider
  返回 null 时直接早退，不会因为另处建了新 provider 而自动换源。
  ⇒ **推演**：伪边沿反复失效会令网络持有死亡源引用，抽取卡住；这不是海绵 drain 报账算法的结论。

**修复边界**：只修本模组容器生命周期；保留「能力存在性翻转才通知」口径，不在每次 fill / drain
失效能力，不加 Create Mixin、不改第三方源码。干海绵仍是可灌空罐，存量归零不等于能力消失。

**验收范围（2026-11-06）**：

| 场景 | 状态 |
|---|---|
| 使用修复包，不放铜灯，仅海绵持续抽取 | ✅ 用户复测确认停抽问题解决 |
| 修复前加铜灯对照 | ✅ 用户确认加灯后正常，支持旧清理根因 |
| 无领域账本保护，真实 `processContext` 连续 3600 次 | ✅ 回归通过：EDGE 保留，存在性通知不重复；移除后只通知一次 |
| 海绵取出再放回、管道断开重连、重载及跨流体变化的组合 | 未专项穷举；本次持续抽取验收不等于修复 Create 所有缓存失效场景 |

自动验证见 `ContainerCacheSweepTest`；全量状态以测试 XML 与 `doc_check` 为准。

### 12.2 多堆抽取预算与异种扣减（2026-11-06）

指名抽取曾向每堆传原始完整请求，600+600mB库存对1000mB请求可交付1200mB；
不指名抽取曾在判类型前执行扣减，水／岩浆／水排列会扣掉未交付的岩浆，并挡住后面的水。

约束：每堆只消耗剩余请求，异种在扣减前拒绝；第一堆可抽流体由模拟选定，后续走同种指名请求。
一次调用仅扫描一次容器；均分损耗仍由单堆内核处理，返回实际存量的代表流体。
`SpongeContainerCapabilityTest` 守卫请求上限、混种不动、模拟不写、干罐/尘跳过与代表流体口径。
与 §12.1 的能力通知问题独立；保存通知和 handler 生命周期待办未随本轮改变。

### 12.3 容器读取不是写回：组件提交与恢复限度（2026-11-06）

`ContainerContext.getItem` 可以返回副本；直接让 ITEM handler 改这份栈，随后报 fill / drain
成功，会产生「槽内资产没变、对外已经报账」的守恒漏洞。反过来，依赖活引用会绕过明确的
存储、标脏与同步入口。**两种读取形态都不应决定 BLOCK 面是否正确**。

**本批修复范围**：共享组件提交协议 + 海绵 **BLOCK fill / drain**。每操作现扫一次逻辑槽与
复制栈列表，只在副本计算；只有 `commitItemComponents` 确认成功才计该堆报账，失败即停并
返回已提交的部分。Item、count、同流体、剩余请求及代表流体口径不变；ITEM handler 的传入栈
责任不变。海绵吸收、GUI 汲及其它内部读引用路径未迁移，不得据此宣称所有组件写入
或所有容器形态都已修复。铜灯BLOCK外部充放已于后续批次接入，见 [红电 §4.2](living-power-tech.md)。

**写入器要求与回滚限度**：必须有声明支持的直接可修改精确写入面；不支持槽连模拟也返回 0。
声明支持却只读 / no-op 的后端仍可能模拟出正量、执行返回 0；「模拟 = 执行」必须附带稳定状态
和有效写入器条件。失败后部分改变的槽会尝试精确恢复；恢复失败记 ERROR，并走最终变化的
标脏 / 同步通道，**不是普遍可保证的原子回滚**。不得用容量夹断、插入/抽取回退、null side
绕过只读能力，或反复失效能力来遮掩写入问题；完整协议以
[api-contract §1.8](../system-design/api-contract.md#18-槽位物品组件提交2026-11-06-生效) 为准。

**实机对照（2026-11-06，用户）**：含流体的活海绵放在 Storage Drawers 抽屉内，
旧版「可以被无限汲取和储存」，新版没有该问题。这是实际游玩中的旧新版本对照，
与副本后端测试的「对外报成功、槽内存量不变」相符；不再只是理论兼容风险。
未记录具体抽屉变体、管道或版本号，不把该结果扩展为所有容器的验收。

**玩法边界（同日用户明确）**：抽屉没有箱子式槽位 GUI，不要求它承载整套活物品玩法；
活物品在其中不工作可以接受，**虚报充放成功、复制资源则不可接受**。
本次无精确 writer 返回0属于安全不工作，不意味着要另做抽屉专用适配，
也不据此增加通用的“有无 GUI”容器准入规则。

**能力与验收边界**：罐存在性及 handler 生命周期不改；不新增 handler 刷新或重复能力失效。
仍按实际游玩观察，不安排专项游戏测试。抽屉旧新对照与 §12.1 的 Create 停抽验收分别成立，
不等于所有后端已验收，也不代表违约后端已获原子性保证。

---

## §13 关联与远期

- **活滴水石锥**（[设计稿](../buffer/活滴水石锥.md)）：泵通道（滴水石锥指向海绵 ⇒
  均分扣除 1000mB + 下游空槽成源）——独立活物品，另立项；
- **下界 parity**：倒水成源不查 ultraWarm（TODO 活流体 #11 拍板接受）；
- **设计历程**：拍板过程 / 推翻记录（②②b③）/ 表示法定理 / 横切表全量 =
  [活海绵-design.md](../archive/活海绵-design.md)（已按模板 §2.3 归档）。
