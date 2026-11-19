package com.pannulabs.schoolprayer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.pannulabs.schoolprayer.databinding.ItemDeviceBinding

@SuppressLint("MissingPermission", "NotifyDataSetChanged")
class DeviceAdapter(
    private val onTap: (BluetoothDevice) -> Unit,
    private val statusOf: (BluetoothDevice) -> Pair<String, Boolean>, // text, highlighted
) : RecyclerView.Adapter<DeviceAdapter.Holder>() {

    private var items: List<BluetoothDevice> = emptyList()

    fun submit(devices: Collection<BluetoothDevice>) {
        items = devices.sortedWith(
            compareByDescending<BluetoothDevice> { isAudio(it) }.thenBy { label(it).lowercase() }
        )
        notifyDataSetChanged()
    }

    fun refresh() = notifyDataSetChanged()

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val d = items[position]
        val b = holder.binding
        val ctx = b.root.context
        val (status, highlight) = statusOf(d)
        b.name.text = label(d)
        b.status.text = status
        b.status.setTextColor(
            ContextCompat.getColor(ctx, if (highlight) R.color.connected else R.color.text_secondary)
        )
        b.icon.setImageResource(if (isAudio(d)) R.drawable.ic_speaker else R.drawable.ic_bluetooth)
        b.root.setOnClickListener { onTap(d) }
    }

    class Holder(val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        fun label(d: BluetoothDevice): String = try {
            d.name?.takeIf { it.isNotBlank() } ?: d.address
        } catch (_: SecurityException) {
            d.address
        }

        fun isAudio(d: BluetoothDevice): Boolean = try {
            d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        } catch (_: SecurityException) {
            false
        }
    }
}
