package dev.ajithv.sonybridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restart the bridge after a reboot. (User still re-enables the hotspot — OS limitation.) */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs(context).enabled) {
            ContextCompat.startForegroundService(context, Intent(context, BridgeService::class.java))
        }
    }
}
