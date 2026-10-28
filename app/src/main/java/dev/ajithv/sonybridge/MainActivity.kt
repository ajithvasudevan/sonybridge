package dev.ajithv.sonybridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var uploads: TextView
    private lateinit var setup: TextView
    private lateinit var thumb: ImageView
    private var thumbShownPath: String? = null
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); h.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        uploads = findViewById(R.id.uploads)
        setup = findViewById(R.id.setup)
        thumb = findViewById(R.id.thumb)
        thumb.setOnClickListener {
            Uploader.lastAssetId?.let { id ->
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("immich://my.immich.app/photos/$id"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {}
            }
        }
        findViewById<Button>(R.id.start).setOnClickListener {
            Prefs(this).enabled = true
            startBridge()
            ensureBatteryExempt()      // only on explicit Start — launches the battery settings dialog
        }
        findViewById<Button>(R.id.stop).setOnClickListener {
            Prefs(this).enabled = false
            stopService(Intent(this, BridgeService::class.java))
        }
        findViewById<Button>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // resume if previously enabled, or when launched with autostart (reboot / automation)
        if (intent?.getBooleanExtra("autostart", false) == true || Prefs(this).enabled) {
            Prefs(this).enabled = true
            startBridge()
        }
    }

    private fun startBridge() {
        // start the FGS FIRST while we're still foreground (Android 12+ blocks background FGS starts),
        // then request the notification permission (an overlay dialog, doesn't background us).
        ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java))
        ensureNotifPerm()
    }

    private fun ensureBatteryExempt() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        } catch (_: Exception) {}
    }

    /** Load a downscaled thumbnail of the current/last-uploaded photo off the main thread. */
    private fun updateThumb() {
        val tp = Uploader.lastThumbPath
        if (tp == thumbShownPath) return
        thumbShownPath = tp
        if (tp == null) { thumb.setImageDrawable(null); thumb.visibility = View.GONE; return }
        Thread {
            val bmp = try { decodeThumb(tp, 200) } catch (_: Exception) { null }
            runOnUiThread {
                if (Uploader.lastThumbPath == tp) {
                    if (bmp != null) { thumb.setImageBitmap(bmp); thumb.visibility = View.VISIBLE }
                    else { thumb.setImageDrawable(null); thumb.visibility = View.GONE }
                }
            }
        }.start()
    }

    private fun ago(t: Long): String {
        val s = ((System.currentTimeMillis() - t) / 1000).coerceAtLeast(0)
        return if (s < 60) "${s}s" else if (s < 3600) "${s / 60}m" else "${s / 3600}h"
    }

    private fun decodeThumb(path: String, reqPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null                // not a decodable image (RAW/video) -> hidden
        var s = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / (s * 2) >= reqPx) s *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = s }
        return BitmapFactory.decodeFile(path, opts)
    }

    override fun onResume() {
        super.onResume()
        if (Prefs(this).enabled) startBridge()   // ensure running whenever the app is opened
        h.post(tick)
    }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun refresh() {
        val net = Net.scan()
        val cam = when {
            BridgeService.clients > 0 ->
                "● Camera CONNECTED" + (BridgeService.lastClientAddr?.let { " ($it)" } ?: "")
            BridgeService.lastClientAt > 0L ->
                "○ Camera not connected  (last seen ${ago(BridgeService.lastClientAt)})"
            else -> "○ Camera: waiting for connection…"
        }
        status.text = "Service: ${if (BridgeService.running) "RUNNING" else "stopped"}\n$cam\n${BridgeService.status}"
        uploads.text = "Uploaded: ${Uploader.sent}   Pending: ${Uploader.pending}   Failed: ${Uploader.failed}\n" +
                "Last: ${Uploader.last}"
        updateThumb()
        setup.text = buildString {
            if (net.apIp != null) {
                append("Camera FTP setup\n")
                append("  Server : ").append(net.apIp).append("   (").append(net.apIface).append(")\n")
                append("  Port   : ").append(BuildConfig.FTP_PORT).append('\n')
                append("  User   : ").append(BuildConfig.FTP_USER).append('\n')
                append("  Pass   : ").append(BuildConfig.FTP_PASS).append('\n')
                append("  Passive: ON")
            } else {
                append("⚠ No Wi-Fi/hotspot address found — turn ON the phone hotspot.")
            }
            append("\n\nInterfaces: ")
            append(if (net.all.isEmpty()) "(none)" else net.all.joinToString(", ") { "${it.first}=${it.second}" })
        }
    }

    private fun ensureNotifPerm() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
