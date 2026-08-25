package `is`.siggi.nextpost.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import `is`.siggi.nextpost.R
import `is`.siggi.nextpost.ui.theme.Spacing

/**
 * Section 5.1: two primary buttons, Create new game and Play Nextpost, plus a secondary
 * My games link. Play has nowhere to go yet (join/play land in M4/M5), so it stays disabled
 * rather than pointing at a dead route.
 */
@Composable
fun HomeScreen(
    onCreateGame: () -> Unit,
    onMyGames: () -> Unit,
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
            text = stringResource(R.string.home_welcome, stringResource(R.string.app_name)),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.xl))
        Button(
            onClick = onCreateGame,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.primaryTouchTarget)
        ) {
            Text(stringResource(R.string.home_create_game))
        }
        Spacer(Modifier.height(Spacing.md))
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.primaryTouchTarget)
        ) {
            Text(stringResource(R.string.home_play))
        }
        Spacer(Modifier.height(Spacing.md))
        TextButton(onClick = onMyGames) {
            Text(stringResource(R.string.home_my_games))
        }
    }
}
