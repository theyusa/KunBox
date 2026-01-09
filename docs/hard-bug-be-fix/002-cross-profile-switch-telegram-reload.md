# 跨配置切换导致 Telegram 二次加载

## 问题现象

1. 启动 VPN 连接到节点 A（配置 1）
2. 切换到配置 2 的节点 B
3. 再切换到配置 2 的节点 C（同配置内切换）
4. 此时 UI 显示节点 C，通知栏显示节点 C，但实际流量走的是节点 B

另一个问题：Telegram 在节点切换时反复显示"连接中..."多次。

## 根因分析

### 问题 1: 跨配置切换后同配置热切换失效

**根因**：`ConfigRepository.kt` 中 `lastRunOutboundTags` 在 App 重启后为 null，导致 `tagsChanged` 判断逻辑不完善。

关键场景：
- `isVpnStartingNotReady`：VPN 正在启动但核心还没准备好接受热切换
- `needsConfigReload`：`lastRunOutboundTags == null && remoteRunning` 表示状态丢失

如果不检测这些场景，热切换请求会发送到尚未准备好的 sing-box 核心，导致切换无效。

### 问题 2: Telegram 反复加载

**根因**：之前的代码在节点切换时会触发"网络震荡"（`setUnderlyingNetworks(null)` 然后恢复），这会向系统发送 CONNECTIVITY_CHANGE 广播，导致 Telegram 等应用感知到网络变化并重新加载。

同时，多个地方会调用 `closeAllConnectionsImmediate()`，每次调用都会触发应用重连。

## 修复方案

### 1. 完善跨配置切换判断逻辑 (ConfigRepository.kt)

```kotlin
// 2025-fix-v5: 统一的重启判断逻辑
val tagsActuallyChanged = lastRunOutboundTags != null && lastRunOutboundTags != currentTags
val isVpnStartingNotReady = SingBoxRemote.isStarting.value && !SingBoxRemote.isRunning.value
val needsConfigReload = lastRunOutboundTags == null && remoteRunning

val tagsChanged = tagsActuallyChanged || profileChanged || isVpnStartingNotReady || needsConfigReload
```

### 2. 跨配置切换预清理机制 (ConfigRepository.kt)

在 VPN 重启前发送 `ACTION_PREPARE_RESTART`，让 Service 提前关闭现有连接：

```kotlin
if (tagsChanged && remoteRunning) {
    val prepareIntent = Intent(context, SingBoxService::class.java).apply {
        action = SingBoxService.ACTION_PREPARE_RESTART
    }
    context.startService(prepareIntent)
    delay(200)
}
```

### 3. 移除 hotSwitchNode 中的网络震荡 (SingBoxService.kt)

NekoBox 风格：直接依赖 sing-box 的 `interrupt_exist_connections` 机制，不额外触发网络震荡：

```kotlin
suspend fun hotSwitchNode(nodeTag: String): Boolean {
    // Step 1: wake() 唤醒核心
    boxService?.wake()

    // Step 2: selectOutbound 切换节点
    // sing-box 内部自动处理连接中断 (interrupt_exist_connections=true)
    client.selectOutbound(selectorTag, nodeTag)

    // 不再调用:
    // - setUnderlyingNetworks(null) / setUnderlyingNetworks(network)
    // - closeAllConnectionsImmediate()
    // - requestCoreNetworkReset()
}
```

### 4. 添加连接重置防抖 (SingBoxService.kt)

```kotlin
private var lastConnectionsResetAtMs: Long = 0L
private val connectionsResetDebounceMs: Long = 2000L

private suspend fun closeAllConnectionsImmediate(skipDebounce: Boolean = false) {
    val now = SystemClock.elapsedRealtime()
    val elapsed = now - lastConnectionsResetAtMs
    if (!skipDebounce && elapsed < connectionsResetDebounceMs) {
        return // 2秒内不重复重置
    }
    lastConnectionsResetAtMs = now
    // ...
}
```

### 5. 简化屏幕唤醒/应用前台健康检查

移除网络震荡，只调用 `wake()` 和可选的 `resetNetwork()`：

```kotlin
private suspend fun performScreenOnHealthCheck() {
    service.wake()
    boxService?.resetNetwork()
    // 不再调用 closeAllConnectionsImmediate() 和 setUnderlyingNetworks 震荡
}

private suspend fun performAppForegroundHealthCheck() {
    service.wake()
    // 不进行网络震荡
}
```

### 6. Doze 退出时强制关闭连接

只在设备退出 Doze 模式时强制关闭所有连接（`skipDebounce=true`）：

```kotlin
PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
    if (powerManager?.isDeviceIdleMode == false) {
        boxService?.wake()
        closeAllConnectionsImmediate(skipDebounce = true) // Doze 退出是关键时刻
    }
}
```

## 修改的文件

- `app/src/main/java/com/kunk/singbox/repository/ConfigRepository.kt`
  - 完善 `tagsChanged` 判断逻辑
  - 添加 `ACTION_PREPARE_RESTART` 预清理机制

- `app/src/main/java/com/kunk/singbox/service/SingBoxService.kt`
  - 移除 `hotSwitchNode` 中的网络震荡
  - 添加 `closeAllConnectionsImmediate` 防抖
  - 简化屏幕唤醒/应用前台健康检查
  - 添加 Doze 退出监听

- `app/src/main/java/com/kunk/singbox/service/ProxyOnlyService.kt`
  - 添加 `ACTION_PREPARE_RESTART` 处理

## 验收标准

1. 跨配置切换后，同配置内热切换正常工作
2. 切换节点时，Telegram 只显示一次"连接中..."，不会反复加载
3. UI/通知栏/实际流量保持一致

## 修复日期

2025-01-10
