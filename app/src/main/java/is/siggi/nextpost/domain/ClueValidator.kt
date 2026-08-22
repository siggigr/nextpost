package `is`.siggi.nextpost.domain

/**
 * The 3-clue minimum and the empty-clue rule are the same rule and must not be able to
 * disagree, so both the M2 clue editor and the M4 publish flow call this rather than
 * re-deriving it. Pure and Android-free so it runs on the JVM test source set. See section 5.2.
 */
object ClueValidator {
    const val MIN_CLUES_PER_SCORED_POST = 3

    data class Result(
        val isValid: Boolean,
        val nonBlankCount: Int,
        val firstBlankIndex: Int?
    )

    /** Blank means empty or whitespace-only; a blank clue never counts towards the minimum. */
    fun validate(clueTexts: List<String>): Result {
        val firstBlankIndex = clueTexts.indexOfFirst { it.isBlank() }.takeIf { it >= 0 }
        val nonBlankCount = clueTexts.count { it.isNotBlank() }
        val isValid = firstBlankIndex == null && nonBlankCount >= MIN_CLUES_PER_SCORED_POST
        return Result(isValid, nonBlankCount, firstBlankIndex)
    }

    /** What should actually be persisted: whitespace trimmed, blank entries dropped. */
    fun sanitize(clueTexts: List<String>): List<String> =
        clueTexts.map { it.trim() }.filter { it.isNotEmpty() }
}
