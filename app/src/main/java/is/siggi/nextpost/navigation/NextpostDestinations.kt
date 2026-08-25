package `is`.siggi.nextpost.navigation

object NextpostDestinations {
    const val HOME = "home"
    const val MY_GAMES = "my_games"
    const val JOIN = "join"

    /** Route argument value meaning "start a new draft" rather than an actual Firestore id. */
    const val NEW_GAME_ARG = "new"

    const val CREATE_GRAPH_PATTERN = "create/{gameId}"
    const val GAME_ID_ARG = "gameId"

    /** Nested graph so [CREATE_GAME] and [CREATE_CLUES] share one CreateGameViewModel. */
    const val CREATE_GAME = "create_game"
    const val CREATE_CLUES = "create_clues"

    /** [gameId] omitted starts a new draft; pass an existing id to resume one from My games. */
    fun createGraphRoute(gameId: String = NEW_GAME_ARG) = "create/$gameId"
}
