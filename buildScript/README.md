# buildScript - 内核构建脚本

## 目录结构

```
buildScript/
├── tasks/
│   └── build_libbox.ps1      # sing-box 内核构建脚本
└── singbox-build/
    ├── box_ext.go            # KunBox 扩展: 节点切换/电源管理/流量统计
    └── urltest_ext.go        # KunBox 扩展: 内核级延迟测试
```

## 快速开始

### 首次构建

```powershell
.\buildScript\tasks\build_libbox.ps1
```

**自动完成**:
1. 下载 Go 1.24.11
2. 克隆 sing-box 最新稳定版源码
3. 注入 KunBox 扩展代码
4. 安装 gomobile 工具
5. 编译 libbox.aar (仅 arm64-v8a)
6. 输出到 `app/libs/libbox.aar`

**耗时**: 首次 10-20 分钟，后续 2-5 分钟

---

## KunBox 扩展架构

### 设计原则

```
官方 sing-box 源码           KunBox 扩展 (独立文件)
        │                           │
        ▼                           ▼
┌─────────────────┐    注入    ┌──────────────────┐
│ experimental/   │ ◀──────── │ singbox-build/   │
│ libbox/         │            │ ├── box_ext.go   │
│ ├── service.go  │            │ └── urltest_ext  │
│ └── config.go   │            └──────────────────┘
└─────────────────┘
        │
        ▼
   libbox.aar (包含扩展)
```

**核心优势**: 扩展是**独立文件**，不修改官方代码，编译时自动注入。

### 扩展功能列表

#### box_ext.go - BoxWrapper 扩展

| 方法 | 功能 |
|------|------|
| `WrapBoxService(service)` | 创建 BoxWrapper 实例 |
| `SelectOutbound(tag)` | 热切换节点 (无需重连 VPN) |
| `GetSelectedOutbound()` | 获取当前选中节点 |
| `Pause() / Resume()` | 电源管理 (息屏省电) |
| `GetUploadTotal() / GetDownloadTotal()` | 流量统计 |
| `ResetAllConnections(system)` | 重置所有连接 |

#### urltest_ext.go - 内核级延迟测试

| 方法 | 功能 |
|------|------|
| `URLTestOutbound(tag, url, timeout)` | 单节点测速 (VPN 运行时) |
| `URLTestBatch(tags, url, timeout, concurrency)` | 批量测速 |
| `URLTestStandalone(config, tag, url, timeout)` | 独立测速 (VPN 未运行) |
| `GetURLTestHistory(tag)` | 获取历史延迟 |

---

## 同步官方更新

当官方 sing-box 发布新版本时:

```powershell
# 1. 清理源码缓存 (强制重新克隆)
Remove-Item -Recurse "$env:TEMP\SingBoxBuildCache_Fixed\singbox-source-*"

# 2. 重新构建 (自动获取最新版)
.\buildScript\tasks\build_libbox.ps1
```

### 兼容性说明

| 扩展版本 | 兼容 sing-box 版本 | 说明 |
|----------|-------------------|------|
| v1.1.0 | v1.10.0 - v1.12.x | 当前版本 |

扩展仅依赖官方稳定 API:
- `github.com/sagernet/sing-box/common/urltest`
- `github.com/sagernet/sing-box/adapter`
- `github.com/sagernet/sing-box/protocol/group`

---

## 添加新扩展

### 步骤 1: 创建扩展文件

在 `buildScript/singbox-build/` 目录创建 `my_ext.go`:

```go
package libbox

import (
    "log"
)

// MyCustomFunction 自定义功能
func MyCustomFunction() string {
    log.Println("[KunBox] MyCustomFunction called")
    return "Hello from KunBox"
}
```

### 步骤 2: 更新构建脚本

编辑 `buildScript/tasks/build_libbox.ps1`，在注入部分添加:

```powershell
# Inject my extension
$MyExtFile = Join-Path $ExtensionDir "my_ext.go"
if (Test-Path $MyExtFile) {
    $LibboxDir = Join-Path $BuildDir "experimental\libbox"
    if (Test-Path $LibboxDir) {
        $DestFile = Join-Path $LibboxDir "my_ext.go"
        Copy-Item $MyExtFile $DestFile -Force
        Write-Host "Injected KunBox extension: my_ext.go" -ForegroundColor Cyan
    }
}
```

### 步骤 3: 重新编译

```powershell
# 清理缓存并重新编译
Remove-Item -Recurse "$env:TEMP\SingBoxBuildCache_Fixed\singbox-source-*"
.\buildScript\tasks\build_libbox.ps1
```

### 步骤 4: Kotlin 层调用

```kotlin
// 直接调用 (如果 API 稳定)
val result = Libbox.myCustomFunction()

// 或通过反射 (兼容旧内核)
try {
    val method = Libbox::class.java.getMethod("myCustomFunction")
    val result = method.invoke(null) as String
} catch (e: NoSuchMethodException) {
    // 内核不支持此方法
}
```

---

## 配置选项

### 单架构编译 (默认)

仅编译 arm64-v8a，减少 75% 体积:

```powershell
# build_libbox.ps1:211
go run ./cmd/internal/build_libbox -target android/arm64
```

### 多架构编译

支持老设备，修改为:

```powershell
go run ./cmd/internal/build_libbox -target android/arm64,android/arm
```

### 自定义 Build Tags

当前配置 (NekoBox 风格优化):

```powershell
$BUILD_TAGS = "with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api"
```

已移除: `with_naive_outbound`, `with_tailscale` (节省 2-3MB)

---

## 前置要求

| 依赖 | 说明 |
|------|------|
| Android NDK | 自动检测 `$env:ANDROID_SDK_ROOT\ndk\*` |
| Java 17 | OpenJDK 或 Android Studio 自带 |
| 网络连接 | 首次下载 Go 和源码 |
| PowerShell 5.1+ | Windows 自带 |

---

## 故障排除

### 问题: gomobile 安装失败

```
Failed to install gomobile
```

**解决**:
```powershell
# 清理 Go 模块缓存
Remove-Item -Recurse "$env:TEMP\SingBoxBuildCache_Fixed\gopath"
# 重试
.\buildScript\tasks\build_libbox.ps1
```

### 问题: NDK 未找到

```
NDK not found. Please install Android NDK.
```

**解决**:
```powershell
# 手动设置 NDK 路径
$env:ANDROID_NDK_HOME = "C:\Users\你的用户名\AppData\Local\Android\Sdk\ndk\29.0.14206865"
.\buildScript\tasks\build_libbox.ps1
```

### 问题: 扩展编译错误

如果官方 API 变更导致扩展无法编译:

1. 查看错误信息，定位问题 API
2. 参考最新官方源码调整扩展代码
3. 更新扩展文件中的兼容版本注释

---

## 缓存目录

所有构建缓存位于:

```
%TEMP%\SingBoxBuildCache_Fixed\
├── go1.24.11.zip           # Go 安装包
├── go_extract\             # Go 运行时
├── gopath\                 # Go 模块缓存
└── singbox-source-v1.12.x\ # sing-box 源码 (含注入的扩展)
```

清理所有缓存:
```powershell
Remove-Item -Recurse "$env:TEMP\SingBoxBuildCache_Fixed"
```

---

**当前版本**: KunBox Extension v1.1.0
**内核版本**: sing-box v1.12.16
**最后更新**: 2026-01-16
