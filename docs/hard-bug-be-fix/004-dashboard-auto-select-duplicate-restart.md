# DashboardViewModel 自动选择节点导致重复 VPN 重启

## 问题现象

切换配置时，Telegram 等应用会二次加载（显示两次"连接中..."）。

## 根因分析

`DashboardViewModel.filteredNodes` 的 `combine` Flow 中有以下逻辑：

```kotlin
// 如果当前活跃节点不在过滤后的列表中，自动选择第一个过滤后的节点
if (sorted.isNotEmpty() && (currentActiveNodeId == null || sorted.none { it.id == currentActiveNodeId })) {
    viewModelScope.launch {
        configRepository.setActiveNode(sorted.first().id)
    }
}
```

**问题**：配置切换时，`ProfilesViewModel.setActiveProfile()` 已经处理了节点切换。这里再次调用 `setActiveNode()` 会导致重复触发 VPN 重启。

**流程**：
1. 用户切换配置 → `ProfilesViewModel.setActiveProfile()` → 触发 VPN 重启
2. 节点列表更新 → `filteredNodes` Flow 重新计算 → 发现当前节点不在列表中 → 调用 `setActiveNode()` → 再次触发 VPN 重启

## 修复方案

移除自动选择节点的逻辑：

```kotlin
// 2025-fix: 移除自动选择第一个节点的逻辑
// 原因: 配置切换时 ProfilesViewModel/DashboardViewModel.setActiveProfile() 已经处理了节点切换
// 这里再次调用 setActiveNode() 会导致重复触发 VPN 重启，造成 TG 等应用二次加载
// 如果用户过滤后当前节点不在列表中，UI 只需显示第一个节点即可，不需要强制切换 VPN

sorted
```

## 修改的文件

- `app/src/main/java/com/kunk/singbox/viewmodel/DashboardViewModel.kt`
  - 移除 `filteredNodes` 中自动调用 `setActiveNode()` 的逻辑

## 验收标准

1. 切换配置时，VPN 只重启一次
2. Telegram 只显示一次"连接中..."

## 修复日期

2025-01-10
