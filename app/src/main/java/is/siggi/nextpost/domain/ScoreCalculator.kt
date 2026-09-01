package `is`.siggi.nextpost.domain

import kotlin.math.round

/**
 * Section 4's scoring formula. Pure and Android-free so it runs on the JVM test source set,
 * same as [ProximityChecker] and [ClueValidator]. [ClueValidator.MAX_CLUES_PER_POST] is what
 * keeps this floor-safe — see section 4's "why clues per post are capped at 10."
 */
object ScoreCalculator {
    const val MAX_POINTS = 100.0
    const val MIN_POINTS = 10.0

    /**
     * @param totalClues total clues attached to the post (>= 3 in a published game)
     * @param extraCluesOpened clues opened beyond the free first clue
     */
    fun scoreForPost(totalClues: Int, extraCluesOpened: Int): Double {
        if (totalClues <= 1) return MAX_POINTS
        val penaltyPerClue = MAX_POINTS / (totalClues - 1)
        val raw = MAX_POINTS - extraCluesOpened * penaltyPerClue
        return maxOf(MIN_POINTS, raw)
    }

    /**
     * Section 4: "Display scores rounded to one decimal." Storage keeps full precision
     * ([scoreForPost]'s raw return value); this is presentation-only, trimming a trailing
     * ".0" so a clean 75.0 reads as "75" rather than "75.0".
     */
    fun formatScore(score: Double): String {
        val rounded = round(score * 10) / 10.0
        val wholeNumber = rounded.toLong()
        return if (rounded == wholeNumber.toDouble()) wholeNumber.toString() else rounded.toString()
    }
}
