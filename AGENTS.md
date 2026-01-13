# AGENTS.md - KunBox for Android

> AI Agent Guidelines for KunBox Android Codebase

## Project Overview

KunBox is an Android VPN client built on sing-box core with OLED-optimized UI.

- **Language**: Kotlin 1.9 (100% Kotlin)
- **UI**: Jetpack Compose + Material 3
- **Architecture**: MVVM + Repository Pattern
- **Core**: sing-box (libbox.aar via JNI)
- **Min SDK**: 24 | Target SDK: 36

---

## Build Commands

```powershell
# Full build (release APK)
./gradlew assembleRelease

# Debug build
./gradlew assembleDebug

# Build AAB for Play Store
./gradlew bundleRelease

# Clean build
./gradlew clean assembleRelease
```

### Testing

```powershell
# Run all unit tests
./gradlew test

# Run single test class
./gradlew testDebugUnitTest --tests "com.kunk.singbox.utils.ClashConfigParserTest"

# Run single test method
./gradlew testDebugUnitTest --tests "com.kunk.singbox.utils.ClashConfigParserTest.testParseSimpleClashConfig"

# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest
```

### Lint & Diagnostics

```powershell
# Lint check
./gradlew lint

# Dependency updates check
./gradlew dependencyUpdates
```

---

## Project Structure

```
app/src/main/java/com/kunk/singbox/
  model/          # Data classes (Gson serialization)
  repository/     # Data layer (DataStore, file I/O)
  service/        # Android Services (VpnService, TileService)
  viewmodel/      # UI state management
  ui/
    components/   # Reusable Composables
    screens/      # Full-screen Composables
    theme/        # Colors, Typography
  utils/
    parser/       # Protocol parsers (Clash YAML, node links)
  ipc/            # AIDL interfaces for IPC
  core/           # libbox wrapper
```

---

## Code Style Guidelines

### Imports

```kotlin
// Order: Android -> AndroidX -> Third-party -> Project
import android.content.Context
import androidx.compose.runtime.*
import com.google.gson.Gson
import com.kunk.singbox.model.AppSettings
```

- Use explicit imports, avoid wildcards except for Compose
- Group imports by package with blank lines between groups

### Naming Conventions

| Type | Convention | Example |
|------|------------|---------|
| Classes | PascalCase | `SettingsViewModel` |
| Functions | camelCase | `toggleConnection()` |
| Constants | SCREAMING_SNAKE | `ACTION_UPDATE_SETTING` |
| Composables | PascalCase | `NodeCard()` |
| State flows | camelCase with suffix | `connectionState`, `isRunning` |
| Private fields | underscore prefix for backing | `_downloadingRuleSets` |

### Formatting

- **Indentation**: 4 spaces
- **Max line length**: ~120 characters (soft limit)
- **Trailing commas**: Use in multi-line parameter lists
- **Braces**: K&R style (opening brace on same line)

```kotlin
fun example(
    param1: String,
    param2: Int,  // trailing comma
) {
    // implementation
}
```

### Type Safety

```kotlin
// GOOD: Explicit null handling
val latency: Long? = null
val text = latency?.let { "${it}ms" } ?: "---"

// GOOD: Safe enum parsing
val mode = runCatching { EnumType.valueOf(raw) }.getOrDefault(EnumType.DEFAULT)

// BAD: Never use these
as Any  // forbidden
@ts-ignore  // forbidden (wrong language but principle applies)
!!  // avoid, use safe calls instead
```

### Data Models

```kotlin
// Use @SerializedName for JSON field mapping
data class AppSettings(
    @SerializedName("autoConnect") val autoConnect: Boolean = false,
    @SerializedName("tunEnabled") val tunEnabled: Boolean = true,
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
    // Use MaterialTheme.colorScheme for colors
    val borderColor = if (isSelected) 
        MaterialTheme.colorScheme.primary 
    else 
        MaterialTheme.colorScheme.outline
    
    // State hoisting - state lives in ViewModel
    // Composables are stateless when possible
}
```

### ViewModel Pattern

```kotlin
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository.getInstance(application)
    
    // Expose immutable StateFlow
    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
    
    // Actions launch coroutines
    fun setAutoConnect(value: Boolean) {
        viewModelScope.launch { repository.setAutoConnect(value) }
    }
}
```

### Repository Pattern

```kotlin
class SettingsRepository(private val context: Context) {
    private val gson = Gson()
    
    // Use DataStore for persistence
    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        // Parse preferences to data class
    }.flowOn(Dispatchers.Default)
    
    // Singleton pattern
    companion object {
        @Volatile private var INSTANCE: SettingsRepository? = null
        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
```

---

## Error Handling

```kotlin
// Use Result for operations that can fail
suspend fun importFromFile(uri: Uri): Result<ImportResult> {
    return runCatching {
        // operation
    }
}

// Use try-catch with specific exceptions
try {
    gson.fromJson(json, SomeType::class.java)
} catch (e: JsonSyntaxException) {
    Log.e(TAG, "Failed to parse JSON", e)
    return emptyList()
}

// Never swallow exceptions silently
// BAD: catch (e: Exception) { }
```

---

## Theme & Colors

OLED-first design with true black background:

```kotlin
// Dark theme (default)
val AppBackground = Color(0xFF000000)  // True black
val SurfaceCard = Color(0xFF121212)
val TextPrimary = Color(0xFFFFFFFF)

// Always use MaterialTheme.colorScheme
MaterialTheme.colorScheme.surface      // not hardcoded colors
MaterialTheme.colorScheme.onSurface
MaterialTheme.colorScheme.primary
```

---

## Testing Guidelines

```kotlin
class ClashConfigParserTest {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    
    @Test
    fun testParseSimpleClashConfig() {
        val yaml = """
            proxies:
              - name: "ss1"
                type: ss
        """.trimIndent()
        
        val config = ClashYamlParser().parse(yaml)
        assertNotNull(config)
        assertEquals("shadowsocks", config?.outbounds?.first()?.type)
    }
}
```

---

## ProGuard Rules

Critical classes that must be preserved:

- `go.**` - Gomobile bindings
- `io.nekohasekai.libbox.**` - sing-box core
- `com.kunk.singbox.model.**` - JSON serialization
- `com.kunk.singbox.service.*` - Android services
- `com.kunk.singbox.ipc.**` - AIDL interfaces

---

## Common Patterns

### Restart Required Notification

```kotlin
// When settings change requires VPN restart
suspend fun setTunEnabled(value: Boolean) {
    context.dataStore.edit { it[KEY] = value }
    notifyRestartRequired()  // Emit event for UI to show snackbar
}
```

### Background Work

```kotlin
// Use WorkManager for periodic tasks
class RuleSetAutoUpdateWorker(context: Context, params: WorkerParameters) 
    : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        // Perform update
        return Result.success()
    }
}
```

### IPC Communication

```kotlin
// Service runs in separate process
// Use AIDL for cross-process communication
// Use Intent extras for simple commands
val intent = Intent(context, SingBoxService::class.java).apply {
    action = SingBoxService.ACTION_UPDATE_SETTING
    putExtra(EXTRA_SETTING_KEY, "show_notification_speed")
    putExtra(EXTRA_SETTING_VALUE_BOOL, value)
}
context.startService(intent)
```

---

## Do NOT

- Use `as any` type casts or suppress type errors
- Commit secrets or API keys
- Use hardcoded colors (use MaterialTheme)
- Create empty catch blocks
- Use `!!` operator without justification
- Modify libbox.aar directly (rebuild from source)
- Skip ProGuard rules for new model classes

---

## Dependencies

| Library | Purpose |
|---------|---------|
| libbox.aar | sing-box VPN core |
| OkHttp 4.12 | HTTP client |
| Gson 2.11 | JSON serialization |
| SnakeYAML 2.2 | YAML parsing |
| DataStore | Preferences persistence |
| WorkManager | Background scheduling |
| ZXing | QR code scanning |
