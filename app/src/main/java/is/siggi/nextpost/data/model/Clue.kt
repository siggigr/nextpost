package `is`.siggi.nextpost.data.model

/** Index 0 is the free clue, revealed with no score penalty. See section 1 glossary. */
data class Clue(
    val index: Int,
    val text: String
)
