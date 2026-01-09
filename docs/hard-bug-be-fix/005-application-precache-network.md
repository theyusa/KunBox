# Application 层预缓存物理网络

## 问题现象

VPN 启动时需要等待 NetworkCallback 返回物理网络信息，有延迟。

## 根因分析

之前的实现中，`startDefaultInterfaceMonitor` 在被 libbox 调用时才开始注册 NetworkCallback，然后等待 `onAvailable` 回调返回物理网络。这有两个问题：

1. **延迟**：注册 callback 到收到回调需要时间
2. **cgo crash**：在 libbox cgo callback 上下文中调用 `registerNetworkCallback` 会导致 Go runtime crash（详见 001-go-runtime-stack-split-crash.md）

## 修复方案

参考 NekoBox，在 Application 启动时就开始监听网络：

### 1. DefaultNetworkListener 工具类 (已存在)

`utils/DefaultNetworkListener.kt` 提供静态的 `underlyingNetwork` 字段，缓存当前物理网络。

### 2. SingBoxApplication.onCreate() 中初始化

```kotlin
override fun onCreate() {
    super.onCreate()

    if (isMainProcess()) {
        applicationScope.launch {
            // 预缓存物理网络 - 参考 NekoBox 优化
            // VPN 启动时可直接使用已缓存的网络，避免应用二次加载
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                DefaultNetworkListener.start(cm, this@SingBoxApplication) { network ->
                    android.util.Log.d("SingBoxApp", "Underlying network updated: $network")
                }
            }
            // ...
        }
    }
}
```

### 3. startDefaultInterfaceMonitor 使用缓存

```kotlin
override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
    // 直接使用 Application 层预缓存的网络
    var initialNetwork: Network? = DefaultNetworkListener.underlyingNetwork

    // 如果预缓存不可用，尝试 lastKnownNetwork 或 activeNetwork
    // ...
}
```

## 修改的文件

- `app/src/main/java/com/kunk/singbox/SingBoxApplication.kt`
  - 添加 `DefaultNetworkListener.start()` 调用

- `app/src/main/java/com/kunk/singbox/service/SingBoxService.kt`
  - `startDefaultInterfaceMonitor` 使用 `DefaultNetworkListener.underlyingNetwork`

## 验收标准

1. VPN 启动更快（无需等待 NetworkCallback 首次回调）
2. 网络切换时 VPN 正常切换到新网络
3. 无 Go runtime crash

## 修复日期

2025-01-10
