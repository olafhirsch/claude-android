package com.example.feature.items.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.domain.model.Item
import com.example.core.domain.usecase.DeleteItemUseCase
import com.example.core.domain.usecase.GetItemsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── UI State ────────────────────────────────────────────────────────────────
//
// Sealed class models every possible screen state as a single type.
// This eliminates illegal combinations like (isLoading=true, error="…") that
// separate boolean/nullable fields would allow.

sealed class ItemListUiState {
    // Use data object (not data class) for states with no payload — Kotlin 1.9+
    data object Loading : ItemListUiState()

    data class Success(
        val items: List<ItemUi>,
        // Additional state that coexists with a successful list
        val isRefreshing: Boolean = false
    ) : ItemListUiState()

    data class Error(
        val message: String,
        val cause: Throwable? = null   // Keep cause for logging; don't show it to users
    ) : ItemListUiState()
}

// ─── UI Events ───────────────────────────────────────────────────────────────
//
// One-shot effects the UI should consume exactly once (navigation, snackbars).
// These must NOT live in UiState — state is replayed to new collectors; events
// must not be replayed.

sealed class ItemListUiEvent {
    data class NavigateToDetail(val itemId: Int) : ItemListUiEvent()
    data class ShowSnackbar(val message: String) : ItemListUiEvent()
    data object NavigateBack : ItemListUiEvent()
}

// ─── UI Model ────────────────────────────────────────────────────────────────
//
// A separate UI-layer model decouples Composables from domain changes.
// Pre-format data here (dates, currencies) so Composables stay pure.

data class ItemUi(
    val id: Int,
    val title: String,
    val formattedDate: String
)

fun Item.toUi(): ItemUi = ItemUi(
    id = id,
    title = title,
    formattedDate = createdAt.toString()  // Replace with a real formatter in production
)

// ─── ViewModel ───────────────────────────────────────────────────────────────

// @HiltViewModel tells Hilt to generate a factory for this ViewModel.
// The Hilt plugin handles wiring; never call ViewModelProvider.Factory manually.
@HiltViewModel
class ItemListViewModel @Inject constructor(
    private val getItemsUseCase: GetItemsUseCase,
    private val deleteItemUseCase: DeleteItemUseCase,
    // SavedStateHandle is injected automatically by Hilt.
    // Use it to persist small UI state (search query, scroll position) across
    // process death. Also use it to read navigation arguments via toRoute().
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // ── State ──────────────────────────────────────────────────────────────
    //
    // _uiState is private — only the ViewModel mutates it.
    // uiState is public and exposed as StateFlow (immutable interface).
    // asStateFlow() enforces that collectors cannot cast back to Mutable*.

    private val _uiState = MutableStateFlow<ItemListUiState>(ItemListUiState.Loading)
    val uiState: StateFlow<ItemListUiState> = _uiState.asStateFlow()

    // ── Events ─────────────────────────────────────────────────────────────
    //
    // replay = 0 is critical: events must not be replayed to new collectors
    // (e.g., after a config change). A replayed navigation event would navigate
    // the user again after rotation.

    private val _uiEvent = MutableSharedFlow<ItemListUiEvent>(replay = 0)
    val uiEvent: SharedFlow<ItemListUiEvent> = _uiEvent.asSharedFlow()

    // ── Persisted state ────────────────────────────────────────────────────
    //
    // getStateFlow() reads from the Bundle and emits on changes.
    // Update via savedStateHandle["key"] = newValue.

    val searchQuery: StateFlow<String> = savedStateHandle.getStateFlow("search_query", "")

    init {
        // Start loading immediately when the ViewModel is created.
        // If init logic can fail or involves async work, put it in a function
        // so it can be retried (see retry()).
        loadItems()
    }

    // ── Data loading ───────────────────────────────────────────────────────

    private fun loadItems() {
        viewModelScope.launch {
            getItemsUseCase()
                // Emit Loading before the first item arrives from the Flow
                .onStart { _uiState.value = ItemListUiState.Loading }
                // Catch terminal errors in the Flow chain.
                // .catch must come after operators that might throw.
                .catch { e ->
                    _uiState.value = ItemListUiState.Error(
                        message = e.message ?: "An unexpected error occurred",
                        cause = e
                    )
                }
                .collect { items ->
                    _uiState.value = ItemListUiState.Success(
                        items = items.map { it.toUi() }
                    )
                }
        }
    }

    // ── User actions ───────────────────────────────────────────────────────
    //
    // Action functions are called by the UI (via lambdas) and dispatch to
    // the ViewModel. They must not be suspend functions — the UI calls them
    // from onClick handlers which are not coroutine contexts.

    fun onItemClicked(itemId: Int) {
        // Emit a one-shot navigation event. The Composable collects this via
        // LaunchedEffect and calls navController.navigate().
        viewModelScope.launch {
            _uiEvent.emit(ItemListUiEvent.NavigateToDetail(itemId))
        }
    }

    fun onDeleteItem(itemId: Int) {
        viewModelScope.launch {
            // Optimistic update: show the item as deleted before the DB confirms
            val currentState = _uiState.value
            if (currentState is ItemListUiState.Success) {
                _uiState.value = currentState.copy(
                    items = currentState.items.filter { it.id != itemId }
                )
            }

            deleteItemUseCase(itemId)
                .onSuccess {
                    _uiEvent.emit(ItemListUiEvent.ShowSnackbar("Item deleted"))
                }
                .onFailure { e ->
                    // Roll back the optimistic update on failure
                    _uiState.value = currentState
                    _uiEvent.emit(ItemListUiEvent.ShowSnackbar("Delete failed: ${e.message}"))
                }
        }
    }

    fun onSearchQueryChanged(query: String) {
        savedStateHandle["search_query"] = query
    }

    // Called by the UI's "Retry" button in the Error state
    fun retry() {
        loadItems()
    }
}
