package `is`.siggi.nextpost.ui.create

import `is`.siggi.nextpost.data.model.Clue
import `is`.siggi.nextpost.data.model.GameStatus
import `is`.siggi.nextpost.data.model.Post
import `is`.siggi.nextpost.data.repository.GameRepository
import `is`.siggi.nextpost.domain.ClueValidator
import `is`.siggi.nextpost.ui.common.WriteError
import `is`.siggi.nextpost.ui.common.toWriteError
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Section 5.2's post edit mode. [targetIndex] is the index the post has (when editing) or
 * will get on save (when adding, this is fixed at entry to `posts.size` rather than
 * recomputed live, so a mid-edit delete elsewhere can't silently retarget the draft).
 * [postId] is the Firestore document id once this post has been saved at least once; empty
 * for a post that has never been persisted. [clueEntryStarted] gates validation messaging in
 * the clue editor: false for a freshly seeded blank field so arriving there doesn't read as
 * an error, flipped true by the first real edit (or already true on reopening a post that
 * already has clues).
 */
sealed interface CreateScreenMode {
    /** Blocks the map until the game has a title. See section 5.2 and AC-18. */
    data object Naming : CreateScreenMode

    data object Overview : CreateScreenMode

    data class PostEditor(
        val editingIndex: Int?,
        val targetIndex: Int,
        val postId: String,
        val lat: Double,
        val lng: Double,
        val locationConfirmed: Boolean,
        val clues: List<Clue>,
        val clueEntryStarted: Boolean = false
    ) : CreateScreenMode

    /**
     * The post-publish confirmation (section 5.2): replaces the map entirely rather than
     * overlaying it, since a published game is read-only for the creator from here — there's
     * nothing left on this screen to go back to editing. [code] is shown large with a share
     * sheet.
     */
    data class Published(val code: String) : CreateScreenMode
}

/** 1 to 60 characters once trimmed, per section 5.2 and AC-18. */
const val MAX_GAME_TITLE_LENGTH = 60

sealed interface CreateGameValidation {
    data object Valid : CreateGameValidation
    data object TooFewPosts : CreateGameValidation
    data class InsufficientClues(val postIndex: Int, val clueCount: Int) : CreateGameValidation
    /** Section 4's 10-clue cap, checked again at publish time — see [ClueValidator]. */
    data class TooManyClues(val postIndex: Int, val clueCount: Int) : CreateGameValidation
}

data class CreateGameUiState(
    val gameId: String? = null,
    /** Confirmed title once naming is done; the live text field value while still naming. */
    val titleInput: String = "",
    val posts: List<Post> = emptyList(),
    val mode: CreateScreenMode = CreateScreenMode.Naming,
    /** Always DRAFT for a game not yet loaded from Firestore — a brand-new draft is never published. */
    val gameStatus: GameStatus = GameStatus.DRAFT,
    /** Empty until loaded; a published game's code, for the read-only overview's share sheet — the
     * only way to retrieve it once the one-time post-publish confirmation screen is gone. */
    val gameCode: String = "",
    val selectedPostIndex: Int? = null,
    val isLoadingDraft: Boolean = false,
    val isSaving: Boolean = false,
    val isCreatingDraft: Boolean = false,
    val isPublishing: Boolean = false,
    /** Section 7: "then surfacing an error" — covers retry exhaustion and any other failure. */
    val publishError: WriteError? = null,
    /** A post Save that fails must say so, not just silently drop the creator's work. */
    val saveError: WriteError? = null,
    /** A post delete that fails must say so — and see [CreateGameViewModel.deleteSelectedPost]
     * for why the optimistic local removal is reverted alongside this, not just reported. */
    val deletePostError: WriteError? = null,
    /** Naming/renaming a draft is a write too (createDraftGame/renameDraftGame) and can fail. */
    val namingError: WriteError? = null,
    /**
     * Trimmed, lower-cased titles of this creator's *other* games (this draft's own current
     * title excluded), fetched once for the duplicate-title warning in section 5.2. A warning
     * only — titles aren't identifiers, the game code is, so this never blocks naming.
     */
    val existingTitlesLoaded: Boolean = false,
    val existingTitles: Set<String> = emptySet()
) {
    val validation: CreateGameValidation = computeValidation(posts)

    val isDuplicateTitle: Boolean = titleInput.trim().lowercase().let { normalized ->
        normalized.isNotEmpty() && normalized in existingTitles
    }
}

private fun computeValidation(posts: List<Post>): CreateGameValidation {
    if (posts.size < 2) return CreateGameValidation.TooFewPosts

    val scoredPosts = posts.filter { it.index != 0 }.sortedBy { it.index }

    // Checked ahead of the minimum below: the clue editor already disables Add clue at the
    // cap (section 4), so this is a defensive re-check at publish time, the same way the
    // minimum is re-checked here rather than trusted from the editor alone.
    val tooMany = scoredPosts.firstOrNull { it.clues.size > ClueValidator.MAX_CLUES_PER_POST }
    if (tooMany != null) {
        return CreateGameValidation.TooManyClues(tooMany.index, tooMany.clues.size)
    }

    val offending = scoredPosts
        .map { post -> post.index to ClueValidator.validate(post.clues.map { it.text }) }
        .firstOrNull { (_, result) -> !result.isValid }
    return if (offending != null) {
        val (postIndex, result) = offending
        CreateGameValidation.InsufficientClues(postIndex, result.nonBlankCount)
    } else {
        CreateGameValidation.Valid
    }
}

/**
 * Owns create-flow state as a StateFlow, per section 2's MVVM layering. All Firestore access
 * goes through [repository]; this class never touches Firebase directly.
 */
class CreateGameViewModel(private val repository: GameRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(CreateGameUiState())
    val uiState: StateFlow<CreateGameUiState> = _uiState.asStateFlow()

    /**
     * Resumes an existing draft, e.g. opened from My games. No-ops if already loaded. A draft
     * created before naming was required can still have a blank title; route it back through
     * Naming rather than leaving it permanently untitled with no other way to fix that.
     */
    fun loadDraft(gameId: String) {
        if (_uiState.value.gameId == gameId) return
        _uiState.update { it.copy(isLoadingDraft = true) }
        viewModelScope.launch {
            try {
                val (game, posts) = repository.loadDraftGame(gameId)
                _uiState.update {
                    it.copy(
                        gameId = game.id,
                        titleInput = game.title,
                        posts = posts,
                        gameStatus = game.status,
                        gameCode = game.code,
                        isLoadingDraft = false,
                        mode = if (game.title.isBlank()) CreateScreenMode.Naming else CreateScreenMode.Overview
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingDraft = false) }
            }
        }
    }

    fun updateTitleInput(text: String) {
        _uiState.update { it.copy(titleInput = text.take(MAX_GAME_TITLE_LENGTH)) }
    }

    /**
     * Fetches this creator's own games once, purely to power the duplicate-title warning in
     * section 5.2 — never a global query, and never used to block naming. No-op once loaded;
     * the naming screen calls this on every composition, so this guard is what keeps it a
     * single fetch. The draft being named (if it already has an id, i.e. a rename) is
     * excluded so a creator re-confirming their own unchanged title doesn't warn against
     * itself.
     */
    fun loadExistingTitlesForDuplicateCheck() {
        if (_uiState.value.existingTitlesLoaded) return
        viewModelScope.launch {
            try {
                val ownGameId = _uiState.value.gameId
                val titles = repository.loadMyGames()
                    .asSequence()
                    .filter { it.id != ownGameId }
                    .map { it.title.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                _uiState.update { it.copy(existingTitles = titles, existingTitlesLoaded = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A warning that fails to load should fail quiet, not retry-loop: treat it as
                // "no known duplicates" rather than blocking or repeatedly refetching.
                _uiState.update { it.copy(existingTitlesLoaded = true) }
            }
        }
    }

    /**
     * Confirms the title and, for a brand-new draft, is the lazy-creation trigger the
     * repository doc mentions: the game document is written here rather than at first post
     * save, so a title exists in Firestore the moment it can appear in My games (AC-18).
     */
    fun confirmTitle() {
        val state = _uiState.value
        val trimmed = state.titleInput.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_GAME_TITLE_LENGTH || state.isCreatingDraft) return

        _uiState.update { it.copy(isCreatingDraft = true, namingError = null) }
        viewModelScope.launch {
            try {
                val resolvedGameId = state.gameId
                if (resolvedGameId != null) {
                    repository.renameDraftGame(resolvedGameId, trimmed)
                    _uiState.update {
                        it.copy(titleInput = trimmed, isCreatingDraft = false, mode = CreateScreenMode.Overview)
                    }
                } else {
                    val game = repository.createDraftGame(trimmed)
                    _uiState.update {
                        it.copy(
                            gameId = game.id,
                            titleInput = trimmed,
                            isCreatingDraft = false,
                            mode = CreateScreenMode.Overview
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isCreatingDraft = false, namingError = e.toWriteError()) }
            }
        }
    }

    fun selectPost(index: Int) {
        _uiState.update { state ->
            state.copy(selectedPostIndex = if (state.selectedPostIndex == index) null else index)
        }
    }

    fun beginAddPost(initialLat: Double, initialLng: Double) {
        _uiState.update { state ->
            state.copy(
                mode = CreateScreenMode.PostEditor(
                    editingIndex = null,
                    targetIndex = state.posts.size,
                    postId = "",
                    lat = initialLat,
                    lng = initialLng,
                    locationConfirmed = false,
                    clues = emptyList()
                )
            )
        }
    }

    fun beginEditSelectedPost() {
        val index = _uiState.value.selectedPostIndex ?: return
        _uiState.update { state ->
            val post = state.posts.find { it.index == index } ?: return@update state
            state.copy(
                mode = CreateScreenMode.PostEditor(
                    editingIndex = post.index,
                    targetIndex = post.index,
                    postId = post.id,
                    lat = post.lat,
                    lng = post.lng,
                    locationConfirmed = true,
                    clues = post.clues,
                    clueEntryStarted = post.clues.isNotEmpty()
                )
            )
        }
    }

    fun confirmLocation(lat: Double, lng: Double) {
        _uiState.update { state ->
            val editor = state.mode as? CreateScreenMode.PostEditor ?: return@update state
            state.copy(mode = editor.copy(lat = lat, lng = lng, locationConfirmed = true))
        }
    }

    /**
     * The exact inverse of [confirmLocation]: re-attaches the pin to the map centre instead
     * of a fixed coordinate, so panning moves it again. Not a separate mode, just the same
     * unset state Set started from. See section 5.2 and AC-20.
     */
    fun beginReposition() {
        _uiState.update { state ->
            val editor = state.mode as? CreateScreenMode.PostEditor ?: return@update state
            state.copy(mode = editor.copy(locationConfirmed = false))
        }
    }

    fun cancelPostEditor() {
        _uiState.update { it.copy(mode = CreateScreenMode.Overview) }
    }

    /**
     * Waits for the write to succeed before leaving post-edit mode, unlike delete below.
     * A new post's [CreateScreenMode.PostEditor.postId] stays blank until the repository
     * hands back a real document id; updating local state optimistically here would let a
     * second Save (or a fast Edit-then-Save) fire before that id exists and create a
     * duplicate document instead of updating the first one.
     */
    fun savePost() {
        val state = _uiState.value
        val editor = state.mode as? CreateScreenMode.PostEditor ?: return
        if (!editor.locationConfirmed || state.isSaving) return

        val sanitizedClues = ClueValidator.sanitize(editor.clues.map { it.text })
            .mapIndexed { newIndex, text -> Clue(index = newIndex, text = text) }
        val pendingPost = Post(
            id = editor.postId,
            index = editor.targetIndex,
            lat = editor.lat,
            lng = editor.lng,
            clues = sanitizedClues
        )
        val postCountAfterSave = if (editor.editingIndex != null) {
            state.posts.size
        } else {
            state.posts.size + 1
        }
        val scoredPostCountAfterSave = (postCountAfterSave - 1).coerceAtLeast(0)

        _uiState.update { it.copy(isSaving = true, saveError = null) }
        viewModelScope.launch {
            try {
                val result = repository.saveDraftPost(
                    gameId = state.gameId,
                    post = pendingPost,
                    postCount = postCountAfterSave,
                    scoredPostCount = scoredPostCountAfterSave
                )
                _uiState.update { current ->
                    val posts = if (editor.editingIndex != null) {
                        current.posts.map { if (it.index == editor.editingIndex) result.post else it }
                    } else {
                        current.posts + result.post
                    }
                    current.copy(
                        gameId = result.gameId,
                        posts = posts,
                        mode = CreateScreenMode.Overview,
                        selectedPostIndex = null,
                        isSaving = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A denied write here (e.g. this game was published from another device between
                // opening it and tapping Save) must say so, not discard the creator's post and
                // clues with no explanation — see PostEditorControls' saveError text.
                _uiState.update { it.copy(isSaving = false, saveError = e.toWriteError()) }
            }
        }
    }

    /**
     * Section 5.2's publish step. Trusts [CreateGameUiState.validation], computed from the
     * posts already held locally, rather than re-validating here — the same trust
     * [savePost] places in the clue list it's handed. Guarded on validity and on
     * [CreateGameUiState.isPublishing] so a double-tap can't fire two publishes racing each
     * other for a code.
     */
    fun publishGame() {
        val state = _uiState.value
        val gameId = state.gameId ?: return
        if (state.validation !is CreateGameValidation.Valid || state.isPublishing) return

        _uiState.update { it.copy(isPublishing = true, publishError = null) }
        viewModelScope.launch {
            try {
                val published = repository.publishGame(gameId)
                _uiState.update {
                    it.copy(isPublishing = false, mode = CreateScreenMode.Published(published.code))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Section 7: retries-exhausted and any other failure (permission denial,
                // network) all land here and must actually be visible — a silent reset here
                // is exactly how a broken publish path went undetected before. Classified via
                // toWriteError() rather than a flat message, so a permission denial doesn't
                // read as "check your connection" — see OverviewControls' publishError text.
                _uiState.update { it.copy(isPublishing = false, publishError = e.toWriteError()) }
            }
        }
    }

    /**
     * Updates local state immediately, unlike save above: deleting carries no risk of
     * creating a duplicate document, so there's no reason to make the creator wait for the
     * round trip. The Firestore delete is queued durably in the background regardless.
     *
     * The optimistic removal is reverted on failure, not left in place: a denied delete (e.g.
     * this game was published from another device moments ago) previously left the post gone
     * from the screen forever while the document survived in Firestore — silently wrong local
     * state, worse than just failing loudly. Restoring [previousPosts] keeps the two in sync
     * and re-selects the post so the creator can see what failed and retry.
     */
    fun deleteSelectedPost() {
        val state = _uiState.value
        val index = state.selectedPostIndex ?: return
        val gameId = state.gameId ?: return
        val target = state.posts.find { it.index == index } ?: return
        val previousPosts = state.posts
        val remaining = state.posts
            .filter { it.index != index }
            .sortedBy { it.index }
            .mapIndexed { newIndex, post -> post.copy(index = newIndex) }

        _uiState.update { it.copy(posts = remaining, selectedPostIndex = null, deletePostError = null) }
        viewModelScope.launch {
            try {
                repository.deleteDraftPost(gameId, target.id, remaining)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(posts = previousPosts, selectedPostIndex = index, deletePostError = e.toWriteError())
                }
            }
        }
    }

    /**
     * Seeds one empty clue so the editor never opens on a blank list requiring a tap before
     * typing. Deliberately does not mark entry as started: an untouched seeded field is not
     * an error state. No-op if clues already exist (e.g. Edit). See section 5.2.
     */
    fun ensureClueDraftSeeded() {
        _uiState.update { state ->
            val editor = state.mode as? CreateScreenMode.PostEditor ?: return@update state
            if (editor.clues.isNotEmpty()) return@update state
            state.copy(mode = editor.copy(clues = listOf(Clue(id = UUID.randomUUID().toString(), index = 0, text = ""))))
        }
    }

    fun addClue(text: String) {
        val newId = UUID.randomUUID().toString()
        updateClues(markEntryStarted = true) { clues -> clues + Clue(id = newId, index = clues.size, text = text) }
    }

    fun updateClueText(clueIndex: Int, text: String) {
        updateClues(markEntryStarted = true) { clues ->
            clues.map { if (it.index == clueIndex) it.copy(text = text) else it }
        }
    }

    fun deleteClue(clueIndex: Int) = updateClues { clues ->
        clues.filter { it.index != clueIndex }
            .sortedBy { it.index }
            .mapIndexed { newIndex, clue -> clue.copy(index = newIndex) }
    }

    fun moveClue(fromIndex: Int, toIndex: Int) = updateClues { clues ->
        if (fromIndex !in clues.indices || toIndex !in clues.indices) return@updateClues clues
        val mutable = clues.sortedBy { it.index }.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        mutable.mapIndexed { newIndex, clue -> clue.copy(index = newIndex) }
    }

    private inline fun updateClues(
        markEntryStarted: Boolean = false,
        crossinline transform: (List<Clue>) -> List<Clue>
    ) {
        _uiState.update { state ->
            val editor = state.mode as? CreateScreenMode.PostEditor ?: return@update state
            state.copy(
                mode = editor.copy(
                    clues = transform(editor.clues),
                    clueEntryStarted = editor.clueEntryStarted || markEntryStarted
                )
            )
        }
    }
}
