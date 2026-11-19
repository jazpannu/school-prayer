package com.pannulabs.schoolprayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.pannulabs.schoolprayer.databinding.ItemTrackBinding

class TrackAdapter(
    private val onPlay: (Track) -> Unit,
    private val onMore: (Track, View) -> Unit,
    private val onFavorite: (Track) -> Unit,
) : RecyclerView.Adapter<TrackAdapter.Holder>() {

    private var allTracks: List<Track> = emptyList()
    private var shown: List<Track> = emptyList()
    private var query: String = ""
    var playingUri: String? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun submit(tracks: List<Track>) {
        allTracks = tracks.toList()
        applyFilter()
    }

    fun filter(text: String) {
        query = text.trim()
        applyFilter()
    }

    val visibleCount: Int get() = shown.size

    private fun applyFilter() {
        shown = if (query.isEmpty()) allTracks
        else allTracks.filter { it.name.contains(query, ignoreCase = true) }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val b = ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(b)
    }

    override fun getItemCount() = shown.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val track = shown[position]
        val b = holder.binding
        val ctx = b.root.context
        val isPlaying = track.uri == playingUri

        b.title.text = track.name
        b.subtitle.visibility = if (isPlaying) View.VISIBLE else View.GONE
        b.icon.setImageResource(if (isPlaying) R.drawable.ic_volume else R.drawable.ic_music_note)
        b.icon.setColorFilter(
            ContextCompat.getColor(ctx, if (isPlaying) R.color.accent else R.color.primary)
        )
        b.root.setOnClickListener { onPlay(track) }
        b.root.setOnLongClickListener { onMore(track, b.more); true }
        b.more.setOnClickListener { onMore(track, it) }
        b.fav.setImageResource(if (track.favorite) R.drawable.ic_star else R.drawable.ic_star_border)
        b.fav.setColorFilter(
            ContextCompat.getColor(ctx, if (track.favorite) R.color.accent else R.color.text_secondary)
        )
        b.fav.contentDescription = ctx.getString(
            if (track.favorite) R.string.unfavorite else R.string.favorite
        )
        b.fav.setOnClickListener { onFavorite(track) }
    }

    class Holder(val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root)
}
