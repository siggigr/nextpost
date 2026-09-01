package `is`.siggi.nextpost.ui.join

import `is`.siggi.nextpost.data.repository.GameRepository
import `is`.siggi.nextpost.data.repository.JoinOutcome
import `is`.siggi.nextpost.data.repository.PlayerNamePreferenceRepository
import `is`.siggi.nextpost.domain.GameCodeGenerator
import `is`.siggi.nextpost.ui.common.WriteError
import `is`.siggi.nextpost.ui.common.toWriteError
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Section 5.3: 1 to 24 characters, trimmed. */
const val MAX_PLAYER_NAME_LENGTH = 24

sealed interface JoinFieldError {
    data object NameRequired : JoinFieldError
    data object CodeIncomplete : JoinFieldError
    data object UnknownCode : JoinFieldError
    data object GameDeleted : JoinFieldError
    data object Generic : JoinFieldError
}

data class JoinGameUiState(
    val name: String = "",
    val code: String = "",
    val isJoining: Boolean = false,
    val nameError: JoinFieldError? = null,
    val codeError: JoinFieldError? = null,
    /** Non-null once a join succeeds; the screen navigates away and this is a one-shot value. */
    val joinedGameId: String? = null,
    /** Section 5.3's "session already finished (offer restart)" — the gameId awaiting a choice. */
    val pendingRestartGameId: String? = null,
    /** A denied or dropped restartSession write must say so — the dialog stays open on failure
     * (see [JoinGameViewModel.restartAndJoin]) so this explains why rather than leaving the
     * creator wondering why tapping Restart did nothing. */
    val restartError: WriteError? = null
)

/**
 * Section 5.3's join flow. Deliberately thin: [GameRepository.joinGame] does the actual
 * code-lookup-then-session-write, this only owns field state, client-side validation (name
 * non-blank, code length — the things a repository round trip shouldn't be needed to check),
 * and mapping outcomes to the field errors the screen shows.
 */
class JoinGameViewModel(
    private val repository: GameRepository,
    private val playerNamePreferenceRepository: PlayerNamePreferenceRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(JoinGameUiState())
    val uiState: StateFlow<JoinGameUiState> = _uiState.asStateFlow()

    init {
        // Section 5.3: prefilled from the last name this device used, but left editable —
        // someone handing their phone to a friend needs to change it.
        viewModelScope.launch {
            val savedName = playerNamePreferenceRepository.lastName().first()
            if (savedName.isNotEmpty()) {
                _uiState.update { it.copy(name = savedName) }
            }
        }
    }

    fun updateName(value: String) {
        _uiState.update { it.copy(name = value.take(MAX_PLAYER_NAME_LENGTH), nameError = null) }
    }

    /**
     * Normalised as it's typed (section 5.3), so pasted lowercase or spaced input still fits.
     *
     * A paste is detected by the jump in length: ordinary typing adds at most one character
     * per call, while a paste drops in several at once. Only then is the code extracted from
     * surrounding text (e.g. a share-sheet message) — for plain typing, take() keeps the
     * simpler, front-anchored behaviour so an extra keystroke past the sixth character is
     * just ignored rather than sliding the window and rewriting what's already been typed.
     */
    fun updateCode(value: String) {
        val current = _uiState.value.code
        val isPaste = value.length > current.length + 1
        val normalized = if (isPaste) {
            GameCodeGenerator.extractCode(value)
        } else {
            GameCodeGenerator.normalize(value).take(GameCodeGenerator.CODE_LENGTH)
        }
        _uiState.update { it.copy(code = normalized, codeError = null) }
    }

    fun join() {
        val state = _uiState.value
        if (state.isJoining) return

        val trimmedName = state.name.trim()
        val nameError = if (trimmedName.isEmpty()) JoinFieldError.NameRequired else null
        val codeError = if (state.code.length != GameCodeGenerator.CODE_LENGTH) JoinFieldError.CodeIncomplete else null
        if (nameError != null || codeError != null) {
            _uiState.update { it.copy(nameError = nameError, codeError = codeError) }
            return
        }

        _uiState.update { it.copy(isJoining = true, nameError = null, codeError = null) }
        viewModelScope.launch {
            try {
                when (val outcome = repository.joinGame(state.code, trimmedName)) {
                    is JoinOutcome.Joined -> {
                        playerNamePreferenceRepository.saveLastName(trimmedName)
                        _uiState.update { it.copy(isJoining = false, joinedGameId = outcome.gameId) }
                    }
                    is JoinOutcome.AlreadyFinished -> {
                        _uiState.update { it.copy(isJoining = false, pendingRestartGameId = outcome.gameId) }
                    }
                    JoinOutcome.UnknownCode -> {
                        _uiState.update { it.copy(isJoining = false, codeError = JoinFieldError.UnknownCode) }
                    }
                    JoinOutcome.GameDeleted -> {
                        _uiState.update { it.copy(isJoining = false, codeError = JoinFieldError.GameDeleted) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isJoining = false, codeError = JoinFieldError.Generic) }
            }
        }
    }

    /** Section 5.3's restart offer, accepted: same session, progress reset to the start. */
    fun restartAndJoin() {
        val gameId = _uiState.value.pendingRestartGameId ?: return
        val trimmedName = _uiState.value.name.trim()

        _uiState.update { it.copy(isJoining = true, restartError = null) }
        viewModelScope.launch {
            try {
                repository.restartSession(gameId, trimmedName)
                playerNamePreferenceRepository.saveLastName(trimmedName)
                _uiState.update {
                    it.copy(isJoining = false, pendingRestartGameId = null, joinedGameId = gameId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isJoining = false, restartError = e.toWriteError()) }
            }
        }
    }

    fun dismissRestartOffer() {
        _uiState.update { it.copy(pendingRestartGameId = null, restartError = null) }
    }
}
