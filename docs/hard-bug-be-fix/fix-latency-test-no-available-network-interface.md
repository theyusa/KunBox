# 修复：节点测速 "no available network interface" 错误

## 问题描述

所有节点测速都超时失败，即使节点本身可以正常上网。无论 VPN 是否运行，测速都返回 502 错误。

## 错误日志

```
ERROR: no available network interface
ERROR: connection: open connection to cp.cloudflare.com:80 using outbound/vless[...]: no available network interface
ERROR: dns: lookup failed for cdns.doon.eu.org: no available network interface
```

## 根因分析

### 测速架构

KunBox 使用临时 `BoxService` + HTTP 代理方式进行测速：

1. 为每个节点（或批量节点）启动一个临时 `BoxService`
2. `BoxService` 监听本地端口 (127.0.0.1:随机端口)
3. `OkHttpClient` 通过该端口发起 HTTP 请求
4. 测量响应时间

### 问题定位

sing-box 内核在 `route/network.go` 中初始化网络管理器时，会检查 `platformInterface` 是否存在：

```go
// 当 platformInterface != nil 时，使用 platform 的 interfaceMonitor
if usePlatformDefaultInterfaceMonitor {
    interfaceMonitor := nm.platformInterface.CreateDefaultInterfaceMonitor(logger)
    interfaceMonitor.RegisterCallback(nm.notifyInterfaceUpdate)
    nm.interfaceMonitor = interfaceMonitor
}
```

`CreateDefaultInterfaceMonitor` 内部会调用 `startDefaultInterfaceMonitor` 回调，期望 Android 端通知当前的默认网络接口。

**问题代码** (`SingBoxCore.kt` 中的 `TestPlatformInterface`)：

```kotlin
override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
    // 测速服务不需要网络接口监控，直接返回
    // 注意：在 cgo 回调中注册 NetworkCallback 会导致 Go runtime 栈溢出崩溃
}
```

这个空实现导致 sing-box 内核无法获取默认网络接口信息。当 `autoDetectInterface = true` 但 `interfaceMonitor` 没有收到任何接口更新时，`AutoDetectInterfaceFunc()` 无法确定使用哪个网络接口，从而报错 `no available network interface`。

### 为什么 VPN 服务没有这个问题？

`SingBoxService.kt` 中的 `startDefaultInterfaceMonitor` 实现正确：

1. 同步获取当前活跃网络
2. 立即调用 `listener.updateDefaultInterface()` 通知 sing-box
3. 延迟注册 `NetworkCallback` 以监听后续网络变化

## 修复方案

修改 `TestPlatformInterface.startDefaultInterfaceMonitor()` 实现，同步获取当前网络状态并立即通知 sing-box：

```kotlin
override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
    if (listener == null) return
    
    try {
        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork != null) {
            val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
            val interfaceName = linkProperties?.interfaceName ?: ""
            if (interfaceName.isNotEmpty()) {
                val index = try {
                    java.net.NetworkInterface.getByName(interfaceName)?.index ?: 0
                } catch (e: Exception) { 0 }
                val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
                val isExpensive = caps?.hasCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED
                ) == false
                listener.updateDefaultInterface(interfaceName, index, isExpensive, false)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "TestPlatformInterface: failed to get default interface: ${e.message}")
    }
}
```

### 关键点

1. **必须同步通知** - 在 `startDefaultInterfaceMonitor` 返回前调用 `listener.updateDefaultInterface()`
2. **不能注册 NetworkCallback** - 在 cgo 回调中注册系统回调会导致 Go runtime 栈溢出崩溃
3. **测速服务是短暂的** - 不需要监听网络变化，只需要初始状态

## 修改文件

| 文件 | 修改内容 |
|------|----------|
| `app/src/main/java/com/kunk/singbox/core/SingBoxCore.kt` | `TestPlatformInterface.startDefaultInterfaceMonitor()` 从空实现改为同步获取并通知默认网络接口 |

## 参考

- sing-box 源码: `route/network.go` - `NewNetworkManager()`, `AutoDetectInterfaceFunc()`
- sing-box 源码: `experimental/libbox/service.go` - `platformInterfaceWrapper`
- NekoBox/sing-box-for-android 的实现方式

## 测试验证

修复后，测速功能应正常工作：
- VPN 未运行时：测速流量通过物理网络
- VPN 运行时：测速流量通过 `protect(fd)` 绕过 VPN
