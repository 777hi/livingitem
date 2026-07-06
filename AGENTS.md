# Living Item Template (活物品模板)

**Minecraft 1.21.1 + NeoForge**

## 📖 项目概述

将世界中的方块功能（熔炉、漏斗等）**活化到物品层面**。活物品在容器（箱子、背包等）内自动运行，状态通过NBT持久化。

### 核心特性

- **活按钮UI**: 点击可将手持物品转化为活物品
- **容器内自动执行**: 含活物品的被加载容器会自动tick
- **相对寻址**: 活物品自动识别周围槽位作为输入/输出/燃料端
- **状态持久化**: 所有运行数据保存在物品NBT中
- **跨容器迁移**: 移动活物品时保留完整状态（如燃烧时间）

---

## 🏗️ 架构设计

### 中间层框架

```
LivingFunctionConfig (配置声明)
       ↓
FunctionExecutor (编排调度)
       ↓
┌─────────────────────────────┐
│  ProgressComponent          │ ← 进度管理
│  FuelConsumeComponent       │ ← 燃料消耗  
│  ItemTransformComponent      │ ← 物品转化
└─────────────────────────────┘
       ↓
ContainerContext → SimpleContainerContext (容器操作)
```

### 关键组件

| 层级 | 文件 | 职责 |
|------|------|------|
| **核心层** | `LivingItemManager` | DataComponent注册、数据读写枢纽 |
| | `LivingFunctionData` | NBT数据容器、Tooltip显示 |
| | `LivingItemFunction` | 功能接口定义 |
| **中间层** | `FunctionExecutor` | 组件编排核心（单例） |
| | `SlotResolver` | 槽位解析（支持边界检查） |
| | `ComponentContext` | 组件执行上下文 |
| **组件层** | `ProgressComponent` | 进度计时与暂停逻辑 |
| | `FuelConsumeComponent` | 燃料消耗与可用性检查 |
| | `ItemTransformComponent` | 配方匹配与物品转化 |
| **容器层** | `ContainerContext` | 容器操作抽象接口 |
| | `SimpleContainerContext` | 具体实现（带异常保护） |
| | `ContainerLivingItemHandler` | 容器扫描与分组调度 |

---

## ✅ 已完成功能

### 基础设施
- [x] 活按钮UI与物品活化机制
- [x] DataComponent数据持久化系统
- [x] 容器自动扫描与tick分发
- [x] 多活物品并行处理（无冲突）
- [x] 物品栏信息栏数据显示（进度、状态等）

### 活熔炉功能
- [x] 自动识别输入(左)/燃料(下)/输出(右)槽位
- [x] 配方匹配与物品转化
- [x] 燃料消耗与燃烧时间管理
- [x] 无效条件时暂停并回退进度
- [x] 非燃料物品不触发消耗
- [x] **跨容器状态保持**（移动后保留burnTime）
- [x] **环境自适应**（如漏斗中无燃料槽时使用储备时间）
- [x] 多实例加速（不同槽位的活熔炉独立工作）

### 容器兼容性
- [x] 标准矩形容器（27格箱子、54格大箱）
- [x] 线性容器（5格漏斗）✨ *已验证工作正常*
- [x] 边界检查与异常安全（防崩溃）
- [x] 槽位越界保护（ArrayIndexOutOfBoundsException修复）
- [x] 容器销毁时的竞态条件处理

### 性能与稳定性
- [x] 槽位冲突检测（多活物品不重复操作同槽位）
- [x] 条件门控（无有效输入/燃料时不增长进度）
- [x] 预计算缓存架构（ContainerCacheManager）
- [x] 混合解析器（HybridContainerResolver - 5层优先级链）
- [x] 运行时验证器（RuntimeContainerValidator - 可选）

---

## 🚧 开发进展

### 当前版本: v0.2-alpha

**最近更新** (2026-07-06):
- ✅ 修复容器销毁时崩溃问题（4层防御体系）
- ✅ 实现完整的容器兼容性方案（5种策略）
- ✅ 验证跨容器状态保持特性（漏斗测试通过）

### 待办事项

#### 高优先级
- [ ] UI视觉反馈（储备燃料 vs 外部燃料的差异化显示）
- [ ] Tooltip增强（显示工作模式、预计剩余时间）
- [ ] 音效差异化（不同状态的音效变化）

#### 中优先级  
- [ ] 容器生命周期监听（破坏前主动清理）
- [ ] 调试命令 `/livingitem info`
- [ ] 成就系统集成

#### 低优先级 / 未来规划
- [ ] 更多活物品类型（活药锅、活工作台、活耕地等）
- [ ] 活物品状态切换机制（如漏斗传输方向切换）
- [ ] 第三方模组适配器API开放
- [ ] JSON配置文件支持（用户自定义容器规则）

---

## 🔧 技术栈

- **Java 21** + **NeoForge 21.1.x**
- **Minecraft 1.21.1**
- 构建工具: Gradle 9.2.1
- 数据持久化: Minecraft DataComponent API + NBT
- 容器访问: NeoForge Container接口

---

## 📂 核心文件索引

```
src/main/java/com/qiqi/li/living/
├── core/
│   ├── FunctionExecutor.java          # ⭐ 编排核心
│   ├── SlotResolver.java               # 槽位解析
│   ├── Direction2D.java                # 方向枚举
│   ├── ContainerCacheManager.java       # 缓存管理
│   ├── HybridContainerResolver.java    # 混合解析器
│   ├── RuntimeContainerValidator.java  # 运行时验证
│   └── components/
│       ├── ProgressComponent.java      # 进度组件
│       ├── FuelConsumeComponent.java   # 燃料组件
│       └── ItemTransformComponent.java # 转化组件
├── adapters/                           # 特殊容器适配器
│   ├── ContainerAdapter.java           # 适配器接口
│   ├── HopperAdapter.java             # 漏斗适配器
│   └── AdapterRegistry.java           # 注册中心
├── config/
│   └── ContainerCompatibilityConfig.java  # 兼容性配置
├── LivingItemManager.java              # 核心管理器
├── LivingFurnaceFunction.java         # 活熔炉实现
├── SimpleContainerContext.java        # 容器上下文
└── ContainerLivingItemHandler.java    # 容器处理器
```

---

## 🎯 设计原则

1. **能力组件化**: 功能拆分为可复用的原子组件
2. **配置驱动执行**: 通过声明式配置组合功能
3. **状态完全持久化**: 所有数据存储在NBT，跟随物品迁移
4. **防御性编程**: 多层边界检查，优雅降级不崩溃
5. **开放扩展**: 支持第三方容器适配器和自定义活物品

---

## 💡 已知限制

- 当前仅支持**相对寻址**（上下左右），暂不支持自定义槽位映射
- 大型容器（>256格）可能需要特殊处理
- AE2/RS等虚拟存储网络尚未支持
- 暂无GUI配置工具（需代码注册新活物品类型）

---

## 📚 相关文档

- [容器兼容性完整指南](./CONTAINER_COMPATIBILITY_GUIDE.md) - 5种兼容方案的详细说明
- [开发日志](./docs/) - （待创建）

---

## 🤝 贡献指南

### 开发规范
- 新活物品类型: 实现 `LivingItemFunction` 接口 + 创建对应的 `LivingFunctionConfig`
- 新容器支持: 实现 `ContainerAdapter` 接口或添加 `ContainerRule` 配置
- 新组件: 继承基础组件接口并在 `FunctionExecutor` 中注册

### 代码风格
- 使用中文注释（与项目语言一致）
- 遵循现有命名约定（Config/Component/Context后缀）
- 异常处理必须使用try-catch包装容器操作

---

*最后更新: 2026-07-06*  
*状态: Alpha测试阶段 - 核心功能已完成，正在完善用户体验*