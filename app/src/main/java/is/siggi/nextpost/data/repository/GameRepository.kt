package `is`.siggi.nextpost.data.repository

import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestore
import `is`.siggi.nextpost.data.firebase.FirestorePaths
import `is`.siggi.nextpost.data.firebase.awaitResult
import `is`.siggi.nextpost.data.firebase.toClue
import `is`.siggi.nextpost.data.firebase.toFieldMap
import `is`.siggi.nextpost.data.firebase.toGame
import `is`.siggi.nextpost.data.firebase.toPost
import `is`.siggi.nextpost.data.model.Game
import `is`.siggi.nextpost.data.model.GameStatus
import `is`.siggi.nextpost.data.model.Post
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class SavePostResult(val gameId: String, val post: Post)

/**
 * Owns all Firestore access for games/posts/clues, per section 2's architecture: the
 * ViewModel calls this, never Firebase directly. M3 scope only — publishing, codes and
 * sessions are M4/M5.
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
     * fires signInAnonymously() without awaiting it, so a creator tapping "Create new game"
     * on a cold start could otherwise race ahead of it.
     */
    private suspend fun awaitCreatorUid(): String {
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
        val creatorUid = awaitCreatorUid()
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
        val creatorUid = awaitCreatorUid()
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
}
