package `is`.siggi.nextpost.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import `is`.siggi.nextpost.data.repository.GameRepository
import `is`.siggi.nextpost.data.repository.PlayerNamePreferenceRepository
import `is`.siggi.nextpost.ui.create.ClueEditorScreen
import `is`.siggi.nextpost.ui.create.CreateGameScreen
import `is`.siggi.nextpost.ui.create.CreateGameViewModel
import `is`.siggi.nextpost.ui.home.HomeScreen
import `is`.siggi.nextpost.ui.join.JoinGameScreen
import `is`.siggi.nextpost.ui.join.JoinGameViewModel
import `is`.siggi.nextpost.ui.mygames.MyGamesScreen
import `is`.siggi.nextpost.ui.mygames.MyGamesViewModel
import `is`.siggi.nextpost.ui.play.PlayScreen
import `is`.siggi.nextpost.ui.play.PlayViewModel

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
                onCreateGame = { navController.navigate(NextpostDestinations.createGraphRoute()) },
                onPlay = { navController.navigate(NextpostDestinations.JOIN) },
                onMyGames = { navController.navigate(NextpostDestinations.MY_GAMES) }
            )
        }

        composable(NextpostDestinations.JOIN) {
            val context = LocalContext.current
            val viewModel: JoinGameViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { JoinGameViewModel(GameRepository(), PlayerNamePreferenceRepository(context)) }
                }
            )
            JoinGameScreen(
                viewModel = viewModel,
                onNavigateUp = { navController.popBackStack() },
                // M5: joining now hands off to the play screen. Join is popped off the back
                // stack in the same step, so leaving Play returns straight to Home rather than
                // back to an already-completed join form.
                onJoined = { gameId ->
                    navController.navigate(NextpostDestinations.playRoute(gameId)) {
                        popUpTo(NextpostDestinations.JOIN) { inclusive = true }
                    }
                }
            )
        }

        composable(
            NextpostDestinations.PLAY_PATTERN,
            arguments = listOf(navArgument(NextpostDestinations.GAME_ID_ARG) { type = NavType.StringType })
        ) { backStackEntry ->
            val gameId = backStackEntry.arguments?.getString(NextpostDestinations.GAME_ID_ARG).orEmpty()
            val viewModel: PlayViewModel = viewModel(
                factory = viewModelFactory { initializer { PlayViewModel(GameRepository()) } }
            )
            PlayScreen(
                viewModel = viewModel,
                gameId = gameId,
                onExit = { navController.popBackStack() }
            )
        }

        composable(NextpostDestinations.MY_GAMES) {
            val viewModel: MyGamesViewModel = viewModel(
                factory = viewModelFactory { initializer { MyGamesViewModel(GameRepository()) } }
            )
            MyGamesScreen(
                viewModel = viewModel,
                onNavigateUp = { navController.popBackStack() },
                onOpenDraft = { gameId ->
                    navController.navigate(NextpostDestinations.createGraphRoute(gameId))
                }
            )
        }

        // Nested graph so CreateGameScreen and ClueEditorScreen share one
        // CreateGameViewModel instance, scoped to the graph rather than either screen.
        navigation(
            startDestination = NextpostDestinations.CREATE_GAME,
            route = NextpostDestinations.CREATE_GRAPH_PATTERN,
            arguments = listOf(navArgument(NextpostDestinations.GAME_ID_ARG) { type = NavType.StringType })
        ) {
            composable(NextpostDestinations.CREATE_GAME) { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(NextpostDestinations.CREATE_GRAPH_PATTERN)
                }
                val viewModel: CreateGameViewModel = viewModel(
                    viewModelStoreOwner = parentEntry,
                    factory = viewModelFactory { initializer { CreateGameViewModel(GameRepository()) } }
                )
                val gameIdArg = parentEntry.arguments?.getString(NextpostDestinations.GAME_ID_ARG)
                val initialGameId = gameIdArg?.takeIf { it != NextpostDestinations.NEW_GAME_ARG }
                CreateGameScreen(
                    viewModel = viewModel,
                    initialGameId = initialGameId,
                    onNavigateUp = { navController.popBackStack() },
                    onAddClue = {
                        // Seeded here, before navigating, so the editor's very first frame
                        // already has one blank field rather than an empty list. See 5.2.
                        viewModel.ensureClueDraftSeeded()
                        navController.navigate(NextpostDestinations.CREATE_CLUES)
                    }
                )
            }
            composable(NextpostDestinations.CREATE_CLUES) { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(NextpostDestinations.CREATE_GRAPH_PATTERN)
                }
                val viewModel: CreateGameViewModel = viewModel(
                    viewModelStoreOwner = parentEntry,
                    factory = viewModelFactory { initializer { CreateGameViewModel(GameRepository()) } }
                )
                ClueEditorScreen(
                    viewModel = viewModel,
                    onDone = { navController.popBackStack() }
                )
            }
        }
    }
}
