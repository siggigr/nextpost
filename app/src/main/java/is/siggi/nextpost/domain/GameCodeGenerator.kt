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
}
