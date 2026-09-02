package `is`.siggi.nextpost.ui.mygames

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import `is`.siggi.nextpost.R
import `is`.siggi.nextpost.data.model.Game
import `is`.siggi.nextpost.data.model.GameStatus
import `is`.siggi.nextpost.ui.common.WriteError
import `is`.siggi.nextpost.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Section 5.1: this creator's games, drafts and published alike. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyGamesScreen(
    viewModel: MyGamesViewModel,
    onNavigateUp: () -> Unit,
    onOpenDraft: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    // Confirmation names the game and is irreversible, since the delete control sits right
    // next to the tap-to-open row action and a mis-tap destroys a route someone built. See
    // section 5.1 and 14.1.
    var pendingDelete by remember { mutableStateOf<Game?>(null) }

    // The sole trigger for loading this screen, initial load included: a lifecycle that has
    // already reached RESUMED replays up to that state for a newly added observer, so this fires
    // once on first composition and again on every return. That covers the staleness this exists
    // for — data goes off the moment the creator leaves to publish or edit a game and comes back,
    // a status still reading "draft" right after publishing being the most visible case — without
    // an init-block load doubling up on the first fetch. Same shape as the M1 location permission
    // gate (LocationAccess.kt).
    val currentRefresh by rememberUpdatedState(viewModel::refresh)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mygames_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.create_nav_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // A delete that fails must say so: the row already reappears via refresh() to keep
            // the list truthful, but that alone says only that it failed, not why.
            val deleteError = uiState.deleteError
            if (deleteError != null) {
                Text(
                    text = when (deleteError) {
                        WriteError.PermissionDenied -> stringResource(R.string.mygames_delete_error_permission)
                        WriteError.Unreachable -> stringResource(R.string.mygames_delete_error_unreachable)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                )
            }

            val loadError = uiState.loadError
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                    // Checked ahead of the empty-list branch below: an empty list here can mean
                    // either "no games yet" or "the fetch that would have found them failed" —
                    // see MyGamesUiState.loadError's doc for why those must not read the same.
                    // A refresh that fails with existing rows still on screen falls through to
                    // the list branch instead, keeping the stale-but-real data visible.
                    loadError != null && uiState.games.isEmpty() -> MyGamesLoadErrorContent(
                        error = loadError,
                        onRetry = viewModel::refresh,
                        modifier = Modifier.align(Alignment.Center)
                    )

                    uiState.games.isEmpty() -> Text(
                        text = stringResource(R.string.mygames_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(Spacing.lg)
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(Spacing.md)
                    ) {
                        items(uiState.games, key = { it.id }) { game ->
                            GameRow(
                                game = game,
                                onClick = { onOpenDraft(game.id) },
                                onDeleteClick = { pendingDelete = game }
                            )
                        }
                    }
                }
            }
        }

        val deleteTarget = pendingDelete
        if (deleteTarget != null) {
            val deleteTargetTitle = deleteTarget.title.ifBlank { stringResource(R.string.mygames_untitled) }
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(stringResource(R.string.mygames_delete_dialog_title)) },
                text = { Text(stringResource(R.string.mygames_delete_dialog_body, deleteTargetTitle)) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDelete = null
                        viewModel.deleteGame(deleteTarget)
                    }) {
                        Text(stringResource(R.string.mygames_delete_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) {
                        Text(stringResource(R.string.mygames_delete_cancel))
                    }
                }
            )
        }
    }
}

/** M7 hardening: distinguishes a failed [MyGamesViewModel.refresh] from a creator who
 * genuinely has no games yet — see [MyGamesUiState.loadError]'s doc. */
@Composable
private fun MyGamesLoadErrorContent(error: WriteError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = when (error) {
                WriteError.PermissionDenied -> stringResource(R.string.mygames_load_error_permission)
                WriteError.Unreachable -> stringResource(R.string.mygames_load_error_unreachable)
            },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.md))
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) {
            Text(stringResource(R.string.mygames_load_retry))
        }
    }
}

@Composable
private fun GameRow(
    game: Game,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = game.title.ifBlank { stringResource(R.string.mygames_untitled) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)

            val statusLabel = when (game.status) {
                GameStatus.DRAFT -> stringResource(R.string.mygames_status_draft)
                GameStatus.PUBLISHED -> stringResource(R.string.mygames_status_published)
            }
            val postCount = pluralStringResource(R.plurals.create_post_count, game.postCount, game.postCount)
            Text(
                text = stringResource(R.string.mygames_row_subtitle, postCount, statusLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Rows stay distinguishable regardless of naming (section 5.2) — titles are no
            // longer unique even within one creator's own games, since duplicates are now
            // warned about rather than blocked.
            val createdAtLabel = game.createdAt?.let { millis ->
                Instant.ofEpochMilli(millis)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
            }
            if (createdAtLabel != null) {
                Text(
                    text = stringResource(R.string.mygames_row_created, createdAtLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Available for drafts and published games alike (section 5.1): deletion is a
        // creator-owns-it decision, not tied to status.
        IconButton(onClick = onDeleteClick) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.mygames_delete_content_description, title)
            )
        }
    }
}
