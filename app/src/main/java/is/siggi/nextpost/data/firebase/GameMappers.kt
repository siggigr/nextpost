package `is`.siggi.nextpost.data.firebase

import com.google.firebase.firestore.DocumentSnapshot
import `is`.siggi.nextpost.data.model.Clue
import `is`.siggi.nextpost.data.model.Game
import `is`.siggi.nextpost.data.model.GameStatus
import `is`.siggi.nextpost.data.model.Post

/**
 * Manual field-by-field mapping rather than Firestore's reflective toObject(), so
 * `data/model/` stays free of Firebase annotations and this stays the one place that can
 * disagree with section 3's schema.
 */
internal fun Game.toFieldMap(): Map<String, Any?> = mapOf(
    "code" to code,
    "title" to title,
    "creatorUid" to creatorUid,
    "status" to status.wireValue,
    "postCount" to postCount,
    "scoredPostCount" to scoredPostCount,
    "defaultRadiusMeters" to defaultRadiusMeters
)

internal fun DocumentSnapshot.toGame(): Game = Game(
    id = id,
    code = getString("code") ?: "",
    title = getString("title") ?: "",
    creatorUid = getString("creatorUid") ?: "",
    status = GameStatus.fromWireValue(getString("status")),
    postCount = (getLong("postCount") ?: 0L).toInt(),
    scoredPostCount = (getLong("scoredPostCount") ?: 0L).toInt(),
    defaultRadiusMeters = (getLong("defaultRadiusMeters") ?: Post.DEFAULT_RADIUS_METERS.toLong()).toInt(),
    createdAt = getTimestamp("createdAt")?.toDate()?.time,
    publishedAt = getTimestamp("publishedAt")?.toDate()?.time
)

internal fun Post.toFieldMap(): Map<String, Any?> = mapOf(
    "index" to index,
    "title" to title,
    "lat" to lat,
    "lng" to lng,
    "radiusMeters" to radiusMeters,
    "clueCount" to clues.size
)

internal fun DocumentSnapshot.toPost(clues: List<Clue>): Post = Post(
    id = id,
    index = (getLong("index") ?: 0L).toInt(),
    title = getString("title") ?: "",
    lat = getDouble("lat") ?: 0.0,
    lng = getDouble("lng") ?: 0.0,
    radiusMeters = (getLong("radiusMeters") ?: Post.DEFAULT_RADIUS_METERS.toLong()).toInt(),
    clues = clues
)

internal fun Clue.toFieldMap(): Map<String, Any?> = mapOf(
    "index" to index,
    "text" to text
)

internal fun DocumentSnapshot.toClue(): Clue = Clue(
    id = id,
    index = (getLong("index") ?: 0L).toInt(),
    text = getString("text") ?: ""
)
