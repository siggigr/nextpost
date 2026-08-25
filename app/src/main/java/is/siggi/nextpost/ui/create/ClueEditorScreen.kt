package `is`.siggi.nextpost.ui.create

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import `is`.siggi.nextpost.R
import `is`.siggi.nextpost.data.model.Clue
import `is`.siggi.nextpost.domain.ClueValidator
import `is`.siggi.nextpost.ui.theme.Spacing

/** Clues are read on a phone outdoors at a glance; a pasted paragraph is unusable there. */
private const val MAX_CLUE_LENGTH = 200

/** Counter only appears once it's actually relevant, to avoid cluttering every row. */
private const val CLUE_LENGTH_COUNTER_THRESHOLD = 150

/**
 * Section 5.2's clue editor: ordered list with add/edit/reorder/delete, guidance at the top.
 * The 3-clue minimum is enforced by the bottom action itself rather than a blocking dialog:
 * Add clue is primary until the minimum is met, then Done takes over as the primary action
 * and Add clue is demoted above it. A blank clue (whitespace-only counts as blank) blocks
 * both: Add clue waits for the previous row to have text, and Done names the first blank
 * row rather than letting one through. Operates on the clue list of the shared
 * CreateGameViewModel's current PostEditor mode.
 *
 * No field is ever focused programmatically, including the seeded first one: a Samsung
 * keyboard was found to drop in-flight keystrokes when programmatic focus restarted its
 * input connection mid-composition. The creator taps whichever field they want to type into.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClueEditorScreen(
    viewModel: CreateGameViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val editor = uiState.mode as? CreateScreenMode.PostEditor

    if (editor == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }

    // Same rule the publish flow uses (ClueValidator, domain/): a blank clue (whitespace-only
    // counts as blank) never counts towards the minimum.
    val validation = ClueValidator.validate(editor.clues.map { it.text })
    val minimumMet = validation.isValid

    val keyboardController = LocalSoftwareKeyboardController.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.clue_editor_title, editor.targetIndex)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.create_nav_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        // imePadding so Add clue/Done rise above the keyboard instead of sitting behind it.
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = Spacing.md)
                .imePadding()
        ) {
            Text(
                text = stringResource(R.string.clue_editor_guidance),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.sm)
            )

            // A plain Column, not a LazyColumn: the clue list is capped well under ten rows
            // (ClueValidator), so there's nothing to gain from lazy composition.
            val scrollState = rememberScrollState()
            var previousClueCount by remember { mutableIntStateOf(editor.clues.size) }

            // A newly appended field can land below the fold; scroll it into view above the
            // keyboard so the creator can see what they just added without a manual scroll.
            // Keyed on count increasing specifically, not on the count itself, so a delete or
            // reorder never triggers an unwanted scroll.
            LaunchedEffect(editor.clues.size) {
                if (editor.clues.size > previousClueCount) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                }
                previousClueCount = editor.clues.size
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                editor.clues.forEach { clue ->
                    // Keyed on the clue's stable id, not its position: index is reassigned on
                    // every reorder/delete (see Clue.id's doc), and keying on it would make
                    // Compose tear down and recreate every row a reorder shifts, not just the
                    // one that moved.
                    key(clue.id) {
                        ClueRow(
                            clue = clue,
                            clueCount = editor.clues.size,
                            onTextChange = { text -> viewModel.updateClueText(clue.index, text) },
                            onImeDone = { keyboardController?.hide() },
                            onMoveUp = { viewModel.moveClue(clue.index, clue.index - 1) },
                            onMoveDown = { viewModel.moveClue(clue.index, clue.index + 1) },
                            onDelete = { viewModel.deleteClue(clue.index) }
                        )
                    }
                }
            }

            // Enough rows have been attempted that Done is worth showing, even though a
            // blank among them still blocks it.
            val doneIsRelevant = editor.clues.size >= ClueValidator.MIN_CLUES_PER_SCORED_POST
            // A creator can't stack up blank fields: the next Add clue waits until the
            // previous one has something in it.
            val canAddClue = editor.clues.lastOrNull()?.text?.isBlank() != true

            val statusText = when {
                validation.firstBlankIndex != null -> stringResource(
                    R.string.clue_editor_blank_clue,
                    validation.firstBlankIndex + 1
                )
                !minimumMet -> pluralStringResource(
                    R.plurals.clue_editor_status_below,
                    ClueValidator.MIN_CLUES_PER_SCORED_POST - validation.nonBlankCount,
                    ClueValidator.MIN_CLUES_PER_SCORED_POST - validation.nonBlankCount
                )
                else -> stringResource(R.string.clue_editor_status_met)
            }

            // Add clue is primary until the minimum is met, then Done takes over as the
            // full-width primary action and Add clue is demoted above it. The layout itself
            // signals what still needs doing, rather than a status line with no action.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                if (minimumMet) {
                    OutlinedButton(
                        onClick = { viewModel.addClue("") },
                        enabled = canAddClue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.minTouchTarget)
                    ) {
                        Text(stringResource(R.string.clue_editor_add))
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onDone,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.minTouchTarget)
                    ) {
                        Text(stringResource(R.string.clue_editor_done))
                    }
                } else {
                    // Suppressed until the creator has actually typed something: the seeded
                    // blank first field is not an error just for having just arrived. See
                    // section 5.2.
                    if (editor.clueEntryStarted) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = { viewModel.addClue("") },
                        enabled = canAddClue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.minTouchTarget)
                    ) {
                        Text(stringResource(R.string.clue_editor_add))
                    }
                    // Visible once enough rows exist so a blank-blocked Done still reads as
                    // "you're close" rather than "not started", even though it can't be
                    // tapped until that blank is fixed.
                    if (doneIsRelevant) {
                        OutlinedButton(
                            onClick = onDone,
                            enabled = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Spacing.minTouchTarget)
                        ) {
                            Text(stringResource(R.string.clue_editor_done))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClueRow(
    clue: Clue,
    clueCount: Int,
    onTextChange: (String) -> Unit,
    onImeDone: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        OutlinedTextField(
            value = clue.text,
            onValueChange = { text -> onTextChange(text.take(MAX_CLUE_LENGTH)) },
            label = { Text(stringResource(R.string.clue_editor_clue_label, clue.index + 1)) },
            // Single-line so an IME action key is available at all instead of a newline; the
            // 200-char cap still applies, it just scrolls horizontally within the field rather
            // than wrapping past the cap.
            singleLine = true,
            // autoCorrect off: kept from the investigation into a Samsung-keyboard
            // dropped-keystroke bug, whose actual fix was removing all programmatic focus
            // (see this file's top doc). Clues are short phrases; losing autocorrect on them
            // costs nothing, so there's no reason to turn it back on.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { onImeDone() }
            ),
            supportingText = if (clue.text.length > CLUE_LENGTH_COUNTER_THRESHOLD) {
                {
                    Text(
                        stringResource(
                            R.string.clue_editor_char_counter,
                            clue.text.length,
                            MAX_CLUE_LENGTH
                        )
                    )
                }
            } else {
                null
            },
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onMoveUp, enabled = clue.index > 0) {
            Icon(
                imageVector = Icons.Filled.ArrowUpward,
                contentDescription = stringResource(
                    R.string.clue_editor_move_up_content_description,
                    clue.index + 1
                )
            )
        }
        IconButton(onClick = onMoveDown, enabled = clue.index < clueCount - 1) {
            Icon(
                imageVector = Icons.Filled.ArrowDownward,
                contentDescription = stringResource(
                    R.string.clue_editor_move_down_content_description,
                    clue.index + 1
                )
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(
                    R.string.clue_editor_delete_content_description,
                    clue.index + 1
                )
            )
        }
    }
}
