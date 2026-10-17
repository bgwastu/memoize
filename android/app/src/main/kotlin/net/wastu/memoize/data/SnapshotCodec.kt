package net.wastu.memoize.data

import org.json.JSONArray
import org.json.JSONObject

internal object SnapshotCodec {
    fun attachments(values: List<NoteAttachment>): String = JSONArray(values.map(::attachment)).toString()
    fun attachments(json: String): List<NoteAttachment> = JSONArray(json).let { array ->
        (0 until array.length()).map { attachment(array.getJSONObject(it)) }
    }
    fun tags(values: List<String>): String = JSONArray(values).toString()
    fun tags(json: String): List<String> = JSONArray(json).let { array ->
        (0 until array.length()).map { array.getString(it) }
    }
    fun location(value: NoteLocation?): String? = value?.let {
        JSONObject().apply {
            put("placeholder", it.placeholder)
            put("latitude", it.latitude)
            put("longitude", it.longitude)
        }.toString()
    }
    fun location(json: String?): NoteLocation? = json?.takeIf { it.isNotBlank() }?.let { raw ->
        runCatching {
            val obj = JSONObject(raw)
            NoteLocation(
                obj.getString("placeholder"),
                obj.getDouble("latitude"),
                obj.getDouble("longitude")
            )
        }.getOrNull()
    }
    fun snapshot(value: MemoSnapshot): String = JSONObject().apply {
        put("remoteName", value.remoteName)
        put("content", value.content)
        put("createdAt", value.createdAt)
        put("updatedAt", value.updatedAt)
        put("state", value.state.name)
        put("visibility", value.visibility.name)
        put("pinned", value.pinned)
        value.location?.let { loc ->
            put("location", JSONObject().apply {
                put("placeholder", loc.placeholder)
                put("latitude", loc.latitude)
                put("longitude", loc.longitude)
            })
        }
        put("attachments", JSONArray(attachments(value.attachments)))
    }.toString()
    fun snapshot(json: String): MemoSnapshot = JSONObject(json).let {
        val locObj = it.optJSONObject("location")
        val loc = locObj?.let { obj ->
            NoteLocation(
                obj.getString("placeholder"),
                obj.getDouble("latitude"),
                obj.getDouble("longitude")
            )
        }
        MemoSnapshot(
            nullable(it, "remoteName"), it.getString("content"), it.getLong("createdAt"),
            it.getLong("updatedAt"), NoteState.valueOf(it.getString("state")),
            NoteVisibility.valueOf(it.getString("visibility")), attachments(it.getJSONArray("attachments").toString()),
            pinned = it.optBoolean("pinned", false),
            location = loc,
        )
    }
    private fun attachment(value: NoteAttachment): JSONObject = JSONObject().apply {
        put("id", value.id)
        put("remoteName", value.remoteName)
        put("filename", value.filename)
        put("mimeType", value.mimeType)
        put("size", value.size)
        put("localPath", value.localPath)
        put("externalLink", value.externalLink)
        put("uploaded", value.uploaded)
    }
    private fun attachment(value: JSONObject): NoteAttachment = NoteAttachment(
        value.getString("id"), nullable(value, "remoteName"), value.getString("filename"),
        value.getString("mimeType"), value.getLong("size"), nullable(value, "localPath"),
        nullable(value, "externalLink"), value.getBoolean("uploaded"),
    )
    private fun nullable(value: JSONObject, key: String): String? =
        if (value.isNull(key) || !value.has(key)) null else value.getString(key)
}
