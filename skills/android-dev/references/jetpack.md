# Jetpack Libraries Reference

## Navigation Component (Compose)

Use `androidx.navigation:navigation-compose` version 2.8+. Navigation 2.8 introduces type-safe routes via `@Serializable` Kotlin classes, eliminating string-based routing and argument bundles entirely.

### Setup

```kotlin
// libs.versions.toml
navigation = "2.8.7"
kotlinx-serialization = "1.8.0"

// Module build.gradle.kts
alias(libs.plugins.kotlin.serialization)
implementation(libs.navigation.compose)
implementation(libs.kotlinx.serialization.json)
```

### Route Definitions

Define routes as `@Serializable` objects or data classes at the top of the navigation file or in a dedicated `Routes.kt`:

```kotlin
import kotlinx.serialization.Serializable

@Serializable
object ItemList              // No arguments

@Serializable
data class ItemDetail(       // With arguments — type-safe
    val itemId: Int
)

@Serializable
object Settings

@Serializable
data class Auth(
    val returnRoute: String? = null   // Optional argument
)
```

### NavHost Setup

```kotlin
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: Any = ItemList
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable<ItemList> {
            ItemListScreen(
                onNavigateToDetail = { id -> navController.navigate(ItemDetail(id)) },
                onNavigateToSettings = { navController.navigate(Settings) }
            )
        }
        composable<ItemDetail> { backStackEntry ->
            val args: ItemDetail = backStackEntry.toRoute()
            ItemDetailScreen(
                itemId = args.itemId,
                onNavigateBack = navController::popBackStack
            )
        }
        composable<Settings> {
            SettingsScreen(onNavigateBack = navController::popBackStack)
        }
        authGraph(navController)  // Nested graph
    }
}
```

### Nested Graphs

```kotlin
fun NavGraphBuilder.authGraph(navController: NavHostController) {
    navigation<Auth>(startDestination = Login) {
        composable<Login> {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(ItemList) {
                        popUpTo<Auth> { inclusive = true }
                    }
                },
                onNavigateToRegister = { navController.navigate(Register) }
            )
        }
        composable<Register> {
            RegisterScreen(onNavigateBack = navController::popBackStack)
        }
    }
}
```

### Clearing the Back Stack

Use `popUpTo` with `inclusive = true` to clear the stack on login/logout:

```kotlin
// After login — clear auth graph from back stack
navController.navigate(ItemList) {
    popUpTo(navController.graph.id) { inclusive = true }
}

// After logout — clear entire back stack and go to Login
navController.navigate(Auth()) {
    popUpTo(navController.graph.id) { inclusive = true }
    launchSingleTop = true
}
```

### Bottom Navigation

```kotlin
@Composable
fun BottomNavBar(navController: NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination

    NavigationBar {
        bottomNavItems.forEach { item ->
            NavigationBarItem(
                selected = currentRoute?.hasRoute(item.route::class) == true,
                onClick = {
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) }
            )
        }
    }
}
```

### Deep Links

```kotlin
@Serializable
data class ItemDetail(val itemId: Int)

// In the NavHost:
composable<ItemDetail>(
    deepLinks = listOf(
        navDeepLink<ItemDetail>(basePath = "https://example.com/items")
    )
) { backStackEntry ->
    val args: ItemDetail = backStackEntry.toRoute()
    ItemDetailScreen(args.itemId)
}
```

Declare the intent filter in `AndroidManifest.xml`:

```xml
<activity android:name=".MainActivity">
    <intent-filter android:autoVerify="true">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="https" android:host="example.com" />
    </intent-filter>
</activity>
```

### ViewModel in Navigation Destinations

```kotlin
// Gets a ViewModel scoped to this destination, injected by Hilt
@Composable
fun ItemListScreen(viewModel: ItemListViewModel = hiltViewModel()) { ... }

// Gets a ViewModel scoped to a parent navigation graph
@Composable
fun ChildScreen(navController: NavController) {
    val parentEntry = remember(navController) {
        navController.getBackStackEntry<ParentGraph>()
    }
    val sharedViewModel: SharedViewModel = hiltViewModel(parentEntry)
}
```

---

## DataStore

Use `androidx.datastore:datastore-preferences` to replace `SharedPreferences`. Use `androidx.datastore:datastore` (Proto DataStore) when the data has a complex schema and type safety is critical.

### Preferences DataStore

```kotlin
// DataStore is a singleton — create it once using the delegate
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")
```

Provide it via Hilt:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.dataStore
}
```

Define keys and a repository wrapper:

```kotlin
object UserPrefsKeys {
    val THEME = stringPreferencesKey("theme")
    val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
    val LAST_SYNC = longPreferencesKey("last_sync_timestamp")
}

class UserPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val theme: Flow<String> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs -> prefs[UserPrefsKeys.THEME] ?: "system" }

    suspend fun setTheme(theme: String) {
        dataStore.edit { prefs -> prefs[UserPrefsKeys.THEME] = theme }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[UserPrefsKeys.NOTIFICATIONS_ENABLED] = enabled }
    }
}
```

**Never** access `DataStore.data` from the main thread. Collect in `viewModelScope` or map in a `Flow` chain.

---

## WorkManager

Use `androidx.work:work-runtime-ktx` for deferrable, guaranteed background work. WorkManager survives app death and device reboots.

### CoroutineWorker

Prefer `CoroutineWorker` over `Worker` — it runs on a coroutine dispatcher and supports suspend functions:

```kotlin
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncRepository: SyncRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val filter = inputData.getString(KEY_FILTER) ?: "all"
            syncRepository.sync(filter)
            Result.success()
        } catch (e: HttpException) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        } catch (e: Exception) {
            Result.failure(
                workDataOf(KEY_ERROR to (e.message ?: "Unknown error"))
            )
        }
    }

    companion object {
        const val KEY_FILTER = "filter"
        const val KEY_ERROR = "error"
        const val MAX_RETRIES = 3

        fun buildRequest(filter: String): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_FILTER to filter))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
    }
}
```

Inject `CoroutineWorker` with Hilt via `@HiltWorker` (requires `hilt-work` artifact):

```kotlin
// build.gradle.kts
implementation(libs.hilt.work)
ksp(libs.hilt.compiler)

// Worker class
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncRepository: SyncRepository
) : CoroutineWorker(context, params) { ... }

// Application class
class App : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
```

Remove the default WorkManager initializer from `AndroidManifest.xml`:

```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    tools:node="remove" />
```

### Scheduling Work

```kotlin
// One-time work
val syncRequest = SyncWorker.buildRequest(filter = "active")
WorkManager.getInstance(context).enqueueUniqueWork(
    "sync_items",
    ExistingWorkPolicy.REPLACE,
    syncRequest
)

// Periodic work (minimum interval: 15 minutes)
val periodicSync = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
    .build()

WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    "periodic_sync",
    ExistingPeriodicWorkPolicy.KEEP,
    periodicSync
)
```

### Chaining Work

```kotlin
WorkManager.getInstance(context)
    .beginWith(cleanupRequest)
    .then(syncRequest)
    .then(notifyRequest)
    .enqueue()
```

### Observing Work State

```kotlin
WorkManager.getInstance(context)
    .getWorkInfoByIdFlow(syncRequest.id)
    .collect { workInfo ->
        when (workInfo?.state) {
            WorkInfo.State.RUNNING -> showProgress()
            WorkInfo.State.SUCCEEDED -> showSuccess()
            WorkInfo.State.FAILED -> {
                val error = workInfo.outputData.getString(SyncWorker.KEY_ERROR)
                showError(error)
            }
            else -> Unit
        }
    }
```

---

## Paging 3

Use `androidx.paging:paging-compose` for paginated lists from network or database sources.

### Dependencies

```toml
# libs.versions.toml
paging = "3.3.5"

[libraries]
paging-runtime = { group = "androidx.paging", name = "paging-runtime", version.ref = "paging" }
paging-compose = { group = "androidx.paging", name = "paging-compose", version.ref = "paging" }
paging-testing = { group = "androidx.paging", name = "paging-testing", version.ref = "paging" }
```

### PagingSource (Network only)

```kotlin
class ItemPagingSource @Inject constructor(
    private val api: ItemApi,
    private val query: String
) : PagingSource<Int, ItemDto>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ItemDto> {
        val page = params.key ?: STARTING_PAGE
        return try {
            val response = api.searchItems(query = query, page = page, pageSize = params.loadSize)
            LoadResult.Page(
                data = response.items,
                prevKey = if (page == STARTING_PAGE) null else page - 1,
                nextKey = if (response.items.isEmpty()) null else page + 1
            )
        } catch (e: IOException) {
            LoadResult.Error(e)
        } catch (e: HttpException) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, ItemDto>): Int? =
        state.anchorPosition?.let { anchor ->
            state.closestPageToPosition(anchor)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchor)?.nextKey?.minus(1)
        }

    companion object {
        const val STARTING_PAGE = 1
    }
}
```

### RemoteMediator (Network + Room cache)

```kotlin
@OptIn(ExperimentalPagingApi::class)
class ItemRemoteMediator @Inject constructor(
    private val api: ItemApi,
    private val database: AppDatabase
) : RemoteMediator<Int, ItemEntity>() {

    override suspend fun load(loadType: LoadType, state: PagingState<Int, ItemEntity>): MediatorResult {
        val page = when (loadType) {
            LoadType.REFRESH -> STARTING_PAGE
            LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
            LoadType.APPEND -> {
                val lastPage = database.remoteKeyDao().getLastPage()
                    ?: return MediatorResult.Success(endOfPaginationReached = false)
                lastPage + 1
            }
        }

        return try {
            val response = api.getItems(page = page, pageSize = state.config.pageSize)
            database.withTransaction {
                if (loadType == LoadType.REFRESH) {
                    database.itemDao().clearAll()
                    database.remoteKeyDao().clearAll()
                }
                database.itemDao().insertAll(response.items.map { it.toEntity() })
                database.remoteKeyDao().insert(RemoteKey(page = page))
            }
            MediatorResult.Success(endOfPaginationReached = response.items.isEmpty())
        } catch (e: IOException) {
            MediatorResult.Error(e)
        } catch (e: HttpException) {
            MediatorResult.Error(e)
        }
    }
}
```

### Repository with Pager

```kotlin
class ItemRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val remoteMediator: ItemRemoteMediator
) : ItemRepository {

    @OptIn(ExperimentalPagingApi::class)
    override fun getItemsPaged(): Flow<PagingData<Item>> = Pager(
        config = PagingConfig(
            pageSize = 20,
            prefetchDistance = 5,
            enablePlaceholders = false
        ),
        remoteMediator = remoteMediator,
        pagingSourceFactory = { database.itemDao().pagingSource() }
    ).flow.map { pagingData ->
        pagingData.map { entity -> entity.toDomain() }
    }
}
```

### Compose UI

```kotlin
@Composable
fun ItemListScreen(viewModel: ItemListViewModel = hiltViewModel()) {
    val items: LazyPagingItems<Item> = viewModel.itemsPaged.collectAsLazyPagingItems()

    LazyColumn {
        items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
            items[index]?.let { item -> ItemCard(item) }
        }

        // Handle loading and error states
        when {
            items.loadState.refresh is LoadState.Loading -> {
                item { Box(Modifier.fillParentMaxSize()) { CircularProgressIndicator() } }
            }
            items.loadState.refresh is LoadState.Error -> {
                val error = (items.loadState.refresh as LoadState.Error).error
                item {
                    ErrorItem(message = error.localizedMessage, onRetry = items::retry)
                }
            }
            items.loadState.append is LoadState.Loading -> {
                item { CircularProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
            }
        }
    }
}
```
