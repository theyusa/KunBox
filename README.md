# SingBox for Android

一个基于 Sing-box 核心的现代、极简 Android 代理客户端。专为追求极致性能和简约设计的用户打造。

## 📱 界面预览

<div align="center">
  <img src="https://beone.kuz7.com/p/bTJJUBRl5tjaUX5kWJ5JBnrCK-IWOGwzx32fL8mGuB0" width="30%" alt="首页概览" />
  &nbsp;&nbsp;&nbsp;&nbsp;
  <img src="https://beone.kuz7.com/p/J47jgAo14XU34TXAyXwo-8zaAIWoKfqUytzI0UGzpws" width="30%" alt="节点列表" />
  &nbsp;&nbsp;&nbsp;&nbsp;
  <img src="https://beone.kuz7.com/p/jK9YTrZ6ZOITiSNxLBfHZtbKRdCu2o88vK62t1qNGgI" width="30%" alt="演示动画" />
</div>
<br/>
<div align="center">
  <img src="https://beone.kuz7.com/p/1kkW3veYE4cjVrDUUUMVfVL2jKPpGl6ccavhge8ilpU" width="30%" />
  &nbsp;&nbsp;&nbsp;&nbsp;
  <img src="https://beone.kuz7.com/p/nP4l6zRX1T4eWQMHKN4b0VOVYeau7B5r3vW44NmE7xk" width="30%" />
</div>

## ✨ 核心功能

*   **极简设计**: 采用 OLED 友好的纯黑白灰配色，无干扰的 UI 设计，专注于核心体验。
*   **高性能核心**: 基于最新的 Sing-box (libbox) 核心，提供稳定、快速的网络代理服务。
*   **Clash 配置兼容**: 内置强大的配置解析器，直接支持 Clash 格式的订阅链接和配置文件 (YAML)。
*   **智能分流**: 支持基于规则的流量分流，灵活控制网络访问。
*   **实时监控**: 首页直观展示连接状态、延迟和网络波动。
*   **延迟测试**: 基于 Clash API 的真实延迟测试，准确反映节点质量。

## 🚀 支持协议

本项目支持广泛的现代代理协议和传输方式：

*   **基础协议**: VMess, VLESS, Trojan
*   **新兴协议**: Hysteria2, TUIC (v5), AnyTLS
*   **传输方式**: TCP, UDP, WebSocket, gRPC, HTTP/2, HTTP
*   **高级特性**:
    *   **Reality**: 下一代防探测技术
    *   **uTLS**: 客户端指纹模拟
    *   **Flow**: XTLS-rprx-vision 等流控支持

## 🛠️ 技术栈

本项目采用最新的 Android 开发技术栈构建：

*   **语言**: [Kotlin](https://kotlinlang.org/) (100%)
*   **UI 框架**: [Jetpack Compose](https://developer.android.com/jetpack/compose) (Material3)
*   **架构模式**: MVVM (Model-View-ViewModel)
*   **核心引擎**: [Sing-box](https://github.com/SagerNet/sing-box) (通过 JNI 集成 libbox)
*   **网络库**: OkHttp
*   **数据解析**: Gson (JSON), SnakeYAML (YAML)
*   **异步处理**: Kotlin Coroutines, Flow
*   **后台任务**: WorkManager
*   **构建工具**: Gradle (Kotlin DSL)

## 🎨 设计理念

**"OLED Hyper-Minimalist"**

*   **纯粹**: 摒弃多余的色彩，仅使用黑、白、灰。
*   **专注**: 关键操作（如开关）占据视觉核心，次要信息自动折叠。
*   **流畅**: 强调非线性的流体动画，提供自然的交互反馈。

## 📦 构建说明

1.  克隆项目到本地。
2.  确保已安装 Android Studio Hedgehog 或更新版本 (JDK 17+)。
3.  下载或编译 Sing-box 的 Android 库 (`libbox.aar`) 并放置在 `app/libs/` 目录下。
4.  同步 Gradle 项目。
5.  连接 Android 设备并运行。

## 📝 许可证

本项目基于 [MIT 许可证](LICENSE) 开源。

本项目仅供学习和研究网络技术使用。
