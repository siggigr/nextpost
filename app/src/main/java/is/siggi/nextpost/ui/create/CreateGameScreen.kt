package `is`.siggi.nextpost.ui.create

import android.location.Location
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import `is`.siggi.nextpost.R
import `is`.siggi.nextpost.data.model.Post
import `is`.siggi.nextpost.data.repository.LocationRepository
import `is`.siggi.nextpost.ui.common.ArrivalRadiusCircle
import `is`.siggi.nextpost.ui.common.LocationAccessGate
import `is`.siggi.nextpost.ui.common.rememberLocationAccessState
import `is`.siggi.nextpost.ui.theme.Spacing
import kotlinx.coroutines.CancellationException

/** Fallback centre before the first GPS fix lands, matching the M0/M1 default. */
private val REYKJAVIK = LatLng(64.1466, -21.9426)

/**
 * Roughly building level. Below this, a fingertip on screen covers more ground than the
 * 25 m arrival radius, so a placement can look precise while actually being off by tens of
 * metres. See section 5.2.
 */
private const val MIN_ZOOM_FOR_SET = 17f

/**
 * Section 5.2. Location gating reuses the M1 infrastructure: the map itself (not the whole
 * app) is what needs a precise fix, so the gate sits inside this screen's content, below a
 * top bar that stays reachable regardless of permission state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGameScreen(
    viewModel: CreateGameViewModel,
    onNavigateUp: () -> Unit,
    onAddClue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val locationAccessState = rememberLocationAccessState()
    val editor = uiState.mode as? CreateScreenMode.PostEditor
    var showDiscardConfirm by remember { mutableStateOf(false) }

    // Cancel lives in the top bar rather than the bottom control cluster, specifically so
    // it can never end up adjacent to Save. See the section 5.2 layout constraint.
    val requestCancel: () -> Unit = {
        if (editor != null && editor.clues.isNotEmpty()) {
            showDiscardConfirm = true
        } else {
            viewModel.cancelPostEditor()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    val title = when {
                        editor == null -> stringResource(R.string.create_game_title)
                        editor.editingIndex == null -> stringResource(R.string.create_editor_title_add)
                        else -> stringResource(R.string.create_editor_title_edit, editor.editingIndex)
                    }
                    Text(title)
                },
                navigationIcon = {
                    if (editor != null) {
                        IconButton(onClick = requestCancel) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.create_cancel)
                            )
                        }
                    } else {
                        IconButton(onClick = onNavigateUp) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.create_nav_back)
                            )
                        }
                    }
                },
                actions = {
                    if (editor == null) {
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
            LocationAccessGate(state = locationAccessState) {
                CreateGameContent(
                    uiState = uiState,
                    viewModel = viewModel,
                    onAddClue = onAddClue
                )
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

@Composable
private fun CreateGameContent(
    uiState: CreateGameUiState,
    viewModel: CreateGameViewModel,
    onAddClue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val locationRepository = remember { LocationRepository(context) }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(REYKJAVIK, 15f)
    }
    var lastKnownLocation by remember { mutableStateOf<LatLng?>(null) }

    LaunchedEffect(Unit) {
        try {
            val location: Location? = locationRepository.getCurrentLocation()
            if (location != null) {
                val target = LatLng(location.latitude, location.longitude)
                lastKnownLocation = target
                cameraPositionState.position = CameraPosition.fromLatLngZoom(target, 17f)
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
            val mapProperties = MapProperties(isMyLocationEnabled = true)

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
                            onClick = { viewModel.selectPost(post.index) }
                        )
                    }
                }

                // Once Set is tapped the pin detaches from the map centre and locks to that
                // coordinate; from here the map pans freely underneath without moving it.
                if (mode is CreateScreenMode.PostEditor && mode.locationConfirmed) {
                    PendingPostMarker(lat = mode.lat, lng = mode.lng, index = mode.targetIndex)
                }

                // Makes the 25 m arrival radius visible while placing a post rather than
                // something the creator has to imagine. Reused as-is on the M5 play screen.
                if (mode is CreateScreenMode.PostEditor) {
                    val circleCenter = if (mode.locationConfirmed) {
                        LatLng(mode.lat, mode.lng)
                    } else {
                        cameraPositionState.position.target
                    }
                    ArrivalRadiusCircle(center = circleCenter)
                }
            }

            // Fixed centre pin: while the location isn't confirmed yet, the map centre IS
            // the post location, so the pin stays screen-fixed and the map pans under it
            // rather than the pin being dragged. See section 5.2.
            if (mode is CreateScreenMode.PostEditor && !mode.locationConfirmed) {
                Icon(
                    imageVector = Icons.Filled.LocationOn,
                    contentDescription = stringResource(R.string.create_pin_content_description),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = -Spacing.md)
                        .size(Spacing.xl)
                )
            }
        }

        when (mode) {
            is CreateScreenMode.Overview -> OverviewControls(
                uiState = uiState,
                onAdd = {
                    val seed = lastKnownLocation ?: cameraPositionState.position.target
                    viewModel.beginAddPost(seed.latitude, seed.longitude)
                },
                onEdit = viewModel::beginEditSelectedPost,
                onDelete = viewModel::deleteSelectedPost
            )

            is CreateScreenMode.PostEditor -> PostEditorControls(
                mode = mode,
                currentZoom = cameraPositionState.position.zoom,
                onSet = {
                    val target = cameraPositionState.position.target
                    viewModel.confirmLocation(target.latitude, target.longitude)
                },
                onAddClue = onAddClue,
                onSave = viewModel::savePost
            )
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
        onClick = {
            onClick()
            true
        }
    ) {
        // Primary is reserved for the current target per section 14.3, so a saved post only
        // borrows it while selected for Edit/Delete; otherwise it's the neutral secondary.
        PostBadge(
            index = post.index,
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
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
        title = stringResource(R.string.create_post_marker_content_description, index)
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
    modifier: Modifier = Modifier
) {
    val hasSelection = uiState.selectedPostIndex != null
    val hasPosts = uiState.posts.isNotEmpty()

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
                onClick = onDelete,
                enabled = hasSelection,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Spacing.minTouchTarget)
            ) {
                Text(stringResource(R.string.create_delete))
            }
        }

        val validation = uiState.validation
        // Publishing (code generation, Firestore write) is M4 scope. This button only
        // needs to be correctly gated for AC-1 in M2.
        Button(
            onClick = {},
            enabled = validation is CreateGameValidation.Valid,
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
        }
        if (validationMessage != null) {
            Text(
                text = validationMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PostEditorControls(
    mode: CreateScreenMode.PostEditor,
    currentZoom: Float,
    onSet: () -> Unit,
    onAddClue: () -> Unit,
    onSave: () -> Unit,
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
            // The verb stays constant and the object varies, per 14.5: the button fixes a
            // coordinate, it does not create or number anything, so it names the location
            // rather than the post. "Set location for post N" only applies past post 0
            // because nothing else on this screen shows which post is being placed while
            // adding one. See section 5.2.
            val setLabel = if (mode.targetIndex == 0) {
                stringResource(R.string.create_set_start_location)
            } else {
                stringResource(R.string.create_set_post_location, mode.targetIndex)
            }
            OutlinedButton(
                onClick = onSet,
                enabled = zoomedInEnough,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Spacing.minTouchTarget)
            ) {
                Text(setLabel)
            }
            // Add clue only becomes available once Set has locked a location (so looking
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

        // Save is the sole bottom action. Cancel lives in the top bar instead of beside it,
        // per the section 5.2 layout constraint: a mis-tap discarding written clues has no undo.
        Button(
            onClick = onSave,
            enabled = mode.locationConfirmed,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.minTouchTarget)
        ) {
            Text(stringResource(R.string.create_save))
        }
    }
}
