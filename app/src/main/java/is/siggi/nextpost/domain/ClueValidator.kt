package `is`.siggi.nextpost.domain

/**
 * The 3-clue minimum and the empty-clue rule are the same rule and must not be able to
 * disagree, so both the M2 clue editor and the M4 publish flow call this rather than
 * re-deriving it. Pure and Android-free so it runs on the JVM test source set. See section 5.2.
 */
object ClueValidator {
    const val MIN_CLUES_PER_SCORED_POST = 3

    /**
     * Section 4: the score floor is a `max()`, so without a cap the last ~10% of clues on a
     * long list cost nothing to open, which is a flat zone in the scoring curve, not a
     * scoring bug. Ten keeps the floor reachable only on the final clue and matches 5.2's
     * "vaguest to dead giveaway" arc, which ten clues is already generous for. Enforced here
     * (Add clue disabled at the cap, per the clue editor) and again at publish time (AC-1's
     * validator), the same way the minimum is.
     */
    const val MAX_CLUES_PER_POST = 10

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
