package com.example.feature.items.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.serialization.Serializable

// ─── Route Definition ─────────────────────────────────────────────────────────
//
// Define routes as @Serializable objects or data classes alongside the screen
// they map to. Import from a shared Routes.kt if routes are referenced in
// multiple places (e.g., deep links, bottom nav).

@Serializable
object ItemListRoute

@Serializable
data class ItemDetailRoute(val itemId: Int)

// ─── Stateful Entry Composable ────────────────────────────────────────────────
//
// This is the only composable in the file that knows about the ViewModel.
// It is placed in the NavHost's composable<ItemListRoute> block.
//
// Responsibilities:
//   - Obtain the ViewModel via hiltViewModel()
//   - Collect state with collectAsStateWithLifecycle() (not collectAsState())
//   - Consume one-shot events via LaunchedEffect
//   - Pass state and lambdas down to the stateless content composable
//
// collectAsStateWithLifecycle() stops collection when the app goes to background,
// preventing unnecessary recompositions and resource use.

@Composable
fun ItemListScreen(
    navController: NavController,
    viewModel: ItemListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // LaunchedEffect(Unit) runs once when this composable enters composition.
    // Unit as key means "run on entry, never re-run on recomposition".
    // Use a different key (e.g., a specific state value) to re-run when it changes.
    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is ItemListUiEvent.NavigateToDetail ->
                    navController.navigate(ItemDetailRoute(event.itemId))

                is ItemListUiEvent.ShowSnackbar ->
                    snackbarHostState.showSnackbar(event.message)

                ItemListUiEvent.NavigateBack ->
                    navController.popBackStack()
            }
        }
    }

    ItemListContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onItemClicked = viewModel::onItemClicked,
        onDeleteItem = viewModel::onDeleteItem,
        onRetry = viewModel::retry
    )
}

// ─── Stateless Content Composable ────────────────────────────────────────────
//
// This composable is a pure function of its parameters:
//   - No ViewModel, no hiltViewModel()
//   - No NavController
//   - All user interactions are represented as lambda parameters
//
// Benefits:
//   - Trivially testable with createComposeRule() — no DI needed
//   - Previewable with @PreviewParameter
//   - Reusable in different navigation contexts (e.g., tablet two-pane layout)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemListContent(
    uiState: ItemListUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onItemClicked: (itemId: Int) -> Unit,
    onDeleteItem: (itemId: Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Items") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        // Exhaustive when — the compiler enforces that all UiState branches
        // are handled. Adding a new sealed class subtype causes a compile error
        // here, forcing the UI to handle it.
        when (uiState) {
            is ItemListUiState.Loading -> LoadingContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )

            is ItemListUiState.Success -> SuccessContent(
                items = uiState.items,
                onItemClicked = onItemClicked,
                onDeleteItem = onDeleteItem,
                modifier = Modifier.padding(innerPadding)
            )

            is ItemListUiState.Error -> ErrorContent(
                message = uiState.message,
                onRetry = onRetry,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        }
    }
}

// ─── State Branch Composables ─────────────────────────────────────────────────

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // contentDescription is announced by TalkBack to convey the loading state
        CircularProgressIndicator(
            modifier = Modifier.semantics { contentDescription = "Loading items" }
        )
    }
}

@Composable
private fun SuccessContent(
    items: List<ItemUi>,
    onItemClicked: (Int) -> Unit,
    onDeleteItem: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Always provide a stable key — enables correct animations and prevents
        // full list recompositions when an item is inserted or deleted
        items(items = items, key = { it.id }) { item ->
            ItemCard(
                item = item,
                onClicked = { onItemClicked(item.id) },
                onDeleteClicked = { onDeleteItem(item.id) },
                // Modifier.animateItem() animates insert/delete/move transitions
                modifier = Modifier.animateItem()
            )
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
        Button(
            onClick = onRetry,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text("Retry")
        }
    }
}

// ─── Leaf Composable ──────────────────────────────────────────────────────────
//
// Leaf composables are the smallest reusable pieces. They receive only the data
// they need and a single callback per user action.

@Composable
fun ItemCard(
    item: ItemUi,
    onClicked: () -> Unit,
    onDeleteClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                // The click action description is announced by TalkBack
                onClickLabel = "Open ${item.title}",
                onClick = onClicked
            )
            // mergeDescendants = true: TalkBack treats the whole card as one unit
            // and reads the combined semantic information instead of individual children
            .semantics(mergeDescendants = true) {
                contentDescription = "Item: ${item.title}, created ${item.formattedDate}"
            }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = item.formattedDate,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────
//
// Use @PreviewParameter to preview all UiState branches from a single @Preview.
// The PreviewParameterProvider supplies each value in the sequence as a separate
// preview panel in Android Studio.

private class ItemListUiStateProvider : PreviewParameterProvider<ItemListUiState> {
    override val values = sequenceOf(
        ItemListUiState.Loading,
        ItemListUiState.Success(
            items = listOf(
                ItemUi(id = 1, title = "Buy groceries", formattedDate = "Feb 28, 2025"),
                ItemUi(id = 2, title = "Write unit tests", formattedDate = "Feb 27, 2025"),
                ItemUi(id = 3, title = "Review PR", formattedDate = "Feb 26, 2025")
            )
        ),
        ItemListUiState.Error(message = "Failed to load items. Check your connection.")
    )
}

@Preview(showBackground = true, name = "Item List Screen")
@Composable
private fun ItemListContentPreview(
    @PreviewParameter(ItemListUiStateProvider::class) uiState: ItemListUiState
) {
    // Always wrap previews in the app theme for accurate color and typography
    // Replace AppTheme with your actual theme composable
    MaterialTheme {
        ItemListContent(
            uiState = uiState,
            onItemClicked = {},
            onDeleteItem = {},
            onRetry = {}
        )
    }
}

@Preview(showBackground = true, name = "Item Card")
@Composable
private fun ItemCardPreview() {
    MaterialTheme {
        ItemCard(
            item = ItemUi(id = 1, title = "Preview item", formattedDate = "Feb 28, 2025"),
            onClicked = {},
            onDeleteClicked = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
