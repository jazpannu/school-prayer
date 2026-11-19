package com.pannulabs.schoolprayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One saved prayer audio file. [uri] is a content:// URI with a persisted read permission. */
data class Track(val uri: String, val name: String, val favorite: Boolean = false)

/** Favourites first, then A–Z. */
fun MutableList<Track>.sortTracks() =
    sortWith(compareByDescending<Track> { it.favorite }.thenBy { it.name.lowercase() })

/** Saves the list of added files on the device so it survives app restarts. */
class TrackStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("prayer_tracks", Context.MODE_PRIVATE)

    /**
     * Files bundled inside the app: file name in res/raw (without .mp3) to display name.
     * To add one, drop the MP3 into res/raw and add a line here. It appears on every phone
     * after the app updates, unless the teacher had removed it from the list.
     */
    private val builtIns = listOf(
        "jan_gan_man" to "Jan Gan Man",
        "vande_mataram" to "Vande Mataram",
        "mujhe_is_duniya_me_laaya" to "Mujhe is Duniya me laaya",
        "subah_savere" to "Subah Savere lete tera naam",
        "sansaar_ke_mali_ne" to "Sansaar ke mali ne",
    )

    /** Name-based URI, so it stays correct when more files are added to res/raw. */
    private fun rawUri(name: String) = "android.resource://${context.packageName}/raw/$name"

    fun load(): MutableList<Track> {
        val tracks = mutableListOf<Track>()
        try {
            prefs.getString(KEY, null)?.let { raw ->
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    tracks += Track(o.getString("uri"), o.getString("name"), o.optBoolean("fav", false))
                }
            }
        } catch (_: Exception) { }

        // Older builds saved built-ins by numeric resource id, which shifts when files are added.
        // Drop those and re-add the built-ins by name, keeping favourites where the name matches.
        val oldFavs = mutableSetOf<String>()
        val legacy = tracks.filter { it.uri.startsWith("android.resource://") && it.uri.substringAfterLast('/').all(Char::isDigit) }
        legacy.filter { it.favorite }.forEach { oldFavs += it.name.lowercase() }
        var changed = tracks.removeAll(legacy.toSet())
        val seen = prefs.getStringSet(KEY_SEEN, emptySet())!!.toMutableSet()
        if (legacy.isNotEmpty()) seen.clear()

        for ((file, title) in builtIns) {
            if (file in seen) continue // already offered once; respect a removal
            val uri = rawUri(file)
            if (tracks.none { it.uri == uri }) {
                tracks += Track(uri, title, title.lowercase() in oldFavs)
            }
            seen += file
            changed = true
        }
        if (changed) {
            prefs.edit().putStringSet(KEY_SEEN, seen).apply()
            save(tracks)
        }
        return tracks
    }

    fun save(tracks: List<Track>) {
        val arr = JSONArray()
        tracks.forEach { arr.put(JSONObject().put("uri", it.uri).put("name", it.name).put("fav", it.favorite)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    /** School name entered on first launch; null until set. */
    var schoolName: String?
        get() = prefs.getString(KEY_SCHOOL, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_SCHOOL, value?.trim()).apply()

    private companion object {
        const val KEY = "tracks"
        const val KEY_SCHOOL = "school_name"
        const val KEY_SEEN = "seen_builtins"
    }
}
