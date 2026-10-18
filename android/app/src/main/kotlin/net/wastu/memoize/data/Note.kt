package net.wastu.memoize.data

import java.util.UUID

enum class NoteState { NORMAL, ARCHIVED }
enum class NoteVisibility { PRIVATE, PROTECTED, PUBLIC }
enum class AttachmentFilter { ANY, IMAGES, FILES }
enum class NoteSort { NEWEST, OLDEST, UPDATED }
enum class NoteProblem(val code: String) { CONFLICT("CONFLICT") }

data class NoteFilter(
    val query: String = "",
    val tag: String? = null,
    val attachments: AttachmentFilter = AttachmentFilter.ANY,
    val state: NoteState = NoteState.NORMAL,
    val sort: NoteSort = NoteSort.NEWEST,
)

/** UTF-16 offsets, matching TextFieldState and the shared Mac search contract. */
data class TextMatch(val start: Int, val end: Int)

data class EditSnapshot(
    val content: String,
    val selectionStart: Int = 0,
    val selectionEnd: Int = selectionStart,
    val scroll: Int = 0,
    val origin: MemoSnapshot? = null,
)

data class NoteAttachment(
    val id: String = UUID.randomUUID().toString().lowercase(),
    val remoteName: String? = null,
    val filename: String,
    val mimeType: String,
    val size: Long,
    val localPath: String? = null,
    val externalLink: String? = null,
    val uploaded: Boolean = false,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}
data class NoteLocation(
    val placeholder: String,
    val latitude: Double,
    val longitude: Double,
)

/** A durable body/version at the remote API boundary, independent of client clocks. */
data class MemoSnapshot(
    val remoteName: String? = null,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val state: NoteState = NoteState.NORMAL,
    val visibility: NoteVisibility = NoteVisibility.PRIVATE,
    val attachments: List<NoteAttachment> = emptyList(),
    val pinned: Boolean = false,
    val location: NoteLocation? = null,
) {
    fun sameBody(other: MemoSnapshot): Boolean =
        content == other.content && state == other.state && visibility == other.visibility &&
            pinned == other.pinned && location == other.location &&
            attachments.map { it.id }.toSet() == other.attachments.map { it.id }.toSet()
}

data class Note(
    val id: String,
    val account: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val state: NoteState,
    val visibility: NoteVisibility,
    val remoteName: String?,
    val revision: Long,
    val syncedRevision: Long,
    val base: MemoSnapshot?,
    val sent: MemoSnapshot?,
    val sentRevision: Long?,
    val deleteAfter: Long?,
    val error: String?,
    val selectionStart: Int,
    val selectionEnd: Int,
    val scroll: Int,
    val attachments: List<NoteAttachment>,
    val tags: List<String>,
    val problem: NoteProblem? = null,
    val pinned: Boolean = false,
    val location: NoteLocation? = null,
) {
    val hasConflict: Boolean get() = problem == NoteProblem.CONFLICT
    val isDirty: Boolean get() = revision > syncedRevision || deleteAfter != null
    val isBlankDraft: Boolean get() = remoteName == null && content.isBlank() && attachments.isEmpty() && location == null
    val title: String get() = content.lineSequence().firstOrNull { it.isNotBlank() }
        ?.trim()?.take(120) ?: "New note"
    fun snapshot(): MemoSnapshot = MemoSnapshot(
        remoteName, content, createdAt, updatedAt, state, visibility, attachments, pinned, location,
    )
}

/** Committed list fields, without loading editor state or full sync/recovery versions. */
data class NotePreview(
    val id: String,
    val account: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val state: NoteState,
    val visibility: NoteVisibility = NoteVisibility.PRIVATE,
    val remoteName: String?,
    val error: String?,
    val attachments: List<NoteAttachment>,
    val tags: List<String>,
    val problem: NoteProblem? = null,
    val pinned: Boolean = false,
    val location: NoteLocation? = null,
) {
    val hasConflict: Boolean get() = problem == NoteProblem.CONFLICT
    val isBlankDraft: Boolean get() = remoteName == null && content.isBlank() && attachments.isEmpty() && location == null
    val title: String get() = content.lineSequence().firstOrNull { it.isNotBlank() }
        ?.trim()?.take(120) ?: "New note"
}

data class NoteResult(
    val note: NotePreview,
    val excerpt: String,
    val excerptOffset: Int,
    val matches: List<TextMatch>,
) {
    val excerptMatches: List<TextMatch> get() = matches.mapNotNull { match ->
        val start = maxOf(match.start, excerptOffset)
        val end = minOf(match.end, excerptOffset + excerpt.length)
        if (end > start) TextMatch(start - excerptOffset, end - excerptOffset) else null
    }
}

data class RecoveryRevision(
    val id: String,
    val noteId: String,
    val snapshot: MemoSnapshot,
    val reason: String,
    val savedAt: Long,
)

data class PendingOperation(
    val noteId: String,
    val revision: Long,
    val kind: String,
    val attempts: Int,
    val retryAt: Long?,
    val error: String?,
)
