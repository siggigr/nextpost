package `is`.siggi.nextpost.ui.mygames

import `is`.siggi.nextpost.data.model.Game
import `is`.siggi.nextpost.data.repository.GameRepository
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
    val games: List<Game> = emptyList()
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
            _uiState.update { it.copy(isLoading = true) }
            try {
                val games = repository.loadMyGames()
                _uiState.update { it.copy(isLoading = false, games = games) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
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
        _uiState.update { state -> state.copy(games = state.games.filterNot { it.id == game.id }) }
        viewModelScope.launch {
            try {
                repository.deleteGame(game)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // TODO(M7): surface a retry-able error instead of silently re-syncing.
                refresh()
            }
        }
    }
}
