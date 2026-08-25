package `is`.siggi.nextpost.data.repository

import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.firestore
import `is`.siggi.nextpost.data.firebase.FirestorePaths
import `is`.siggi.nextpost.data.firebase.awaitResult
import `is`.siggi.nextpost.data.firebase.toClue
import `is`.siggi.nextpost.data.firebase.toFieldMap
import `is`.siggi.nextpost.data.firebase.toGame
import `is`.siggi.nextpost.data.firebase.toPost
import `is`.siggi.nextpost.data.firebase.toSession
import `is`.siggi.nextpost.data.model.Game
import `is`.siggi.nextpost.data.model.GameStatus
import `is`.siggi.nextpost.data.model.Post
import `is`.siggi.nextpost.data.model.SessionStatus
import `is`.siggi.nextpost.domain.GameCodeGenerator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class SavePostResult(val gameId: String, val post: Post)

/** section 5.3's join outcomes, one per distinct message the join screen must show. */
sealed interface JoinOutcome {
    data class Joined(val gameId: String) : JoinOutcome
    data class AlreadyFinished(val gameId: String) : JoinOutcome
    data object UnknownCode : JoinOutcome
    data object GameDeleted : JoinOutcome
}

/** [publishGame] gives up after retrying [GameCodeGenerator.MAX_GENERATION_ATTEMPTS] times. */
class GameCodeGenerationFailedException : Exception("Could not generate a unique game code")

/** Internal-only signal from inside a transaction that the candidate code collided. */
private class GameCodeCollisionException : Exception()

/**
 * Owns all Firestore access for games/posts/clues/sessions, per section 2's architecture: the
 * ViewModel calls this, never Firebase directly. Publish and join are M4 scope; the play loop
 * that writes session progress (currentPostIndex, cluesOpenedForCurrentPost, scores) is M5.
 */
class GameRepository(
    private val firestore: FirebaseFirestore = Firebase.firestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    private fun gamesCollection() = firestore.collection(FirestorePaths.GAMES)

    private fun postsCollection(gameId: String) =
        gamesCollection().document(gameId).collection(FirestorePaths.POSTS)

    private fun cluesCollection(gameId: String, postId: String) =
        postsCollection(gameId).document(postId).collection(FirestorePaths.CLUES)

    private fun sessionsCollection(gameId: String) =
        gamesCollection().document(gameId).collection(FirestorePaths.SESSIONS)

    private fun gameCodesCollection() = firestore.collection(FirestorePaths.GAME_CODES)

    /**
     * Waits for anonymous sign-in to land rather than assuming it already has. MainActivity
     * fires signInAnonymously() without awaiting it, so a cold start tapping "Create new game"
     * or "Join game" could otherwise race ahead of it. Used by creator and player flows alike
     * — anonymous auth doesn't distinguish the two, only which uid ends up in which field.
     */
    private suspend fun awaitUid(): String {
        auth.currentUser?.uid?.let { return it }
        return suspendCancellableCoroutine { continuation ->
            lateinit var listener: FirebaseAuth.AuthStateListener
            listener = FirebaseAuth.AuthStateListener { state ->
                val uid = state.currentUser?.uid
                if (uid != null && continuation.isActive) {
                    continuation.resume(uid)
                    state.removeAuthStateListener(listener)
                }
            }
            auth.addAuthStateListener(listener)
            continuation.invokeOnCancellation { auth.removeAuthStateListener(listener) }
        }
    }

    /**
     * Section 5.2: naming is now the lazy-creation trigger instead of the first post save,
     * since My games lists drafts by title and the title has to exist before it can show up
     * there. [title] is expected pre-trimmed and non-blank; the caller (CreateGameViewModel)
     * enforces that.
     */
    suspend fun createDraftGame(title: String = ""): Game {
        val creatorUid = awaitUid()
        val docRef = gamesCollection().document()
        val fields = mapOf(
            "code" to "",
            "title" to title,
            "creatorUid" to creatorUid,
            "status" to GameStatus.DRAFT.wireValue,
            "postCount" to 0,
            "scoredPostCount" to 0,
            "defaultRadiusMeters" to Post.DEFAULT_RADIUS_METERS,
            "createdAt" to FieldValue.serverTimestamp(),
            "publishedAt" to null
        )
        docRef.set(fields).awaitResult()
        return Game(id = docRef.id, creatorUid = creatorUid, title = title)
    }

    /** Renames a draft still missing its title, e.g. one created before naming was required. */
    suspend fun renameDraftGame(gameId: String, title: String) {
        gamesCollection().document(gameId).update("title", title).awaitResult()
    }

    /**
     * Persists one post and its clues. [gameId] is expected non-null now that naming creates
     * the draft game up front (see [createDraftGame]); the null-gameId path is kept only as a
     * defensive fallback, creating an untitled draft rather than crashing.
     * [postCount]/[scoredPostCount] are supplied by the caller, which already knows the
     * post-save total locally — cheaper than a second read here.
     *
     * The clue subcollection is replaced wholesale (old docs deleted, new ones written)
     * rather than diffed: clues stay as individual documents per section 8, and at this
     * scale (a handful per post) a full replace is simpler and the extra writes are the
     * "irrelevant at this scale" cost section 15.2 already accepts.
     */
    suspend fun saveDraftPost(
        gameId: String?,
        post: Post,
        postCount: Int,
        scoredPostCount: Int
    ): SavePostResult {
        val resolvedGameId = gameId ?: createDraftGame().id
        val postsRef = postsCollection(resolvedGameId)
        val postDocRef = if (post.id.isBlank()) postsRef.document() else postsRef.document(post.id)

        val batch = firestore.batch()
        if (post.id.isNotBlank()) {
            val existingClues = cluesCollection(resolvedGameId, post.id).get().awaitResult()
            existingClues.documents.forEach { batch.delete(it.reference) }
        }
        val savedClues = post.clues.map { clue ->
            val clueRef = cluesCollection(resolvedGameId, postDocRef.id).document()
            batch.set(clueRef, clue.toFieldMap())
            clue.copy(id = clueRef.id)
        }
        val savedPost = post.copy(id = postDocRef.id, clues = savedClues)
        batch.set(postDocRef, savedPost.toFieldMap())
        batch.update(
            gamesCollection().document(resolvedGameId),
            mapOf("postCount" to postCount, "scoredPostCount" to scoredPostCount)
        )
        batch.commit().awaitResult()

        return SavePostResult(gameId = resolvedGameId, post = savedPost)
    }

    /**
     * Deletes a post and its clue subcollection, and re-indexes whatever remains so
     * 0..n-1 stays contiguous — the same shift the local ViewModel state already applies,
     * mirrored here so the two never drift apart.
     */
    suspend fun deleteDraftPost(gameId: String, deletedPostId: String, remainingPosts: List<Post>) {
        val batch = firestore.batch()
        val deletedClues = cluesCollection(gameId, deletedPostId).get().awaitResult()
        deletedClues.documents.forEach { batch.delete(it.reference) }
        batch.delete(postsCollection(gameId).document(deletedPostId))
        remainingPosts.forEach { post ->
            batch.update(postsCollection(gameId).document(post.id), "index", post.index)
        }
        batch.update(
            gamesCollection().document(gameId),
            mapOf(
                "postCount" to remainingPosts.size,
                "scoredPostCount" to (remainingPosts.size - 1).coerceAtLeast(0)
            )
        )
        batch.commit().awaitResult()
    }

    /** Full round trip for resuming a draft: the game doc, then every post with its clues. */
    suspend fun loadDraftGame(gameId: String): Pair<Game, List<Post>> {
        val gameSnapshot = gamesCollection().document(gameId).get().awaitResult()
        val game = gameSnapshot.toGame()
        val postDocs = postsCollection(gameId).get().awaitResult()
        val posts = postDocs.documents
            .map { doc ->
                val clueDocs = cluesCollection(gameId, doc.id).get().awaitResult()
                val clues = clueDocs.documents.map { it.toClue() }.sortedBy { it.index }
                doc.toPost(clues)
            }
            .sortedBy { it.index }
        return game to posts
    }

    /**
     * One-shot get(), not a listener: nothing in v1 needs My games to update live while
     * open, and a creator editing their own draft elsewhere is the least likely case in the
     * whole app to need that. See section 15.2. Both statuses: My games lists drafts and
     * published games alike (section 5.1).
     */
    suspend fun loadMyGames(): List<Game> {
        val creatorUid = awaitUid()
        val snapshot = gamesCollection()
            .whereEqualTo("creatorUid", creatorUid)
            .get()
            .awaitResult()
        return snapshot.documents.map { it.toGame() }.sortedByDescending { it.createdAt ?: 0L }
    }

    /**
     * Walks the whole document tree before deleting the game itself, since Firestore does not
     * cascade deletes to subcollections: clues, then posts, then sessions, then the game
     * document. A published game's `gameCodes/{CODE}` entry goes in the same batch, or the
     * code stays resolvable and a player joining it lands on a game that no longer exists.
     * See section 5.1 and AC-21.
     */
    suspend fun deleteGame(game: Game) {
        val gameId = game.id
        val batch = firestore.batch()

        val postDocs = postsCollection(gameId).get().awaitResult()
        for (postDoc in postDocs.documents) {
            val clueDocs = cluesCollection(gameId, postDoc.id).get().awaitResult()
            clueDocs.documents.forEach { batch.delete(it.reference) }
            batch.delete(postDoc.reference)
        }

        val sessionDocs = sessionsCollection(gameId).get().awaitResult()
        sessionDocs.documents.forEach { batch.delete(it.reference) }

        batch.delete(gamesCollection().document(gameId))

        if (game.status == GameStatus.PUBLISHED && game.code.isNotBlank()) {
            batch.delete(gameCodesCollection().document(game.code))
        }

        batch.commit().awaitResult()
    }

    /**
     * Section 5.2's publish step and section 7's code generation. Code generation and the
     * uniqueness check must be one atomic unit — a transaction that reads `gameCodes/{CODE}`
     * and, only if it's unclaimed, creates it and flips the game to published in the same
     * commit — or two publishes racing the same candidate could both believe they won it.
     * On a collision the transaction throws and rolls back cleanly with nothing written, so
     * retrying with a fresh candidate is safe; [GameCodeGenerator.MAX_GENERATION_ATTEMPTS]
     * bounds that per section 7.
     *
     * Validation (AC-1, the 10-clue cap) is the caller's job — this trusts the ViewModel
     * already checked, the same way [saveDraftPost] trusts the clue list it's handed.
     */
    suspend fun publishGame(gameId: String): Game {
        val gameRef = gamesCollection().document(gameId)
        repeat(GameCodeGenerator.MAX_GENERATION_ATTEMPTS) {
            val candidate = GameCodeGenerator.generate()
            val codeRef = gameCodesCollection().document(candidate)
            try {
                firestore.runTransaction { transaction ->
                    if (transaction.get(codeRef).exists()) throw GameCodeCollisionException()
                    transaction.set(codeRef, mapOf("gameId" to gameId))
                    transaction.update(
                        gameRef,
                        mapOf(
                            "code" to candidate,
                            "status" to GameStatus.PUBLISHED.wireValue,
                            "publishedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }.awaitResult()
                return gameRef.get().awaitResult().toGame()
            } catch (e: GameCodeCollisionException) {
                // Candidate taken; loop again with a fresh one.
            }
        }
        throw GameCodeGenerationFailedException()
    }

    /**
     * Section 5.3's join step. Deliberately never reads `games/{gameId}` here — per section 8,
     * a player must hold a session before the game document becomes readable to them at all,
     * so reading it here (before one exists) would just be denied by the rules this is meant
     * to satisfy. `gameCodes/{CODE}` is the one collection a signed-in user can always read,
     * so that's the whole lookup: it hands back a gameId, and everything past that is a write.
     *
     * A pre-existing session is resumed, not replaced — [displayName] updates it if the player
     * changed their name, but progress fields are untouched. [GameDeleted] surfaces from the
     * *create* path only: it's the rules' own `get()` check on the game's status rejecting the
     * write, which is the signal available for an orphaned code (this shouldn't happen, since
     * deleting a game deletes its code in the same operation — section 5.1 — but a session
     * that already exists implies the game was published at some point, so only a brand new
     * session's create can actually hit this).
     */
    suspend fun joinGame(code: String, displayName: String): JoinOutcome {
        val codeDoc = gameCodesCollection().document(code).get().awaitResult()
        val gameId = codeDoc.getString("gameId") ?: return JoinOutcome.UnknownCode

        val uid = awaitUid()
        val sessionRef = sessionsCollection(gameId).document(uid)
        val existing = sessionRef.get().awaitResult()

        return try {
            if (existing.exists()) {
                val existingSession = existing.toSession()
                if (existingSession.displayName != displayName) {
                    sessionRef.update("displayName", displayName).awaitResult()
                }
                if (existingSession.status == SessionStatus.FINISHED) {
                    JoinOutcome.AlreadyFinished(gameId)
                } else {
                    JoinOutcome.Joined(gameId)
                }
            } else {
                sessionRef.set(newSessionFields(uid, displayName)).awaitResult()
                JoinOutcome.Joined(gameId)
            }
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                JoinOutcome.GameDeleted
            } else {
                throw e
            }
        }
    }

    /**
     * The "offer restart" path from section 5.3: same session document, progress zeroed.
     * [displayName] is whatever the player is submitting right now, the same as a normal
     * resume — not whatever the finished session happened to hold, which could be stale by
     * the time the player accepts the restart offer.
     */
    suspend fun restartSession(gameId: String, displayName: String) {
        val uid = awaitUid()
        sessionsCollection(gameId).document(uid).set(newSessionFields(uid, displayName)).awaitResult()
    }

    private fun newSessionFields(uid: String, displayName: String): Map<String, Any?> = mapOf(
        "playerUid" to uid,
        "displayName" to displayName,
        "status" to SessionStatus.ACTIVE.wireValue,
        "currentPostIndex" to 0,
        "cluesOpenedForCurrentPost" to 0,
        "postScores" to emptyMap<String, Double>(),
        "totalScore" to 0.0,
        "startedAt" to FieldValue.serverTimestamp(),
        "finishedAt" to null
    )
}
