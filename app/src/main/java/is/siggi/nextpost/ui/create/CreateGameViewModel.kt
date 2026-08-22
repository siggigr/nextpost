package `is`.siggi.nextpost.ui.create

import `is`.siggi.nextpost.data.model.Clue
import `is`.siggi.nextpost.data.model.Post
import `is`.siggi.nextpost.domain.ClueValidator
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Section 5.2's post edit mode. [targetIndex] is the index the post has (when editing) or
 * will get on save (when adding, this is fixed at entry to `posts.size` rather than
 * recomputed live, so a mid-edit delete elsewhere can't silently retarget the draft).
 */
sealed interface CreateScreenMode {
    data object Overview : CreateScreenMode

    data class PostEditor(
        val editingIndex: Int?,
        val targetIndex: Int,
        val lat: Double,
        val lng: Double,
        val locationConfirmed: Boolean,
        val clues: List<Clue>
    ) : CreateScreenMode
}

sealed interface CreateGameValidation {
    data object Valid : CreateGameValidation
    data object TooFewPosts : CreateGameValidation
    data class InsufficientClues(val postIndex: Int, val clueCount: Int) : CreateGameValidation
}

data class CreateGameUiState(
    val posts: List<Post> = emptyList(),
    val mode: CreateScreenMode = CreateScreenMode.Overview,
    val selectedPostIndex: Int? = null
) {
    val validation: CreateGameValidation = computeValidation(posts)
}

private fun computeValidation(posts: List<Post>): CreateGameValidation {
    if (posts.size < 2) return CreateGameValidation.TooFewPosts
    val offending = posts
        .filter { it.index != 0 }
        .sortedBy { it.index }
        .map { post -> post.index to ClueValidator.validate(post.clues.map { it.text }) }
        .firstOrNull { (_, result) -> !result.isValid }
    return if (offending != null) {
        val (postIndex, result) = offending
        CreateGameValidation.InsufficientClues(postIndex, result.nonBlankCount)
    } else {
        CreateGameValidation.Valid
    }
}

/** Local-only for M2: nothing here persists. Firestore round-trip lands in M3. */
class CreateGameViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(CreateGameUiState())
    val uiState: StateFlow<CreateGameUiState> = _uiState.asStateFlow()

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
                    lat = post.lat,
                    lng = post.lng,
                    locationConfirmed = true,
                    clues = post.clues
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

    fun cancelPostEditor() {
        _uiState.update { it.copy(mode = CreateScreenMode.Overview) }
    }

    fun savePost() {
        _uiState.update { state ->
            val editor = state.mode as? CreateScreenMode.PostEditor ?: return@update state
            if (!editor.locationConfirmed) return@update state
            // Sanitize before persisting: a blank clue must never count towards the 3-clue
            // minimum, here or at publish time. Same rule ClueValidator applies in the editor.
            val savedClues = ClueValidator.sanitize(editor.clues.map { it.text })
                .mapIndexed { newIndex, text -> Clue(index = newIndex, text = text) }
            val savedPost = Post(
                index = editor.targetIndex,
                lat = editor.lat,
                lng = editor.lng,
                clues = savedClues
            )
            val posts = if (editor.editingIndex != null) {
                state.posts.map { if (it.index == editor.editingIndex) savedPost else it }
            } else {
                state.posts + savedPost
            }
            state.copy(posts = posts, mode = CreateScreenMode.Overview, selectedPostIndex = null)
        }
    }

    /** Re-indexes the remaining posts so 0..n-1 stays contiguous and post 0 stays the start. */
    fun deleteSelectedPost() {
        _uiState.update { state ->
            val index = state.selectedPostIndex ?: return@update state
            val posts = state.posts
                .filter { it.index != index }
                .sortedBy { it.index }
                .mapIndexed { newIndex, post -> post.copy(index = newIndex) }
            state.copy(posts = posts, selectedPostIndex = null)
        }
    }

    fun addClue(text: String) = updateClues { clues -> clues + Clue(index = clues.size, text = text) }

    fun updateClueText(clueIndex: Int, text: String) = updateClues { clues ->
        clues.map { if (it.index == clueIndex) it.copy(text = text) else it }
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

    private inline fun updateClues(crossinline transform: (List<Clue>) -> List<Clue>) {
        _uiState.update { state ->
            val editor = state.mode as? CreateScreenMode.PostEditor ?: return@update state
            state.copy(mode = editor.copy(clues = transform(editor.clues)))
        }
    }
}
