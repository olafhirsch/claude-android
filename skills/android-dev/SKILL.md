---
name: android-dev
description: This skill should be used when the user asks to "create an Android app",
  "build a Kotlin Android project", "add Jetpack Compose screen", "set up MVVM
  architecture", "create a ViewModel", "add Room database", "configure Hilt dependency
  injection", "set up Navigation Component", "add Coroutines or Flow", "write Android
  unit tests", "create a Repository", "build a Composable", or is working on any
  Android mobile development task using Kotlin, Java, Gradle, AndroidX, or Jetpack.
---

# Android App Development

## Overview

Build Android apps in Kotlin using Jetpack Compose for UI, MVVM with Clean Architecture for structure, Hilt for dependency injection, and Coroutines/Flow for async work. Use Java only when integrating legacy codebases or third-party SDKs that require it. Target API 35 (compileSdk and targetSdk) with minSdk 26 unless business requirements dictate otherwise.

Default to the latest stable versions of all AndroidX libraries. Use the Compose BOM to keep all Compose library versions aligned automatically.

---

## Core Architecture

Structure every feature across three layers:

| Layer | Package | Responsibilities |
|---|---|---|
| **UI** | `feature.<name>.ui` | Composables, ViewModels, UiState, UiEvent |
| **Domain** | `core.domain` | Use cases, repository interfaces, domain models |
| **Data** | `core.data` | Repository implementations, Room DAOs, Retrofit services, DataStore |

**UiState rule**: Model screen state as a sealed class. Never use multiple `isLoading`, `isError`, `data` booleans — they create illegal combinations.

```kotlin
sealed class UiState<out T> {
    data object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}
```

**StateFlow over LiveData**: Use `StateFlow` for observable UI state in ViewModels. Use `SharedFlow` (replay=0) for one-shot events (navigation, snackbars). Never expose `MutableStateFlow` or `MutableSharedFlow` directly — always expose via `asStateFlow()` / `asSharedFlow()`.

**Single source of truth**: Room is the single source of truth for persistent data. Network responses write to Room; the UI observes Room via `Flow<List<T>>`.

**Repository interface in domain layer**: Define repository interfaces in `:core:domain`. Implement them in `:core:data`. Inject the interface, never the implementation.

**Use cases for complex logic**: Extract multi-step business logic into use cases (`GetItemsUseCase`, `SyncDataUseCase`). Keep ViewModels thin — they call use cases, transform results into UiState.

See `references/architecture.md` for full patterns including `networkBoundResource()`, `Result<T>` wrappers, `SavedStateHandle`, and multi-module project layout.

---

## Project Setup Checklist

Use Kotlin DSL (`.kts`) for all Gradle build scripts. Centralize all versions in `gradle/libs.versions.toml`.

**`gradle/libs.versions.toml` skeleton:**

```toml
[versions]
agp = "8.7.3"
kotlin = "2.1.0"
ksp = "2.1.0-1.0.29"
compose-bom = "2025.01.00"
hilt = "2.54"
room = "2.7.0"
navigation = "2.8.7"

[libraries]
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
room = { id = "androidx.room", version.ref = "room" }
```

**Module-level `build.gradle.kts` essentials:**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)   // Required for Compose in Kotlin 2.0+
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    compileSdk = 35
    defaultConfig {
        applicationId = "com.fivesixtythree.<appname>"   // Replace <appname> with the app's name
        minSdk = 26
        targetSdk = 35
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")             // No version — BOM manages it
    implementation("androidx.compose.material3:material3")

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}
```

Always apply the `kotlin.plugin.compose` plugin when using Compose with Kotlin 2.0+. This replaces the need to specify `composeOptions { kotlinCompilerExtensionVersion }` manually.

---

## Hilt Dependency Injection

Annotate the `Application` class with `@HiltAndroidApp`. Annotate every Activity, Fragment, Service with `@AndroidEntryPoint`.

```kotlin
@HiltAndroidApp
class App : Application()
```

**ViewModels**: Use `@HiltViewModel` with `@Inject constructor`. Never use `ViewModelProvider.Factory` manually.

```kotlin
@HiltViewModel
class ItemViewModel @Inject constructor(
    private val getItemsUseCase: GetItemsUseCase
) : ViewModel()
```

**Modules**: Define Hilt modules with `@Module` and `@InstallIn`. Bind interfaces to implementations — never provide the concrete class when an interface exists.

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindItemRepository(impl: ItemRepositoryImpl): ItemRepository
}
```

Use `@Provides` (not `@Binds`) only for classes you don't own (Retrofit, OkHttp, Room database instance).

Use `@Singleton` for app-scoped dependencies (database, network client, repositories). Use `@ViewModelScoped` for ViewModel-scoped dependencies.

See `references/architecture.md` for `NetworkModule`, `DatabaseModule`, and `RepositoryModule` examples.

---

## Coroutines & Flow

**Scopes**: Use `viewModelScope` in ViewModels. Use `lifecycleScope` in Activities/Fragments only for UI-bound work. Never use `GlobalScope`.

**StateFlow vs SharedFlow**:
- `StateFlow`: holds current value, replays to new collectors — use for UI state
- `SharedFlow` (replay=0): no value held, fire-and-forget — use for navigation events, snackbars

**Threading**: Call `flowOn(Dispatchers.IO)` at the repository layer for database and network operations. ViewModels and use cases operate on `Dispatchers.Default` (or `Main` for StateFlow updates).

```kotlin
// In repository
fun getItems(): Flow<List<Item>> = dao.getAllItems()
    .flowOn(Dispatchers.IO)

// In ViewModel
viewModelScope.launch {
    repository.getItems()
        .catch { e -> _uiState.value = UiState.Error(e.message ?: "Unknown error") }
        .collect { items -> _uiState.value = UiState.Success(items) }
}
```

**Error handling**: Wrap network/DB calls in `try/catch` inside `launch {}`. Use `.catch {}` operator on `Flow` chains. Never let exceptions propagate uncaught to the coroutine scope.

---

## Room Database

Annotate data classes with `@Entity`. Annotate DAOs with `@Dao`. Extend `RoomDatabase` with `@Database`.

**Flow from queries**: Return `Flow<List<T>>` from DAO query methods — Room automatically re-emits when data changes.

**Suspend for writes**: Annotate insert, update, delete methods with `suspend`.

**Migrations**: Define explicit `Migration` objects for every schema version increment. Never use `fallbackToDestructiveMigration()` in production.

**Type converters**: Register converters in the `@Database` annotation using `@TypeConverters`.

See `examples/RoomEntity.kt` for a complete annotated example and `references/architecture.md` for the Hilt `DatabaseModule`.

---

## Navigation Component

Use Navigation Compose (`androidx.navigation:navigation-compose`) with typed routes via `@Serializable` data classes/objects. Require Navigation 2.8+.

```kotlin
@Serializable
object ItemList

@Serializable
data class ItemDetail(val itemId: Int)

NavHost(navController, startDestination = ItemList) {
    composable<ItemList> { ItemListScreen(navController) }
    composable<ItemDetail> { backStackEntry ->
        val args: ItemDetail = backStackEntry.toRoute()
        ItemDetailScreen(args.itemId)
    }
}
```

Never use string routes or argument bundles. Use `hiltViewModel()` inside composable destinations to get Hilt-injected ViewModels. Use `popUpTo` with `inclusive = true` to clear the back stack on logout/login flows.

See `references/jetpack.md` for nested graphs, deep links, and bottom navigation integration.

---

## Compose Patterns

**State hoisting**: Every composable that receives state and emits events should be stateless — accept lambdas for all user interactions. The entry-point composable (route composable) is stateful: it holds the ViewModel and collects state.

```kotlin
// Stateful entry — knows about ViewModel
@Composable
fun ItemListScreen(viewModel: ItemListViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ItemListContent(uiState = uiState, onRetry = viewModel::retry)
}

// Stateless content — pure function of its parameters
@Composable
fun ItemListContent(uiState: UiState<List<Item>>, onRetry: () -> Unit) {
    when (uiState) {
        is UiState.Loading -> CircularProgressIndicator()
        is UiState.Success -> ItemList(uiState.data)
        is UiState.Error -> ErrorScreen(uiState.message, onRetry)
    }
}
```

**`collectAsStateWithLifecycle()`**: Always use this instead of `collectAsState()` to stop collection when the composable leaves the screen.

**`LaunchedEffect` for one-shot events**: Consume `SharedFlow` events in the entry-point composable using `LaunchedEffect(Unit) { viewModel.uiEvent.collect { } }`.

**No business logic in Composables**: Composables call lambdas. ViewModels contain logic. Never call a repository or use case directly from a Composable.

See `references/compose.md` for layouts, theming, performance optimizations, animations, and accessibility.

---

## Testing

Follow the test pyramid: 70% unit tests, 20% integration tests, 10% UI/end-to-end tests.

- **Unit tests**: Test ViewModels with `UnconfinedTestDispatcher`. Test use cases and repositories in isolation with fakes (not mocks) for dependencies.
- **Integration tests**: Test Room DAOs with an in-memory database. Test repository implementations against real Room + fake network.
- **UI tests**: Test Compose screens with `createComposeRule()`. Test critical user flows with `createAndroidComposeRule<MainActivity>()`.

Use `turbine` for asserting `Flow` emissions. Use `@HiltAndroidTest` for instrumented tests that require the full DI graph.

See `references/testing.md` for full patterns, dispatcher setup, Hilt test configuration, and the complete test dependency list.

---

## Library Compatibility

Run this checklist whenever adding a new dependency or bumping a version:

1. Check that the Compose compiler version supports the current Kotlin version (Kotlin 2.0+ bundles the compiler — no separate version needed)
2. Verify KSP version matches Kotlin version exactly (format: `{kotlin}-1.0.X`)
3. Verify AGP version is compatible with the Gradle wrapper version
4. Use Compose BOM for all `androidx.compose.*` dependencies — remove any hardcoded Compose library versions
5. After changes, check for conflicts: `./gradlew :app:dependencies | grep "conflict"`

See `references/compatibility.md` for version tables, error diagnosis, and the full compatibility chain reference.

---

## Resource Map

| File | Contents |
|---|---|
| `references/architecture.md` | Multi-module layout, sealed UiState/UiEvent, Hilt modules, offline-first with `networkBoundResource()`, `Result<T>`, `SavedStateHandle` |
| `references/jetpack.md` | Navigation typed routes, nested graphs, deep links, DataStore, WorkManager, Paging 3 |
| `references/compose.md` | Layouts, Material 3 theming, `derivedStateOf`, side effects, animations, accessibility semantics |
| `references/compatibility.md` | Kotlin↔Compose↔KSP↔AGP↔Gradle version chains, common build errors and fixes, `libs.versions.toml` alignment patterns |
| `references/testing.md` | Test pyramid, ViewModel testing, Fake repositories, Room in-memory DB, Hilt test setup, Compose UI testing, `turbine` |
| `examples/ViewModel.kt` | Complete `@HiltViewModel` with `UiState`, `UiEvent`, `StateFlow`, `SharedFlow`, `viewModelScope` |
| `examples/RoomEntity.kt` | `@Entity`, `@Dao`, `@Database`, Hilt `DatabaseModule` in one annotated file |
| `examples/ComposeScreen.kt` | Stateful entry + stateless content pattern, event collection, `when(uiState)`, `@Preview` |
