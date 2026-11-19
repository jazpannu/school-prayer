package com.pannulabs.schoolprayer

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pannulabs.schoolprayer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: TrackStore
    private lateinit var dnd: DndHelper
    private var awaitingDndAccess = false
    private lateinit var adapter: TrackAdapter
    private val tracks = mutableListOf<Track>()

    private var player: MediaPlayer? = null
    private var current: Track? = null
    private var userSeeking = false
    private var prepared = false
    private val handler = Handler(Looper.getMainLooper())
    private val progressTick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    /** System file picker; lets the teacher pick one or many audio files at once. */
    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) addFiles(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TrackStore(this)
        dnd = DndHelper(this)
        // If the app was closed mid-prayer with DND on, put the phone back to normal.
        dnd.restore()
        // First launch: ask for the school name before showing the home screen.
        if (store.schoolName == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        showSchoolName()

        tracks.addAll(store.load())
        tracks.sortTracks()

        adapter = TrackAdapter(onPlay = ::play, onMore = ::showTrackMenu, onFavorite = ::toggleFavorite)
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.addButton.setOnClickListener { openPicker() }
        binding.searchInput.doAfterTextChanged {
            adapter.filter(it?.toString().orEmpty())
            refreshEmptyState()
        }

        binding.btnPlayPause.setOnClickListener { togglePause() }
        binding.btnStop.setOnClickListener { stopPlayback() }
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.timeText.text = timeLabel(progress, sb.max)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                if (prepared) player?.seekTo(sb.progress)
            }
        })

        binding.btBanner.setOnClickListener { openBluetooth() }
        binding.dndSwitch.isChecked = dnd.enabled && dnd.hasAccess
        binding.dndSwitch.setOnCheckedChangeListener { _, checked -> onDndToggled(checked) }
        binding.dndRow.setOnClickListener { binding.dndSwitch.toggle() }

        refreshList()
        updatePlayerBar()
    }

    override fun onStart() {
        super.onStart()
        if (!::binding.isInitialized) return
        getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(audioCallback, handler)
        updateBtBanner()
    }

    override fun onStop() {
        if (::binding.isInitialized) getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(audioCallback)
        super.onStop()
    }

    // ---------- Bluetooth ----------

    private fun openBluetooth() = startActivity(Intent(this, BluetoothActivity::class.java))

    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = updateBtBanner()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = updateBtBanner()
    }

    /** Shows "Playing through <speaker>" on the main screen when a Bluetooth speaker is connected. */
    private fun updateBtBanner() {
        val name = BtAudio.connectedSpeakerName(this)
        binding.btBanner.visibility = if (name != null) View.VISIBLE else View.GONE
        if (name != null) binding.btBanner.text = getString(R.string.bt_banner, name)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == R.id.action_add) { openPicker(); return true }
        if (item.itemId == R.id.action_bluetooth) { openBluetooth(); return true }
        if (item.itemId == R.id.action_school) { editSchoolName(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        // Back from the system "Do Not Disturb access" screen.
        if (awaitingDndAccess) {
            awaitingDndAccess = false
            if (dnd.hasAccess) {
                dnd.enabled = true
                setDndSwitch(true)
                if (player?.isPlaying == true) dnd.activate()
                Toast.makeText(this, R.string.dnd_ready, Toast.LENGTH_SHORT).show()
            } else {
                setDndSwitch(false)
            }
        } else if (dnd.enabled && !dnd.hasAccess) {
            // Access was revoked in settings.
            dnd.enabled = false
            setDndSwitch(false)
        }
        updateDndBadge()
    }

    // ---------- Silent mode (Do Not Disturb) ----------

    private fun onDndToggled(checked: Boolean) {
        if (!checked) {
            dnd.enabled = false
            dnd.restore()
            updateDndBadge()
            return
        }
        if (dnd.hasAccess) {
            dnd.enabled = true
            if (prepared && player?.isPlaying == true) dnd.activate()
            updateDndBadge()
            return
        }
        // One-time permission: Android only allows this from its settings screen.
        setDndSwitch(false)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dnd_access_title)
            .setMessage(R.string.dnd_access_body)
            .setPositiveButton(R.string.dnd_allow) { _, _ ->
                awaitingDndAccess = true
                try {
                    startActivity(dnd.accessSettingsIntent())
                } catch (_: Exception) {
                    awaitingDndAccess = false
                    Toast.makeText(this, R.string.dnd_unavailable, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Changes the switch without triggering the listener. */
    private fun setDndSwitch(on: Boolean) {
        binding.dndSwitch.setOnCheckedChangeListener(null)
        binding.dndSwitch.isChecked = on
        binding.dndSwitch.setOnCheckedChangeListener { _, checked -> onDndToggled(checked) }
    }

    private fun updateDndBadge() {
        binding.dndBadge.visibility = if (dnd.isActive) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        if (::dnd.isInitialized && isFinishing) dnd.restore()
        handler.removeCallbacks(progressTick)
        player?.release()
        player = null
        super.onDestroy()
    }

    // ---------- Adding files ----------

    private fun openPicker() = pickAudio.launch(arrayOf("audio/*"))

    private fun addFiles(uris: List<Uri>) {
        var added = 0
        for (uri in uris) {
            // Keep read access after the app restarts.
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) { }

            val key = uri.toString()
            if (tracks.any { it.uri == key }) continue
            tracks.add(Track(key, displayName(uri)))
            added++
        }
        tracks.sortTracks()
        store.save(tracks)
        refreshList()
        val msg = if (added == 0) getString(R.string.already_added)
        else resources.getQuantityString(R.plurals.files_added, added, added)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (_: Exception) { }
        val raw = name ?: uri.lastPathSegment ?: "Prayer"
        return raw.substringBeforeLast('.', raw)
    }

    // ---------- List ----------

    private fun refreshList() {
        adapter.submit(tracks)
        adapter.filter(binding.searchInput.text?.toString().orEmpty())
        refreshEmptyState()
    }

    private fun refreshEmptyState() {
        val noFiles = tracks.isEmpty()
        val noMatches = !noFiles && adapter.visibleCount == 0
        binding.emptyView.visibility = if (noFiles) View.VISIBLE else View.GONE
        binding.noMatches.visibility = if (noMatches) View.VISIBLE else View.GONE
        binding.recycler.visibility = if (noFiles || noMatches) View.GONE else View.VISIBLE
        binding.countText.text = resources.getQuantityString(R.plurals.file_count, tracks.size, tracks.size)
    }

    private fun showTrackMenu(track: Track, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 3, 0, if (track.favorite) R.string.unfavorite else R.string.favorite)
            menu.add(0, 1, 1, R.string.rename)
            menu.add(0, 2, 2, R.string.remove)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> renameTrack(track)
                    2 -> confirmRemove(track)
                    3 -> toggleFavorite(track)
                }
                true
            }
        }.show()
    }

    private fun toggleFavorite(track: Track) {
        val i = tracks.indexOfFirst { it.uri == track.uri }
        if (i < 0) return
        val updated = track.copy(favorite = !track.favorite)
        tracks[i] = updated
        if (current?.uri == track.uri) current = updated
        tracks.sortTracks()
        store.save(tracks)
        refreshList()
        binding.recycler.scrollToPosition(0)
        Toast.makeText(
            this,
            if (updated.favorite) R.string.added_to_fav else R.string.removed_from_fav,
            Toast.LENGTH_SHORT
        ).show()
    }

    // ---------- School name ----------

    private fun showSchoolName() {
        binding.schoolName.text = store.schoolName
        binding.schoolName.setOnClickListener { editSchoolName() }
    }

    private fun editSchoolName() {
        val input = EditText(this).apply {
            setText(store.schoolName)
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.school_name)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    store.schoolName = name
                    showSchoolName()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renameTrack(track: Track) {
        val input = EditText(this).apply {
            setText(track.name)
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val i = tracks.indexOfFirst { it.uri == track.uri }
                    if (i >= 0) {
                        tracks[i] = track.copy(name = newName)
                        if (current?.uri == track.uri) current = tracks[i]
                        tracks.sortTracks()
                        store.save(tracks)
                        refreshList()
                        updatePlayerBar()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRemove(track: Track) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remove)
            .setMessage(getString(R.string.remove_confirm, track.name))
            .setPositiveButton(R.string.remove) { _, _ ->
                if (current?.uri == track.uri) stopPlayback()
                tracks.removeAll { it.uri == track.uri }
                if (track.uri.startsWith("content://")) try {
                    contentResolver.releasePersistableUriPermission(
                        Uri.parse(track.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) { }
                store.save(tracks)
                refreshList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- Playback ----------

    private fun play(track: Track) {
        if (current?.uri == track.uri && player != null) {
            togglePause()
            return
        }
        releasePlayer()
        binding.seekBar.progress = 0
        binding.timeText.text = ""
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(this, Uri.parse(track.uri))
            mp.setOnPreparedListener {
                prepared = true
                it.start()
                binding.seekBar.max = it.duration
                handler.post(progressTick)
                dnd.activate()
                updateDndBadge()
                updatePlayerBar()
            }
            mp.setOnCompletionListener { onFinished() }
            mp.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, R.string.cant_play, Toast.LENGTH_LONG).show()
                stopPlayback()
                true
            }
            player = mp
            current = track
            mp.prepareAsync()
        } catch (e: Exception) {
            mp.release()
            player = null
            current = null
            Toast.makeText(this, R.string.cant_play, Toast.LENGTH_LONG).show()
        }
        adapter.playingUri = current?.uri
        updatePlayerBar()
    }

    private fun togglePause() {
        val mp = player ?: return
        if (!prepared) return
        if (mp.isPlaying) mp.pause() else mp.start()
        updatePlayerBar()
    }

    private fun onFinished() {
        dnd.restore()
        updateDndBadge()
        if (prepared) player?.seekTo(0)
        binding.seekBar.progress = 0
        updatePlayerBar()
    }

    private fun stopPlayback() {
        releasePlayer()
        dnd.restore()
        updateDndBadge()
        current = null
        adapter.playingUri = null
        updatePlayerBar()
    }

    private fun releasePlayer() {
        handler.removeCallbacks(progressTick)
        player?.release()
        player = null
        prepared = false
    }

    private fun updatePlayerBar() {
        val track = current
        binding.playerBar.visibility = if (track == null) View.GONE else View.VISIBLE
        val playing = prepared && player?.isPlaying == true
        binding.btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.btnPlayPause.contentDescription = getString(if (playing) R.string.pause else R.string.play)
        binding.nowPlayingTitle.text = track?.name.orEmpty()
        // Keep the screen awake while a prayer is playing (e.g. during assembly).
        if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateProgress()
    }

    private fun updateProgress() {
        val mp = player ?: return
        if (!prepared || userSeeking) return
        try {
            binding.seekBar.progress = mp.currentPosition
            binding.timeText.text = timeLabel(mp.currentPosition, binding.seekBar.max)
        } catch (_: IllegalStateException) { }
    }

    private fun timeLabel(pos: Int, total: Int) = "${fmt(pos)} / ${fmt(total)}"

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
