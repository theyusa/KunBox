# 重构计划：集成 NetworkManager 和 TrafficMonitor 到 SingBoxService

## 当前进度

### Phase 1: TrafficMonitor 集成 ✅ 已完成

**已完成的修改：**

1. **添加 import** (SingBoxService.kt 第50行)
   ```kotlin
   import com.kunk.singbox.service.network.TrafficMonitor
   ```

2. **添加 TrafficMonitor 实例和 Listener** (第627-672行)
   - 创建 `trafficMonitor = TrafficMonitor(serviceScope)`
   - 实现 `trafficListener` 处理 `onTrafficUpdate` 和 `onTrafficStall`
   - 流量停滞检测逻辑已移至 TrafficMonitor.Listener 回调

3. **删除重复变量** (原第621-641行)
   - 删除: `trafficStatsBaseTx/Rx`, `trafficStatsLastTx/Rx`, `trafficStatsLastSampleTime`, `trafficStatsMonitorJob`
   - 删除: `stallCheckIntervalMs`, `stallMinBytesDelta`, `stallMinSamples`, `lastStallCheckAtMs`, `stallConsecutiveCount`, `lastStallTrafficBytes`
   - 保留: `lastSpeedUpdateTime` (用于 libbox 内部流量统计)

4. **修改 startVpn** (第3143行)
   ```kotlin
   trafficMonitor.start(Process.myUid(), trafficListener)
   ```

5. **修改 stopVpn** (第3219行)
   ```kotlin
   trafficMonitor.stop()
   stallRefreshAttempts = 0
   ```

6. **删除方法** (原第4294-4364行)
   - 删除: `startTrafficStatsMonitor()`
   - 删除: `stopTrafficStatsMonitor()`

7. **简化健康检查** (第866-880行, 第1230-1250行)
   - `startPeriodicHealthCheck`: 移除重复的流量停滞检测逻辑
   - `performLightweightHealthCheck`: 移除重复的流量停滞检测逻辑

---

### Phase 2: NetworkManager 集成 ✅ 已完成

**已完成的修改：**

1. **添加 import** (SingBoxService.kt 第50行)
   ```kotlin
   import com.kunk.singbox.service.network.NetworkManager
   ```

2. **添加 NetworkManager 实例** (第680行)
   ```kotlin
   private var networkManager: NetworkManager? = null
   ```

3. **委托 findBestPhysicalNetwork** (原第2102-2185行，现约第2106-2109行)
   - 原 ~80 行复杂逻辑简化为 3 行委托代码
   ```kotlin
   private fun findBestPhysicalNetwork(): Network? {
       return networkManager?.findBestPhysicalNetwork()
           ?: connectivityManager?.activeNetwork
   }
   ```

4. **在 startVpn 中初始化** (第2970行)
   ```kotlin
   networkManager = NetworkManager(this@SingBoxService, this@SingBoxService)
   ```

5. **在 stopVpn 中清理** (第3143-3146行)
   ```kotlin
   networkManager?.reset()
   if (stopService) {
       networkManager = null
   }
   ```

**设计决策：**
- `lastSetUnderlyingNetworksAtMs` 和 `setUnderlyingNetworksDebounceMs` 保留在 SingBoxService 中
- 原因：`updateDefaultInterface` 方法需要调用 libbox 的 `currentInterfaceListener` 回调，这是 NetworkManager 无法替代的
- NetworkManager 作为辅助类，主要委托 `findBestPhysicalNetwork` 的网络选择逻辑

---

### Phase 3: 验证 ✅ 已完成

```powershell
# 编译检查 - 通过
./gradlew compileDebugKotlin
# BUILD SUCCESSFUL in 40s
```

---

## 文件位置参考

| 文件 | 路径 |
|------|------|
| SingBoxService.kt | `app/src/main/java/com/kunk/singbox/service/SingBoxService.kt` |
| TrafficMonitor.kt | `app/src/main/java/com/kunk/singbox/service/network/TrafficMonitor.kt` |
| NetworkManager.kt | `app/src/main/java/com/kunk/singbox/service/network/NetworkManager.kt` |

---

## 关键行号 (Phase 2 完成后)

| 内容 | 行号 |
|------|------|
| NetworkManager import | 50 |
| TrafficMonitor import | 51 |
| trafficMonitor 实例 | 628 |
| trafficListener | 629-672 |
| networkManager 实例 | 680 |
| findBestPhysicalNetwork (简化后) | 2106-2109 |
| startVpn 初始化 networkManager | 2970 |
| stopVpn 清理 networkManager | 3143-3146 |

---

## 实际效果

- **删除**: ~75 行重复代码 (findBestPhysicalNetwork 方法体)
- **新增**: ~10 行委托代码
- **净减少**: ~65 行
- **可维护性**: 网络选择逻辑集中到 NetworkManager，便于单独测试和维护
