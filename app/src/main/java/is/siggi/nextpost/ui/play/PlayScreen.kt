package `is`.siggi.nextpost.ui.play

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import `is`.siggi.nextpost.R
import `is`.siggi.nextpost.data.model.Clue
import `is`.siggi.nextpost.data.repository.GameCompletionSummary
import `is`.siggi.nextpost.data.repository.LocationRepository
import `is`.siggi.nextpost.data.repository.PostScoreBreakdown
import `is`.siggi.nextpost.domain.ScoreCalculator
import `is`.siggi.nextpost.ui.common.ArrivalRadiusCircle
import `is`.siggi.nextpost.ui.common.LocationAccessGate
import `is`.siggi.nextpost.ui.common.WriteError
import `is`.siggi.nextpost.ui.common.rememberLocationAccessState
import `is`.siggi.nextpost.ui.theme.Spacing
import kotlin.math.roundToInt

/** Fallback centre before the first GPS fix lands, matching M0/M1's default (see CreateGameScreen). */
private val REYKJAVIK = LatLng(64.1466, -21.9426)

/** One lookup shared by every write error this screen surfaces — see [WriteError]'s own doc. */
@Composable
private fun writeErrorText(error: WriteError, permissionDeniedRes: Int, unreachableRes: Int): String =
    when (error) {
        WriteError.PermissionDenied -> stringResource(permissionDeniedRes)
        WriteError.Unreachable -> stringResource(unreachableRes)
    }

/**
 * Section 5.3's play screen. Location gating mirrors CreateGameScreen: the map (not the whole
 * screen) needs a precise fix, so the gate sits below a top bar that stays reachable regardless
 * of permission state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayScreen(
    viewModel: PlayViewModel,
    gameId: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val locationAccessState = rememberLocationAccessState()

    LaunchedEffect(gameId) { viewModel.load(gameId) }

    // Section 6: keep the screen awake for the whole time this screen is on-screen, which is
    // exactly "during play." Reset on dispose rather than left set, so leaving the play screen
    // doesn't keep the rest of the app awake too.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(playTopBarTitle(uiState)) },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.create_nav_back)
                        )
                    }
                },
                actions = {
                    // Section 14.1: GPS quality is glanceable top-bar state, not something
                    // that needs a tap to discover. AC-6's waiting-for-signal state surfaces
                    // here as well as blocking arrival itself.
                    if (uiState.isWaitingForGoodFix && !uiState.isLoading) {
                        Icon(
                            imageVector = Icons.Filled.GpsNotFixed,
                            contentDescription = stringResource(R.string.play_gps_waiting),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = Spacing.md)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            val loadError = uiState.loadError
            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                loadError != null -> PlayLoadErrorContent(
                    error = loadError,
                    onRetry = { viewModel.load(gameId) },
                    modifier = Modifier.fillMaxSize()
                )

                uiState.isGameComplete -> PlayCompleteContent(
                    summary = uiState.completionSummary,
                    isRestarting = uiState.isRestartingGame,
                    playAgainError = uiState.playAgainError,
                    onPlayAgain = viewModel::playAgain,
                    onBackHome = onExit,
                    modifier = Modifier.fillMaxSize()
                )

                else -> LocationAccessGate(state = locationAccessState) {
                    PlayingContent(uiState = uiState, viewModel = viewModel)
                }
            }
        }

        val outcome = uiState.arrivalOutcome
        if (outcome != null) {
            AlertDialog(
                onDismissRequest = viewModel::dismissArrivalOutcome,
                title = { Text(arrivalOutcomeTitle(outcome)) },
                text = { Text(arrivalOutcomeBody(outcome)) },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissArrivalOutcome) {
                        Text(
                            if (outcome is ArrivalOutcome.GameFinished) {
                                stringResource(R.string.play_arrival_finish_done)
                            } else {
                                stringResource(R.string.play_arrival_continue)
                            }
                        )
                    }
                }
            )
        }

        if (uiState.showClueConfirmation) {
            ClueConfirmationDialog(uiState = uiState, viewModel = viewModel)
        }
    }
}

@Composable
private fun playTopBarTitle(uiState: PlayUiState): String {
    if (uiState.isGameComplete) return stringResource(R.string.play_complete_title)
    val target = uiState.target
    val base = if (target == null || target.index == 0) {
        stringResource(R.string.play_post_counter_start)
    } else {
        stringResource(R.string.play_post_counter, target.index, uiState.game?.scoredPostCount ?: target.index)
    }
    // Section 5.3: "Running total score in the top bar." Left off until the session has
    // loaded rather than showing a misleading "0 pts" during the initial fetch.
    val session = uiState.session ?: return base
    return stringResource(R.string.play_top_bar_with_score, base, ScoreCalculator.formatScore(session.totalScore))
}

@Composable
private fun arrivalOutcomeTitle(outcome: ArrivalOutcome): String = when (outcome) {
    is ArrivalOutcome.StartPostReached -> stringResource(R.string.play_arrival_start_title)
    is ArrivalOutcome.PostScored -> stringResource(R.string.play_arrival_scored_title, outcome.postIndex)
    is ArrivalOutcome.GameFinished -> stringResource(R.string.play_arrival_finished_title)
}

@Composable
private fun arrivalOutcomeBody(outcome: ArrivalOutcome): String = when (outcome) {
    is ArrivalOutcome.StartPostReached -> stringResource(R.string.play_arrival_start_body)
    is ArrivalOutcome.PostScored ->
        stringResource(R.string.play_arrival_scored_body, ScoreCalculator.formatScore(outcome.score))
    is ArrivalOutcome.GameFinished ->
        stringResource(
            R.string.play_arrival_finished_body,
            outcome.postIndex,
            ScoreCalculator.formatScore(outcome.score)
        )
}

/**
 * The active play loop: map, clue card, arrival controls. Split out from [PlayScreen] so the
 * location-permission gate above it can wrap just this, not the loading/error/complete states.
 */
@Composable
private fun PlayingContent(
    uiState: PlayUiState,
    viewModel: PlayViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val locationRepository = remember { LocationRepository(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(REYKJAVIK, 16f)
    }
    var hasCenteredOnDevice by remember { mutableStateOf(false) }

    // Section 6: live only while this screen is RESUMED. Backgrounding the app exits this
    // scope, which tears the update subscription down via LocationRepository's awaitClose;
    // returning to the foreground starts a fresh one. See LocationRepository.locationUpdates.
    LaunchedEffect(locationRepository) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            locationRepository.locationUpdates().collect { location ->
                viewModel.onLocationUpdate(location.latitude, location.longitude, location.accuracy.toDouble())
                if (!hasCenteredOnDevice) {
                    hasCenteredOnDevice = true
                    cameraPositionState.position =
                        CameraPosition.fromLatLngZoom(LatLng(location.latitude, location.longitude), 17f)
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = false,
                    mapToolbarEnabled = false,
                    myLocationButtonEnabled = false
                ),
                properties = MapProperties(isMyLocationEnabled = true, mapType = MapType.NORMAL)
            ) {
                // AC-5: the current target is never drawn, except post 0 — section 5.3 and
                // the model notes are explicit that the start post is a real arrival, marked
                // like any other post would be if the game ever showed them. Nothing else is.
                val target = uiState.target
                if (target != null && target.index == 0) {
                    val center = LatLng(target.lat, target.lng)
                    MarkerComposable(
                        state = rememberUpdatedMarkerState(position = center),
                        title = stringResource(R.string.play_start_marker_content_description)
                    ) {
                        StartPostMarker()
                    }
                    ArrivalRadiusCircle(center = center, radiusMeters = target.radiusMeters.toDouble())
                }
            }
        }

        ClueCard(uiState = uiState, onShowNextClue = viewModel::requestOpenNextClue)

        val arrivalError = uiState.arrivalError
        if (arrivalError != null) {
            // Takes priority over the miss/waiting states below: the player was inside the
            // radius with a good fix and the write itself failed, which is a technical problem
            // to report, not routine "not there yet" feedback.
            Text(
                text = writeErrorText(
                    arrivalError,
                    permissionDeniedRes = R.string.play_arrival_error_permission,
                    unreachableRes = R.string.play_arrival_error_unreachable
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            )
        } else if (uiState.manualMiss != null) {
            val miss = uiState.manualMiss
            val message = if (miss.wasAccuracyRejected) {
                stringResource(
                    R.string.play_manual_miss_bad_accuracy,
                    miss.distanceMeters.roundToInt(),
                    miss.accuracyMeters.roundToInt()
                )
            } else {
                stringResource(R.string.play_manual_miss, miss.distanceMeters.roundToInt())
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            )
        } else if (uiState.isWaitingForGoodFix) {
            // AC-6: shown persistently while the fix is too poor to trust, not just after a
            // failed manual tap — automatic detection is silently blocked by the same check.
            Text(
                text = stringResource(R.string.play_gps_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            )
        }

        // Section 14.1: the costly action (Show next clue, inside the clue card above) and
        // the frequently-tapped one must not be adjacent — this is the wide 56 dp primary
        // action at the very bottom, on its own.
        Button(
            onClick = viewModel::checkArrivalManually,
            enabled = !uiState.isCheckingArrival,
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md)
                .heightIn(min = Spacing.primaryTouchTarget)
        ) {
            Text(stringResource(R.string.play_i_think_im_here))
        }
    }
}

/**
 * Section 5.3: "collapsed state shows the most recent clue only," drag up to reread earlier
 * ones. Simplified here to a tap-to-expand affordance rather than a true draggable bottom
 * sheet — same collapsed/expanded content, a lighter-weight gesture.
 */
@Composable
private fun ClueCard(
    uiState: PlayUiState,
    onShowNextClue: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val target = uiState.target

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = Spacing.hairline * 3,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            if (!uiState.showClueCard) {
                // AC-9: nothing to hint at yet, before the start post has been reached.
                Text(
                    text = stringResource(R.string.play_no_clues_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }
            if (target == null) return@Column

            val clues = target.clues.sortedBy { it.index }
            if (clues.size > 1) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) {
                            stringResource(R.string.play_clue_card_collapse)
                        } else {
                            stringResource(R.string.play_clue_card_expand)
                        }
                    )
                }
            }
            val visibleClues: List<Clue> = if (expanded) clues else clues.takeLast(1)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                visibleClues.forEach { clue ->
                    Text(text = clue.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            // Section 5.3: disabled once every clue is revealed, not hidden — there's still a
            // clue card to look at, just nothing left to open.
            Button(
                onClick = onShowNextClue,
                enabled = !uiState.allCluesRevealed,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.minTouchTarget)
            ) {
                Text(stringResource(R.string.play_show_next_clue))
            }
        }
    }
}

/**
 * Section 5.3/14.1: names the cost before it's paid. [session]'s cluesOpenedForCurrentPost is
 * "extra clues already opened"; the clue about to open is the free one plus that count plus
 * one, i.e. clue number `cluesOpenedForCurrentPost + 2`.
 */
@Composable
private fun ClueConfirmationDialog(
    uiState: PlayUiState,
    viewModel: PlayViewModel,
    modifier: Modifier = Modifier
) {
    val target = uiState.target ?: return
    val session = uiState.session ?: return
    val nextClueNumber = session.cluesOpenedForCurrentPost + 2
    val resultingScore = ScoreCalculator.scoreForPost(target.clueCount, session.cluesOpenedForCurrentPost + 1)

    AlertDialog(
        modifier = modifier,
        onDismissRequest = viewModel::dismissClueConfirmation,
        title = { Text(stringResource(R.string.play_open_clue_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    stringResource(
                        R.string.play_open_clue_confirm_body,
                        nextClueNumber,
                        target.clueCount,
                        ScoreCalculator.formatScore(resultingScore)
                    )
                )
                // The dialog stays open on failure (see confirmOpenNextClue) so the confirmed
                // cost above doesn't appear to have been silently charged — this says the write
                // didn't go through and the Confirm button is safe to tap again.
                val revealClueError = uiState.revealClueError
                if (revealClueError != null) {
                    Text(
                        text = writeErrorText(
                            revealClueError,
                            permissionDeniedRes = R.string.play_reveal_clue_error_permission,
                            unreachableRes = R.string.play_reveal_clue_error_unreachable
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmOpenNextClue, enabled = !uiState.isRevealingClue) {
                Text(stringResource(R.string.play_open_clue_confirm_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissClueConfirmation) {
                Text(stringResource(R.string.play_open_clue_confirm_cancel))
            }
        }
    )
}

/** Always primary-tinted, per section 14.3: it's always the current target while it's drawn. */
@Composable
private fun StartPostMarker() {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Filled.LocationOn,
            contentDescription = null,
            tint = Color.Black.copy(alpha = 0.55f),
            modifier = Modifier.size(Spacing.xl + Spacing.xs)
        )
        Icon(
            imageVector = Icons.Filled.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Spacing.xl)
        )
    }
}

/**
 * Section 5.3's "Game complete" screen: total, maximum possible, a per-post breakdown of clues
 * opened, and elapsed time (recorded for display only — it never affects score, per section 4/6).
 * [summary] is null only for a single frame between [isGameComplete] flipping true and the load
 * or arrival result that carries it landing; the loading indicator covers that gap.
 */
@Composable
private fun PlayCompleteContent(
    summary: GameCompletionSummary?,
    isRestarting: Boolean,
    playAgainError: WriteError?,
    onPlayAgain: () -> Unit,
    onBackHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (summary == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = modifier
            .padding(Spacing.lg)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = stringResource(R.string.play_complete_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = stringResource(
                R.string.play_complete_total,
                ScoreCalculator.formatScore(summary.totalScore),
                ScoreCalculator.formatScore(summary.maxPossibleScore)
            ),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.play_complete_elapsed, formatElapsed(summary.elapsedMillis)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.lg))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            summary.breakdown.forEach { row -> PostBreakdownRow(row) }
        }
        Spacer(Modifier.height(Spacing.xl))
        Button(
            onClick = onPlayAgain,
            enabled = !isRestarting,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.minTouchTarget)
        ) {
            Text(stringResource(R.string.play_complete_play_again))
        }
        if (playAgainError != null) {
            Text(
                text = writeErrorText(
                    playAgainError,
                    permissionDeniedRes = R.string.play_restart_error_permission,
                    unreachableRes = R.string.play_restart_error_unreachable
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        TextButton(
            onClick = onBackHome,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.minTouchTarget)
        ) {
            Text(stringResource(R.string.play_complete_back_home))
        }
        Spacer(Modifier.height(Spacing.lg))
    }
}

@Composable
private fun PostBreakdownRow(row: PostScoreBreakdown, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(
                text = stringResource(R.string.play_complete_breakdown_title, row.postIndex),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(
                    R.string.play_complete_breakdown_detail,
                    ScoreCalculator.formatScore(row.score),
                    row.cluesOpened,
                    row.totalClues
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** mm:ss — enough precision for a display-only figure that never affects score. */
private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Section 14.5: errors state what happened, not a vague "something went wrong" — and a denied
 * read is not the same problem as a dropped connection, so it needs different words rather
 * than folding into the generic connectivity message. See [PlayLoadError]'s doc.
 *
 * M7 hardening: [onRetry] gives this an actual way out. A player standing in a field with no
 * signal is exactly the person who hits [PlayLoadError.Unreachable] here, and "leave the
 * screen and come back" was never a discoverable fix — see [PlayViewModel.load]'s doc for why
 * this button previously would have been a silent no-op even if one had been added.
 */
@Composable
private fun PlayLoadErrorContent(error: PlayLoadError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(Spacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = when (error) {
                PlayLoadError.PermissionDenied -> stringResource(R.string.play_load_error_permission)
                PlayLoadError.Unreachable -> stringResource(R.string.play_load_error_unreachable)
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.md))
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) {
            Text(stringResource(R.string.play_load_retry))
        }
    }
}
