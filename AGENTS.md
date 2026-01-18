# AGENTS.md - KunBox for Android

> AI Agent Guidelines for KunBox Android Codebase

## Project Overview

KunBox is an Android VPN client built on sing-box core with OLED-optimized UI.

- **Language**: Kotlin 1.9 (100% Kotlin)
- **UI**: Jetpack Compose + Material 3
- **Architecture**: MVVM + Repository Pattern
- **Core**: sing-box (libbox.aar via JNI)
- **SDK**: Min 24 | Target 36 | NDK 29

---

## Build Commands

```powershell
./gradlew assembleDebug          # Debug build
./gradlew assembleRelease        # Release APK
./gradlew bundleRelease          # AAB for Play Store
./gradlew clean assembleRelease  # Clean build
./gradlew lint                   # Lint check
```

### Testing

```powershell
# Run all unit tests
./gradlew test

# Run single test class
./gradlew testDebugUnitTest --tests "com.kunk.singbox.utils.ClashConfigParserTest"

# Run single test method
./gradlew testDebugUnitTest --tests "com.kunk.singbox.utils.ClashConfigParserTest.testParseSimpleClashConfig"

# Instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest
```

---

## Project Structure

```
app/src/main/java/com/kunk/singbox/
  core/              # libbox wrapper (JNI bridge)
  database/          # Room database
    dao/             # Data Access Objects
    entity/          # Database entities
  ipc/               # AIDL interfaces for IPC
  lifecycle/         # App lifecycle observers
  manager/           # System managers
  model/             # Data classes (Gson serialization)
  repository/        # Data layer (DataStore, file I/O)
    config/          # Config generation
    store/           # Persistent storage
    subscription/    # Subscription parsing
  service/           # Android Services
    health/          # Health monitoring
    manager/         # Service managers
    network/         # Network utilities
    notification/    # Notification handling
    tun/             # TUN interface
  ui/
    components/      # Reusable Composables
    navigation/      # Navigation setup
    scanner/         # QR code scanner
    screens/         # Full-screen Composables
    theme/           # Colors, Typography
  utils/
    parser/          # Protocol parsers (Clash YAML, node links)
    perf/            # Performance utilities
  viewmodel/         # UI state management
  worker/            # WorkManager workers
```

---

## Code Style

### Imports
```kotlin
// Order: Android -> AndroidX -> Third-party -> Project
import android.content.Context
import androidx.compose.runtime.*
import com.google.gson.Gson
import com.kunk.singbox.model.AppSettings
```
- Explicit imports preferred; wildcards OK for Compose/layout only

### Naming Conventions
| Type | Convention | Example |
|------|------------|---------|
| Classes | PascalCase | `SettingsViewModel` |
| Functions | camelCase | `toggleConnection()` |
| Constants | SCREAMING_SNAKE | `ACTION_UPDATE_SETTING` |
| Composables | PascalCase | `NodeCard()` |
| Backing fields | underscore prefix | `_downloadingRuleSets` |

### Formatting
- **Indentation**: 4 spaces
- **Max line length**: ~120 chars (soft limit)
- **Trailing commas**: Yes, in multi-line params
- **Braces**: K&R style (opening brace same line)

### Type Safety
```kotlin
// GOOD: Explicit null handling
val text = latency?.let { "${it}ms" } ?: "---"

// GOOD: Safe enum parsing
val mode = runCatching { EnumType.valueOf(raw) }.getOrDefault(EnumType.DEFAULT)
```

### Data Models
```kotlin
// Use @SerializedName for JSON field mapping
data class AppSettings(
    @SerializedName("autoConnect") val autoConnect: Boolean = false,
)

// Enums with display resources
enum class TunStack(@StringRes val displayNameRes: Int) {
    @SerializedName("SYSTEM") SYSTEM(R.string.tun_stack_system),
    @SerializedName("GVISOR") GVISOR(R.string.tun_stack_gvisor);
}
```

### Compose UI
```kotlin
@Composable
fun NodeCard(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier  // modifier always last with default
) {
    // Use MaterialTheme.colorScheme for colors, never hardcode
}
```

### ViewModel & Repository
- ViewModel exposes immutable `StateFlow`, actions launch coroutines via `viewModelScope`
- Repository uses singleton pattern with `getInstance(context)`
- Use DataStore/Room for persistence, `flowOn(Dispatchers.Default)` for parsing

---

## Error Handling

```kotlin
// Use Result for fallible operations
suspend fun importFromFile(uri: Uri): Result<ImportResult> = runCatching { ... }

// Catch specific exceptions, log with context
try {
    gson.fromJson(json, SomeType::class.java)
} catch (e: JsonSyntaxException) {
    Log.e(TAG, "Failed to parse JSON", e)
    return emptyList()
}
```

---

## Theme (OLED-first)

```kotlin
val OLEDBlack = Color(0xFF000000)  // True black background
val Neutral900 = Color(0xFF121212) // Surface card
// Always use MaterialTheme.colorScheme, never hardcoded colors in components
```

---

## ProGuard Rules

Critical classes preserved (see `app/proguard-rules.pro`):
- `go.**`, `io.nekohasekai.libbox.**` - Native/JNI
- `com.kunk.singbox.model.**` - JSON serialization
- `com.kunk.singbox.service.*` - Android services
- `com.kunk.singbox.ipc.**` - AIDL interfaces

**When adding new model classes**: Ensure fields use `@SerializedName`

---

## Dependencies

| Library | Purpose |
|---------|---------|
| libbox.aar | sing-box VPN core |
| OkHttp 4.12 | HTTP client |
| Gson 2.11 | JSON serialization |
| SnakeYAML 2.2 | YAML parsing |
| DataStore | Preferences persistence |
| WorkManager 2.9 | Background scheduling |
| Room 2.6 | Database |
| ZXing | QR code scanning |
| MMKV 1.3 | Cross-process KV storage |

---

## Do NOT

- Use `!!` operator (use safe calls `?.` or `?: default`)
- Use `as Any` type casts or suppress type errors
- Create empty catch blocks `catch (e: Exception) { }`
- Commit secrets or API keys
- Use hardcoded colors (use `MaterialTheme.colorScheme`)
- Modify libbox.aar directly (rebuild from source)
- Skip ProGuard rules for new model classes
