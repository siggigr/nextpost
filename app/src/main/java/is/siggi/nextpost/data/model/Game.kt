package `is`.siggi.nextpost.data.model

/** Wire values match the Firestore `status` field exactly. See section 3. */
enum class GameStatus(val wireValue: String) {
    DRAFT("draft"),
    PUBLISHED("published");

    companion object {
        fun fromWireValue(value: String?): GameStatus =
            entries.firstOrNull { it.wireValue == value } ?: DRAFT
    }
}

/**
 * [id] is the Firestore document id. [createdAt]/[publishedAt] are epoch millis rather than
 * a Firebase Timestamp, so this model stays free of Firebase imports like the rest of
 * `data/model/` — the mapping happens at the `data/firebase/` boundary.
 */
data class Game(
    val id: String = "",
    val code: String = "",
    val title: String = "",
    val creatorUid: String = "",
    val status: GameStatus = GameStatus.DRAFT,
    val postCount: Int = 0,
    val scoredPostCount: Int = 0,
    val defaultRadiusMeters: Int = Post.DEFAULT_RADIUS_METERS,
    val createdAt: Long? = null,
    val publishedAt: Long? = null
)
