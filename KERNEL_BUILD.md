# sing-box 内核编译指南

本文档说明如何编译 sing-box Android 内核库 (libbox.aar)。

## 环境要求

- Windows 10/11
- PowerShell 5.1+
- Android SDK (含 NDK)
- Java 17 (OpenJDK)
- Git

## 快速开始

```powershell
# 在项目根目录执行
.\buildScript\tasks\build_libbox.ps1
```

脚本会自动：
1. 下载 Go 1.24.11
2. 获取最新 sing-box 版本
3. 安装 gomobile/gobind
4. 编译 libbox.aar
5. 复制到 `app/libs/`

## 构建选项

```powershell
# 使用最新版本（默认）
.\buildScript\tasks\build_libbox.ps1

# 指定版本
.\buildScript\tasks\build_libbox.ps1 -Version "1.12.15"

# 指定输出目录
.\buildScript\tasks\build_libbox.ps1 -OutputDir "C:\output"
```

## 目录结构

```
singboxforandriod/
├── buildScript/
│   ├── tasks/
│   │   └── build_libbox.ps1      # 主构建脚本
│   └── singbox-build/
│       └── box_ext.go            # 自定义扩展（可选）
├── temp/
│   └── sing-box-custom/          # sing-box 源码（备用）
│       └── cmd/internal/build_libbox/  # 官方构建工具
└── app/libs/
    └── libbox.aar                # 编译输出
```

## 构建参数说明

### 默认 Build Tags

```
with_gvisor      - 用户态网络栈
with_quic        - QUIC 协议支持
with_wireguard   - WireGuard 支持
with_utls        - uTLS 指纹伪装
with_clash_api   - Clash API 兼容
with_conntrack   - 连接跟踪
with_tailscale   - Tailscale 支持
```

### 优化参数

脚本已配置体积优化：
- `-Os` 优化体积
- `-trimpath` 移除路径信息
- `-s -w` strip 符号表
- 仅构建 `arm64-v8a` 架构

## 手动构建（高级）

如需更多控制，可使用 Go 原生工具：

```powershell
cd temp/sing-box-custom

# 需先配置环境
$env:ANDROID_NDK_HOME = "C:\path\to\ndk"
$env:JAVA_HOME = "C:\path\to\jdk17"

# 安装 gomobile
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.8
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.8
gomobile init

# 构建
go run ./cmd/internal/build_libbox -target android -platform android/arm64
```

## 自定义扩展

在 `buildScript/singbox-build/box_ext.go` 放置自定义代码，构建时会自动注入到 `experimental/libbox/` 目录。

示例扩展功能：
- ResetAllConnections - 重置所有连接
- 自定义 API 接口

## 常见问题

### Q: 构建失败提示 Java 版本错误
A: 确保 JAVA_HOME 指向 OpenJDK 17

### Q: NDK 找不到
A: 设置 `ANDROID_NDK_HOME` 环境变量，或通过 Android Studio SDK Manager 安装

### Q: gomobile init 失败
A: 清理缓存后重试：
```powershell
Remove-Item -Recurse "$env:TEMP\SingBoxBuildCache_Fixed\gopath\pkg\gomobile"
```

### Q: 构建产物过大
A: 脚本默认仅构建 arm64-v8a。如需多架构，修改脚本中的 `-platform` 参数

## 版本兼容性

| sing-box 版本 | Go 版本 | 备注 |
|--------------|---------|------|
| 1.12.x | 1.24.11+ | 当前支持 |
| 1.11.x | 1.22.x | 旧版本 |

## 相关链接

- [sing-box 官方仓库](https://github.com/SagerNet/sing-box)
- [gomobile 文档](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile)
