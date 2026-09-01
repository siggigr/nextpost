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
import `is`.siggi.nextpost.data.model.Clue
import `is`.siggi.nextpost.data.model.Game
import `is`.siggi.nextpost.data.model.GameStatus
import `is`.siggi.nextpost.data.model.Post
import `is`.siggi.nextpost.data.model.PostResult
import `is`.siggi.nextpost.data.model.Session
import `is`.siggi.nextpost.data.model.SessionStatus
import `is`.siggi.nextpost.domain.GameCodeGenerator
import `is`.siggi.nextpost.domain.ScoreCalculator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class SavePostResult(val gameId: String, val post: Post)

/**
 * M6's completion breakdown for one scored post: [PostResult]'s stored [PostResult.score] and
 * [PostResult.cluesOpened] (the free clue plus however many extras), alongside [totalClues] from
 * the post itself, which the session doesn't carry.
 */
data class PostScoreBreakdown(
    val postIndex: Int,
    val cluesOpened: Int,
    val totalClues: Int,
    val score: Double
)

/**
 * Section 5.3's "Game complete" screen. [maxPossibleScore] is `scoredPostCount * 100` per
 * section 4. [elapsedMillis] is recorded for display only — section 6/4 are explicit that time
 * never affects score.
 */
data class GameCompletionSummary(
    val totalScore: Double,
    val maxPossibleScore: Double,
    val elapsedMillis: Long,
    val breakdown: List<PostScoreBreakdown>
)

/**
 * M5's play-screen load: the game (for [Game.scoredPostCount], to know when play ends), the
 * player's own session, and — for [Active] — whichever post they're currently hunting for.
 * [Completed] is what a resumed-after-finishing load lands on instead (AC-7's resume applies to
 * a finished game too): there is no target post past the end of the route to load, so this
 * carries the completion summary in its place.
 */
sealed interface PlayState {
    data class Active(val game: Game, val session: Session, val target: Post) : PlayState
    data class Completed(val session: Session, val summary: GameCompletionSummary) : PlayState
}

/**
 * Section 5.3's arrival outcome, from [GameRepository.recordArrival]. [nextTarget] is null only
 * when [gameFinished] is true — there is no post past the last scored one to load. [completionSummary]
 * is non-null exactly when [gameFinished] is true.
 */
data class ArrivalResult(
    val session: Session,
    val nextTarget: Post?,
    val awardedScore: Double,
    val isStartPost: Boolean,
    val gameFinished: Boolean,
    val completionSummary: GameCompletionSummary? = null
)

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
 * ViewModel calls this, never Firebase directly. Publish and join are M4 scope; [loadPlayState],
 * [openNextClue] and [recordArrival] are the M5 play loop that writes session progress
 * (currentPostIndex, cluesOpenedForCurrentPost, scores).
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
     * cascade deletes to subcollections: clues, then posts, then sessions (and each session's
     * archived `attempts` — section 13.1's restart history would otherwise be orphaned exactly
     * like the sessions it hangs off), then the game document. A published game's
     * `gameCodes/{CODE}` entry goes in the same batch, or the code stays resolvable and a
     * player joining it lands on a game that no longer exists. See section 5.1 and AC-21.
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
        for (sessionDoc in sessionDocs.documents) {
            val attemptDocs = sessionDoc.reference.collection(FirestorePaths.ATTEMPTS).get().awaitResult()
            attemptDocs.documents.forEach { batch.delete(it.reference) }
            batch.delete(sessionDoc.reference)
        }

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
     * The "offer restart" path from section 5.3, and section 13.1's decision that a restart
     * must not destroy the attempt it replaces: the finished session is archived to
     * `sessions/{uid}/attempts/{attemptId}` — its fields copied verbatim, so `totalScore`,
     * `postScores`, `startedAt` and `finishedAt` all survive intact for a future leaderboard —
     * and only then does the live `sessions/{uid}` document reset. Both writes are one batch
     * commit, so either both land or neither does: a dropped write can never archive the score
     * and fail to reset, or reset the session and silently lose the score it was holding.
     * [displayName] is whatever the player is submitting right now, the same as a normal
     * resume — not whatever the finished session happened to hold, which could be stale by
     * the time the player accepts the restart offer.
     */
    suspend fun restartSession(gameId: String, displayName: String) {
        val uid = awaitUid()
        val sessionRef = sessionsCollection(gameId).document(uid)
        val existing = sessionRef.get().awaitResult()
        val existingAttemptNumber = (existing.getLong("attemptNumber") ?: 1L).toInt()

        val batch = firestore.batch()
        val attemptRef = sessionRef.collection(FirestorePaths.ATTEMPTS).document()
        batch.set(attemptRef, existing.data ?: emptyMap<String, Any?>())
        batch.set(sessionRef, newSessionFields(uid, displayName, attemptNumber = existingAttemptNumber + 1))
        batch.commit().awaitResult()
    }

    /**
     * The post the player is currently hunting for, identified by [index] rather than a
     * document id the caller doesn't have yet. The posts query's own `whereEqualTo("index", ...)`
     * mirrors `canPlayerReadPost`'s `postIndex <= session.currentPostIndex` closely enough
     * (we only ever ask for the current target, i.e. `index == currentPostIndex`) that
     * Firestore can prove the query safe. The clue subcollection read needs the same
     * treatment, and more literally: `canPlayerReadClue`'s rule is genuinely per-document
     * (`clue.index <= cluesOpenedForCurrentPost` varies clue to clue), so a plain unfiltered
     * `.get()` here isn't just unproven, it was measured denying the whole list outright with
     * PERMISSION_DENIED — Firestore won't silently filter a list query down to the allowed
     * subset unless the query's own filters already guarantee every possible result would
     * pass. `whereLessThanOrEqualTo("index", maxClueIndex)` is what makes that provable, the
     * same way [loadDraftGame]'s unfiltered clue read gets away with no filter at all: that
     * one is gated on `isGameOwner`, which is constant across every document in the
     * collection, not data-dependent like this one.
     */
    private suspend fun loadPostByIndex(gameId: String, index: Int, maxClueIndex: Int): Post {
        val postDocs = postsCollection(gameId).whereEqualTo("index", index).limit(1).get().awaitResult()
        val doc = postDocs.documents.firstOrNull()
            ?: error("No post at index $index for game $gameId")
        val clueDocs = cluesCollection(gameId, doc.id)
            .whereLessThanOrEqualTo("index", maxClueIndex)
            .get()
            .awaitResult()
        val clues = clueDocs.documents.map { it.toClue() }.sortedBy { it.index }
        return doc.toPost(clues)
    }

    /**
     * M5's play-screen load, and the whole answer to AC-7's resume: this is always a fresh
     * read from Firestore, never trusted from in-memory state, so "the app was killed and
     * reopened" isn't a special case — it's just this method running again after a fresh join
     * (which resumes the existing session untouched, per [joinGame]'s doc).
     */
    suspend fun loadPlayState(gameId: String): PlayState {
        val uid = awaitUid()
        val game = gamesCollection().document(gameId).get().awaitResult().toGame()
        val session = sessionsCollection(gameId).document(uid).get().awaitResult().toSession()
        if (session.status == SessionStatus.FINISHED) {
            // finishedAt is resolved by now (it was written via serverTimestamp() when the game
            // finished and this is always a fresh read), unlike the in-memory value recordArrival
            // returns at the moment of finishing — see buildCompletionSummary's other call site.
            val elapsedMillis = ((session.finishedAt ?: System.currentTimeMillis()) - (session.startedAt ?: 0L))
                .coerceAtLeast(0L)
            return PlayState.Completed(session, buildCompletionSummary(gameId, game, session, elapsedMillis))
        }
        val target = loadPostByIndex(gameId, session.currentPostIndex, session.cluesOpenedForCurrentPost)
        return PlayState.Active(game, session, target)
    }

    /**
     * Shared by [loadPlayState]'s resume path and [recordArrival]'s just-finished path. `score`
     * and `cluesOpened` both come straight from [Session.postScores] — [recordArrival] writes
     * both at arrival time, so there's nothing to derive here. Posts are still fetched, for
     * `clueCount` (the "of N clues" half of the breakdown), which the session doesn't carry.
     *
     * `whereLessThanOrEqualTo("index", session.currentPostIndex)`, not a plain get() — the same
     * reason as [loadPostByIndex]'s doc: `canPlayerReadPost`'s rule is per-document
     * (`resource.data.index <= session.currentPostIndex`), so Firestore can only prove an
     * unfiltered list query safe for the game owner (whose rule branch is constant across every
     * document), not for a player. The filter is what makes it provable here — by the time a
     * session is finished, `currentPostIndex` has advanced past every real post index, so this
     * still returns the whole route without the query being rejected outright.
     */
    private suspend fun buildCompletionSummary(
        gameId: String,
        game: Game,
        session: Session,
        elapsedMillis: Long
    ): GameCompletionSummary {
        val postDocs = postsCollection(gameId)
            .whereLessThanOrEqualTo("index", session.currentPostIndex)
            .get()
            .awaitResult()
        val breakdown = postDocs.documents
            .map { it.toPost(emptyList()) }
            .filter { it.index >= 1 }
            .sortedBy { it.index }
            .map { post ->
                val result = session.postScores[post.index.toString()] ?: PostResult()
                PostScoreBreakdown(
                    postIndex = post.index,
                    cluesOpened = result.cluesOpened,
                    totalClues = post.clueCount,
                    score = result.score
                )
            }
        return GameCompletionSummary(
            totalScore = session.totalScore,
            maxPossibleScore = game.scoredPostCount * ScoreCalculator.MAX_POINTS,
            elapsedMillis = elapsedMillis,
            breakdown = breakdown
        )
    }

    /**
     * Section 5.3's clue reveal, after the player has already accepted the confirmation
     * naming its cost. Writes the incremented count first, then re-reads the clue
     * subcollection rather than appending the new clue text locally — the rules (section 8)
     * are the actual source of truth for which clues are visible, so this keeps the client
     * from ever displaying a clue it wasn't just handed permission to read.
     */
    suspend fun openNextClue(gameId: String, postId: String, currentExtraOpened: Int): List<Clue> {
        val uid = awaitUid()
        val newExtraOpened = currentExtraOpened + 1
        sessionsCollection(gameId).document(uid)
            .update("cluesOpenedForCurrentPost", newExtraOpened)
            .awaitResult()
        // whereLessThanOrEqualTo, not a plain get() — see loadPostByIndex's doc for why an
        // unfiltered list query against this same per-document rule gets denied outright.
        val clueDocs = cluesCollection(gameId, postId)
            .whereLessThanOrEqualTo("index", newExtraOpened)
            .get()
            .awaitResult()
        return clueDocs.documents.map { it.toClue() }.sortedBy { it.index }
    }

    /**
     * Section 5.3's arrival path, shared by automatic polling and the manual "I think I'm
     * here" fallback — both call this once [is.siggi.nextpost.domain.ProximityChecker] has
     * already confirmed arrival; this trusts that and only writes the result.
     *
     * Post 0 (the start post, section 3's model notes) scores nothing and only unlocks post 1.
     * Every other post is scored via [ScoreCalculator] and folded into
     * [Session.postScores]/[Session.totalScore]. Reaching the last scored post
     * (`target.index == game.scoredPostCount`) finishes the session instead of attempting to
     * load a post past the end of the route.
     */
    suspend fun recordArrival(gameId: String, game: Game, session: Session, target: Post): ArrivalResult {
        val uid = awaitUid()
        val isStartPost = target.index == 0
        // The free clue plus whatever extras were open when this post was reached — captured
        // now because it's not derivable later: inverting the score against the floor is
        // ambiguous once more than one clue count can land on the same floored score, which the
        // 10-clue cap only happens to avoid today. See section 3's model notes.
        val cluesOpened = session.cluesOpenedForCurrentPost + 1
        val awardedScore = if (isStartPost) {
            0.0
        } else {
            ScoreCalculator.scoreForPost(target.clueCount, session.cluesOpenedForCurrentPost)
        }
        val nextIndex = target.index + 1
        val gameFinished = !isStartPost && target.index == game.scoredPostCount

        val updates = mutableMapOf<String, Any?>(
            "currentPostIndex" to nextIndex,
            "cluesOpenedForCurrentPost" to 0
        )
        if (!isStartPost) {
            updates["postScores.${target.index}"] = mapOf(
                "score" to awardedScore,
                "cluesOpened" to cluesOpened
            )
            updates["totalScore"] = session.totalScore + awardedScore
        }
        if (gameFinished) {
            updates["status"] = SessionStatus.FINISHED.wireValue
            updates["finishedAt"] = FieldValue.serverTimestamp()
        }
        sessionsCollection(gameId).document(uid).update(updates).awaitResult()

        val updatedSession = session.copy(
            currentPostIndex = nextIndex,
            cluesOpenedForCurrentPost = 0,
            postScores = if (isStartPost) {
                session.postScores
            } else {
                session.postScores + (target.index.toString() to PostResult(awardedScore, cluesOpened))
            },
            totalScore = if (isStartPost) session.totalScore else session.totalScore + awardedScore,
            status = if (gameFinished) SessionStatus.FINISHED else session.status
        )
        // The freshly-advanced post always starts at 0 extra clues opened — see the
        // "cluesOpenedForCurrentPost" to 0 write above, which this mirrors.
        val nextTarget = if (gameFinished) null else loadPostByIndex(gameId, nextIndex, maxClueIndex = 0)

        // finishedAt above is a serverTimestamp() write, unresolved locally until the next fresh
        // read (see loadPlayState's resume path) — client time is close enough for a completion
        // screen shown seconds after this call, and elapsed time never affects score anyway.
        val completionSummary = if (gameFinished) {
            val elapsedMillis = (System.currentTimeMillis() - (session.startedAt ?: System.currentTimeMillis()))
                .coerceAtLeast(0L)
            buildCompletionSummary(gameId, game, updatedSession, elapsedMillis)
        } else {
            null
        }

        return ArrivalResult(
            session = updatedSession,
            nextTarget = nextTarget,
            awardedScore = awardedScore,
            isStartPost = isStartPost,
            gameFinished = gameFinished,
            completionSummary = completionSummary
        )
    }

    private fun newSessionFields(uid: String, displayName: String, attemptNumber: Int = 1): Map<String, Any?> = mapOf(
        "playerUid" to uid,
        "displayName" to displayName,
        "status" to SessionStatus.ACTIVE.wireValue,
        "currentPostIndex" to 0,
        "cluesOpenedForCurrentPost" to 0,
        "postScores" to emptyMap<String, PostResult>(),
        "totalScore" to 0.0,
        "startedAt" to FieldValue.serverTimestamp(),
        "finishedAt" to null,
        "attemptNumber" to attemptNumber
    )
}
