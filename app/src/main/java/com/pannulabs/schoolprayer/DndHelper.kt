package com.pannulabs.schoolprayer

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Turns Do Not Disturb on while a prayer plays, and puts the phone back exactly as it was afterwards.
 *
 * Calls, notifications, alarms and system sounds are silenced. Media is still allowed,
 * so the prayer itself keeps playing (through the phone or the Bluetooth speaker).
 *
 * The previous DND state is saved to disk, so if the app is closed or crashes mid-prayer
 * it is restored the next time the app opens.
 */
class DndHelper(context: Context) {

    private val ctx = context.applicationContext
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val prefs = ctx.getSharedPreferences("dnd", Context.MODE_PRIVATE)

    /** The user's on/off choice for "Silent mode while playing". */
    var enabled: Boolean
        get() = prefs.getBoolean(K_ENABLED, false)
        set(v) = prefs.edit().putBoolean(K_ENABLED, v).apply()

    /** Has the user allowed this app to control Do Not Disturb (one-time, in system settings)? */
    val hasAccess: Boolean get() = nm?.isNotificationPolicyAccessGranted == true

    /** True while this app has DND switched on. */
    val isActive: Boolean get() = prefs.getBoolean(K_ACTIVE, false)

    fun accessSettingsIntent() = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    /** Call when a prayer starts. Returns true if DND is now on because of us. */
    fun activate(): Boolean {
        if (!enabled || !hasAccess || isActive) return isActive
        val nm = nm ?: return false
        // If the phone is already in DND (set by the teacher), leave it alone.
        if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return false
        return try {
            val e = prefs.edit()
                .putBoolean(K_ACTIVE, true)
                .putInt(K_OLD_FILTER, nm.currentInterruptionFilter)
            if (Build.VERSION.SDK_INT >= 28) {
                val old = nm.notificationPolicy
                e.putBoolean(K_HAS_POLICY, true)
                    .putInt(K_P_CAT, old.priorityCategories)
                    .putInt(K_P_CALL, old.priorityCallSenders)
                    .putInt(K_P_MSG, old.priorityMessageSenders)
                    .putInt(K_P_VIS, old.suppressedVisualEffects)
                if (Build.VERSION.SDK_INT >= 30) e.putInt(K_P_CONV, old.priorityConversationSenders)
                e.commit()
                // Priority-only DND where the only allowed category is media → total silence except our audio.
                nm.notificationPolicy = NotificationManager.Policy(
                    NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA, 0, 0
                )
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            } else {
                e.commit()
                // Android 8: "Alarms only" mode silences calls and notifications but allows media.
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            }
            true
        } catch (_: SecurityException) {
            prefs.edit().putBoolean(K_ACTIVE, false).apply()
            false
        }
    }

    /** Call when the prayer stops or finishes (and on app start, to recover after a crash). */
    fun restore() {
        if (!isActive) return
        val nm = nm
        try {
            if (nm != null && hasAccess) {
                if (Build.VERSION.SDK_INT >= 28 && prefs.getBoolean(K_HAS_POLICY, false)) {
                    val cat = prefs.getInt(K_P_CAT, 0)
                    val call = prefs.getInt(K_P_CALL, 0)
                    val msg = prefs.getInt(K_P_MSG, 0)
                    val vis = prefs.getInt(K_P_VIS, 0)
                    nm.notificationPolicy =
                        if (Build.VERSION.SDK_INT >= 30)
                            NotificationManager.Policy(cat, call, msg, vis, prefs.getInt(K_P_CONV, 0))
                        else NotificationManager.Policy(cat, call, msg, vis)
                }
                nm.setInterruptionFilter(
                    prefs.getInt(K_OLD_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL)
                )
            }
        } catch (_: Exception) {
        } finally {
            prefs.edit().putBoolean(K_ACTIVE, false).putBoolean(K_HAS_POLICY, false).apply()
        }
    }

    private companion object {
        const val K_ENABLED = "enabled"
        const val K_ACTIVE = "active"
        const val K_OLD_FILTER = "old_filter"
        const val K_HAS_POLICY = "has_policy"
        const val K_P_CAT = "p_cat"
        const val K_P_CALL = "p_call"
        const val K_P_MSG = "p_msg"
        const val K_P_VIS = "p_vis"
        const val K_P_CONV = "p_conv"
    }
}
