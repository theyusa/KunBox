# Android VPN 磁贴与多进程架构修复总结

## 1. 核心问题
*   **磁贴乱跳/状态不准**：UI、磁贴、系统 VPN 状态三方不一致。
*   **无法连接/卡连接中**：`VpnService` 权限导致 AIDL 无法绑定，或进程崩溃导致启动失败。
*   **被抢占后状态错误**：其他 VPN 启动后，磁贴未能及时熄灭。

## 2. 根因分析
1.  **Binder 权限死锁**：`VpnService` 声明 `BIND_VPN_SERVICE` 权限后，导致本应用（UI/磁贴）无法绑定它获取 AIDL 状态，只能盲猜状态。
2.  **进程崩溃**：`RemoteCallbackList` 在多线程环境下并发调用 `beginBroadcast` 导致重入异常，直接炸掉 `:bg` 进程。
3.  **竞态条件**：`onServiceDisconnected` 强行覆盖状态，与持久化逻辑冲突，导致磁贴闪烁。
4.  **落盘不及时**：`onRevoke`（被抢占）时，进程可能被系统快速查杀，导致 `vpn_active=false` 未能写入磁盘。
5.  **启动阻塞**：磁贴启动时未处理 `VpnService.prepare()` 确认弹窗，导致静默失败。

## 3. 架构重构 (NekoBox 模式)
将 VPN 核心与 IPC 通信分离，确保状态权威且可访问。

*   **SingBoxService (`:bg`)**：
    *   专注 VPN 流量处理。
    *   保持 `BIND_VPN_SERVICE` 权限（系统规范）。
    *   **不再直接暴露 AIDL**，而是将状态推送到 `SingBoxIpcHub`。
*   **SingBoxIpcService (`:bg`)**：
    *   **新增**普通 Service，无特殊权限限制。
    *   专门承载 AIDL Binder，供 UI 和磁贴绑定订阅。
*   **SingBoxIpcHub (单例)**：
    *   进程内状态中枢，负责串行化广播，防止并发崩溃。

## 4. 关键修复点
### 稳定性
*   **AIDL 迁移**：UI/磁贴改为绑定 `SingBoxIpcService`，彻底解决连不上服务的问题。
*   **广播防崩**：在 `SingBoxIpcHub` 实现**全局锁 + 消息合并**，杜绝 `RemoteCallbackList` 重入崩溃。

### 交互体验
*   **磁贴启动预检**：点击磁贴时先调用 `VpnService.prepare()`。
    *   若需授权（或有其他 VPN）：直接拉起系统确认页。
    *   若无须授权：直接启动，不再卡“连接中”。
*   **抢占处理 (`onRevoke`)**：
    *   进入回调**第一时间**强制落盘 `vpn_active=false`。
    *   立即请求磁贴刷新，确保图标迅速熄灭。
*   **消除竞态**：移除 `onServiceDisconnected` 中强行置 `STOPPED` 的逻辑，防止断连瞬间状态误判。

## 5. 结论
通过**架构分层**（VPN vs IPC）和**时序优化**（Pre-check / Immediate Persist），实现了磁贴与主程序状态的精准同步及高稳定性。
