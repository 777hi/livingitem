# archive 层 —— 时间线与历史现场

> 33 份 / 801K（`changelog.md` 358K 占 45%）。**只有问「哪天」才来这里**；问「为什么」去 [decisions](../decisions.md) 或该子系统的 tech §0。
> 清单不在此维护：`find docs\archive -name "*.md"`

## 读法

1. **本层是现场，不是现状**：这里的设计稿、计划、审查台账描述的是**当时**，不得被活跃层当作「现状 / 工具」路由（规约见 [docs/README.md](../README.md) §1）。
2. **按用途定位**：
   - **时间线**：[changelog.md](changelog.md)（按日期倒序，一行一事，是「哪天改的」的唯一来源）
   - **原始设计稿**：`*-design.md` / `活*-design.md`（当时怎么想的；结论已归位到 tech / decisions）
   - **重构与迁移计划**：`*-plan.md` / `*-refactoring*.md`（做完即归档，不更新）
   - **审查与事故台账**：`problem-*.md` / `dev-issues.md` / `*-review.md`（当时的现场记录）
3. **从本层取结论要回链**：引用归档里的判断时，必须找到它在稳定层（tech / decisions / api-contract）的现行版本，否则按「已过时」处理。
4. **本层不维护、不整理、不重写**：只追加。历史不改写。

## 与其他层的边界

| 要查的 | 不在本层，去 |
|---|---|
| 现在的行为是什么 | 该子系统的 `docs/tech/living-*-tech.md` §0 |
| 为什么这么定 / 是否被取代 | [decisions.md](../decisions.md) |
| 还没定的方案 | [buffer](../buffer/README.md) |
| 未实现部分 | [TODO.md](../TODO.md) |
