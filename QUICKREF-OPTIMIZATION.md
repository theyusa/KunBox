# 快速参考 - libbox 内核优化

## ✅ 当前优化状态

| 项目 | 原始 | 已优化 | 可进一步优化 |
|------|------|--------|-------------|
| AAR 体积 | 66.36 MB | **15.55 MB** | **8-10 MB** (UPX压缩) |
| APK 大小 | - | **15 MB** | **13 MB** (资源优化) |
| 安装后占用 | - | **68 MB** | **40-45 MB** (编译优化+UPX) |
| 架构数量 | 4 个 | 1 个 (arm64) | 1 个 |

**功能**: ✅ 完全保留所有协议和特性

---

## 🔥 快速优化命令

### 立即可用 (低风险)
```bash
# 1. 编译优化 (减少 10-15%)
.\buildScript\tasks\optimization_patch.ps1  # 应用编译优化补丁
.\buildScript\tasks\build_libbox.ps1        # 重新编译

# 2. 资源优化 (已配置好,直接构建)
.\gradlew assembleRelease
```

### 进阶优化 (中等风险,仅用于非 Google Play 分发)
```bash
# UPX 压缩 (减少 40-50%)
# 先下载 UPX 工具,然后:
.\buildScript\tasks\compress_libbox.ps1
```

---

## 🔥 常用命令

### 优化 AAR（从现有文件）
```bash
.\gradlew stripLibboxAar
cp app\build\stripped-libs\libbox-stripped-*.aar app\libs\libbox.aar
```

### 从源码重新编译
```bash
.\buildScript\tasks\build_libbox.ps1
```

### 构建测试 APK
```bash
.\gradlew assembleDebug
.\gradlew installDebug
```

### 回滚到原版
```bash
cp app\libs\libbox.aar.backup_20260109 app\libs\libbox.aar
```

---

## 📂 重要文件

| 文件 | 说明 | 大小/备注 |
|------|------|---------|
| `app/libs/libbox.aar` | 优化后内核 | 15.55 MB |
| `app/libs/libbox.aar.backup_*` | 原版备份 | 66.36 MB |
| `docs/LIBBOX-OPTIMIZATION.md` | 基础优化文档 | - |
| `docs/KERNEL-SIZE-OPTIMIZATION-ADVANCED.md` | **进阶优化指南** | 5种方案 |
| `buildScript/tasks/build_libbox.ps1` | 构建脚本 | - |
| `buildScript/tasks/compress_libbox.ps1` | **UPX 压缩脚本** | 新增 |
| `buildScript/tasks/optimization_patch.ps1` | **编译优化补丁** | 新增 |

---

## 🎯 优化方案对比

| 方案 | 效果 | 风险 | 适用场景 |
|------|------|------|---------|
| 编译优化 | 减少 10-15% | ✅ 低 | 所有场景 (推荐) |
| UPX 压缩 | 减少 40-50% | ⚠️ 中 | 非 Play 分发 |
| 资源优化 | 减少 5-10% | ✅ 低 | 所有场景 |
| 裁剪协议 | 减少 30-60% | ⚠️⚠️ 高 | 特定用户群 |

详细说明见: `docs/KERNEL-SIZE-OPTIMIZATION-ADVANCED.md`

---

## ⚠️ 注意事项

### 设备兼容性
- ✅ 支持 99%+ 现代设备 (2019+, arm64-v8a)
- ❌ 不支持老旧 32 位设备
- 如遇 "INSTALL_FAILED_NO_MATCHING_ABIS" → 恢复备份

### UPX 压缩风险
- ⚠️ Google Play 可能标记为可疑
- 建议仅用于 GitHub/F-Droid 分发
- 部分老设备可能不支持 (<5% 概率)
- 必须在真机上测试 VPN 功能

---

**基础优化文档**: [LIBBOX-OPTIMIZATION.md](docs/LIBBOX-OPTIMIZATION.md)
**进阶优化指南**: [KERNEL-SIZE-OPTIMIZATION-ADVANCED.md](docs/KERNEL-SIZE-OPTIMIZATION-ADVANCED.md)
