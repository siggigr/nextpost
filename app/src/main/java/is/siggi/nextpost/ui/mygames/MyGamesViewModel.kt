package `is`.siggi.nextpost.ui.mygames

import `is`.siggi.nextpost.data.model.Game
import `is`.siggi.nextpost.data.repository.GameRepository
import `is`.siggi.nextpost.ui.common.WriteError
import `is`.siggi.nextpost.ui.common.toWriteError
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MyGamesUiState(
    val isLoading: Boolean = true,
    val games: List<Game> = emptyList(),
    /** A denied or dropped delete must say so — the row reappearing via [MyGamesViewModel.refresh]
     * already signals *that* it failed, but not *why*. */
    val deleteError: WriteError? = null,
    /** M7 hardening: a failed [MyGamesViewModel.refresh] must not look identical to a creator
     * who genuinely has no games yet. [games] is left untouched on failure rather than cleared
     * — a stale list beats losing an already-loaded one to a transient refresh error — so the
     * screen only needs to check this when [games] is also empty to tell the two states apart. */
    val loadError: WriteError? = null
)

/**
 * Drafts and published games alike, per section 5.1. One-shot get() on open and on
 * pull-to-refresh, not a listener: see section 15.2 and GameRepository.
 */
class MyGamesViewModel(private val repository: GameRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(MyGamesUiState())
    val uiState: StateFlow<MyGamesUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadError = null) }
            try {
                val games = repository.loadMyGames()
                _uiState.update { it.copy(isLoading = false, games = games) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, loadError = e.toWriteError()) }
            }
        }
    }

    /**
     * Removes the row immediately, like [CreateGameViewModel.deleteSelectedPost]: deletion
     * carries no risk of creating a duplicate, so there's no reason to make the creator wait
     * for the round trip through GameRepository.deleteGame's whole-tree walk. On failure,
     * refresh() re-fetches so a row that didn't actually delete doesn't stay silently gone.
     */
    fun deleteGame(game: Game) {
        _uiState.update { state ->
            state.copy(games = state.games.filterNot { it.id == game.id }, deleteError = null)
        }
        viewModelScope.launch {
            try {
                repository.deleteGame(game)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // refresh() brings the row back (this can't just re-insert it locally — the
                // row it filtered out is gone from state by now), but a reappearing row says
                // only *that* the delete failed, not *why*; deleteError carries the why.
                _uiState.update { it.copy(deleteError = e.toWriteError()) }
                refresh()
            }
        }
    }

    /** Dismisses the delete-failure message once the creator has seen it. */
    fun dismissDeleteError() {
        _uiState.update { it.copy(deleteError = null) }
    }
}
