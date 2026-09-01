package `is`.siggi.nextpost.data.model

import `is`.siggi.nextpost.domain.ProximityChecker

/**
 * Index 0 is the start post: no clues lead to it, and it is never a scored post.
 * [id] is the Firestore document id, empty until the post has been persisted.
 *
 * [clueCount] is the post's *total* clue count, separate from `clues.size`. For the creator,
 * who always loads every clue, the two agree. For a player (M5), security rules only ever
 * hand back the clues currently permitted — the free one plus however many extras have been
 * opened — so `clues.size` there is "clues visible so far," not the total the scoring formula
 * needs. Defaults to `clues.size` so existing creator-side call sites, which never set this
 * explicitly, keep behaving exactly as before.
 */
data class Post(
    val id: String = "",
    val index: Int,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Int = DEFAULT_RADIUS_METERS,
    val clues: List<Clue> = emptyList(),
    val clueCount: Int = clues.size
) {
    companion object {
        const val DEFAULT_RADIUS_METERS = ProximityChecker.DEFAULT_ARRIVAL_RADIUS_METERS
    }
}
