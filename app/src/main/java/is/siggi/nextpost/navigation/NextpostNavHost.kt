package `is`.siggi.nextpost.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import `is`.siggi.nextpost.ui.create.ClueEditorScreen
import `is`.siggi.nextpost.ui.create.CreateGameScreen
import `is`.siggi.nextpost.ui.create.CreateGameViewModel
import `is`.siggi.nextpost.ui.home.HomeScreen

@Composable
fun NextpostNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = NextpostDestinations.HOME,
        modifier = modifier
    ) {
        composable(NextpostDestinations.HOME) {
            HomeScreen(
                onCreateGame = { navController.navigate(NextpostDestinations.CREATE_GRAPH) }
            )
        }

        // Nested graph so CreateGameScreen and ClueEditorScreen share one
        // CreateGameViewModel instance, scoped to the graph rather than either screen.
        navigation(
            startDestination = NextpostDestinations.CREATE_GAME,
            route = NextpostDestinations.CREATE_GRAPH
        ) {
            composable(NextpostDestinations.CREATE_GAME) { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(NextpostDestinations.CREATE_GRAPH)
                }
                val viewModel: CreateGameViewModel = viewModel(parentEntry)
                CreateGameScreen(
                    viewModel = viewModel,
                    onNavigateUp = { navController.popBackStack() },
                    onAddClue = { navController.navigate(NextpostDestinations.CREATE_CLUES) }
                )
            }
            composable(NextpostDestinations.CREATE_CLUES) { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(NextpostDestinations.CREATE_GRAPH)
                }
                val viewModel: CreateGameViewModel = viewModel(parentEntry)
                ClueEditorScreen(
                    viewModel = viewModel,
                    onDone = { navController.popBackStack() }
                )
            }
        }
    }
}
