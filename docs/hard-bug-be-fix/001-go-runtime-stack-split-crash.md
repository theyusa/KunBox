# Go Runtime Crash: stack split at bad time

## 问题现象

VPN 无法启动，应用直接崩溃。logcat 显示：

```
fatal error: runtime: stack split at bad time

runtime stack:
runtime.throw({0x7b83e0dd06?, 0x0?})
        runtime/panic.go:1077 +0x40 fp=0x7ffd5fbca0 sp=0x7ffd5fbc70 pc=0x7b872ce420
runtime.newstack()
        runtime/stack.go:1120 +0x400 fp=0x7ffd5fbe50 sp=0x7ffd5fbca0 pc=0x7b872e7170
runtime.morestack()
        runtime/asm_arm64.s:347 +0x70 fp=0x7ffd5fbe50 sp=0x7ffd5fbe50 pc=0x7b872fcf40
```

## 根因分析

### 直接原因
`SingBoxService.startDefaultInterfaceMonitor()` 在 libbox cgo callback 上下文中直接调用 `ConnectivityManager.registerNetworkCallback()`。

### 底层原理
1. libbox (sing-box Go 库) 通过 JNI 调用 `PlatformInterface.startDefaultInterfaceMonitor()`
2. 此时 Go runtime 处于 cgo callback 状态，栈是固定大小的
3. `registerNetworkCallback()` 是系统 API，内部可能触发 binder IPC、线程切换等复杂操作
4. 这些操作导致 Go runtime 尝试扩展栈，但在 cgo callback 期间不允许栈扩展
5. Go runtime panic: `stack split at bad time`

### 证据链
- crash 发生在 `startDefaultInterfaceMonitor` 被调用时
- 该方法由 libbox 通过 `PlatformInterface` 回调触发
- 移除 `registerNetworkCallback()` 调用后 crash 消失

## 修复方案

参考 NekoBox 的设计模式：

### 1. Application 层预缓存网络 (已存在)

`DefaultNetworkListener.kt` 在 `SingBoxApplication.onCreate()` 启动时注册网络监听，缓存当前物理网络到 `underlyingNetwork` 字段。

### 2. VPN Service 使用缓存值

在 `startDefaultInterfaceMonitor()` 中：
- 直接使用 `DefaultNetworkListener.underlyingNetwork` 获取预缓存的网络
- 不在 cgo callback 中调用任何可能触发栈扩展的系统 API

### 3. 延迟注册 NetworkCallback

通过 `Handler.post()` 将 `registerNetworkCallback()` 延迟到 cgo callback 返回之后执行：

```kotlin
private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
    // 使用预缓存的网络，立即返回给 libbox
    var initialNetwork: Network? = DefaultNetworkListener.underlyingNetwork
    // ... 处理 initialNetwork ...

    // 延迟注册 callback，避免在 cgo callback 中调用
    mainHandler.post {
        registerNetworkCallbacksDeferred()
    }
}
```

## 修改的文件

- `app/src/main/java/com/kunk/singbox/service/SingBoxService.kt`
  - 添加 `mainHandler` 字段
  - 重写 `startDefaultInterfaceMonitor()` 使用预缓存网络
  - 新增 `registerNetworkCallbacksDeferred()` 方法

## 验收标准

1. VPN 可以正常启动，无 crash
2. 网络切换时 VPN 流量正常切换到新网络
3. 无 Go runtime panic 日志

## 参考

- NekoBox 源码: 网络监听在 Application 启动时初始化
- Go runtime cgo 文档: cgo callback 期间栈不可扩展

## 修复日期

2025-01-10
