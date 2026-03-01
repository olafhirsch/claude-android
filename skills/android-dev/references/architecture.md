# Architecture Reference

## Multi-Module Project Layout

Structure large apps as a multi-module Gradle project. The standard layout:

```
:app                          # Application module — ties everything together
:core:domain                  # Pure Kotlin — interfaces, use cases, domain models
:core:data                    # Repository implementations, Room, Retrofit, DataStore
:core:ui                      # Shared Composables, theme, design system components
:core:common                  # Utility extensions, Result type, constants
:feature:items                # Feature module — UI, ViewModel, feature-local models
:feature:settings
:feature:auth
```

**Dependency rules:**
- `:feature:*` depends on `:core:domain` and `:core:ui`, never on `:core:data`
- `:core:data` depends on `:core:domain`
- `:core:domain` has no Android dependencies (pure Kotlin/Java only)
- `:app` depends on all `:feature:*` modules and `:core:data` (for Hilt wiring)

**Module-level `build.gradle.kts` for a feature module:**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.feature.items"
    compileSdk = 35
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:ui"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
```

---

## Sealed UiState Pattern

Model every screen's state with a sealed class. Include all data the screen needs to render in the `Success` branch.

```kotlin
// In :feature:items:ui
sealed class ItemListUiState {
    data object Loading : ItemListUiState()
    data class Success(
        val items: List<ItemUi>,
        val isRefreshing: Boolean = false
    ) : ItemListUiState()
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : ItemListUiState()
}
```

Define a separate `ItemUi` model in the UI layer that maps from the domain model. Never expose domain or data-layer models directly to Composables — this keeps the layers decoupled and makes UI previews easy.

```kotlin
// Domain model (pure Kotlin, in :core:domain)
data class Item(
    val id: Int,
    val title: String,
    val createdAt: Instant
)

// UI model (in feature module)
data class ItemUi(
    val id: Int,
    val title: String,
    val formattedDate: String          // Pre-formatted for display
)

fun Item.toUi(formatter: DateTimeFormatter): ItemUi = ItemUi(
    id = id,
    title = title,
    formattedDate = formatter.format(createdAt)
)
```

---

## Sealed UiEvent Pattern

Use `UiEvent` for one-shot side effects that the UI should consume exactly once. Never put navigation or snackbar logic in `UiState`.

```kotlin
sealed class ItemListUiEvent {
    data class NavigateToDetail(val itemId: Int) : ItemListUiEvent()
    data class ShowSnackbar(val message: String) : ItemListUiEvent()
    data object NavigateBack : ItemListUiEvent()
}
```

Emit via `MutableSharedFlow(replay = 0)`:

```kotlin
private val _uiEvent = MutableSharedFlow<ItemListUiEvent>(replay = 0)
val uiEvent: SharedFlow<ItemListUiEvent> = _uiEvent.asSharedFlow()

// In a coroutine:
_uiEvent.emit(ItemListUiEvent.NavigateToDetail(item.id))
```

Consume in the Composable's entry point:

```kotlin
LaunchedEffect(Unit) {
    viewModel.uiEvent.collect { event ->
        when (event) {
            is ItemListUiEvent.NavigateToDetail -> navController.navigate(ItemDetail(event.itemId))
            is ItemListUiEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
            ItemListUiEvent.NavigateBack -> navController.popBackStack()
        }
    }
}
```

---

## Complete ViewModel Pattern

```kotlin
@HiltViewModel
class ItemListViewModel @Inject constructor(
    private val getItemsUseCase: GetItemsUseCase,
    private val deleteItemUseCase: DeleteItemUseCase,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow<ItemListUiState>(ItemListUiState.Loading)
    val uiState: StateFlow<ItemListUiState> = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<ItemListUiEvent>(replay = 0)
    val uiEvent: SharedFlow<ItemListUiEvent> = _uiEvent.asSharedFlow()

    init {
        loadItems()
    }

    private fun loadItems() {
        viewModelScope.launch {
            getItemsUseCase()
                .onStart { _uiState.value = ItemListUiState.Loading }
                .catch { e ->
                    _uiState.value = ItemListUiState.Error(
                        message = e.message ?: "Failed to load items",
                        cause = e
                    )
                }
                .collect { items ->
                    _uiState.value = ItemListUiState.Success(items.map { it.toUi() })
                }
        }
    }

    fun onItemClicked(itemId: Int) {
        viewModelScope.launch {
            _uiEvent.emit(ItemListUiEvent.NavigateToDetail(itemId))
        }
    }

    fun onDeleteItem(itemId: Int) {
        viewModelScope.launch {
            try {
                deleteItemUseCase(itemId)
                _uiEvent.emit(ItemListUiEvent.ShowSnackbar("Item deleted"))
            } catch (e: Exception) {
                _uiEvent.emit(ItemListUiEvent.ShowSnackbar("Failed to delete item"))
            }
        }
    }

    fun retry() {
        loadItems()
    }
}
```

---

## Hilt Module Patterns

### NetworkModule

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
        })
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @Provides
    @Singleton
    fun provideItemApi(retrofit: Retrofit): ItemApi = retrofit.create(ItemApi::class.java)
}
```

### DatabaseModule

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "app.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides
    fun provideItemDao(database: AppDatabase): ItemDao = database.itemDao()
}
```

### RepositoryModule

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    // Use @Binds to bind an interface to its implementation
    // Hilt creates less code than @Provides — prefer @Binds when you own the class
    @Binds
    @Singleton
    abstract fun bindItemRepository(impl: ItemRepositoryImpl): ItemRepository

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository
}
```

---

## Repository Interface (Domain Layer)

```kotlin
// In :core:domain
interface ItemRepository {
    fun getItems(): Flow<List<Item>>
    fun getItemById(id: Int): Flow<Item?>
    suspend fun syncItems(): Result<Unit>
    suspend fun deleteItem(id: Int): Result<Unit>
    suspend fun createItem(title: String): Result<Item>
}
```

---

## Repository Implementation (Data Layer)

```kotlin
// In :core:data
class ItemRepositoryImpl @Inject constructor(
    private val itemDao: ItemDao,
    private val itemApi: ItemApi,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : ItemRepository {

    override fun getItems(): Flow<List<Item>> =
        itemDao.getAllItems()
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(dispatcher)

    override fun getItemById(id: Int): Flow<Item?> =
        itemDao.getItemById(id)
            .map { it?.toDomain() }
            .flowOn(dispatcher)

    override suspend fun syncItems(): Result<Unit> = withContext(dispatcher) {
        runCatching {
            val remoteItems = itemApi.getItems()
            itemDao.replaceAll(remoteItems.map { it.toEntity() })
        }
    }

    override suspend fun deleteItem(id: Int): Result<Unit> = withContext(dispatcher) {
        runCatching { itemDao.deleteById(id) }
    }

    override suspend fun createItem(title: String): Result<Item> = withContext(dispatcher) {
        runCatching {
            val response = itemApi.createItem(CreateItemRequest(title))
            val entity = response.toEntity()
            itemDao.insert(entity)
            entity.toDomain()
        }
    }
}
```

---

## Offline-First with networkBoundResource

Use `networkBoundResource` to serve cached data immediately, then refresh from the network:

```kotlin
fun <ResultType, RequestType> networkBoundResource(
    query: () -> Flow<ResultType>,
    fetch: suspend () -> RequestType,
    saveFetchResult: suspend (RequestType) -> Unit,
    shouldFetch: (ResultType) -> Boolean = { true }
): Flow<Resource<ResultType>> = flow {
    val data = query().first()

    val flow = if (shouldFetch(data)) {
        emit(Resource.Loading(data))
        try {
            saveFetchResult(fetch())
            query().map { Resource.Success(it) }
        } catch (t: Throwable) {
            query().map { Resource.Error(t, it) }
        }
    } else {
        query().map { Resource.Success(it) }
    }

    emitAll(flow)
}.flowOn(Dispatchers.IO)
```

```kotlin
sealed class Resource<T>(val data: T? = null, val error: Throwable? = null) {
    class Success<T>(data: T) : Resource<T>(data)
    class Error<T>(error: Throwable, data: T? = null) : Resource<T>(data, error)
    class Loading<T>(data: T? = null) : Resource<T>(data)
}
```

Use in the repository:

```kotlin
fun getItems(): Flow<Resource<List<Item>>> = networkBoundResource(
    query = { itemDao.getAllItems().map { it.map(ItemEntity::toDomain) } },
    fetch = { itemApi.getItems() },
    saveFetchResult = { remoteItems -> itemDao.replaceAll(remoteItems.map(ItemDto::toEntity)) },
    shouldFetch = { cached -> cached.isEmpty() || isStale() }
)
```

---

## Result<T> for Suspend Operations

Use Kotlin's built-in `Result<T>` (or a custom `Resource<T>`) for suspend functions that can fail:

```kotlin
// Repository returns Result<T>
override suspend fun createItem(title: String): Result<Item> = runCatching {
    val response = itemApi.createItem(CreateItemRequest(title))
    response.toEntity().also { itemDao.insert(it) }.toDomain()
}

// ViewModel handles it
viewModelScope.launch {
    repository.createItem(title)
        .onSuccess { item ->
            _uiEvent.emit(ItemListUiEvent.ShowSnackbar("Created: ${item.title}"))
        }
        .onFailure { e ->
            _uiEvent.emit(ItemListUiEvent.ShowSnackbar("Error: ${e.message}"))
        }
}
```

---

## SavedStateHandle for Process Death Survival

Pass `SavedStateHandle` into the ViewModel constructor — Hilt injects it automatically. Use it to persist small pieces of UI state (selected filter, search query) across process death.

```kotlin
@HiltViewModel
class ItemListViewModel @Inject constructor(
    private val getItemsUseCase: GetItemsUseCase,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // Survives process death; initial value is "" if not previously set
    private val searchQuery = savedStateHandle.getStateFlow("search_query", initialValue = "")

    // Persist whenever the user types
    fun onSearchQueryChanged(query: String) {
        savedStateHandle["search_query"] = query
    }
}
```

Also use `savedStateHandle` to retrieve Navigation arguments in the ViewModel:

```kotlin
// Route: @Serializable data class ItemDetail(val itemId: Int)
private val itemId: Int = savedStateHandle.toRoute<ItemDetail>().itemId
```

---

## Use Case Pattern

```kotlin
// In :core:domain — no Android imports
class GetItemsUseCase @Inject constructor(
    private val repository: ItemRepository
) {
    // Operator fun invoke() makes call sites read as getItemsUseCase()
    operator fun invoke(filter: ItemFilter = ItemFilter.All): Flow<List<Item>> =
        repository.getItems()
            .map { items ->
                when (filter) {
                    ItemFilter.All -> items
                    ItemFilter.Active -> items.filter { !it.isCompleted }
                    ItemFilter.Completed -> items.filter { it.isCompleted }
                }
            }
}
```

For use cases with no domain logic beyond delegation, a use case class adds unnecessary indirection. In those cases, call the repository directly from the ViewModel. Add a use case when:
- Multiple repositories need to be combined
- Business logic transforms or filters the data
- The same logic is needed in multiple ViewModels
