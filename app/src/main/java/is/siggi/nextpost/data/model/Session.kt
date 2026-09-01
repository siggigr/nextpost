package `is`.siggi.nextpost.data.model

/** Wire values match the Firestore `status` field exactly. See section 3. */
enum class SessionStatus(val wireValue: String) {
    ACTIVE("active"),
    FINISHED("finished"),
    ABANDONED("abandoned");

    companion object {
        fun fromWireValue(value: String?): SessionStatus =
            entries.firstOrNull { it.wireValue == value } ?: ACTIVE
    }
}

/**
 * One scored post's outcome within a session. [cluesOpened] is stored directly at arrival time
 * (the free clue plus however many extras were open) rather than reconstructed later from
 * [score]: inverting the scoring formula only works while the floor collapses onto a single,
 * unambiguous clue count, which is a fact about the current [is.siggi.nextpost.domain.ClueValidator.MAX_CLUES_PER_POST]
 * value, not a durable property of the formula — see section 3's model notes.
 */
data class PostResult(
    val score: Double = 0.0,
    val cluesOpened: Int = 0
)

/**
 * One player's playthrough of one game. The document id is [playerUid] (`sessions/{uid}`), so
 * unlike [Post]/[Clue] there is no separate `id` field here. [startedAt]/[finishedAt] are epoch
 * millis rather than a Firebase Timestamp, matching the rest of `data/model/` — see [Game].
 *
 * [attemptNumber] counts which run this is, starting at 1 on the first join. Section 13.1:
 * `restartSession` archives the finished session into `sessions/{uid}/attempts/{attemptId}`
 * before resetting this document, so this field is what makes it obvious — while play is in
 * progress — which attempt is live, without needing to count the archive.
 */
data class Session(
    val playerUid: String = "",
    val displayName: String = "",
    val status: SessionStatus = SessionStatus.ACTIVE,
    val currentPostIndex: Int = 0,
    val cluesOpenedForCurrentPost: Int = 0,
    val postScores: Map<String, PostResult> = emptyMap(),
    val totalScore: Double = 0.0,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val attemptNumber: Int = 1
)
