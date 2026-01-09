# sing-box 内核编译指南

## 快速开始 (一条命令)

在项目根目录下执行:

```powershell
.\buildScript\build.ps1
```

或者直接运行核心构建脚本:

```powershell
.\buildScript\tasks\build_libbox.ps1
```

**就这么简单!** 脚本会自动:
1. 下载 Go 1.24.11
2. 获取 sing-box 最新稳定版源码
3. 安装 SagerNet gomobile 工具链
4. 编译生成 `app/libs/libbox.aar`

---

## 构建特定版本

指定版本号:

```powershell
.\buildScript\tasks\build_libbox.ps1 -Version "1.12.15"
```

---

## 构建配置

### 当前配置

- **Go 版本**: 1.24.11 (自动下载到临时目录)
- **gomobile**: github.com/sagernet/gomobile@v0.1.8
- **sing-box**: 默认最新稳定版 (当前 1.12.15)
- **NDK**: 自动检测 `%ANDROID_HOME%/ndk/最新版本`

### 修改 Go 版本

编辑 `buildScript/tasks/build_libbox.ps1` 第 17 行:

```powershell
$GO_VERSION = "1.24.11"  # 改成你需要的版本
```

---

## 构建输出

成功后会生成:

```
app/libs/libbox.aar  (~64MB)
```

Android Studio 会自动识别并使用此文件。

---

## 故障排除

### 问题: 中文乱码

**症状**: 控制台输出显示 `鍏佽鑷姩鍗囩骇鍒?go1.24.11` 等乱码

**原因**: PowerShell 控制台编码设置为 GBK，但脚本输出 UTF-8

**解决**: 脚本已自动修复（添加了 `chcp 65001`）。如果仍有问题，手动执行：

```powershell
chcp 65001
.\buildScript\tasks\build_libbox.ps1
```

或者在 PowerShell 配置文件中永久设置：
```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
```

### 问题: "gobind was not found"

**症状**:
```
C:\Users\...\gomobile: gobind was not found. Please run gomobile init before trying again
```

**原因**: gomobile 缓存损坏或初始化失败

**解决**: 脚本已自动添加缓存清理和重试逻辑。如果仍失败，手动清理：

```powershell
# 清理 gomobile 缓存
Remove-Item -Recurse -Force "$env:TEMP\SingBoxBuildCache_Fixed\gopath\pkg\gomobile" -ErrorAction SilentlyContinue

# 重新构建
.\buildScript\tasks\build_libbox.ps1
```

### 问题: "gomobile not found"

**解决**: 删除缓存重新构建

```powershell
Remove-Item -Recurse -Force $env:TEMP\SingBoxBuildCache_Fixed
.\buildScript\tasks\build_libbox.ps1
```

### 问题: "invalid reference to os.checkPidfdOnce"

**原因**: Go 版本过旧 (< 1.24.11)

**解决**: 脚本已自动使用 Go 1.24.11,如果仍有问题,检查系统是否安装了旧版 Go 并移除。

### 问题: "NDK not found"

**解决**: 安装 Android NDK

在 Android Studio 中:
1. Tools → SDK Manager
2. SDK Tools 标签
3. 勾选 "NDK (Side by side)"
4. 点击 Apply

或手动设置环境变量:

```powershell
$env:ANDROID_NDK_HOME = "C:\Users\你的用户名\AppData\Local\Android\Sdk\ndk\29.0.14206865"
```

### 问题: 编译卡住或极慢

**原因**: 首次编译需下载大量依赖

**正常情况**: 首次编译约 10-15 分钟,后续编译 2-3 分钟

**加速方法**:
1. 使用国内 Go 代理 (已内置 GOPROXY=https://goproxy.cn)
2. 确保网络稳定

---

## 高级用法

### 仅安装 gomobile 工具

```powershell
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.8
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.8
gomobile init
```

### 手动构建 (在源码目录)

```powershell
cd $env:TEMP\SingBoxBuildCache_Fixed\singbox-source-v1.12.15
go run ./cmd/internal/build_libbox -target android
```

生成的 `libbox.aar` 在当前目录下。

### 清理构建缓存

```powershell
# 清理所有缓存 (下次会重新下载 Go 和源码)
Remove-Item -Recurse -Force $env:TEMP\SingBoxBuildCache_Fixed

# 仅清理 Go 模块缓存 (保留 Go 安装和源码)
Remove-Item -Recurse -Force $env:TEMP\SingBoxBuildCache_Fixed\gopath
```

---

## 构建流程说明

脚本执行的步骤:

1. **[1/7]** 设置工作目录 (`%TEMP%\SingBoxBuildCache_Fixed`)
2. **[2/7]** 下载/使用 Go 1.24.11 (~150MB,首次需要)
3. **[3/7]** 配置环境变量 (GOROOT, PATH, NDK, GOTOOLCHAIN)
4. **[4/7]** 安装 SagerNet gomobile 工具链
5. **[5/7]** 克隆 sing-box 源码 (约 50MB,首次需要)
6. **[6/7]** 使用官方构建工具编译 (`go run ./cmd/internal/build_libbox`)
7. **[7/7]** 复制 libbox.aar 到 `app/libs/`

**为什么不直接用 gomobile bind?**

sing-box 1.12+ 使用了 Go 1.24 的新特性,直接调用 `gomobile bind` 会报错:
```
invalid reference to os.checkPidfdOnce
```

官方的 `build_libbox` 工具内部处理了这些兼容性问题。

---

## 版本兼容性

| sing-box 版本 | 所需 Go 版本 | 状态 |
|--------------|-------------|------|
| 1.10.x       | Go 1.20+    | ✅ 稳定 |
| 1.11.x       | Go 1.23.1+  | ✅ 稳定 |
| 1.12.x       | Go 1.24.11+ | ✅ 当前脚本默认 |
| 1.13.0+      | Go 1.24+    | ⚠️ Alpha 测试中 |

---

## 技术细节

### 为什么用 SagerNet 的 gomobile?

官方 `golang.org/x/mobile` 的最新版本 (2024-06) 不支持 Go 1.24。

SagerNet 维护的 fork 修复了这些问题:
- github.com/sagernet/gomobile@v0.1.8

### 构建标签 (Tags)

默认启用的功能:
- `with_gvisor`: gVisor 网络栈
- `with_quic`: QUIC 协议支持
- `with_wireguard`: WireGuard 协议
- `with_utls`: uTLS (TLS 指纹伪装)
- `with_clash_api`: Clash API 兼容
- `with_conntrack`: 连接追踪

### 编译参数

- `-target android/arm64`: 仅编译 ARM64 架构 (减少体积)
- `-trimpath`: 移除路径信息 (减少体积,提高安全性)
- `-ldflags "-s -w"`: 去除调试信息 (减少 50% 体积)
- `-buildid=`: 移除 build ID (可重现构建)

### 缓存位置

所有临时文件存储在:
```
%TEMP%\SingBoxBuildCache_Fixed\
├── go1.24.11.zip           # Go 安装包
├── go_extract\go\          # Go 1.24.11 安装目录
├── gopath\                 # Go 模块缓存
└── singbox-source-v1.12.15\  # sing-box 源码
```

删除此目录可完全清理构建环境。

---

## 常见问题

### Q: 为什么首次构建这么慢?

A: 需要下载:
- Go 1.24.11 (~150MB)
- sing-box 源码 (~50MB)
- 所有 Go 依赖 (~200MB)

总计约 10-15 分钟。后续构建仅需 2-3 分钟。

### Q: 可以交叉编译其他架构吗?

A: 修改 `build_libbox` 命令:

```powershell
# ARM64 + ARMv7 + x86_64
go run ./cmd/internal/build_libbox -target android

# 仅 ARMv7
go run ./cmd/internal/build_libbox -target android -platform armeabi-v7a
```

### Q: 如何验证 AAR 是否正确?

A: 检查文件大小和内容:

```powershell
# 查看大小 (应该 50-70MB)
Get-Item app\libs\libbox.aar | Select-Object Length

# 解压查看内容
Expand-Archive app\libs\libbox.aar -DestinationPath temp_aar
ls temp_aar\jni\arm64-v8a\  # 应有 libgojni.so
```

---

## 更新日志

### 2026-01-09

- ✅ 修复 `os.checkPidfdOnce` 编译错误
- ✅ 升级到 Go 1.24.11
- ✅ 改用官方 `build_libbox` 工具
- ✅ 默认构建 sing-box 1.12.15

---

**编译愉快!如有问题请查看故障排除部分或提 Issue。**
