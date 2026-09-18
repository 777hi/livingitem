# 备份快照 —— 2026-09-18 区块加载死锁修复

## 这批快照对应哪个阶段

修复「living_item 1.3.2 + Create 6.0.10 跨区块传送带 → 服务端线程死锁」时，
**改动文档之前**的原样备份。

| 文件 | 当时状态 |
|---|---|
| `AGENTS.md` | 20,759 字符（超 20,000 预算）、「开发进展」4 个批次（09-18/09-16/09-15/09-14）、测试基线 317 |
| `changelog.md` | 顶部为 `## 2026-09-14`，尚未接收 AGENTS 迁出的 09-14 批次 |
| `living-item-infrastructure.md` | §3.1 仍写 `ServerTickEvent.Post`、§3.2 清理表仍写 `onChunkUnload` 即时移除、§3.2 尚无「区块加载事件红线」 |

> 注意：快照是**修复过程中**的中间态 —— 其中 `AGENTS.md` 已含 2026-09-18 新进展条目，
> 但尚未做归档。要回到「修复前」请用 git，不要用这份。

## 怎么恢复

```bash
cp .workbuddy-ai/backup/2026-09-18-chunk-load-deadlock/AGENTS.md AGENTS.md
cp .workbuddy-ai/backup/2026-09-18-chunk-load-deadlock/changelog.md docs/archive/changelog.md
cp .workbuddy-ai/backup/2026-09-18-chunk-load-deadlock/living-item-infrastructure.md docs/system-design/living-item-infrastructure.md
python tools/doc_check.py   # 恢复后必须重跑
```

## 何时可删

**这批改动提交进 git 之后即可删** —— git 才是长期安全网。
