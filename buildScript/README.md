# buildScript - 内核构建脚本

## 📁 目录结构

```
buildScript/
└── tasks/
    └── build_libbox.ps1    # sing-box 内核构建脚本
```

## 🔧 使用方法

### 构建 sing-box 内核

```powershell
.\buildScript\tasks\build_libbox.ps1
```

**功能**:
- 自动下载 Go 1.24.11
- 克隆 sing-box 最新稳定版源码
- 安装 gomobile 工具
- 编译 libbox.aar（仅 arm64-v8a 架构）
- 输出到 `app/libs/libbox.aar`

**首次构建时间**: 10-20 分钟
**后续构建时间**: 2-5 分钟（使用缓存）

## ⚙️ 配置说明

### 单架构编译（默认）

脚本已配置为仅构建 arm64-v8a 架构，减少 75% 体积。

```powershell
# buildScript/tasks/build_libbox.ps1:167
go run ./cmd/internal/build_libbox -target android/arm64
```

### 支持多架构（可选）

如需支持老设备，修改上述行为:

```powershell
# 同时支持 arm64 和 32 位设备
go run ./cmd/internal/build_libbox -target android/arm64,android/arm
```

## 📋 前置要求

- ✅ Android NDK (自动检测)
- ✅ Java 17 (OpenJDK)
- ✅ 网络连接（首次下载 Go 和源码）

## 📖 相关文档

- **优化指南**: `../docs/LIBBOX-OPTIMIZATION.md`
- **快速参考**: `../QUICKREF-OPTIMIZATION.md`

---

**最后更新**: 2026-01-09
