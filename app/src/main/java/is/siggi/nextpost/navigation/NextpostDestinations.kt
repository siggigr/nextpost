package `is`.siggi.nextpost.navigation

object NextpostDestinations {
    const val HOME = "home"

    /** Nested graph so [CREATE_GAME] and [CREATE_CLUES] share one CreateGameViewModel. */
    const val CREATE_GRAPH = "create"
    const val CREATE_GAME = "create/game"
    const val CREATE_CLUES = "create/clues"
}
