# Testing Reference

## Test Pyramid

Target this distribution across the test suite:

| Level | Share | Tools | Location |
|---|---|---|---|
| Unit tests | 70% | JUnit 5, MockK, Turbine, Coroutines Test | `src/test/` |
| Integration tests | 20% | Room in-memory, Hilt test, real coroutines | `src/test/` or `src/androidTest/` |
| UI / end-to-end | 10% | Compose Test, Espresso, HiltAndroidTest | `src/androidTest/` |

Run the full test suite with:

```bash
# Unit tests
./gradlew test

# Instrumented tests (requires connected device or emulator)
./gradlew connectedAndroidTest

# Single module
./gradlew :feature:items:test
```

---

## Test Dependencies

Add to each module's `build.gradle.kts`:

```toml
# libs.versions.toml
[versions]
junit = "4.13.2"
junit5 = "5.11.4"
mockk = "1.13.14"
turbine = "1.2.0"
coroutines-test = "1.10.1"   # Match kotlinx-coroutines version
espresso = "3.6.1"
hilt-testing = "2.54"        # Match hilt version

[libraries]
# Unit test
junit = { group = "junit", name = "junit", version.ref = "junit" }
junit5-api = { group = "org.junit.jupiter", name = "junit-jupiter-api", version.ref = "junit5" }
junit5-engine = { group = "org.junit.jupiter", name = "junit-jupiter-engine", version.ref = "junit5" }
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines-test" }
# Instrumented test
hilt-testing = { group = "com.google.dagger", name = "hilt-android-testing", version.ref = "hilt-testing" }
espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espresso" }
compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
```

```kotlin
// Module build.gradle.kts
testImplementation(libs.junit)
testImplementation(libs.mockk)
testImplementation(libs.turbine)
testImplementation(libs.coroutines.test)
testImplementation(libs.room.testing)

androidTestImplementation(libs.hilt.testing)
kspAndroidTest(libs.hilt.compiler)
androidTestImplementation(libs.espresso.core)
androidTestImplementation(libs.compose.ui.test.junit4)
debugImplementation(libs.compose.ui.test.manifest)
```

---

## Unit Testing ViewModels

ViewModels use `viewModelScope` which internally uses `Dispatchers.Main`. In unit tests, the main dispatcher doesn't exist — replace it with `UnconfinedTestDispatcher` using the `TestCoroutineRule` pattern.

### TestCoroutineRule

```kotlin
// In :core:testing module or test utilities
class TestCoroutineRule : TestWatcher() {
    val testDispatcher = UnconfinedTestDispatcher()

    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
```

### ViewModel Test Pattern

```kotlin
class ItemListViewModelTest {

    @get:Rule
    val coroutineRule = TestCoroutineRule()

    // Use a fake, not a mock — fakes have real behavior and are more maintainable
    private val fakeRepository = FakeItemRepository()
    private val getItemsUseCase = GetItemsUseCase(fakeRepository)

    private lateinit var viewModel: ItemListViewModel

    @Before
    fun setup() {
        viewModel = ItemListViewModel(getItemsUseCase, SavedStateHandle())
    }

    @Test
    fun `initial state is Loading`() = runTest {
        // The ViewModel is in Loading state before the first collection
        assertIs<ItemListUiState.Loading>(viewModel.uiState.value)
    }

    @Test
    fun `emits Success when repository returns items`() = runTest {
        val items = listOf(Item(1, "Test item", Instant.now()))
        fakeRepository.emit(items)

        viewModel.uiState.test {
            skipItems(1)  // Skip Loading
            val state = awaitItem()
            assertIs<ItemListUiState.Success>(state)
            assertEquals(1, (state as ItemListUiState.Success).items.size)
        }
    }

    @Test
    fun `emits Error when repository throws`() = runTest {
        fakeRepository.setError(RuntimeException("Network error"))

        viewModel.uiState.test {
            skipItems(1)  // Skip Loading
            val state = awaitItem()
            assertIs<ItemListUiState.Error>(state)
            assertEquals("Network error", (state as ItemListUiState.Error).message)
        }
    }

    @Test
    fun `onItemClicked emits NavigateToDetail event`() = runTest {
        viewModel.uiEvent.test {
            viewModel.onItemClicked(itemId = 42)
            val event = awaitItem()
            assertEquals(ItemListUiEvent.NavigateToDetail(42), event)
        }
    }
}
```

### Turbine API Summary

```kotlin
flow.test {
    // Assert the first emission
    val first = awaitItem()
    assertEquals(expected, first)

    // Skip N items
    skipItems(2)

    // Assert completion
    awaitComplete()

    // Assert error
    val error = awaitError()
    assertIs<IOException>(error)

    // Cancel collection (optional at end of test block)
    cancelAndIgnoreRemainingEvents()
}
```

---

## Fake Repositories

Prefer fakes over mocks for repositories. Fakes implement the real interface and give tests control over the data the system under test receives.

```kotlin
// In :core:testing module — shared across feature test modules
class FakeItemRepository : ItemRepository {

    private val itemsFlow = MutableSharedFlow<List<Item>>(replay = 1)
    private var error: Throwable? = null

    /** Emit new items to all observers. */
    suspend fun emit(items: List<Item>) {
        itemsFlow.emit(items)
    }

    /** Set an error to throw on the next suspend call. */
    fun setError(error: Throwable) {
        this.error = error
    }

    override fun getItems(): Flow<List<Item>> = flow {
        emitAll(itemsFlow)
    }

    override suspend fun deleteItem(id: Int): Result<Unit> {
        error?.let { return Result.failure(it) }
        val current = itemsFlow.replayCache.firstOrNull() ?: emptyList()
        itemsFlow.emit(current.filter { it.id != id })
        return Result.success(Unit)
    }

    override suspend fun createItem(title: String): Result<Item> {
        error?.let { return Result.failure(it) }
        val newItem = Item(id = (100..999).random(), title = title, createdAt = Instant.now())
        val current = itemsFlow.replayCache.firstOrNull() ?: emptyList()
        itemsFlow.emit(current + newItem)
        return Result.success(newItem)
    }
}
```

---

## Room In-Memory Database

Test Room DAOs against a real in-memory database — no mocking needed.

```kotlin
class ItemDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: ItemDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        )
            .allowMainThreadQueries()   // OK in tests only
            .build()
        dao = database.itemDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertAndGetById() = runTest {
        val entity = ItemEntity(id = 0, title = "Test", createdAt = System.currentTimeMillis())
        val insertedId = dao.insert(entity)

        val retrieved = dao.getItemById(insertedId.toInt()).first()
        assertNotNull(retrieved)
        assertEquals("Test", retrieved!!.title)
    }

    @Test
    fun getAllItemsEmitsOnInsert() = runTest {
        dao.getAllItems().test {
            // Initial state is empty list
            assertEquals(emptyList(), awaitItem())

            dao.insert(ItemEntity(0, "First", System.currentTimeMillis()))
            val afterInsert = awaitItem()
            assertEquals(1, afterInsert.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleteRemovesItem() = runTest {
        val id = dao.insert(ItemEntity(0, "To delete", System.currentTimeMillis())).toInt()
        dao.deleteById(id)
        assertNull(dao.getItemById(id).first())
    }
}
```

---

## Hilt Testing

### Unit Tests with Hilt (not recommended)

For pure unit tests, construct ViewModels and use cases manually with fakes — no Hilt setup needed. This is faster and simpler.

### Instrumented Tests with @HiltAndroidTest

Use `@HiltAndroidTest` for instrumented tests that need the full DI graph:

```kotlin
@HiltAndroidTest
class ItemListScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    // Replace a module with a test module
    @UninstallModules(RepositoryModule::class)
    @Module
    @InstallIn(SingletonComponent::class)
    abstract class TestRepositoryModule {
        @Binds
        @Singleton
        abstract fun bindItemRepository(fake: FakeItemRepository): ItemRepository
    }

    // Inject a test value directly
    @BindValue
    val fakeRepository: FakeItemRepository = FakeItemRepository()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun showsItemsWhenLoaded() = runTest {
        fakeRepository.emit(listOf(Item(1, "Test item", Instant.now())))

        composeRule.onNodeWithText("Test item").assertIsDisplayed()
    }
}
```

Add to `AndroidManifest.xml` in `src/debug/` (or `src/androidTest/`):

```xml
<application
    android:name="com.example.HiltTestApplication"
    tools:replace="android:name" />
```

Or use the Hilt test runner in `build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        testInstrumentationRunner = "com.google.dagger.hilt.android.testing.HiltTestRunner"
    }
}
```

---

## Compose UI Testing

### createComposeRule

Test stateless composables in isolation — no Activity or Hilt needed:

```kotlin
class ItemListContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsLoadingIndicator() {
        composeRule.setContent {
            AppTheme {
                ItemListContent(
                    uiState = ItemListUiState.Loading,
                    onItemClicked = {},
                    onRetry = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("Loading").assertIsDisplayed()
    }

    @Test
    fun showsItemsInSuccessState() {
        val items = listOf(ItemUi(1, "Item One", "Jan 1"), ItemUi(2, "Item Two", "Jan 2"))

        composeRule.setContent {
            AppTheme {
                ItemListContent(
                    uiState = ItemListUiState.Success(items),
                    onItemClicked = {},
                    onRetry = {}
                )
            }
        }

        composeRule.onNodeWithText("Item One").assertIsDisplayed()
        composeRule.onNodeWithText("Item Two").assertIsDisplayed()
    }

    @Test
    fun retryButtonCallsCallback() {
        var retryCalled = false

        composeRule.setContent {
            AppTheme {
                ItemListContent(
                    uiState = ItemListUiState.Error("Network error"),
                    onItemClicked = {},
                    onRetry = { retryCalled = true }
                )
            }
        }

        composeRule.onNodeWithText("Retry").performClick()
        assertTrue(retryCalled)
    }
}
```

### Semantic Matchers

```kotlin
// Text content
composeRule.onNodeWithText("Submit").assertIsDisplayed()
composeRule.onNodeWithText("Submit", useUnmergedTree = true).assertExists()

// Content description (set via contentDescription or semantics)
composeRule.onNodeWithContentDescription("Delete item").performClick()

// Tag (use sparingly — prefer semantic matchers)
composeRule.onNodeWithTag("item_list").assertIsDisplayed()

// Combined matchers
composeRule.onNode(hasText("Item One") and hasClickAction()).performClick()

// Scrolling to an item in a lazy list
composeRule.onNodeWithText("Item far down")
    .performScrollTo()
    .assertIsDisplayed()
```

### waitUntil for Async Operations

```kotlin
@Test
fun showsItemsAfterLoading() {
    // Start with loading state
    fakeRepository.setDelay(500)

    composeRule.setContent { AppTheme { ItemListScreen() } }

    // Wait until the list appears (default timeout: 1000ms)
    composeRule.waitUntil(timeoutMillis = 3000) {
        composeRule.onAllNodesWithText("Item").fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithText("Item").assertIsDisplayed()
}
```

### Testing Navigation

```kotlin
@Test
fun clickingItemNavigatesToDetail() {
    val navController = TestNavHostController(LocalContext.current)

    composeRule.setContent {
        AppTheme {
            AppNavHost(navController = navController)
        }
    }

    composeRule.onNodeWithText("Item One").performClick()

    // Verify navigation occurred to the correct route
    assertEquals(ItemDetail(itemId = 1), navController.currentBackStackEntry?.toRoute())
}
```

---

## Use Case Unit Tests

```kotlin
class GetItemsUseCaseTest {

    private val fakeRepository = FakeItemRepository()
    private val useCase = GetItemsUseCase(fakeRepository)

    @Test
    fun `filters active items when filter is Active`() = runTest {
        val items = listOf(
            Item(1, "Active", Instant.now(), isCompleted = false),
            Item(2, "Done", Instant.now(), isCompleted = true)
        )
        fakeRepository.emit(items)

        useCase(filter = ItemFilter.Active).test {
            val filtered = awaitItem()
            assertEquals(1, filtered.size)
            assertEquals("Active", filtered.first().title)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```
