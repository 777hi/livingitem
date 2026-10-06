# 倒桶路径对齐：B 档 ——「水倒进岩浆**源**格 ⇒ 替换成水源」

> **性质**：口径收窄+ 对齐原版的记录文档（用户 2026-10-06 在 A/B/C 三档里选 **B**）。
> 前置阅读：`living-fluid-tech.md` §3.5（跨流体反应四格场景）、§5（倒/汲语义）、§9（挂起项）。

## §1 背景：倒桶路径目前比原版宽松

原版倒桶走 `BucketItem.emptyContents` → 问**目标格里的流体本身**「你能不能被替换」
（`Fluid.canBeReplacedWith`）：

| 流体 | 原版 1.21 规则 | 源码 |
|---|---|---|
| 岩浆 | `fluidState.getHeight() >= 0.444 && incoming.is(WATER)` ⇒ **源**（高度 0.889）可被水替换 | `LavaFluid.canBeReplacedWith` |
| 水 | `direction == DOWN && !fluid.is(WATER)` ⇒ **2D 里没有 DOWN ⇒ 永不可被替换** | `WaterFluid.canBeReplacedWith` |

我们当前的 `pour`（`LivingBucketInteractSupport`）**完全不问这件事**：

```java
if (!fluidData.isSource(containerSlot)) {
    fluidData.registerGeneratedSource(containerSlot, fluid.getFluidType());
}
```

⇒ 只要目标格不是源格，就直接在该格诞生源（把异种流动格覆盖掉），桶排空。

**2D 投影的一个有用结论**：原版 0.444 高度门槛在我们的 `maxLevel 3` 下（流动 level 1~3 ⇒
高度 0.111~0.333）**永远达不到** ⇒「严格对齐」在 2D 里等价于**只有源格可被水替换**。

## §2 定稿（B 档）

| 目标格 | 行为 |
|---|---|
| 空格 | 诞生源 + 桶排空（不变） |
| **同种**源格 | **源不变**，桶排空（原版语义：往水里倒水） |
| **异种源格** | 问该源 `canBeReplacedBy(incoming, selfIsSource=true)`：**true ⇒ 同键覆盖源类型**（水浇岩浆源 ⇒ 该格变水源、岩浆源湮灭）；false ⇒ 源不变、桶排空 |
| **异种流动格** | **直接覆盖**（B 档明确保留的宽松口子，不对齐原版的「倒不进」） |

⇒实际只有一条新行为：**水倒进岩浆源格 ⇒ 该格变成水源**（原版同款，玩家可用它「浇灭」岩浆源）。
其余三种情形与现状一致。

## §3 实现落点

- **契约**（`FluidFlowBehavior`，default `false` ⇒ 未覆写的流体**永不被替换**，对齐
  `WaterFluid`）：`canBeReplacedBy(FluidType incoming, boolean selfIsSource)`；
- **熔岩覆写**：`incoming == WATER && selfIsSource`（0.444 门槛的 2D 投影）；
- **水不覆写** ⇒ 水源格永不被替换（对齐 `WaterFluid`：仅 DOWN + 非水）；
- **引擎/pour**（`LivingBucketInteractSupport`）：源格且异种 ⇒ 问一句再决定是否覆盖；
  判定抽成包级私有纯函数 `replacesResidentSource(resident, incoming)` 供单测；
- **不新增客户端预判**：客户端快照虽有调色板可判流体类型，但复制一遍规则=第二份真相；
  拒绝时表现为「右键无反应」，与原版倒不进一致。

## §4 被否选项

| 选项 | 否掉的理由 |
|---|---|
| **A 严格对齐**（异种流动格也拒绝） | 用户选 B：保留「倒进异种流动格 ⇒ 直接覆盖」这个便利口子（容器里没有「高度」概念，门槛本身是 3D 规则） |
| **C 不动** | 用户选改造：原版「水浇岩浆源」是玩家可预期的直觉行为，缺失会导致「水桶对岩浆源毫无反应」的困惑 |
| 在 `pour` 里硬编码「水→岩浆源」 | 流体知识会漏进交互层；契约化后其它流体可各自表达（对齐既有 `frontierReaction` / `incinerateResult` 的接缝风格） |
| 客户端也预判拒绝 | 规则会分裂成两份；服务端权威已足够（拒绝时无反应=原版行为） |

## §5 连带影响

- 覆盖是**同键改写**（`generatedSources.put`）⇒ 下一拍 `pruneActual` 把该格的岩浆移除、
  下游失去供给的岩浆按目标层收敛退走，**不需要额外清理逻辑**；
- 该格若是**圆石/黑曜石**等产物格：倒水前必须格内无物品（客户端+服务端都判），不冲突；
- 覆盖后该格变成水源 ⇒ 可被活空桶汲走 ⇒ 「浇灭岩浆源后回收水」闭环成立；
- 岩浆源被覆盖 = **矿脉型资源被玩家主动销毁**，属预期（原版水桶就能这么做）。

## §6 测试守卫

| # | 守卫 | 落点 |
|---|---|---|
| G1 | `canBeReplacedBy` 默认 `false`（⇒ 水源格永不被替换，对齐 `WaterFluid`） | `LivingBucketInteractSupportTest` |
| G2 | 熔岩：`(WATER, true)` ⇒ true（可被水替换）；`(LAVA, true)`/`(WATER, false)`/`(LAVA, false)` ⇒ false | 同上 |
| G3 | `replacesResidentSource`：岩浆源+水 ⇒ true；同种 ⇒ false；水源+岩浆 ⇒ false | 同上 |
| G4 | 覆盖语义：岩浆源格被水覆盖 ⇒ `sourceFluid` 变水、tick 后实际层是水源、**下游岩浆退走** | `ContainerFluidDataTest` |

## §7 文档跟进清单

- [ ] `living-fluid-tech.md` §5（倒/汲语义补「异种源格 ⇒ 替换」）+ §9（`pour` 路径黑曜石**仍挂起**）
- [ ] `changelog.md` + `AGENTS.md` 进展行 + 测试计数
- [ ] `docs/TODO.md` 若有相关挂起项
- [ ] `doc_check.py` 全过

## §8 遗留

- **倒水进岩浆源 ⇒ 黑曜石**（原版 `pour` 侧的石头/黑曜石）**仍挂起**：原版倒桶路径本身
  不产生黑曜石（黑曜石只来自熔岩**蔓延**到水格，见机制五），故 B 档**不需要**补它——
  与 §3.5 早前记录的方向相反，此处更正认知。
- 目标格有物品 ⇒ 客户端不拦截 ⇒ 桶会被放进熔岩格（活的挤没源 / 非活的被焚毁）—— 单列项，未处理。
