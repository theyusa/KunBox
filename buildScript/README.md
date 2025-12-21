# SingBox Android 构建脚本说明

本文档说明了 `buildScript` 目录下的脚本用途及使用方法。

## 目录结构

```text
buildScript/
├── build.ps1              # 主入口脚本（推荐使用）
├── README.md              # 说明文档
├── tasks/                 # 具体任务脚本
│   ├── build_libbox.ps1   # 编译 libbox.aar (Full Build)
│   ├── fix_classes.ps1    # 修复 classes.jar 接口问题
│   ├── extract_kernel.ps1 # 从 NekoBox 提取内核
│   └── inspect_symbols.ps1# 检查 AAR 符号
└── legacy/                # 旧版本脚本备份
```

## 快速开始

推荐直接运行主菜单脚本，它提供了一个交互式界面来执行各项任务：

```powershell
.\buildScript\build.ps1
```

运行后会出现如下菜单，输入数字即可执行对应任务：

1. **Build libbox (Full Build)**: 下载 Go 环境，配置依赖，完整编译 libbox.aar。
2. **Fix classes.jar**: 修复生成的 AAR 中 Java 接口定义不匹配的问题。
3. **Extract Kernel from NekoBox**: 从 NekoBox APK 中提取高度优化的 libbox.so 并注入到当前的 AAR 中（解决体积过大问题）。
4. **Inspect AAR Symbols**: 检查生成的 AAR 文件中的 JNI 符号，用于调试。

## 脚本详细说明

如果您需要单独运行某个任务，可以直接调用 `tasks` 目录下的脚本：

### 1. 编译核心 (`tasks/build_libbox.ps1`)

完整编译流程。脚本会自动：
- 检查/下载 Go 1.23.4 (缓存于 `%TEMP%\SingBoxBuildCache_Fixed`)
- 设置 gomobile 环境
- 编译 `libbox.aar`
- 自动复制到 `app/libs/` 目录

```powershell
.\buildScript\tasks\build_libbox.ps1
```

### 2. 修复接口 (`tasks/fix_classes.ps1`)

**用途**：解决 SingBox 源码变动导致的 Java 接口与 Native 实现不匹配产生的 Crash 问题。
**操作**：解压 AAR -> 替换 `classes.jar` 中的 `CommandClientHandler` 等接口 -> 重新打包 AAR。

```powershell
.\buildScript\tasks\fix_classes.ps1
```

### 3. 提取优化内核 (`tasks/extract_kernel.ps1`)

**用途**：自行编译的内核体积可能较大。此脚本可以从 MatsuriDayo 发布的 NekoBox APK 中提取预编译好的、高度优化的 `libbox.so`，替换掉我们自己编译的 so 文件。
**注意**：需要网络能访问 GitHub 下载 APK，或者手动下载放到指定位置。

```powershell
.\buildScript\tasks\extract_kernel.ps1
```

### 4. 符号检查 (`tasks/inspect_symbols.ps1`)

**用途**：开发调试用。用于查看 AAR 中的 `.so` 文件导出了哪些 Java_ 开头的 JNI 函数。需要 Android NDK 环境。

```powershell
.\buildScript\tasks\inspect_symbols.ps1