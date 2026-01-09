# 项目文档索引

## 🚀 快速开始

### 1. 内核优化
- **快速参考**: [QUICKREF-OPTIMIZATION.md](QUICKREF-OPTIMIZATION.md) ⭐
- **基础优化**: [docs/LIBBOX-OPTIMIZATION.md](docs/LIBBOX-OPTIMIZATION.md)
- **进阶优化**: [docs/KERNEL-SIZE-OPTIMIZATION-ADVANCED.md](docs/KERNEL-SIZE-OPTIMIZATION-ADVANCED.md) 🔥

### 2. 构建内核
- **构建脚本**: [buildScript/tasks/build_libbox.ps1](buildScript/tasks/build_libbox.ps1)
- **优化补丁**: [buildScript/tasks/optimization_patch.ps1](buildScript/tasks/optimization_patch.ps1) 🔥
- **UPX 压缩**: [buildScript/tasks/compress_libbox.ps1](buildScript/tasks/compress_libbox.ps1) 🔥
- **使用说明**: [buildScript/README.md](buildScript/README.md)

---

## 📚 完整文档列表

### 核心文档（根目录）
- `README.md` - 项目介绍
- `CLAUDE.md` - AI 助手项目配置
- `CHANGELOG.md` - 更新日志
- `QUICKREF-OPTIMIZATION.md` - 优化快速参考 ⭐

### 优化相关
- `docs/LIBBOX-OPTIMIZATION.md` - 内核优化基础指南 ⭐
- `docs/KERNEL-SIZE-OPTIMIZATION-ADVANCED.md` - 进阶优化方案 (5种) 🔥
- `QUICKREF-OPTIMIZATION.md` - 快速参考卡片

### 功能指南
- `docs/URL_SCHEME_GUIDE.md` - URL Scheme 深度链接

### 构建脚本
- `buildScript/README.md` - 构建脚本说明
- `buildScript/tasks/build_libbox.ps1` - 内核构建脚本
- `buildScript/tasks/optimization_patch.ps1` - 编译优化补丁 🔥
- `buildScript/tasks/compress_libbox.ps1` - UPX 压缩脚本 🔥

### Bug 修复记录（docs/bugfix/）
- 各类已修复问题的文档记录

### MCP 设置（docs/mcp-setup/）
- MCP 服务器配置相关文档

---

## 🎯 常用操作

### 优化内核
```bash
.\gradlew stripLibboxAar
cp app\build\stripped-libs\libbox-stripped-*.aar app\libs\libbox.aar
```

### 构建内核
```bash
.\buildScript\tasks\build_libbox.ps1
```

### 构建 APK
```bash
.\gradlew assembleDebug
.\gradlew installDebug
```

---

## 📂 项目结构

```
singboxforandriod/
├── app/                          # 应用源码
│   └── libs/
│       ├── libbox.aar           # 优化后内核 (15.55 MB)
│       └── libbox.aar.backup_*  # 原版备份 (66.36 MB)
│
├── buildScript/                  # 构建脚本
│   ├── README.md
│   └── tasks/
│       └── build_libbox.ps1     # 内核构建
│
├── docs/                         # 文档目录
│   ├── LIBBOX-OPTIMIZATION.md   # 优化指南 ⭐
│   ├── URL_SCHEME_GUIDE.md
│   ├── bugfix/                  # 修复记录
│   └── mcp-setup/               # MCP 配置
│
├── README.md                     # 项目介绍
├── CLAUDE.md                     # AI 配置
├── CHANGELOG.md                  # 更新日志
└── QUICKREF-OPTIMIZATION.md      # 快速参考 ⭐
```

---

**提示**: 带 ⭐ 标记的是最常用文档
