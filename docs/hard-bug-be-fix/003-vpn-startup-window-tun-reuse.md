# VPN 启动窗口期保护与 TUN 复用

## 问题现象

1. 跨配置切换时，VPN 重启耗时较长
2. VPN 启动后短时间内，NetworkCallback 初始回调会重复调用 `setUnderlyingNetworks`，导致 UDP 连接断开

## 根因分析

### 问题 1: 跨配置切换重建 TUN 耗时

每次配置切换都重新调用 `builder.establish()` 创建新的 TUN 接口，即使旧接口仍然有效。

### 问题 2: NetworkCallback 初始回调干扰

`registerNetworkCallback` 注册后会立即触发 `onAvailable` 回调，此时如果调用 `setUnderlyingNetworks`，会覆盖 `openTun` 中刚设置的网络，导致 UDP 连接断开。

## 修复方案

### 1. TUN 接口复用 (SingBoxService.kt)

跨配置切换时，如果现有的 VPN 接口仍然有效，直接复用其 fd：

```kotlin
override fun openTun(options: TunOptions): Int {
    synchronized(this@SingBoxService) {
        val existingInterface = vpnInterface
        if (existingInterface != null) {
            val existingFd = existingInterface.fd
            if (existingFd >= 0) {
                Log.i(TAG, "Reusing existing vpnInterface (fd=$existingFd) for fast config switch")
                return existingFd
            } else {
                // fd 无效，关闭并重建
                try { existingInterface.close() } catch (_: Exception) {}
                vpnInterface = null
            }
        }
    }
    // ... 正常创建新接口
}
```

### 2. VPN 启动窗口期保护 (SingBoxService.kt)

在 VPN 启动后的短时间内，`updateDefaultInterface` 跳过 `setUnderlyingNetworks` 调用：

```kotlin
// VPN 启动窗口期保护 - 参考 NekoBox 设计
private val vpnStartedAtMs = AtomicLong(0)
private val vpnStartupWindowMs: Long = 3000L // 启动后 3 秒内跳过重复设置

// openTun 中设置时间戳
vpnStartedAtMs.set(SystemClock.elapsedRealtime())
lastSetUnderlyingNetworksAtMs.set(SystemClock.elapsedRealtime())
```

### 3. startDefaultInterfaceMonitor 提前设置时间戳

在注册 NetworkCallback 之前就设置时间戳，避免初始回调立即触发 `setUnderlyingNetworks`：

```kotlin
override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
    // 提前设置启动窗口期时间戳，避免 NetworkCallback 的初始回调触发 setUnderlyingNetworks
    vpnStartedAtMs.set(SystemClock.elapsedRealtime())
    lastSetUnderlyingNetworksAtMs.set(SystemClock.elapsedRealtime())

    // ... 注册 NetworkCallback
}
```

## 修改的文件

- `app/src/main/java/com/kunk/singbox/service/SingBoxService.kt`
  - `openTun` 添加 TUN 接口复用逻辑
  - 添加 `vpnStartedAtMs` 和 `vpnStartupWindowMs` 字段
  - `startDefaultInterfaceMonitor` 提前设置时间戳

## 验收标准

1. 跨配置切换时 VPN 重启更快
2. VPN 启动后 UDP 连接（如 QUIC）保持稳定
3. 无重复的 `setUnderlyingNetworks` 调用日志

## 修复日期

2025-01-10
