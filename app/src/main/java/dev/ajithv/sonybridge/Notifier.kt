package dev.ajithv.sonybridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

object Notifier {
    private const val CH_UP = "uploads"
    private const val CH_ALERT = "alerts"
    private const val IP_NID = 42
    private val idGen = AtomicInteger(1000)

    private fun nm(ctx: Context): NotificationManager =
        ctx.getSystemService(NotificationManager::class.java)

    private fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val m = nm(ctx)
        m.createNotificationChannel(NotificationChannel(CH_UP, "Uploads", NotificationManager.IMPORTANCE_DEFAULT))
        m.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true); enableLights(true); lightColor = Color.RED
                vibrationPattern = longArrayOf(0, 400, 200, 400)
            }
        )
    }

    /** One (ungrouped) notification per uploaded photo, with its thumbnail; tap opens the immich photo via the key. */
    fun uploaded(ctx: Context, assetId: String, name: String, baseUrl: String, apiKey: String, thumb: File) {
        ensureChannels(ctx)
        // tap -> immich WEB viewer: opens the exact photo immediately (loaded from the server by id)
        val url = "${baseUrl.trimEnd('/')}/photos/$assetId"
        val pi = PendingIntent.getActivity(
            ctx, assetId.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(ctx, CH_UP)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Uploaded to immich")
            .setContentText(name)
            .setAutoCancel(true)
            .setContentIntent(pi)
        // large, un-rounded rectangular preview (BigPicture); the small collapsed icon is the same image
        val big = decodeThumb(thumb, 1024)
        if (big != null) {
            b.setLargeIcon(big)
            b.setStyle(
                Notification.BigPictureStyle().bigPicture(big)
                    .bigLargeIcon(null as Bitmap?)
            )
        }
        nm(ctx).notify(idGen.incrementAndGet(), b.build())   // unique id => each is separate (ungrouped)
    }

    /** Loud, red alert when the hotspot IP the camera uploads to changes. */
    fun hotspotIpChanged(ctx: Context, oldIp: String, newIp: String) {
        ensureChannels(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(ctx, CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("⚠ Hotspot IP changed")
            .setContentText("$oldIp → $newIp  — update the camera's FTP server!")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "The address the camera uploads to changed:\n$oldIp → $newIp\n\n" +
                        "Update the camera: MENU > Network > FTP Transfer > Server > Host = $newIp"
                )
            )
            .setColor(Color.RED)
            .setColorized(true)
            .setAutoCancel(true)
            .setContentIntent(pi)
        nm(ctx).notify(IP_NID, b.build())
    }

    private fun decodeThumb(f: File, reqPx: Int): Bitmap? = try {
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bo)
        if (bo.outWidth <= 0) null else {
            var s = 1; val m = maxOf(bo.outWidth, bo.outHeight)
            while (m / (s * 2) >= reqPx) s *= 2
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
        }
    } catch (_: Exception) { null }
}
