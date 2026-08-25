package `is`.siggi.nextpost.data.model

/**
 * Index 0 is the free clue, revealed with no score penalty. See section 1 glossary.
 *
 * [id] is this clue's stable identity: a client-generated key while it's still a local draft,
 * overwritten with the real Firestore document id once persisted (see
 * `GameRepository.saveDraftPost`). [index] is display/sort order, not identity — it's
 * reassigned on every reorder and delete, so it can't double as a list key without every
 * shifted row losing its Compose identity (and, mid-edit, its focus) on every such change.
 */
data class Clue(
    val id: String = "",
    val index: Int,
    val text: String
)
