package `is`.siggi.nextpost.data.firebase

import com.google.firebase.firestore.DocumentSnapshot
import `is`.siggi.nextpost.data.model.Clue
import `is`.siggi.nextpost.data.model.Game
import `is`.siggi.nextpost.data.model.GameStatus
import `is`.siggi.nextpost.data.model.Post
import `is`.siggi.nextpost.data.model.PostResult
import `is`.siggi.nextpost.data.model.Session
import `is`.siggi.nextpost.data.model.SessionStatus

/**
 * A single-document `get()` that came back empty. Firestore returns a perfectly valid
 * [DocumentSnapshot] for a path that holds no document, and every getter on it answers null —
 * so without this the mappers below would happily read a missing game as a blank DRAFT and a
 * missing session as an ACTIVE one sitting at post 0, which is indistinguishable from a real
 * document and far worse than a failure. Absence is surfaced as absence and lands in the same
 * catch as any other failed read, rather than being papered over with plausible defaults.
 *
 * Nothing reachable today produces one — the security rules deny the read first on every path
 * that could — but that is a property of rule ordering, not of this code, and correctness here
 * shouldn't depend on it holding.
 */
internal class MissingDocumentException(path: String) : Exception("No document at $path")

private fun DocumentSnapshot.requireExists() {
    if (!exists()) throw MissingDocumentException(reference.path)
}

/**
 * Manual field-by-field mapping rather than Firestore's reflective toObject(), so
 * `data/model/` stays free of Firebase annotations and this stays the one place that can
 * disagree with section 3's schema.
 *
 * Every snapshot reader below refuses a snapshot with no document behind it (see
 * [MissingDocumentException]). Snapshots taken from a query result always exist, so that only
 * ever fires on a single-document `get()`.
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

internal fun DocumentSnapshot.toGame(): Game {
    requireExists()
    return Game(
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
}

internal fun Post.toFieldMap(): Map<String, Any?> = mapOf(
    "index" to index,
    "lat" to lat,
    "lng" to lng,
    "radiusMeters" to radiusMeters,
    "clueCount" to clues.size
)

internal fun DocumentSnapshot.toPost(clues: List<Clue>): Post {
    requireExists()
    return Post(
        id = id,
        index = (getLong("index") ?: 0L).toInt(),
        lat = getDouble("lat") ?: 0.0,
        lng = getDouble("lng") ?: 0.0,
        radiusMeters = (getLong("radiusMeters") ?: Post.DEFAULT_RADIUS_METERS.toLong()).toInt(),
        clues = clues,
        // Read from the stored field rather than trusting clues.size — see Post.clueCount's doc.
        clueCount = (getLong("clueCount") ?: clues.size.toLong()).toInt()
    )
}

internal fun Clue.toFieldMap(): Map<String, Any?> = mapOf(
    "index" to index,
    "text" to text
)

internal fun DocumentSnapshot.toClue(): Clue {
    requireExists()
    return Clue(
        id = id,
        index = (getLong("index") ?: 0L).toInt(),
        text = getString("text") ?: ""
    )
}

/**
 * Read-only: unlike Game/Post/Clue, [Session] has no matching `toFieldMap()`. Its writes
 * (join, resume, restart) each touch a different subset of fields, so GameRepository builds
 * each one directly rather than through a single shared field map.
 */
internal fun DocumentSnapshot.toSession(): Session {
    requireExists()
    @Suppress("UNCHECKED_CAST")
    val rawScores = get("postScores") as? Map<String, Any?> ?: emptyMap()
    val postScores = rawScores.mapValues { (_, value) ->
        val entry = value as? Map<*, *> ?: emptyMap<Any?, Any?>()
        PostResult(
            score = (entry["score"] as? Number)?.toDouble() ?: 0.0,
            cluesOpened = (entry["cluesOpened"] as? Number)?.toInt() ?: 0
        )
    }
    return Session(
        playerUid = getString("playerUid") ?: "",
        displayName = getString("displayName") ?: "",
        status = SessionStatus.fromWireValue(getString("status")),
        currentPostIndex = (getLong("currentPostIndex") ?: 0L).toInt(),
        cluesOpenedForCurrentPost = (getLong("cluesOpenedForCurrentPost") ?: 0L).toInt(),
        postScores = postScores,
        totalScore = getDouble("totalScore") ?: 0.0,
        startedAt = getTimestamp("startedAt")?.toDate()?.time,
        finishedAt = getTimestamp("finishedAt")?.toDate()?.time,
        attemptNumber = (getLong("attemptNumber") ?: 1L).toInt()
    )
}
