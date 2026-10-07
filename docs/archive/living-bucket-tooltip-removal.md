> 🗄 **已归档（2026-10-07）**：本文是**方案留痕**，记录「定稿值 + 被否选项 + 为什么否」，
> 对应改动（活桶 tooltip 删内容行 + 活物品段标题保留）**已落地**（2026-10-07）。
> **现行口径以稳定层为准**（流体：`docs/tech/living-fluid-tech.md`；其余见对应子系统文档 +
> `docs/archive/changelog.md`） —— 本文**不是**现行规范，仅用于回答「当初为什么这么定」。

# 活桶 tooltip 定稿：删行 + 活物品标题惰性化（2026-10-07）

> 状态：**已定稿（用户拍板）**。起因是实测看到 tooltip 里漏出 `%1$s × %2$s mB` 占位符。

## 1. 现象与根因（三个环节，逐一有据）

玩家实测：手持活水桶，tooltip 里出现

```
水桶
minecraft:water_bucket
7个组件

--- 活物品 ---
    储存: %1$s × %2$s mB      ← 占位符原样漏出
Minecraft
```

**环节 1：文案有两个占位符，代码只传了一个参数。**

| 位置 | 内容 |
|---|---|
| `zh_cn.json` / `en_us.json` `tooltip.livingitem.bucket.content` | `"  储存: %1$s × %2$s mB"` / `"  Stored: %1$s × %2$s mB"` |
| `LivingBucketFunction.addToTooltip` | `Component.translatable(key, fluid.getFluidType().getDescription())` —— **1 个参数** |

**环节 2：原版对参数不足的降级是「整条模板当纯文本」。**
`TranslatableContents.java:114-120` — `decomposeTemplate` 抛 `TranslatableFormatException` 时
`decomposedParts = ImmutableList.of(FormattedText.of(s))`（`s` = 语言文件里的原文模板）。
参数越界来自 `getArgument(index)` 的 `index >= args.length ⇒ throw`（同文件 173-184）。
⇒ 所以不是"只漏 `%2$s`"，而是**两个占位符一起原样显示**（这正是截图的样子）。

**环节 3：来历 = FluidStack 时代的遗物。**
`e2d7aa1`（流体侧批次二 · 桶源退役）引入该 lang 时，代码传**两个**参数：
`content.copy().getHoverName()`、`content.getAmount()`（分数源时代：名字 × 量）。
后来 `getBucketFluid` 从返回 `FluidStack` 改回返回 `Fluid`（原版 `BucketItem.content`），
参数**减到 1 个**，lang **没同步** ⇒ 从那批起这条 tooltip 就一直是坏的（用户 10-07 才看到）。

**全仓审计**（一次性脚本 `build/_audit_lang_args.py`，扫全仓字面量 key 调用点 × zh/en 占位符数）：
- **参数不足：仅此 1 处**；
- zh/en 占位符数不一致：**0 处**；
- 参数多余 145 处 —— 原版**忽略**多余参数，无害（所以只有这一处出问题）。

## 2. 定稿：删掉这行（用户拍板）

> 原话：「其实，没有可以显示的信息在tooltip上的。」

理由：**桶的形态本身就是信息** —— 物品形态即`X.getBucket()` / `Items.BUCKET`
（见 `LivingBucketFunction.withFluid`），玩家看到水桶就装水、岩浆桶就装岩浆、空桶就是空。
tooltip 再写一遍内容是**冗余**，而且"储存 × mB"对桶物品（恒 1 个源 = 1000 mB）是废话。

**不做 B 档（补第二个参数显示 `× 1000 mB`）** —— 已否，理由同上。

改动：
1. `LivingBucketFunction`：**删掉 `addToTooltip` 覆写**（`LivingItemFunction` 的 default 就是 no-op），
   连带清掉随之无用的 `Component` / `TooltipFlag` import；
2. lang（zh + en）：**删掉 `tooltip.livingitem.bucket.content`**。

## 3. 配套：「活物品」标题**保留**（用户二次拍板）

> 原话：「标题不要删，标题是正常的。」

**注意：这不是 bug，是标记。** `--- 活物品 ---` 是「这个物品是活物品」的**标记**，
不是内容行 —— 哪怕功能一行都不产出（活桶），标题与其上方的分隔空行**照旧输出**。

我曾实现过「零行 ⇒ 整段不输出」的**惰性标题**（怕留空标题），**已被否**：
那会把「这是活物品」这个标记一起丢掉。tooltip 变成：

```
水桶
minecraft:water_bucket
7个组件

--- 活物品 ---
Minecraft
```

—— 这就是定稿后的样子（标题在、内容行没有）。

保留的实现改动：**渲染体抽成可测静态方法** `renderSection(functions, ctx, flag, stack, sink)`
（functions 由调用方传入，事件处理器传 `LivingItemManager.getAllFunctions()`）——
 行为与原先一致（无条件先输出「空行 + 标题」），但去掉了对全局单例的依赖，测试可喂 stub。

## 4. 测试

- `LivingBucketFunctionTest` +1：**活桶不产出任何 tooltip 行**（钉住"桶无内容行"这条定稿，
  以后有人再加回冗余行会红）；
- 新增 `LivingItemTooltipTest`（client/render）5 项：零功能行 ⇒ **仍输出**标题 /
  不适用该 stack 的功能 ⇒ 同样保留标题 / **真实活桶 ⇒ 只有标题没有内容行**（两条定稿合起来的表现）/
  有内容行 ⇒ 空行+标题+行 / 多功能 ⇒ 标题只出现一次、行序保持。

## 5. 顺手发现（**本批不做**，只记档）

`tooltip.livingitem.water_bucket.status`（`§9活水桶§r`）与 `.water_bucket.flow`
（`  水流: %1$s 格 (最远%2$s格)`）在 `src/main/java` 里**0 引用** —— 也是死键
（活水轮用的是 `tooltip.livingitem.water_wheel.*`）。等实测收口一并清理。