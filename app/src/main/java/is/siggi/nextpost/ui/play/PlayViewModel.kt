package `is`.siggi.nextpost.ui.play

import `is`.siggi.nextpost.data.model.Game
import `is`.siggi.nextpost.data.model.Post
import `is`.siggi.nextpost.data.model.Session
import `is`.siggi.nextpost.data.repository.GameCompletionSummary
import `is`.siggi.nextpost.data.repository.GameRepository
import `is`.siggi.nextpost.data.repository.PlayState
import `is`.siggi.nextpost.domain.ProximityChecker
import `is`.siggi.nextpost.ui.common.WriteError
import `is`.siggi.nextpost.ui.common.toWriteError
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Section 5.3's three arrival shapes, one per success-state wording the screen needs. */
sealed interface ArrivalOutcome {
    /** Section 3's model notes: post 0 "should read as the game starting," not a scored post. */
    data object StartPostReached : ArrivalOutcome
    data class PostScored(val postIndex: Int, val score: Double) : ArrivalOutcome
    data class GameFinished(val postIndex: Int, val score: Double) : ArrivalOutcome
}

/**
 * A permissions failure and a connectivity failure need different words and, for a player,
 * different next steps — reporting both as "check your connection" sends a debugger looking
 * at the network for a rules bug, which is exactly what happened chasing this once already
 * (see the IME-focus investigation this project's notes reference). [PermissionDenied] is
 * everything else the rules explicitly refused; [Unreachable] covers dropped connections,
 * timeouts and anything else.
 */
sealed interface PlayLoadError {
    data object PermissionDenied : PlayLoadError
    data object Unreachable : PlayLoadError
}

/**
 * Feedback for a failed manual "I think I'm here" tap. [accuracyMeters] is always the fix's
 * current accuracy so the message can tell the player which problem they have — wrong place
 * ([wasAccuracyRejected] false) or bad signal ([wasAccuracyRejected] true) — rather than just
 * refusing silently.
 */
data class ManualArrivalMiss(
    val distanceMeters: Double,
    val accuracyMeters: Double,
    val wasAccuracyRejected: Boolean
)

data class PlayUiState(
    val isLoading: Boolean = true,
    val loadError: PlayLoadError? = null,
    val game: Game? = null,
    val session: Session? = null,
    val target: Post? = null,
    /** Null until the first location fix lands; see [isWaitingForGoodFix]'s default. */
    val gpsAccuracyMeters: Double? = null,
    /** AC-6: true before any fix, and whenever the latest fix exceeds the accuracy floor. */
    val isWaitingForGoodFix: Boolean = true,
    val isCheckingArrival: Boolean = false,
    /** Transient: only set by a failed manual "I think I'm here" tap, per section 5.3. */
    val manualMiss: ManualArrivalMiss? = null,
    val showClueConfirmation: Boolean = false,
    val isRevealingClue: Boolean = false,
    /** One-shot, like [is.siggi.nextpost.ui.join.JoinGameUiState.joinedGameId]: shown once, then dismissed. */
    val arrivalOutcome: ArrivalOutcome? = null,
    val isGameComplete: Boolean = false,
    /** Populated whenever [isGameComplete] is set — either from finishing just now, or from a resume that finds the session already finished. */
    val completionSummary: GameCompletionSummary? = null,
    val isRestartingGame: Boolean = false,
    /** A denied or dropped [is.siggi.nextpost.data.repository.GameRepository.recordArrival]
     * write must say so — arriving at a post and having nothing happen, with no explanation, is
     * worse than the write just failing loudly. */
    val arrivalError: WriteError? = null,
    /** Same reasoning for [is.siggi.nextpost.data.repository.GameRepository.openNextClue]. */
    val revealClueError: WriteError? = null,
    /** Same reasoning for [is.siggi.nextpost.data.repository.GameRepository.restartSession]. */
    val playAgainError: WriteError? = null
) {
    /** Section 5.3/AC-9: hidden, not merely disabled, until there's something to hint at. */
    val showClueCard: Boolean get() = (target?.clueCount ?: 0) > 0

    val allCluesRevealed: Boolean get() = target?.let { it.clues.size >= it.clueCount } ?: false
}

private data class LocationFix(val lat: Double, val lng: Double, val accuracyMeters: Double)

/**
 * Section 5.3's play loop. Deliberately reloads from [GameRepository] on [load] rather than
 * accepting state handed in from navigation, which is what makes AC-7 (resume after the app is
 * killed and reopened) fall out for free: rejoining the same code resumes the existing session
 * untouched (see [GameRepository.joinGame]), and this always reads that session fresh.
 */
class PlayViewModel(private val repository: GameRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(PlayUiState())
    val uiState: StateFlow<PlayUiState> = _uiState.asStateFlow()

    private var loadedGameId: String? = null
    private var gameId: String = ""
    private var lastKnownLocation: LocationFix? = null

    /**
     * [loadedGameId] is only set on a *successful* load, deliberately — it exists purely to
     * make this idempotent against the LaunchedEffect(gameId) that calls it once per screen
     * entry, not to gate retries. Setting it unconditionally up front (as this used to) turned
     * [PlayLoadErrorContent]'s retry button into a guaranteed no-op: a failed load left
     * [loadedGameId] pointing at this gameId forever, so calling load() again — from a retry
     * button or otherwise — would hit the guard and return immediately without ever touching
     * the network again. [playAgain] already resets [loadedGameId] to null itself before its
     * own call to this, which still works exactly as before.
     */
    fun load(gameId: String) {
        if (loadedGameId == gameId) return
        this.gameId = gameId
        _uiState.update { it.copy(isLoading = true, loadError = null) }
        viewModelScope.launch {
            try {
                when (val state = repository.loadPlayState(gameId)) {
                    is PlayState.Active -> {
                        loadedGameId = gameId
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                game = state.game,
                                session = state.session,
                                target = state.target
                            )
                        }
                    }
                    // AC-7's resume applies to a finished game too: reopening after finishing
                    // (with or without having dismissed the outcome dialog first) must land
                    // straight on the completion screen, not attempt to load a target post past
                    // the end of the route.
                    is PlayState.Completed -> {
                        loadedGameId = gameId
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                session = state.session,
                                isGameComplete = true,
                                completionSummary = state.summary
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FirebaseFirestoreException) {
                val error = if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    PlayLoadError.PermissionDenied
                } else {
                    PlayLoadError.Unreachable
                }
                _uiState.update { it.copy(isLoading = false, loadError = error) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, loadError = PlayLoadError.Unreachable) }
            }
        }
    }

    /**
     * Section 6's polling feed calls this on every fix. Runs the arrival check silently
     * (no miss-distance feedback — that's reserved for an explicit manual tap, per section 5.3)
     * so automatic detection never nags the player with "not yet" messaging.
     */
    fun onLocationUpdate(lat: Double, lng: Double, accuracyMeters: Double) {
        lastKnownLocation = LocationFix(lat, lng, accuracyMeters)
        _uiState.update {
            it.copy(
                gpsAccuracyMeters = accuracyMeters,
                isWaitingForGoodFix = accuracyMeters > ProximityChecker.MAX_ACCEPTABLE_ACCURACY_METERS
            )
        }
        attemptArrival(showMissDistanceOnFailure = false)
    }

    /** Section 5.3's manual fallback for when GPS drift blocks automatic detection. */
    fun checkArrivalManually() {
        attemptArrival(showMissDistanceOnFailure = true)
    }

    private fun attemptArrival(showMissDistanceOnFailure: Boolean) {
        val state = _uiState.value
        val target = state.target ?: return
        val fix = lastKnownLocation ?: return
        if (state.isCheckingArrival || state.arrivalOutcome != null || state.isGameComplete) return

        val check = ProximityChecker.checkArrival(
            currentLat = fix.lat,
            currentLng = fix.lng,
            currentAccuracyMeters = fix.accuracyMeters,
            targetLat = target.lat,
            targetLng = target.lng,
            radiusMeters = target.radiusMeters.toDouble()
        )
        // AC-6: a poor fix never registers arrival, however close the raw distance looks. On an
        // automatic check that's the end of it — isWaitingForGoodFix (set in onLocationUpdate)
        // already covers that state. A manual tap gets more: since the same gate correctly keeps
        // refusing, the message needs to say why, distinguishing "wrong place" from "bad signal".
        if (check.accuracyRejected) {
            if (showMissDistanceOnFailure) {
                _uiState.update {
                    it.copy(
                        manualMiss = ManualArrivalMiss(
                            distanceMeters = check.distanceMeters,
                            accuracyMeters = fix.accuracyMeters,
                            wasAccuracyRejected = true
                        )
                    )
                }
            }
            return
        }

        if (check.hasArrived) {
            _uiState.update { it.copy(manualMiss = null) }
            processArrival()
        } else if (showMissDistanceOnFailure) {
            _uiState.update {
                it.copy(
                    manualMiss = ManualArrivalMiss(
                        distanceMeters = check.distanceMeters,
                        accuracyMeters = fix.accuracyMeters,
                        wasAccuracyRejected = false
                    )
                )
            }
        }
    }

    private fun processArrival() {
        val state = _uiState.value
        val game = state.game ?: return
        val session = state.session ?: return
        val target = state.target ?: return

        _uiState.update { it.copy(isCheckingArrival = true, arrivalError = null) }
        viewModelScope.launch {
            try {
                val result = repository.recordArrival(gameId, game, session, target)
                val outcome = when {
                    result.gameFinished -> ArrivalOutcome.GameFinished(target.index, result.awardedScore)
                    result.isStartPost -> ArrivalOutcome.StartPostReached
                    else -> ArrivalOutcome.PostScored(target.index, result.awardedScore)
                }
                _uiState.update {
                    it.copy(
                        isCheckingArrival = false,
                        session = result.session,
                        target = result.nextTarget ?: it.target,
                        arrivalOutcome = outcome,
                        completionSummary = result.completionSummary ?: it.completionSummary
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed arrival write must say so — the player is standing on the post,
                // nothing happens, and silence here reads as a broken game, not a retriable
                // network hiccup.
                _uiState.update { it.copy(isCheckingArrival = false, arrivalError = e.toWriteError()) }
            }
        }
    }

    /** Dismissing the success dialog is what actually advances play past a finished game. */
    fun dismissArrivalOutcome() {
        _uiState.update { state ->
            val justFinished = state.arrivalOutcome is ArrivalOutcome.GameFinished
            state.copy(arrivalOutcome = null, isGameComplete = state.isGameComplete || justFinished)
        }
    }

    /**
     * Section 5.3's "play again" from the completion screen: the same restart primitive the
     * join screen offers after "session already finished," reusing this session's own name
     * rather than whatever the finished session happened to hold, per [GameRepository.restartSession]'s
     * doc. Resets local state and re-runs [load] rather than patching fields in place, since a
     * restarted session is a fresh [PlayState.Active] in every respect.
     */
    fun playAgain() {
        val state = _uiState.value
        val session = state.session ?: return
        if (state.isRestartingGame) return

        _uiState.update { it.copy(isRestartingGame = true, playAgainError = null) }
        viewModelScope.launch {
            try {
                repository.restartSession(gameId, session.displayName)
                lastKnownLocation = null
                loadedGameId = null
                _uiState.value = PlayUiState()
                load(gameId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isRestartingGame = false, playAgainError = e.toWriteError()) }
            }
        }
    }

    fun requestOpenNextClue() {
        _uiState.update { it.copy(showClueConfirmation = true) }
    }

    fun dismissClueConfirmation() {
        _uiState.update { it.copy(showClueConfirmation = false, revealClueError = null) }
    }

    fun confirmOpenNextClue() {
        val state = _uiState.value
        val target = state.target ?: return
        val session = state.session ?: return
        if (state.isRevealingClue) return

        _uiState.update { it.copy(isRevealingClue = true, revealClueError = null) }
        viewModelScope.launch {
            try {
                val clues = repository.openNextClue(gameId, target.id, session.cluesOpenedForCurrentPost)
                _uiState.update {
                    it.copy(
                        isRevealingClue = false,
                        showClueConfirmation = false,
                        target = target.copy(clues = clues),
                        session = session.copy(cluesOpenedForCurrentPost = session.cluesOpenedForCurrentPost + 1)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The confirmation dialog already named the point cost before this ran — if the
                // write then fails, that cost must not appear to have been charged silently.
                _uiState.update { it.copy(isRevealingClue = false, revealClueError = e.toWriteError()) }
            }
        }
    }
}
