package `is`.siggi.nextpost.ui.join

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import `is`.siggi.nextpost.R
import `is`.siggi.nextpost.ui.common.WriteError
import `is`.siggi.nextpost.ui.theme.Spacing

/**
 * Section 5.3: name then code — the code is what the player is holding in their hand and
 * wants to type immediately, so it's the field whose IME action submits. Name is prefilled
 * from [JoinGameViewModel]'s DataStore lookup but stays editable, since someone handing their
 * phone to a friend needs to change it.
 *
 * No field is focused programmatically, including the prefilled name field, per 14.1: a
 * Samsung keyboard was found to drop in-flight keystrokes when programmatic focus restarted
 * its input connection mid-composition (see ClueEditorScreen's fuller note). The player taps
 * whichever field they want.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinGameScreen(
    viewModel: JoinGameViewModel,
    onNavigateUp: () -> Unit,
    onJoined: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.joinedGameId) {
        uiState.joinedGameId?.let(onJoined)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.join_title)) },
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
        // Top-aligned and imePadding-backed, same as GameNamingContent: a keyboard-first
        // screen with fields placed low is a screen the keyboard hides the moment a field is
        // tapped. See 14.1.
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .imePadding()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            OutlinedTextField(
                value = uiState.name,
                onValueChange = viewModel::updateName,
                label = { Text(stringResource(R.string.join_name_label)) },
                singleLine = true,
                isError = uiState.nameError != null,
                supportingText = uiState.nameError?.let { error -> { Text(joinFieldErrorText(error)) } },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.code,
                onValueChange = viewModel::updateCode,
                label = { Text(stringResource(R.string.join_code_label)) },
                singleLine = true,
                isError = uiState.codeError != null,
                supportingText = uiState.codeError?.let { error -> { Text(joinFieldErrorText(error)) } },
                // autoCorrect off: codes are typed, not composed prose, and Gboard/Samsung
                // autocorrect actively fights the ambiguity-free alphabet (section 7).
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = { viewModel.join() }),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = viewModel::join,
                enabled = !uiState.isJoining,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.minTouchTarget)
            ) {
                Text(stringResource(R.string.join_button))
            }
        }

        val pendingRestartGameId = uiState.pendingRestartGameId
        if (pendingRestartGameId != null) {
            AlertDialog(
                onDismissRequest = viewModel::dismissRestartOffer,
                title = { Text(stringResource(R.string.join_finished_dialog_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(stringResource(R.string.join_finished_dialog_body))
                        // The dialog stays open on failure (restartAndJoin) so this must say
                        // why, rather than a Restart tap that silently did nothing.
                        val restartError = uiState.restartError
                        if (restartError != null) {
                            Text(
                                text = when (restartError) {
                                    WriteError.PermissionDenied ->
                                        stringResource(R.string.join_finished_restart_error_permission)
                                    WriteError.Unreachable ->
                                        stringResource(R.string.join_finished_restart_error_unreachable)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = viewModel::restartAndJoin) {
                        Text(stringResource(R.string.join_finished_restart))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissRestartOffer) {
                        Text(stringResource(R.string.join_cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun joinFieldErrorText(error: JoinFieldError): String = when (error) {
    JoinFieldError.NameRequired -> stringResource(R.string.join_error_name_required)
    JoinFieldError.CodeIncomplete -> stringResource(R.string.join_error_code_length)
    JoinFieldError.UnknownCode -> stringResource(R.string.join_error_unknown_code)
    JoinFieldError.GameDeleted -> stringResource(R.string.join_error_game_deleted)
    JoinFieldError.Generic -> stringResource(R.string.join_error_generic)
}
