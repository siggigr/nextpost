package `is`.siggi.nextpost.domain

import kotlin.random.Random

/**
 * Section 7. Pure generation; uniqueness is enforced separately by a Firestore transaction on
 * `gameCodes/{CODE}` (see `GameRepository.publishGame`), which is what [MAX_GENERATION_ATTEMPTS]
 * bounds — the retry count on collision, not anything about this function itself.
 */
object GameCodeGenerator {
    const val CODE_LENGTH = 6

    /** No I, O, 0, 1 — the pairs that get mixed up when a code is handwritten or read aloud. */
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    const val MAX_GENERATION_ATTEMPTS = 5

    fun generate(random: Random = Random.Default): String =
        (1..CODE_LENGTH).joinToString("") { ALPHABET[random.nextInt(ALPHABET.length)].toString() }

    /**
     * Codes are case-insensitive on input (section 7) and a player may paste one with
     * surrounding whitespace, so this strips both before the code is looked up or compared.
     */
    fun normalize(input: String): String =
        input.uppercase().filterNot { it.isWhitespace() }

    /**
     * Section 7 paste handling: a share-sheet message carries more than the code (e.g. "Join
     * my Nextpost game! Code: RZ2SBL"), so pasting the whole line shouldn't just take its
     * first six characters. This slides a window over the uppercased input, resetting at any
     * character outside [ALPHABET] — the excluded I, O, 0, 1 mean ordinary words tend to break
     * themselves apart before they can look like a code. The rightmost run of exactly
     * [CODE_LENGTH] alphabet characters wins, since the code is what comes last in the
     * message; if no such run exists, this falls back to whatever alphabet characters are
     * present.
     */
    fun extractCode(input: String): String {
        val upper = input.uppercase()
        var lastCandidate: String? = null
        val window = StringBuilder()
        for (char in upper) {
            if (char in ALPHABET) {
                window.append(char)
                if (window.length > CODE_LENGTH) window.deleteCharAt(0)
                if (window.length == CODE_LENGTH) lastCandidate = window.toString()
            } else {
                window.clear()
            }
        }
        return lastCandidate ?: upper.filter { it in ALPHABET }.take(CODE_LENGTH)
    }
}
