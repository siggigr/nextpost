package `is`.siggi.nextpost.ui.create

import android.content.Intent
import android.location.Location
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import com.google.android.gms.maps.CameraUpdateFactory
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
import `is`.siggi.nextpost.data.model.GameStatus
import `is`.siggi.nextpost.data.model.Post
import `is`.siggi.nextpost.data.repository.LocationRepository
import `is`.siggi.nextpost.data.repository.MapTypePreferenceRepository
import `is`.siggi.nextpost.ui.common.ArrivalRadiusCircle
import `is`.siggi.nextpost.ui.common.LocationAccessGate
import `is`.siggi.nextpost.ui.common.WriteError
import `is`.siggi.nextpost.ui.common.rememberLocationAccessState
import `is`.siggi.nextpost.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Fallback centre before the first GPS fix lands, matching the M0/M1 default. */
private val REYKJAVIK = LatLng(64.1466, -21.9426)

/**
 * Roughly building level. Below this, a fingertip on screen covers more ground than the
 * 18 m arrival radius, so a placement can look precise while actually being off by tens of
 * metres. See section 5.2.
 */
private const val MIN_ZOOM_FOR_SET = 17f

/** One lookup shared by every screen-local write error, so "which words for which failure" is
 * decided in exactly one place per screen rather than re-derived at each call site. */
@Composable
private fun writeErrorText(error: WriteError, permissionDeniedRes: Int, unreachableRes: Int): String =
    when (error) {
        WriteError.PermissionDenied -> stringResource(permissionDeniedRes)
        WriteError.Unreachable -> stringResource(unreachableRes)
    }

/**
 * Section 5.2. Location gating reuses the M1 infrastructure: the map itself (not the whole
 * app) is what needs a precise fix, so the gate sits inside this screen's content, below a
 * top bar that stays reachable regardless of permission state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGameScreen(
    viewModel: CreateGameViewModel,
    initialGameId: String?,
    onNavigateUp: () -> Unit,
    onAddClue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val locationAccessState = rememberLocationAccessState()
    val editor = uiState.mode as? CreateScreenMode.PostEditor
    var showDiscardConfirm by remember { mutableStateOf(false) }

    // Resuming a draft opened from My games: same screen, just pre-loaded. Local-only new
    // drafts (initialGameId null) skip this entirely.
    LaunchedEffect(initialGameId) {
        if (initialGameId != null) {
            viewModel.loadDraft(initialGameId)
        }
    }

    // The one path off the post editor, named for what happens if clues have been written
    // (see the confirmation dialog below) rather than for where it's called from — it's wired
    // to both the row's Cancel button and, via BackHandler below, the system back gesture, so
    // "requestCancel" describes the action regardless of trigger.
    val requestCancel: () -> Unit = {
        if (editor != null && editor.clues.isNotEmpty()) {
            showDiscardConfirm = true
        } else {
            viewModel.cancelPostEditor()
        }
    }

    // PostEditor is a mode within this one screen, not a separate nav destination, so without
    // this the system back gesture would pop CreateGameScreen off the back stack entirely —
    // skipping cancelPostEditor's return to Overview and, with it, the discard confirmation
    // above. That became the only way this could happen once the top bar's Close icon was
    // removed as a second, redundant path to the same requestCancel the row's Cancel button
    // already covers (see PostEditorControls). Disabled outside PostEditor mode, where the
    // system default (pop via onNavigateUp) is exactly right already.
    BackHandler(enabled = editor != null) { requestCancel() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    val title = when {
                        // Also covers Naming: titleInput mirrors the field live, so the bar
                        // previews the name as it's typed rather than showing a fixed label.
                        editor == null -> uiState.titleInput.ifBlank { stringResource(R.string.create_game_title) }
                        editor.editingIndex == null -> stringResource(R.string.create_editor_title_add)
                        else -> stringResource(R.string.create_editor_title_edit, editor.editingIndex)
                    }
                    Text(title)
                },
                navigationIcon = {
                    // No icon at all once published: GamePublishedContent's own Done button is
                    // the only way off this screen. A back arrow here looked like ordinary
                    // navigation and let a reflexive tap skip the mandatory code/share step —
                    // the bug this guards against. See CreateScreenMode.Published's doc.
                    //
                    // Also none in PostEditor mode: the row's Cancel button (PostEditorControls)
                    // is now the one visible exit, and BackHandler above is the one path for
                    // system back — a second top-bar control doing the same job invited the
                    // question of whether it behaved differently, and it went unnoticed even by
                    // the person who added it. See requestCancel's doc.
                    if (editor == null && uiState.mode !is CreateScreenMode.Published) {
                        IconButton(onClick = onNavigateUp) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.create_nav_back)
                            )
                        }
                    }
                },
                actions = {
                    if (editor == null &&
                        uiState.mode !is CreateScreenMode.Naming &&
                        uiState.mode !is CreateScreenMode.Published
                    ) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.create_post_count,
                                uiState.posts.size,
                                uiState.posts.size
                            ),
                            style = MaterialTheme.typography.labelLarge,
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
            val loadDraftError = uiState.loadDraftError
            LocationAccessGate(state = locationAccessState) {
                when {
                    uiState.isLoadingDraft ->
                        // Resuming a draft reads several documents (game, posts, clues); a
                        // blank Overview screen for that moment would read as "my posts are
                        // gone".
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }

                    // Checked ahead of the mode-based branches below: a failed loadDraft never
                    // touches uiState.mode, which is why this can't be folded into one of those
                    // branches instead — mode is still sitting at its Naming default here, and
                    // falling through to it is exactly the bug this guards against. See
                    // CreateGameViewModel.loadDraft's doc. initialGameId is non-null whenever
                    // this error can occur — loadDraft is only ever called with it.
                    loadDraftError != null && initialGameId != null -> LoadDraftErrorContent(
                        error = loadDraftError,
                        onRetry = { viewModel.loadDraft(initialGameId) },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Naming gates everything past it: the map isn't reachable until the
                    // game has a title. See section 5.2 and AC-18.
                    uiState.mode is CreateScreenMode.Naming -> GameNamingContent(
                        titleInput = uiState.titleInput,
                        isCreating = uiState.isCreatingDraft,
                        isDuplicateTitle = uiState.isDuplicateTitle,
                        namingError = uiState.namingError,
                        onTitleChange = viewModel::updateTitleInput,
                        onConfirm = viewModel::confirmTitle,
                        onAppear = viewModel::loadExistingTitlesForDuplicateCheck
                    )

                    // Replaces the map outright rather than overlaying it: a published game
                    // is read-only for the creator, so there's nothing left here to go back
                    // to editing. See CreateScreenMode.Published's own doc.
                    uiState.mode is CreateScreenMode.Published -> {
                        val published = uiState.mode as CreateScreenMode.Published
                        GamePublishedContent(code = published.code, onDone = onNavigateUp)
                    }

                    else -> CreateGameContent(
                        uiState = uiState,
                        viewModel = viewModel,
                        onAddClue = onAddClue,
                        onCancel = requestCancel
                    )
                }
            }
        }

        if (showDiscardConfirm && editor != null) {
            AlertDialog(
                onDismissRequest = { showDiscardConfirm = false },
                title = { Text(stringResource(R.string.create_discard_dialog_title)) },
                text = {
                    Text(
                        pluralStringResource(
                            R.plurals.create_discard_dialog_body,
                            editor.clues.size,
                            editor.clues.size
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDiscardConfirm = false
                        viewModel.cancelPostEditor()
                    }) {
                        Text(stringResource(R.string.create_discard_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDiscardConfirm = false }) {
                        Text(stringResource(R.string.create_discard_keep_editing))
                    }
                }
            )
        }
    }
}

/**
 * M7 hardening: a failed [CreateGameViewModel.loadDraft] stops here rather than falling
 * through to [CreateScreenMode.Naming] — see that function's doc for why the fall-through was
 * a data-integrity bug, not just a missing message. [onRetry] just calls loadDraft again;
 * nothing here needs its own loading spinner since [CreateGameUiState.isLoadingDraft] already
 * takes over the whole screen the moment that retry starts.
 */
@Composable
private fun LoadDraftErrorContent(error: WriteError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(Spacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = writeErrorText(
                error,
                permissionDeniedRes = R.string.create_load_draft_error_permission,
                unreachableRes = R.string.create_load_draft_error_unreachable
            ),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.md))
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) {
            Text(stringResource(R.string.create_load_draft_retry))
        }
    }
}

/**
 * Section 5.2: naming is mandatory and comes before the map, and is the natural trigger for
 * lazy draft creation — see [CreateGameViewModel.confirmTitle]. 1 to 60 characters once
 * trimmed; an empty title keeps the confirm button disabled rather than showing a validation
 * message, matching the "prevent, don't warn" pattern used for empty clues. A title matching
 * one of this creator's *other* games is different: titles aren't identifiers, so that's
 * warned about, not blocked — the button stays enabled and tapping it proceeds regardless.
 */
@Composable
private fun GameNamingContent(
    titleInput: String,
    isCreating: Boolean,
    isDuplicateTitle: Boolean,
    namingError: WriteError?,
    onTitleChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onAppear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isValid = titleInput.trim().isNotEmpty()

    // Fetches this creator's own titles once per visit to this screen, purely for the
    // duplicate warning below — see loadExistingTitlesForDuplicateCheck's own no-op guard for
    // why calling this on every composition is still just the one fetch.
    LaunchedEffect(Unit) { onAppear() }

    // Top-aligned, not centred: per 14.1, a keyboard-first screen puts its content at the
    // top, since a field placed low (as centring it here used to) is a field the keyboard
    // hides the moment it's tapped. imePadding backs that up so the field and button rise
    // above the keyboard on shorter screens rather than sitting behind it.
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            text = stringResource(R.string.create_name_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = stringResource(R.string.create_name_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = titleInput,
            onValueChange = onTitleChange,
            label = { Text(stringResource(R.string.create_name_label)) },
            singleLine = true,
            // Prose, not a code or identifier, so it capitalises like a sentence rather than
            // requiring a manual shift tap. See 14.1.
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth()
        )
        // Non-blocking: the game code is the identifier, not the title, and a creator may
        // legitimately want the same title twice (e.g. the same route for a different
        // group). See section 5.2.
        if (isDuplicateTitle) {
            Text(
                text = stringResource(R.string.create_name_duplicate_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Button(
            onClick = onConfirm,
            enabled = isValid && !isCreating,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.minTouchTarget)
        ) {
            Text(stringResource(R.string.create_name_confirm))
        }
        // A write that fails must say so: this is createDraftGame/renameDraftGame, not just a
        // local field edit, so a denied or dropped write here needs to be visible, not a
        // button that silently stops spinning with the screen otherwise unchanged.
        if (namingError != null) {
            Text(
                text = writeErrorText(
                    namingError,
                    permissionDeniedRes = R.string.create_naming_error_permission,
                    unreachableRes = R.string.create_naming_error_unreachable
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun CreateGameContent(
    uiState: CreateGameUiState,
    viewModel: CreateGameViewModel,
    onAddClue: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val locationRepository = remember { LocationRepository(context) }
    val mapTypeRepository = remember { MapTypePreferenceRepository(context) }
    val mapType by mapTypeRepository.mapType().collectAsState(initial = MapType.NORMAL)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(REYKJAVIK, 15f)
    }
    var lastKnownLocation by remember { mutableStateOf<LatLng?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // Captured once, as of opening this screen: a creator resuming a route built elsewhere
    // should land where they left off, not back at the device. Device location is only the
    // camera's starting point for a brand-new game with no posts yet. See section 5.2 and
    // the "opening an existing game" paragraph there.
    val lastPostOnOpen = remember { uiState.posts.maxByOrNull { it.index } }

    LaunchedEffect(Unit) {
        if (lastPostOnOpen != null) {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(
                LatLng(lastPostOnOpen.lat, lastPostOnOpen.lng),
                17f
            )
        }
        try {
            val location: Location? = locationRepository.getCurrentLocation()
            if (location != null) {
                val target = LatLng(location.latitude, location.longitude)
                lastKnownLocation = target
                if (lastPostOnOpen == null) {
                    cameraPositionState.position = CameraPosition.fromLatLngZoom(target, 17f)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No fix yet; the map stays on its default centre.
        }
    }

    val mode = uiState.mode
    LaunchedEffect(mode) {
        if (mode is CreateScreenMode.PostEditor) {
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(LatLng(mode.lat, mode.lng), 17f)
            )
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            val mapUiSettings = MapUiSettings(
                zoomGesturesEnabled = true,
                zoomControlsEnabled = false,
                mapToolbarEnabled = false,
                myLocationButtonEnabled = false
            )
            val mapProperties = MapProperties(isMyLocationEnabled = true, mapType = mapType)

            // The post currently being added/edited is drawn separately below, either as
            // the fixed centre pin or as its own detached marker, never as a numbered one.
            val editingIndex = (mode as? CreateScreenMode.PostEditor)?.editingIndex

            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                uiSettings = mapUiSettings,
                properties = mapProperties
            ) {
                uiState.posts.forEach { post ->
                    if (post.index != editingIndex) {
                        NumberedPostMarker(
                            post = post,
                            selected = uiState.selectedPostIndex == post.index,
                            // Section 5.2: a published game's posts stay visible for review but
                            // selection exists only to feed Edit/Delete, neither of which the
                            // read-only overview offers — so selecting one here has nothing to
                            // do, and doesn't happen.
                            onClick = {
                                if (uiState.gameStatus != GameStatus.PUBLISHED) {
                                    viewModel.selectPost(post.index)
                                }
                            }
                        )
                    }
                }

                // Once Set is tapped the pin detaches from the map centre and locks to that
                // coordinate; from here the map pans freely underneath without moving it.
                if (mode is CreateScreenMode.PostEditor && mode.locationConfirmed) {
                    PendingPostMarker(lat = mode.lat, lng = mode.lng, index = mode.targetIndex)
                }

                // Makes the 18 m arrival radius visible while placing a post rather than
                // something the creator has to imagine. Reused as-is on the M5 play screen.
                if (mode is CreateScreenMode.PostEditor) {
                    val circleCenter = if (mode.locationConfirmed) {
                        LatLng(mode.lat, mode.lng)
                    } else {
                        cameraPositionState.position.target
                    }
                    ArrivalRadiusCircle(center = circleCenter)
                }

                // Section 5.2 hardening: a merely-selected post gets the same radius circle as
                // one actually being edited, for the same reason NumberedPostMarker's selected
                // tint now matches PendingPostMarker's — Edit and Delete already act on this
                // post, so what they'd act on should be as visually unambiguous as it is once
                // Edit is actually tapped. Only in Overview: selection only drives Edit/Delete
                // there, and it can't be set at all on a published game (see the onClick guard
                // on NumberedPostMarker above).
                if (mode is CreateScreenMode.Overview) {
                    val selectedPost = uiState.posts.find { it.index == uiState.selectedPostIndex }
                    if (selectedPost != null) {
                        ArrivalRadiusCircle(
                            center = LatLng(selectedPost.lat, selectedPost.lng),
                            radiusMeters = selectedPost.radiusMeters.toDouble()
                        )
                    }
                }
            }

            // Fixed centre pin: while the location isn't confirmed yet (including
            // mid-Reposition, after the camera has already animated onto the post's
            // coordinate below), the map centre IS the post location, so the pin stays
            // screen-fixed and the map pans under it rather than the pin being dragged. No
            // separate crosshair is drawn — the pin marks the centre whenever it's unset,
            // which is the only state where the aim point matters, so a crosshair would be
            // redundant. Disappears once Set locks a coordinate. See section 5.2 and AC-20.
            if (mode is CreateScreenMode.PostEditor && !mode.locationConfirmed) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = -Spacing.md),
                    contentAlignment = Alignment.Center
                ) {
                    // A dark halo, slightly larger than the pin itself, so it keeps an edge
                    // over dark satellite/hybrid imagery where the flat orange fill alone can
                    // lose contrast against the ground. See the map type control below.
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = null,
                        tint = Color.Black.copy(alpha = 0.55f),
                        modifier = Modifier.size(Spacing.xl + Spacing.xs)
                    )
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = stringResource(R.string.create_pin_content_description),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Spacing.xl)
                    )
                }
            }

            // A single icon button rather than persistent per-type buttons: switching layers
            // is a once-per-session choice, not a per-post one, so it shouldn't cost map area
            // on a screen where the map is the task. Top corner, not the bottom thumb zone —
            // 14.1's thumb-zone rule is about the play screen, where the player is walking. A
            // creator placing posts is standing still. Hybrid matters most here — satellite
            // imagery with labels lets a post land on a specific tree or path junction that a
            // vector map doesn't show, which is the common case for rural routes (section
            // 5.2). The play screen stays on MapType.NORMAL for now; see M5.
            MapTypeControl(
                mapType = mapType,
                onMapTypeChange = { newType ->
                    coroutineScope.launch { mapTypeRepository.setMapType(newType) }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Spacing.md)
            )

            // Optional recentre-on-me: the camera otherwise never returns to the device
            // location after the very first post (see the onAdd seed logic below), so this
            // is the only way back to it when the creator actually wants it. See section 5.2
            // and AC-19.
            val currentLocation = lastKnownLocation
            if (currentLocation != null) {
                FilledTonalIconButton(
                    onClick = {
                        coroutineScope.launch {
                            cameraPositionState.animate(
                                CameraUpdateFactory.newLatLngZoom(currentLocation, 17f)
                            )
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(Spacing.md)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MyLocation,
                        contentDescription = stringResource(R.string.create_recentre_content_description)
                    )
                }
            }
        }

        when (mode) {
            // Section 5.2: "published games are read-only for the creator except for
            // deletion." Add/Edit/Delete/Create-game all write to a document the rules will
            // now refuse — see PublishedOverviewControls' own doc for what replaces them.
            is CreateScreenMode.Overview -> if (uiState.gameStatus == GameStatus.PUBLISHED) {
                PublishedOverviewControls(code = uiState.gameCode)
            } else {
                OverviewControls(
                    uiState = uiState,
                    onAdd = {
                        // Always the current map centre, with no exception for post 0: the only
                        // device-centring in this flow is the initial camera position when opening
                        // a game with no posts yet (see the LaunchedEffect above), and the explicit
                        // recentre-on-me control. Add itself never overrides wherever the creator
                        // has since panned to. See section 5.2 and AC-19.
                        val seed = cameraPositionState.position.target
                        viewModel.beginAddPost(seed.latitude, seed.longitude)
                    },
                    onEdit = viewModel::beginEditSelectedPost,
                    onDelete = viewModel::deleteSelectedPost,
                    onPublish = viewModel::publishGame
                )
            }

            is CreateScreenMode.PostEditor -> PostEditorControls(
                mode = mode,
                currentZoom = cameraPositionState.position.zoom,
                isSaving = uiState.isSaving,
                saveError = uiState.saveError,
                onSet = {
                    val target = cameraPositionState.position.target
                    viewModel.confirmLocation(target.latitude, target.longitude)
                },
                onReposition = {
                    // Animate the camera onto the post's existing coordinate *before*
                    // detaching the pin from the centre, so the pin never jumps to wherever
                    // the map happened to be panned. Only once the camera lands there does
                    // the centre become the post's location again, i.e. AC-22.
                    coroutineScope.launch {
                        cameraPositionState.animate(
                            CameraUpdateFactory.newLatLng(LatLng(mode.lat, mode.lng))
                        )
                        viewModel.beginReposition()
                    }
                },
                onAddClue = onAddClue,
                onSave = viewModel::savePost,
                onCancel = onCancel
            )

            // Unreachable in practice: CreateGameContent is only ever composed for Naming's
            // hand-off to Overview and for whatever comes after, up to but not including
            // Published, which routes to GamePublishedContent instead. See CreateGameScreen's
            // mode routing above.
            is CreateScreenMode.Naming, is CreateScreenMode.Published -> Unit
        }
    }
}

/**
 * Section 5.2's publish confirmation: the code shown large, plus a share sheet. Reached only
 * once, from [CreateScreenMode.Published] — a published game is read-only for the creator, so
 * this screen has nowhere to send the creator but away (Done, which pops back to Home).
 */
@Composable
private fun GamePublishedContent(
    code: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.create_published_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = stringResource(R.string.create_published_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.xl))
        Text(
            text = code,
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.xl))
        ShareCodeButton(code = code, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(Spacing.sm))
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.minTouchTarget)
        ) {
            Text(stringResource(R.string.create_published_done))
        }
    }
}

/**
 * Section 5.2: reopening a published game from My games replaces Add/Edit/Delete/Create-game
 * outright, not just disables them — those all write to a document the rules now refuse (see
 * this file's read-only routing above), so there is nothing left for them to do. What a creator
 * opening a published game actually wants is the code: this is currently the *only* way to
 * retrieve it once the one-time post-publish confirmation screen (GamePublishedContent) is gone,
 * since My games shows status but never the code itself.
 */
@Composable
private fun PublishedOverviewControls(code: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = stringResource(R.string.create_readonly_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = code,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        ShareCodeButton(code = code, modifier = Modifier.fillMaxWidth())
    }
}

/** The one place that builds the join-code share intent — [GamePublishedContent] and
 * [PublishedOverviewControls] both hand a creator the same code the same way. */
@Composable
private fun ShareCodeButton(code: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shareText = stringResource(R.string.create_published_share_text, stringResource(R.string.app_name), code)

    OutlinedButton(
        onClick = {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            context.startActivity(Intent.createChooser(sendIntent, null))
        },
        modifier = modifier.heightIn(min = Spacing.minTouchTarget)
    ) {
        Text(stringResource(R.string.create_published_share))
    }
}

private data class MapTypeOption(val mapType: MapType, val icon: ImageVector, val labelRes: Int)

private val MAP_TYPE_OPTIONS = listOf(
    MapTypeOption(MapType.NORMAL, Icons.Filled.Map, R.string.create_map_type_normal),
    MapTypeOption(MapType.HYBRID, Icons.Filled.Satellite, R.string.create_map_type_hybrid),
    MapTypeOption(MapType.TERRAIN, Icons.Filled.Terrain, R.string.create_map_type_terrain)
)

/**
 * A single Layers button that opens a small menu, the convention Google's own map apps use for
 * this — not three persistent buttons competing with the map for space. See the call site
 * above for why.
 */
@Composable
private fun MapTypeControl(
    mapType: MapType,
    onMapTypeChange: (MapType) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        FilledTonalIconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.Layers,
                contentDescription = stringResource(R.string.create_map_type_button)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MAP_TYPE_OPTIONS.forEach { option ->
                val selected = mapType == option.mapType
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    leadingIcon = { Icon(imageVector = option.icon, contentDescription = null) },
                    // Marks which layer is active, per the same convention.
                    trailingIcon = {
                        if (selected) {
                            Icon(imageVector = Icons.Filled.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        onMapTypeChange(option.mapType)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun NumberedPostMarker(
    post: Post,
    selected: Boolean,
    onClick: () -> Unit
) {
    val markerState = rememberUpdatedMarkerState(position = LatLng(post.lat, post.lng))
    MarkerComposable(
        state = markerState,
        title = stringResource(R.string.create_post_marker_content_description, post.index),
        // MarkerComposable defaults to Offset(0.5, 1.0) — the bottom-centre anchor a
        // pin-shaped icon wants, so its tip lands on the coordinate. PostBadge is a round
        // disc, not a pin: anchored that way, the coordinate sits under the badge's bottom
        // edge, and the whole badge is drawn one diameter above where the post actually is.
        // Centring the anchor instead puts the badge itself on the coordinate. See
        // PendingPostMarker for the same fix applied to the pin actually being placed.
        anchor = Offset(0.5f, 0.5f),
        onClick = {
            onClick()
            true
        }
    ) {
        // Primary is reserved for the current target per section 14.3, so a saved post only
        // borrows it while selected for Edit/Delete; otherwise it's the neutral secondary.
        // The full solid primary/onPrimary pair, not the softer *Container tones — this is
        // the same treatment PendingPostMarker gets in edit mode (see its own doc), so a
        // selected marker reads as unambiguously as one already being edited. A subtler
        // container-tone tint here previously left Edit and Delete enabled with no clear
        // visual target, a real risk with Delete one tap away. See section 5.2.
        PostBadge(
            index = post.index,
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            }
        )
    }
}

/**
 * The post currently being placed/edited, once Set has locked it to a coordinate. Always
 * primary-tinted: per section 14.3, primary is reserved for the current target, and while
 * mid-edit this post is exactly that.
 */
@Composable
private fun PendingPostMarker(lat: Double, lng: Double, index: Int) {
    val markerState = rememberUpdatedMarkerState(position = LatLng(lat, lng))
    MarkerComposable(
        state = markerState,
        title = stringResource(R.string.create_post_marker_content_description, index),
        // See NumberedPostMarker's doc — same round-badge-vs-pin-default anchor mismatch,
        // and the one that matters most: this is the marker a creator is actively aligning
        // against the fixed centre pin while placing a post (see confirmLocation's onSet).
        // Anchored at the default (0.5, 1.0), the badge they're eyeballing sits a full
        // diameter above the coordinate Set actually commits.
        anchor = Offset(0.5f, 0.5f)
    ) {
        PostBadge(
            index = index,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    }
}

@Composable
private fun PostBadge(index: Int, containerColor: Color, contentColor: Color) {
    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(Spacing.hairline, MaterialTheme.colorScheme.outline)
    ) {
        Box(
            modifier = Modifier.size(Spacing.xl),
            contentAlignment = Alignment.Center
        ) {
            Text(text = index.toString(), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun OverviewControls(
    uiState: CreateGameUiState,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPublish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasSelection = uiState.selectedPostIndex != null
    val hasPosts = uiState.posts.isNotEmpty()
    // Section 5.2 hardening: Delete used to fire immediately on tap. With the marker highlight
    // as the only confirmation of which post was targeted (now strengthened, but still no
    // undo), naming the post here is the actual safety net against a mis-selected Delete.
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        if (hasPosts && !hasSelection) {
            Text(
                text = stringResource(R.string.create_select_post_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button(
                onClick = onAdd,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Spacing.minTouchTarget)
            ) {
                Text(stringResource(R.string.create_add))
            }
            OutlinedButton(
                onClick = onEdit,
                enabled = hasSelection,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Spacing.minTouchTarget)
            ) {
                Text(stringResource(R.string.create_edit))
            }
            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                enabled = hasSelection,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Spacing.minTouchTarget)
            ) {
                Text(stringResource(R.string.create_delete))
            }
        }

        val validation = uiState.validation
        Button(
            onClick = onPublish,
            enabled = validation is CreateGameValidation.Valid && !uiState.isPublishing,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.minTouchTarget)
        ) {
            Text(stringResource(R.string.create_create_game))
        }
        val validationMessage = when (validation) {
            is CreateGameValidation.Valid -> null
            is CreateGameValidation.TooFewPosts -> stringResource(R.string.create_validation_too_few_posts)
            is CreateGameValidation.InsufficientClues -> stringResource(
                R.string.create_validation_insufficient_clues,
                validation.postIndex,
                validation.clueCount
            )
            is CreateGameValidation.TooManyClues -> stringResource(
                R.string.create_validation_too_many_clues,
                validation.postIndex,
                validation.clueCount
            )
        }
        if (validationMessage != null) {
            Text(
                text = validationMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Section 7: "then surfacing an error" — retries-exhausted or any other publish
        // failure (a permission denial, dropped connection) shows here rather than silently
        // resetting the button, which is exactly how this went unnoticed before. Distinguished
        // via toWriteError() so a permission denial never reads as a network problem.
        val publishError = uiState.publishError
        if (publishError != null) {
            Text(
                text = writeErrorText(
                    publishError,
                    permissionDeniedRes = R.string.create_publish_error_permission,
                    unreachableRes = R.string.create_publish_error_unreachable
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        // Reverted-and-reselected on failure by deleteSelectedPost, so this always describes
        // the post that's still selected below it.
        val deletePostError = uiState.deletePostError
        if (deletePostError != null) {
            Text(
                text = writeErrorText(
                    deletePostError,
                    permissionDeniedRes = R.string.create_delete_post_error_permission,
                    unreachableRes = R.string.create_delete_post_error_unreachable
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    val selectedIndex = uiState.selectedPostIndex
    if (showDeleteConfirm && selectedIndex != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text(
                    if (selectedIndex == 0) {
                        stringResource(R.string.create_delete_post_dialog_title_start)
                    } else {
                        stringResource(R.string.create_delete_post_dialog_title_post, selectedIndex)
                    }
                )
            },
            text = { Text(stringResource(R.string.create_delete_post_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.create_delete_post_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.create_delete_post_dialog_cancel))
                }
            }
        )
    }
}

@Composable
private fun PostEditorControls(
    mode: CreateScreenMode.PostEditor,
    currentZoom: Float,
    isSaving: Boolean,
    saveError: WriteError?,
    onSet: () -> Unit,
    onReposition: () -> Unit,
    onAddClue: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val zoomedInEnough = currentZoom >= MIN_ZOOM_FOR_SET

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        if (!mode.locationConfirmed) {
            Text(
                text = if (zoomedInEnough) {
                    stringResource(R.string.create_location_not_set_hint)
                } else {
                    stringResource(R.string.create_zoom_in_hint)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            // Section 14.1 only requires Cancel not sit adjacent to Save, not that it stay out
            // of this row entirely — a secondary row above a full-width Save satisfies that
            // while keeping Cancel where it's actually seen. The top bar's close icon (see
            // requestCancel) is the same problem the clue editor had before it gained its own
            // bottom Done: an exit outside the thumb zone doesn't get found. That icon stays,
            // this is a second, more discoverable way to the same place, not a replacement.
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Spacing.minTouchTarget)
            ) {
                Text(stringResource(R.string.create_cancel))
            }
            // The verb stays constant and the object varies, per 14.5: the button fixes a
            // coordinate, it does not create or number anything, so it names the location
            // rather than the post. See section 5.2.
            //
            // Once set, the same button becomes Reposition and its tap is the exact inverse
            // of Set (see CreateGameViewModel.beginReposition) rather than a separate mode.
            // Zoom is only required to commit a coordinate, not to re-enter placement, so
            // Reposition itself is never gated on it. See AC-20.
            //
            // Unqualified once confirmed ("Reposition") and unqualified before it ("Set
            // location") — dropping the post number in both, not just Reposition: with Cancel
            // sharing this row, a number here wraps and crowds the other two buttons the same
            // way whichever of the two is showing. As with Reposition, this trades away the
            // one place a post's number was visible during Add before the header catches up
            // (it stays "Add post", unindexed, through the whole flow) — an accepted trade-off,
            // not an oversight; revisit if it turns out to matter in practice.
            //
            // Start stays distinct — "Set start location" — because that's not the number
            // being dropped, it's that the start post is genuinely a different kind of thing
            // (section 3's model notes: no clues lead to it, it scores nothing), which "Edit
            // post 0" in the header doesn't convey and only this label does.
            val locationLabel = if (mode.locationConfirmed) {
                stringResource(R.string.create_reposition)
            } else {
                if (mode.targetIndex == 0) {
                    stringResource(R.string.create_set_start_location)
                } else {
                    stringResource(R.string.create_set_post_location)
                }
            }
            OutlinedButton(
                onClick = if (mode.locationConfirmed) onReposition else onSet,
                enabled = mode.locationConfirmed || zoomedInEnough,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Spacing.minTouchTarget)
            ) {
                Text(locationLabel)
            }
            // Add clues only becomes available once Set has locked a location (so looking
            // around while writing a clue can't drag the post along), and never for post 0,
            // which takes no clues. See section 5.2.
            if (mode.targetIndex != 0 && mode.locationConfirmed) {
                OutlinedButton(
                    onClick = onAddClue,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Spacing.minTouchTarget)
                ) {
                    Text(stringResource(R.string.create_add_clue))
                }
            }
        }

        // Save is the sole bottom-row action, still never sharing a row with Cancel (see the
        // row above) — a mis-tap discarding written clues has no undo. Disabled while a save
        // is in flight so a second tap can't race the Firestore write and create a duplicate
        // post document.
        Button(
            onClick = onSave,
            enabled = mode.locationConfirmed && !isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.minTouchTarget)
        ) {
            Text(stringResource(R.string.create_save))
        }
        // A denied or dropped save must say so — this used to discard the post and its clues
        // with no explanation at all. See CreateGameViewModel.savePost.
        if (saveError != null) {
            Text(
                text = writeErrorText(
                    saveError,
                    permissionDeniedRes = R.string.create_save_error_permission,
                    unreachableRes = R.string.create_save_error_unreachable
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
