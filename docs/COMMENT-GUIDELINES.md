# 代码注释优化指南

## 现状分析

经过分析,项目现有注释统计:
- **总注释行数**: ~1474 行
- **平均每文件**: ~15.8 行
- **注释最多的文件**:
  - SingBoxService.kt: 358 行
  - ConfigRepository.kt: 325 行
  - SingBoxCore.kt: 101 行

## 注释质量评估

✅ **优点**:
- 大部分注释解释了**为什么**(Why)这样做,而不仅仅是**做什么**(What)
- 记录了重要的 bug 修复和 workaround
- 标注了关键的架构决策和性能优化理由
- 对复杂逻辑有清晰的分步说明

❌ **可改进**:
- 少数注释过于简短,缺乏上下文
- 部分注释描述显而易见的操作
- 缺少统一的注释风格和格式

## 注释编写原则

### 应该写注释的场景

1. **解释"为什么"(Why)**
```kotlin
// ✅ 好的注释 - 解释原因和背景
// 华为设备修复: 追踪是否已经调用过 startForeground(),避免重复调用触发提示音
private val hasForegroundStarted = AtomicBoolean(false)

// ❌ 不好的注释 - 只描述做什么
// 创建一个 AtomicBoolean
private val hasForegroundStarted = AtomicBoolean(false)
```

2. **标注重要的修复和 Workaround**
```kotlin
// ✅ 记录修复的问题
// 修复: Android 14+ 网络切换时,setUnderlyingNetworks 需要短暂延迟
// 否则会导致 "use of closed network connection" 错误
delay(100)

// ❌ 没有说明为什么延迟
// 延迟 100ms
delay(100)
```

3. **解释非常规的代码和魔法数字**
```kotlin
// ✅ 解释为什么是这个值
// 防抖时间 250ms: 平衡响应速度和避免过度调用
// 实测 Android 14 网络切换间隔约 100-200ms
private val debounceMs: Long = 250L

// ❌ 没有解释
// 防抖时间
private val debounceMs: Long = 250L
```

4. **复杂算法的分步说明**
```kotlin
// ✅ 清晰的步骤注释
// 热切换流程:
// 1. 调用 libbox selectOutbound 切换节点
// 2. 关闭所有旧连接(避免复用旧连接)
// 3. 触发系统级网络重置(让应用感知网络变更)
// 4. 清理 DNS 缓存

// ❌ 无注释或仅有冗余注释
// 切换节点
// 关闭连接
// 重置网络
```

5. **临时代码和待办事项**
```kotlin
// ✅ 明确标注
// TODO: 升级到 libbox 1.10+ 后可以移除此 workaround
// FIXME: 部分设备上可能导致内存泄漏,需要进一步排查
// HACK: 绕过系统限制,需要测试兼容性
```

### 不应该写的注释

1. **描述显而易见的代码**
```kotlin
// ❌ 冗余注释
// 设置 URL
url = newUrl

// ✅ 直接不写注释,变量名已经说明了
url = newUrl
```

2. **重复函数/变量名的注释**
```kotlin
// ❌ 没有附加信息
// 获取设置
fun getSettings()

// ✅ 要么不写,要么补充细节
// 从 DataStore 加载设置,缓存 500ms 避免频繁读取
fun getSettings()
```

3. **过时的注释**(比删除注释更危险)
```kotlin
// ❌ 代码已改但注释未更新
// 返回节点列表 (实际代码已改为返回 Flow)
fun getNodes(): Flow<List<Node>>

// ✅ 保持同步或删除
fun getNodes(): Flow<List<Node>>
```

4. **被注释掉的代码**(应该删除或说明保留原因)
```kotlin
// ❌ 没有说明为什么保留
// private var oldImplementation: String? = null

// ✅ 说明保留原因
// 保留用于回滚测试,预计 v2.0 删除
// private var oldImplementation: String? = null

// ✅✅ 更好的做法:直接删除,依赖 Git 历史
```

## 注释格式规范

### 单行注释
```kotlin
// 标准格式: // 后面空一格,使用中文,首字母小写(除非专有名词)
// 这是一个标准的单行注释

// ❌ 不规范
//没有空格
//  多个空格
// 句号结尾。
```

### 多行注释
```kotlin
/**
 * 函数文档注释
 *
 * @param configPath 配置文件路径
 * @return 是否启动成功
 */
fun startVpn(configPath: String): Boolean

// 或者用于复杂逻辑的分块注释
/*
 * 网络切换处理流程:
 * 1. 检测到网络变化
 * 2. 更新底层网络
 * 3. 触发重连
 */
```

### 重要标记
```kotlin
// CRITICAL: 修改此处需要同步更新 VpnStateStore
// IMPORTANT: 此操作必须在主线程执行
// WARNING: 可能导致 ANR,确保在后台线程调用
// NOTE: Android 12+ 需要额外权限
// TODO: 优化性能
// FIXME: 已知 bug,待修复
// HACK: 临时方案,需要重构
```

## 推荐的注释密度

- **核心业务逻辑**: 20-30% (每 3-5 行代码有 1 行注释)
- **工具类/辅助函数**: 10-15% (主要是函数文档)
- **Model/Data 类**: 5-10% (主要解释非常规字段)
- **测试代码**: 15-20% (解释测试场景和预期)

## 注释 vs 代码重构

有时候,过多注释意味着代码本身需要重构:

```kotlin
// ❌ 用注释弥补糟糕的命名
// 获取当前激活的节点的延迟
val latency = getCurrentActiveNodeLatency()

// ✅ 好的命名不需要注释
val activeNodeLatency = getCurrentActiveNodeLatency()
```

```kotlin
// ❌ 复杂逻辑需要大量注释
// 检查是否是 VIP 用户并且订阅未过期并且流量足够
if (user.vip && user.expireAt > now() && user.traffic > 1000) { ... }

// ✅ 提取到命名良好的函数
fun User.canUseService() = isVipActive() && hasEnoughTraffic()
if (user.canUseService()) { ... }
```

## 实施建议

### 短期(保守策略)
1. **不进行大规模注释删除** - 现有注释质量较好,保留
2. **只清理明显冗余的** - 空注释行、纯分隔符等
3. **补充遗漏的关键注释** - 特别是 workaround 和 bug 修复

### 长期(逐步优化)
1. **新代码遵循本指南** - 设置 Code Review 检查点
2. **重构时优化注释** - 修改代码时同步更新注释
3. **定期清理过时注释** - 每个版本 Release 前检查

## 工具支持

### Android Studio 插件
- **TODO Highlight**: 高亮 TODO/FIXME 等标记
- **Comment Formatter**: 自动格式化注释
- **Doc Generator**: 生成函数文档注释模板

### Git Hooks
在 commit 时检查:
- 被注释掉的代码是否有说明
- TODO/FIXME 是否有 issue 链接
- 新增代码是否有必要的注释

## 总结

好的注释应该:
- ✅ 解释**为什么**(Why)而不是**做什么**(What)
- ✅ 记录重要的决策、修复和 workaround
- ✅ 帮助未来的维护者(包括6个月后的自己)理解代码
- ✅ 与代码保持同步更新

避免的注释:
- ❌ 重复代码语义
- ❌ 描述显而易见的操作
- ❌ 过时且未更新
- ❌ 用注释弥补糟糕的代码

**记住**: 注释是为了解释那些代码本身无法表达的内容。如果代码足够清晰,就不需要注释;如果需要大量注释,可能是代码需要重构。

---

*编写时间: 2026-01-09*
*基于项目实际代码分析*
