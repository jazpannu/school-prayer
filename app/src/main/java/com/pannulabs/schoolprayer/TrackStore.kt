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

    /** Files bundled inside the app (res/raw). Shown on first launch. */
    private fun builtIns(): MutableList<Track> = mutableListOf(
        Track(rawUri(R.raw.jan_gan_man), "Jan Gan Man"),
        Track(rawUri(R.raw.vande_mataram), "Vande Mataram"),
        Track(rawUri(R.raw.mujhe_is_duniya_me_laaya), "Mujhe is Duniya me laaya"),
        Track(rawUri(R.raw.subah_savere), "Subah Savere lete tera naam"),
    )

    private fun rawUri(resId: Int) = "android.resource://${context.packageName}/$resId"

    fun load(): MutableList<Track> {
        val raw = prefs.getString(KEY, null) ?: return builtIns().also { save(it) }
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Track(o.getString("uri"), o.getString("name"), o.optBoolean("fav", false))
            }
        } catch (e: Exception) {
            mutableListOf()
        }
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
    }
}
