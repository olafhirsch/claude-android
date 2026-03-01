# Jetpack Compose Reference

## Layout Fundamentals

Compose uses three primary layout composables. Choose based on direction of arrangement:

| Composable | Arranges | Equivalent to |
|---|---|---|
| `Column` | Vertically | `LinearLayout` vertical |
| `Row` | Horizontally | `LinearLayout` horizontal |
| `Box` | Overlapping / Z-axis | `FrameLayout` |

### Column and Row

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    horizontalAlignment = Alignment.CenterHorizontally
) {
    Text("Header", style = MaterialTheme.typography.headlineMedium)
    HorizontalDivider()
    Text("Body content")
}

Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
) {
    Text("Left")
    IconButton(onClick = { }) {
        Icon(Icons.Default.MoreVert, contentDescription = "More options")
    }
}
```

### Lazy Lists and Grids

```kotlin
// Vertical scrolling list
LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
) {
    item { Header() }

    items(items = myList, key = { it.id }) { item ->
        // key() is required for correct animation and recomposition
        ItemCard(item = item)
    }

    item { Footer() }
}

// Grid
LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 150.dp),
    contentPadding = PaddingValues(8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
) {
    items(items, key = { it.id }) { item ->
        GridItemCard(item)
    }
}
```

Always provide a `key` lambda to `items()` when the list can change order or size. This allows Compose to correctly animate changes and skip unnecessary recompositions.

### Scaffold with Material 3

`Scaffold` handles the standard Material screen layout (top bar, bottom bar, FAB, snackbar):

```kotlin
@Composable
fun ItemListScreen(viewModel: ItemListViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is ItemListUiEvent.ShowSnackbar ->
                    snackbarHostState.showSnackbar(event.message)
                else -> Unit
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Items") },
                actions = {
                    IconButton(onClick = viewModel::onSyncClicked) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::onAddItemClicked) {
                Icon(Icons.Default.Add, contentDescription = "Add item")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        ItemListContent(
            uiState = uiState,
            modifier = Modifier.padding(innerPadding),
            onItemClicked = viewModel::onItemClicked,
            onRetry = viewModel::retry
        )
    }
}
```

---

## Material 3 Theming

### Theme Setup

```kotlin
// Theme.kt
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,   // Android 12+ only
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}

// Color scheme definitions
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFF625B71),
    // ... full scheme
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    // ...
)
```

### Typography

```kotlin
val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
    // ... define all scales used in the app
)
```

Use `MaterialTheme.typography.bodyLarge`, `MaterialTheme.colorScheme.primary`, etc. throughout composables. Never hardcode colors or text styles.

---

## State and Recomposition Performance

### derivedStateOf

Use `derivedStateOf` when a value derives from other state and the derivation is expensive or would trigger many recompositions:

```kotlin
// Without derivedStateOf — recomposes every time scrollState changes
val showFab = scrollState.firstVisibleItemIndex > 0

// With derivedStateOf — only recomposes when the boolean result changes
val showFab by remember {
    derivedStateOf { scrollState.firstVisibleItemIndex > 0 }
}
```

Use `derivedStateOf` when:
- Computing a filtered/sorted list from a larger list
- Deriving a boolean from a numeric state
- Transforming a frequently-changing value into a less-frequently-changing derived value

### key() in Lazy Lists

Always provide unique, stable keys in lazy lists:

```kotlin
LazyColumn {
    items(
        items = itemList,
        key = { item -> item.id }   // Stable, unique key enables correct animations
    ) { item ->
        ItemCard(item, modifier = Modifier.animateItem())
    }
}
```

### @Stable and @Immutable

Annotate classes that Compose can't infer as stable to prevent unnecessary recompositions:

```kotlin
// Compose infers all-val data class with stable types as stable — no annotation needed
data class ItemUi(val id: Int, val title: String)

// Class with var properties or mutable types needs annotation
@Stable
class ItemViewModel : ViewModel() { ... }

// Data class wrapping a collection needs @Immutable if the list won't change
@Immutable
data class ItemListUiState(val items: List<ItemUi>)
```

### remember and rememberSaveable

```kotlin
// Survives recompositions, lost on config change
val query = remember { mutableStateOf("") }

// Survives config change and process death (uses Bundle serialization)
var query by rememberSaveable { mutableStateOf("") }

// rememberSaveable with custom saver for non-Bundle types
var selectedItem by rememberSaveable(stateSaver = ItemUiSaver) {
    mutableStateOf<ItemUi?>(null)
}
```

---

## Side Effects

### LaunchedEffect

Execute a suspend block when a composable enters composition, or when the key changes. Use for one-time setup, consuming events, or reacting to state changes.

```kotlin
// Run once when the composable enters composition
LaunchedEffect(Unit) {
    viewModel.uiEvent.collect { event -> handleEvent(event) }
}

// Re-run when itemId changes
LaunchedEffect(itemId) {
    viewModel.loadItem(itemId)
}

// Run when an error appears
LaunchedEffect(error) {
    if (error != null) snackbarHostState.showSnackbar(error)
}
```

`LaunchedEffect` is cancelled and re-launched when the key changes. It's cancelled when the composable leaves composition.

### DisposableEffect

Use `DisposableEffect` when a side effect requires cleanup (registering listeners, acquiring resources):

```kotlin
DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) viewModel.onResume()
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
        lifecycleOwner.lifecycle.removeObserver(observer)
    }
}
```

### SideEffect

Use `SideEffect` to publish Compose state to non-Compose code (e.g., updating an analytics tracker):

```kotlin
SideEffect {
    // Called after every successful recomposition
    analytics.setCurrentScreen(screenName)
}
```

### rememberCoroutineScope

Use when you need to launch coroutines in response to user events (not on composition):

```kotlin
val scope = rememberCoroutineScope()

Button(onClick = {
    // scope is tied to this composable's lifecycle
    scope.launch { snackbarHostState.showSnackbar("Done!") }
}) {
    Text("Save")
}
```

---

## Animations

### AnimatedVisibility

```kotlin
AnimatedVisibility(
    visible = showBanner,
    enter = fadeIn() + expandVertically(),
    exit = fadeOut() + shrinkVertically()
) {
    Banner(message = bannerMessage)
}
```

### animateContentSize

Animate layout size changes automatically:

```kotlin
Column(
    modifier = Modifier
        .clip(RoundedCornerShape(8.dp))
        .animateContentSize(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy))
) {
    Text(text = if (expanded) fullText else shortText)
}
```

### animate*AsState

Animate individual property changes:

```kotlin
val alpha by animateFloatAsState(
    targetValue = if (isVisible) 1f else 0f,
    animationSpec = tween(durationMillis = 300),
    label = "visibility_alpha"
)
Box(modifier = Modifier.alpha(alpha)) { ... }

val color by animateColorAsState(
    targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                  else MaterialTheme.colorScheme.surface,
    label = "selection_color"
)
```

Always provide a `label` parameter to `animate*AsState` — it appears in the Layout Inspector's animation debugger.

### Transition API

Use `updateTransition` for coordinated multi-property animations:

```kotlin
val transition = updateTransition(targetState = isExpanded, label = "card_transition")

val elevation by transition.animateDp(label = "elevation") { expanded ->
    if (expanded) 8.dp else 2.dp
}
val backgroundColor by transition.animateColor(label = "background") { expanded ->
    if (expanded) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surface
}
```

---

## Accessibility

### contentDescription

Provide `contentDescription` for all icons and images that convey information:

```kotlin
Icon(
    imageVector = Icons.Default.Favorite,
    contentDescription = "Add to favorites"  // Announced by TalkBack
)

Image(
    painter = painterResource(R.drawable.product_image),
    contentDescription = "Product image: ${product.name}"
)

// Decorative images get null contentDescription — TalkBack skips them
Icon(
    imageVector = Icons.Default.Circle,
    contentDescription = null   // Purely decorative
)
```

### semantics modifier

Use `semantics { }` to provide richer accessibility information:

```kotlin
// Mark a complex component as a single accessibility unit
Row(
    modifier = Modifier.semantics(mergeDescendants = true) { }
) {
    Icon(Icons.Default.Star, contentDescription = null)
    Text("${rating} stars")
}

// Custom actions
Box(
    modifier = Modifier.semantics {
        contentDescription = "Item: ${item.title}"
        onClick(label = "Open item details") {
            onItemClicked(item.id)
            true
        }
        stateDescription = if (item.isFavorite) "Favorited" else "Not favorited"
    }
)
```

### Minimum touch target size

Ensure interactive elements have a minimum 48dp touch target:

```kotlin
IconButton(
    onClick = onDeleteClicked,
    modifier = Modifier.minimumInteractiveComponentSize()  // Enforces 48dp minimum
) {
    Icon(Icons.Default.Delete, contentDescription = "Delete item")
}
```

### Testing accessibility

Use the `accessibilityNodeProvider` in Compose UI tests, or the Android Accessibility Scanner app to validate that all interactive elements have appropriate descriptions and touch target sizes.

---

## Previews

### Basic Preview

```kotlin
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ItemCardPreview() {
    AppTheme {
        ItemCard(
            item = ItemUi(id = 1, title = "Preview item", formattedDate = "Jan 1, 2025"),
            onClicked = {}
        )
    }
}
```

### PreviewParameter

Use `@PreviewParameter` with a `PreviewParameterProvider` for multiple preview states:

```kotlin
class ItemUiStatePreviewProvider : PreviewParameterProvider<ItemListUiState> {
    override val values = sequenceOf(
        ItemListUiState.Loading,
        ItemListUiState.Success(
            items = listOf(
                ItemUi(1, "First item", "Jan 1, 2025"),
                ItemUi(2, "Second item", "Jan 2, 2025")
            )
        ),
        ItemListUiState.Error("Failed to load items")
    )
}

@Preview(showBackground = true)
@Composable
private fun ItemListContentPreview(
    @PreviewParameter(ItemUiStatePreviewProvider::class) uiState: ItemListUiState
) {
    AppTheme {
        ItemListContent(
            uiState = uiState,
            onItemClicked = {},
            onRetry = {}
        )
    }
}
```

### Dark Mode and Font Scale Previews

```kotlin
@Preview(name = "Light", uiMode = UI_MODE_NIGHT_NO)
@Preview(name = "Dark", uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "Large font", fontScale = 1.5f)
@Composable
private fun ItemCardMultiPreview() {
    AppTheme { ItemCard(item = previewItem, onClicked = {}) }
}
```

---

## State Hoisting Pattern Summary

Follow this pattern for every screen:

```
Route Composable (stateful)
│  - calls hiltViewModel()
│  - calls collectAsStateWithLifecycle()
│  - calls LaunchedEffect for events
│  - passes state and lambdas down
│
└── Content Composable (stateless)
    │  - accepts UiState + lambda parameters
    │  - uses when(uiState) { ... }
    │  - calls leaf composables
    │
    ├── Loading Composable
    ├── Error Composable (accepts onRetry lambda)
    └── Success Content Composable
        └── Leaf Composables (ItemCard, etc.)
```

Stateless composables are independently testable with `createComposeRule()` without needing a ViewModel or Hilt setup.
