<div align="center">

# KunBox for Android

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4.svg?style=flat&logo=android)](https://developer.android.com/jetpack/compose)
[![Sing-box](https://img.shields.io/badge/Core-Sing--box-success.svg?style=flat)](https://github.com/SagerNet/sing-box)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat)](LICENSE)
[![Telegram](https://img.shields.io/badge/Telegram-Chat-blue?style=flat&logo=telegram)](https://t.me/+EKxpszVkOBc1MGJl)

> **OLED Hyper-Minimalist**
>
> 专为追求极致性能与视觉纯粹主义者打造的下一代 Android 代理客户端。
> <br/>摒弃繁杂，回归网络本质。

[功能特性](#-核心特性) • [协议支持](#-协议矩阵) • [项目架构](#-项目结构) • [快速开始](#-构建指南) • [交流群](https://t.me/+978J0WfmJLk4ZmQ1)

</div>

---

## 📱 视觉预览

<div align="center">
  <img src="https://beone.kuz7.com/p/bTJJUBRl5tjaUX5kWJ5JBnrCK-IWOGwzx32fL8mGuB0" width="30%" alt="首页概览" />
  &nbsp;&nbsp;
  <img src="https://beone.kuz7.com/p/J47jgAo14XU34TXAyXwo-8zaAIWoKfqUytzI0UGzpws" width="30%" alt="节点列表" />
  &nbsp;&nbsp;
  <img src="https://beone.kuz7.com/p/jK9YTrZ6ZOITiSNxLBfHZtbKRdCu2o88vK62t1qNGgI" width="30%" alt="演示动画" />
</div>
<br/>
<div align="center">
  <img src="https://beone.kuz7.com/p/1kkW3veYE4cjVrDUUUMVfVL2jKPpGl6ccavhge8ilpU" width="30%" />
  &nbsp;&nbsp;
  <img src="https://beone.kuz7.com/p/nP4l6zRX1T4eWQMHKN4b0VOVYeau7B5r3vW44NmE7xk" width="30%" />
</div>

## ✨ 核心特性

### 🎨 OLED 纯黑美学 (Hyper-Minimalist UI)
区别于传统的 Material Design，我们采用了深度定制的 **True Black** 界面。不仅在 OLED 屏幕上实现像素级省电，更带来深邃、沉浸的视觉体验。无干扰的 UI 设计让关键信息（延迟、流量、节点）一目了然。

### 🚀 极致性能核心 (High-Performance Core)
基于 Golang 编写的 **Sing-box (libbox)** 下一代通用代理核心。
- **内存占用**: 相比传统核心降低 30%+
- **启动速度**: 毫秒级冷启动
- **连接稳定性**: 优秀的连接复用与保活机制

### 🛡️ 智能分流与规则集中心 (Smart Routing & RuleSet Hub)
内置强大的路由引擎，支持复杂的规则集匹配。
- **规则集中心**: 在线下载与管理海量规则集（GeoSite/GeoIP/AdGuard 等），支持 Source 与 Binary 格式。
- **精准应用分流**: 采用 `UID` + `Package Name` 双重匹配机制，有效解决部分系统环境下应用分流失效的问题。
- **灵活策略**: 支持 GeoSite、GeoIP、域名后缀、关键字、进程名等多种匹配维度。

### ⚡ 便捷交互 (Quick Actions)
- **Quick Settings Tile**: 支持系统下拉栏快捷开关，无需进入应用即可一键启停 VPN。
- **真·延迟测试**: 基于 URL-Test 的真实连接测试，准确反映 YouTube/Google 等目标网站的真实加载速度。

## 🌐 协议矩阵

我们构建了全方位的协议支持网络，兼容市面上绝大多数代理协议与高级特性。

### 核心代理协议

| 协议 | 标识 | 链接格式 | 核心特性支持 |
|:---|:---|:---|:---|
| **Shadowsocks** | `SS` | `ss://` | SIP002, SIP008, AEAD (AES-128/256-GCM, Chacha20-Poly1305) |
| **VMess** | `VMess` | `vmess://` | WS, gRPC, HTTP/2, Auto Secure, Packet Encoding |
| **VLESS** | `VLESS` | `vless://` | **Reality**, **Vision**, XTLS Flow, uTLS |
| **Trojan** | `Trojan` | `trojan://` | Trojan-Go 兼容, Mux |
| **Hysteria 2** | `Hy2` | `hysteria2://` | 最新 QUIC 协议, 端口跳跃 (Port Hopping), 拥塞控制 |
| **TUIC v5** | `TUIC` | `tuic://` | 0-RTT, BBR 拥塞控制, QUIC 传输 |
| **WireGuard** | `WG` | `wireguard://` | 内核级 VPN 隧道, 预共享密钥 (PSK) |
| **SSH** | `SSH` | `ssh://` | 安全隧道代理, Private Key 认证 |
| **AnyTLS** | `AnyTLS` | `anytls://` | 通用 TLS 包装, 流量伪装 |

### 订阅生态支持
- **Sing-box JSON**: 原生支持，特性最全。
- **Clash YAML**: 完美兼容 Clash / Clash Meta (Mihomo) 配置，自动转换策略组。
- **Standard Base64**: 兼容 V2RayN / Shadowrocket 订阅格式。
- **导入方式**: 支持 剪贴板导入、URL 订阅导入。

## 🏗️ 项目结构

本项目遵循现代 Android 架构的最佳实践，采用 MVVM 模式与 Clean Architecture 设计理念。

```
KunBox-Android/
├── app/
│   ├── src/main/java/com/kunk/singbox/
│   │   ├── model/           # 数据模型 (Config, Profile, RuleSet)
│   │   │   ├── SingBoxConfig.kt   # Sing-box 核心配置映射
│   │   │   └── Outbound.kt        # 节点出站定义
│   │   │
│   │   ├── repository/      # 数据仓库层 (Repository Pattern)
│   │   │   ├── ProfileRepository.kt # 配置文件管理
│   │   │   └── LogRepository.kt     # 日志持久化
│   │   │
│   │   ├── service/         # Android 服务组件
│   │   │   ├── SingBoxService.kt    # VpnService 核心实现
│   │   │   └── VpnTileService.kt    # 快捷开关服务 (QS Tile)
│   │   │
│   │   ├── ui/              # 界面层 (Jetpack Compose)
│   │   │   ├── components/  # 可复用 UI 组件 (Cards, Inputs)
│   │   │   ├── screens/     # 页面级 Composable
│   │   │   │   ├── ProfilesScreen.kt   # 节点列表页
│   │   │   │   ├── RuleSetHubScreen.kt # 规则集中心
│   │   │   │   └── LogsScreen.kt       # 日志监控页
│   │   │   └── theme/       # OLED 主题定义
│   │   │
│   │   ├── utils/           # 工具类集合
│   │   │   └── parser/      # 核心解析器引擎
│   │   │       ├── ClashYamlParser.kt  # YAML 解析实现
│   │   │       └── NodeLinkParser.kt   # 链接协议解析
│   │   │
│   │   └── viewmodel/       # 视图模型 (State Management)
│   │
│   ├── libs/                # 外部依赖 (libbox.aar)
│   └── res/                 # 资源文件 (Vector Drawables)
│
├── buildScript/             # 构建脚本 (Golang -> Android AAR)
│   └── build_libbox.ps1     # 核心编译脚本
│
└── gradle/                  # Gradle 构建配置
```

## 🛠️ 技术栈详情

| 维度 | 技术选型 | 说明 |
|:---|:---|:---|
| **Language** | Kotlin 1.9 | 100% 纯 Kotlin 代码，利用 Coroutines 和 Flow 处理异步流 |
| **UI Framework** | Jetpack Compose | 声明式 UI，Material 3 设计规范 |
| **Architecture** | MVVM | 配合 ViewModel 和 Repository 实现关注点分离 |
| **Core Engine** | Sing-box (Go) | 通过 JNI (Java Native Interface) 与 Go 核心库通信 |
| **Network** | OkHttp 4 | 用于订阅更新、延迟测试等辅助网络请求 |
| **Serialization** | Gson & SnakeYAML | 高性能 JSON 和 YAML 解析 |
| **Build System** | Gradle & CMake | 混合构建系统 |

## 📅 路线图 (Roadmap)

- [x] **v1.0**: 基础功能发布，支持核心协议，Clash/URL 导入。
- [x] **v1.1**: UI 细节打磨，OLED 主题优化，延迟测试重构。
- [x] **v1.2**: 规则集中心 (RuleSet Hub) 上线，支持在线下载与管理路由规则。
- [x] **v1.3**: 应用分流机制重构 (UID + PackageName)，显著提升分流稳定性。
- [ ] **v1.4**: 引入 **Tun 模式** 配置向导，简化 VPN 权限处理。
- [ ] **v1.5**: 支持 **Sub-Store** 格式，更强大的订阅管理。
- [ ] **v2.0**: 插件系统，支持用户自定义脚本与规则集。

## 📦 构建指南

如果你是开发者并希望从源码构建：

1.  **环境准备**:
    *   JDK 17+
    *   Android Studio Hedgehog 或更高版本
    *   Go 1.21+ (用于编译核心)

2.  **获取源码**:
    ```bash
    git clone https://github.com/your-repo/singbox-android.git
    cd singbox-android
    ```

3.  **编译核心 (可选)**:
    如果你需要修改底层核心，运行构建脚本：
    ```powershell
    # Windows
    ./buildScript/build_libbox.ps1
    ```
    这将生成最新的 `libbox.aar` 到 `app/libs/`。

4.  **构建 APK**:
    *   在 Android Studio 中打开项目。
    *   等待 Gradle Sync 完成。
    *   点击 `Run 'app'`。

## ❤️ 致谢与引用

本项目站在巨人的肩膀上，特别感谢以下开源项目：

*   **[SagerNet/sing-box](https://github.com/SagerNet/sing-box)**: The universal proxy platform.
*   **[MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)**: NekoBox for Android.

## 📝 许可证

Copyright © 2024-2025 KunK.
本项目基于 [MIT 许可证](LICENSE) 开源。

---
<div align="center">
<sub>本项目仅供学习和研究网络技术使用，请遵守当地法律法规。</sub>
</div>

