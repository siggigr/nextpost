package `is`.siggi.nextpost.data.model

/**
 * Index 0 is the start post: no clues lead to it, and it is never a scored post.
 * [id] is the Firestore document id, empty until the post has been persisted.
 */
data class Post(
    val id: String = "",
    val index: Int,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Int = DEFAULT_RADIUS_METERS,
    val title: String = "",
    val clues: List<Clue> = emptyList()
) {
    companion object {
        const val DEFAULT_RADIUS_METERS = 25
    }
}
