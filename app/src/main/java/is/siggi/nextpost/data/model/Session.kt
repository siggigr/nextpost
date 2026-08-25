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
 * One player's playthrough of one game. The document id is [playerUid] (`sessions/{uid}`), so
 * unlike [Post]/[Clue] there is no separate `id` field here. [startedAt]/[finishedAt] are epoch
 * millis rather than a Firebase Timestamp, matching the rest of `data/model/` — see [Game].
 */
data class Session(
    val playerUid: String = "",
    val displayName: String = "",
    val status: SessionStatus = SessionStatus.ACTIVE,
    val currentPostIndex: Int = 0,
    val cluesOpenedForCurrentPost: Int = 0,
    val postScores: Map<String, Double> = emptyMap(),
    val totalScore: Double = 0.0,
    val startedAt: Long? = null,
    val finishedAt: Long? = null
)
