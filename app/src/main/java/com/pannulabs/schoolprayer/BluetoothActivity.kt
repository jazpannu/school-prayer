package com.pannulabs.schoolprayer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pannulabs.schoolprayer.databinding.ActivityBluetoothBinding

/**
 * Turn Bluetooth on, list paired devices, search for nearby ones, pair/connect,
 * and show which speaker audio is playing through.
 */
@SuppressLint("MissingPermission") // every Bluetooth call goes through withPerms()/hasPerms()
class BluetoothActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBluetoothBinding
    private val btAdapter: BluetoothAdapter?
        get() = getSystemService(BluetoothManager::class.java)?.adapter
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())

    private var a2dp: BluetoothA2dp? = null
    private val found = linkedMapOf<String, BluetoothDevice>()
    private var pendingConnect: String? = null
    private var pendingAction: (() -> Unit)? = null

    private lateinit var pairedAdapter: DeviceAdapter
    private lateinit var foundAdapter: DeviceAdapter

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val action = pendingAction
            pendingAction = null
            if (res.values.all { it }) {
                openA2dpProxy()
                action?.invoke()
            } else {
                toast(R.string.bt_permission_needed)
            }
            refreshAll()
        }

    private val enableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshAll() }

    // ---------- Lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBluetoothBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        pairedAdapter = DeviceAdapter(::onDeviceTapped, ::pairedStatus)
        foundAdapter = DeviceAdapter(::onDeviceTapped) { getString(R.string.bt_tap_to_pair) to false }
        binding.pairedList.layoutManager = LinearLayoutManager(this)
        binding.pairedList.adapter = pairedAdapter
        binding.foundList.layoutManager = LinearLayoutManager(this)
        binding.foundList.adapter = foundAdapter

        binding.btnTurnOn.setOnClickListener { turnOn() }
        binding.btnSearch.setOnClickListener { startSearch() }
        binding.btnSettings.setOnClickListener { openBluetoothSettings() }

        if (btAdapter == null) {
            binding.statusText.setText(R.string.bt_not_supported)
            binding.btnTurnOn.visibility = View.GONE
            binding.btnSearch.isEnabled = false
        }
        if (hasPerms()) openA2dpProxy() else withPerms { }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        audioManager?.registerAudioDeviceCallback(audioCallback, handler)
        refreshAll()
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        audioManager?.unregisterAudioDeviceCallback(audioCallback)
        if (hasPerms()) try { btAdapter?.cancelDiscovery() } catch (_: SecurityException) { }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        a2dp?.let { btAdapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        super.onDestroy()
    }

    // ---------- Permissions ----------

    private fun requiredPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPerms() = requiredPerms().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun withPerms(action: () -> Unit) {
        if (hasPerms()) action()
        else {
            pendingAction = action
            permLauncher.launch(requiredPerms())
        }
    }

    // ---------- Actions ----------

    private fun turnOn() = withPerms {
        enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    private fun startSearch() = withPerms {
        val adapter = btAdapter ?: return@withPerms
        if (!adapter.isEnabled) {
            turnOn()
            return@withPerms
        }
        // Android 11 and older need Location turned on to find nearby devices.
        if (Build.VERSION.SDK_INT < 31) {
            val lm = getSystemService(LocationManager::class.java)
            if (lm != null && !LocationManagerCompat.isLocationEnabled(lm)) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.bt_location_title)
                    .setMessage(R.string.bt_location_body)
                    .setPositiveButton(R.string.bt_open_settings) { _, _ ->
                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return@withPerms
            }
        }
        found.clear()
        foundAdapter.submit(found.values)
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        if (!adapter.startDiscovery()) toast(R.string.bt_search_failed)
        refreshFoundEmpty()
    }

    private fun onDeviceTapped(device: BluetoothDevice) = withPerms {
        try { btAdapter?.cancelDiscovery() } catch (_: SecurityException) { }
        val name = DeviceAdapter.label(device)
        when {
            isConnected(device) -> MaterialAlertDialogBuilder(this)
                .setTitle(name)
                .setMessage(R.string.bt_connected_already)
                .setPositiveButton(R.string.bt_disconnect) { _, _ -> disconnect(device) }
                .setNegativeButton(R.string.cancel, null)
                .show()

            device.bondState == BluetoothDevice.BOND_BONDED -> connect(device)

            device.bondState == BluetoothDevice.BOND_BONDING -> toast(getString(R.string.bt_pairing, name))

            else -> {
                pendingConnect = device.address
                if (device.createBond()) toast(getString(R.string.bt_pairing, name))
                else {
                    pendingConnect = null
                    toast(getString(R.string.bt_pair_failed, name))
                }
            }
        }
    }

    /**
     * Android has no public API for apps to connect a paired speaker. We try the hidden
     * BluetoothA2dp.connect() (works on many phones); if blocked, we offer Bluetooth settings.
     */
    private fun connect(device: BluetoothDevice) {
        val name = DeviceAdapter.label(device)
        val ok = callA2dp("connect", device)
        if (ok) {
            toast(getString(R.string.bt_connecting, name))
            // If it still isn't connected after a while, offer the settings screen.
            handler.postDelayed({
                if (!isFinishing && !isConnected(device)) showSettingsFallback(name)
            }, 8000)
        } else {
            showSettingsFallback(name)
        }
    }

    private fun disconnect(device: BluetoothDevice) {
        if (!callA2dp("disconnect", device)) showSettingsFallback(DeviceAdapter.label(device))
    }

    private fun callA2dp(method: String, device: BluetoothDevice): Boolean {
        val proxy = a2dp ?: return false
        return try {
            BluetoothA2dp::class.java.getMethod(method, BluetoothDevice::class.java)
                .invoke(proxy, device) as? Boolean == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun showSettingsFallback(name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bt_connect_title)
            .setMessage(getString(R.string.bt_connect_fallback, name))
            .setPositiveButton(R.string.bt_open_settings) { _, _ -> openBluetoothSettings() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openBluetoothSettings() = startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    // ---------- State ----------

    private fun openA2dpProxy() {
        if (a2dp != null) return
        btAdapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                a2dp = proxy as BluetoothA2dp
                refreshAll()
            }
            override fun onServiceDisconnected(profile: Int) {
                a2dp = null
            }
        }, BluetoothProfile.A2DP)
    }

    private fun isConnected(d: BluetoothDevice): Boolean = try {
        a2dp?.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED
    } catch (_: SecurityException) {
        false
    }

    private fun pairedStatus(d: BluetoothDevice): Pair<String, Boolean> {
        val state = try { a2dp?.getConnectionState(d) } catch (_: SecurityException) { null }
        return when (state) {
            BluetoothProfile.STATE_CONNECTED -> getString(R.string.bt_status_connected) to true
            BluetoothProfile.STATE_CONNECTING -> getString(R.string.bt_status_connecting) to false
            else -> getString(R.string.bt_status_paired) to false
        }
    }

    private fun refreshAll() {
        val adapter = btAdapter ?: return
        val on = adapter.isEnabled
        binding.statusText.setText(if (on) R.string.bt_on else R.string.bt_off)
        binding.btnTurnOn.visibility = if (on) View.GONE else View.VISIBLE
        binding.btnSearch.isEnabled = on
        refreshConnected()

        val paired = if (on && hasPerms()) try {
            adapter.bondedDevices.orEmpty()
        } catch (_: SecurityException) { emptySet() } else emptySet()
        pairedAdapter.submit(paired)
        binding.pairedEmpty.visibility = if (paired.isEmpty()) View.VISIBLE else View.GONE
        binding.pairedEmpty.setText(if (on) R.string.bt_no_paired else R.string.bt_turn_on_first)
        refreshFoundEmpty()
    }

    private fun refreshConnected() {
        val name = BtAudio.connectedSpeakerName(this)
        binding.connectedName.text = name ?: getString(R.string.bt_phone_speaker)
        binding.connectedIcon.setImageResource(if (name != null) R.drawable.ic_speaker else R.drawable.ic_volume)
    }

    private fun refreshFoundEmpty() {
        val scanning = try { hasPerms() && btAdapter?.isDiscovering == true } catch (_: SecurityException) { false }
        binding.searchProgress.visibility = if (scanning) View.VISIBLE else View.GONE
        binding.btnSearch.setText(if (scanning) R.string.bt_searching else R.string.bt_search)
        binding.foundEmpty.visibility = if (found.isEmpty() && !scanning) View.VISIBLE else View.GONE
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> refreshAll()

                BluetoothAdapter.ACTION_DISCOVERY_STARTED,
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> refreshFoundEmpty()

                BluetoothDevice.ACTION_FOUND -> {
                    val d = intent.device() ?: return
                    if (d.bondState != BluetoothDevice.BOND_BONDED) {
                        found[d.address] = d
                        foundAdapter.submit(found.values)
                        refreshFoundEmpty()
                    }
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val d = intent.device() ?: return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    if (state == BluetoothDevice.BOND_BONDED) {
                        found.remove(d.address)
                        foundAdapter.submit(found.values)
                        toast(getString(R.string.bt_paired_ok, DeviceAdapter.label(d)))
                        if (pendingConnect == d.address) {
                            pendingConnect = null
                            // Most speakers connect by themselves right after pairing; give them a moment.
                            handler.postDelayed({ if (!isConnected(d)) connect(d) }, 3000)
                        }
                    } else if (state == BluetoothDevice.BOND_NONE && pendingConnect == d.address) {
                        pendingConnect = null
                        toast(getString(R.string.bt_pair_failed, DeviceAdapter.label(d)))
                    }
                    refreshAll()
                }

                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    pairedAdapter.refresh()
                    refreshConnected()
                }
            }
        }
    }

    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refreshConnected()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refreshConnected()
    }

    private fun Intent.device(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
