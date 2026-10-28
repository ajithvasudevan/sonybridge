package dev.ajithv.sonybridge

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Filesystem-backed queue: the FTP inbox IS the pending queue. A file is uploaded only once COMPLETE
 * (FTP STOR-complete hook -> [ready], or size stable across two sweeps). After a successful immich
 * upload + date-album add, a gallery-visible copy is kept in DCIM/SonyBridge via MediaStore (no
 * storage permission needed on API 29+), parallel to the Creators' App CA_IMAGES folder; the inbox
 * working copy is then removed (the photo lives on in DCIM + immich — never lost). If the keep fails,
 * the file is retained in the private sent/ as a fallback and retried.
 */
object Uploader {
    private const val TAG = "sonybridge"
    const val KEEP_REL = "DCIM/SonyBridge"        // gallery-visible, sibling of DCIM/CA_IMAGES

    @Volatile var sent = 0
    @Volatile var failed = 0
    @Volatile var pending = 0
    @Volatile var last = ""
    @Volatile var lastThumbPath: String? = null
    @Volatile var lastAssetId: String? = null      // immich asset id of the last uploaded photo (app deep link)

    val ready: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val lastSize = HashMap<String, Long>()

    private val imageExt = setOf("jpg", "jpeg", "heic", "heif", "png")
    private val videoExt = setOf("mp4", "mov", "m4v")
    private val rawExt = setOf("arw", "dng", "raw")

    fun eligible(f: File, p: Prefs): Boolean {
        if (!f.isFile || f.length() == 0L) return false
        return when (f.extension.lowercase()) {
            in imageExt -> true
            in videoExt -> p.uploadVideo
            in rawExt -> p.uploadRaw
            else -> false
        }
    }

    suspend fun loop(ctx: Context, inbox: File, sentD: File, isRunning: () -> Boolean) {
        while (isRunning()) {
            try { sweep(ctx, inbox, sentD) } catch (e: Exception) { Log.e(TAG, "sweep: ${e.message}") }
            delay(3000)
        }
    }

    private fun sweep(ctx: Context, inbox: File, sentD: File) {
        migrateLeftovers(ctx, sentD)
        val p = Prefs(ctx)
        val all = inbox.listFiles()?.filter { eligible(it, p) } ?: emptyList()
        pending = all.size
        if (all.isEmpty()) { lastSize.clear(); return }

        val names = HashSet<String>()
        val go = ArrayList<File>()
        for (f in all) {
            names.add(f.name)
            val sz = f.length()
            val stable = lastSize[f.name] == sz && sz > 0L
            lastSize[f.name] = sz
            if (ready.contains(f.name) || stable) go.add(f)
        }
        lastSize.keys.retainAll(names)
        if (go.isEmpty()) return

        if (p.url.isBlank() || p.key.isBlank()) { last = "set immich URL/key in Settings"; return }
        val client = ImmichClient(p.url, p.key)
        if (!client.ping()) { last = "immich unreachable — will retry"; return }
        val albumName = LocalDate.now().toString()
        val album = client.ensureAlbum(albumName)

        for (f in go) {
            if (!f.exists()) { ready.remove(f.name); lastSize.remove(f.name); continue }
            try {
                lastThumbPath = f.absolutePath
                val res = client.upload(f, f.name, isoUtc(f.lastModified()))
                if (res == null) { failed++; last = "failed: ${f.name}"; continue }
                lastAssetId = res.id
                album?.let { client.addToAlbum(it, res.id) }
                if (keep(ctx, f)) {
                    Notifier.uploaded(ctx, res.id, f.name, p.url, p.key, f)   // f still present for the thumbnail
                    f.delete()
                } else {                                   // fallback: retain privately, retry next sweep
                    sentD.mkdirs()
                    val dst = File(sentD, f.name)
                    if (!f.renameTo(dst)) { f.copyTo(dst, overwrite = true); f.delete() }
                    lastThumbPath = dst.absolutePath
                    Notifier.uploaded(ctx, res.id, f.name, p.url, p.key, dst)
                }
                ready.remove(f.name); lastSize.remove(f.name)
                sent++; last = "${res.status}: ${f.name}"
                Log.i(TAG, "uploaded ${f.name} -> ${res.status} album=$albumName")
            } catch (e: Exception) {
                failed++; last = "err ${f.name}: ${e.message}"; Log.e(TAG, "upload ${f.name}", e)
            }
        }
        pending = inbox.listFiles()?.count { eligible(it, p) } ?: 0
    }

    /** Write a gallery-visible copy into DCIM/SonyBridge via MediaStore (API 29+) or legacy File API. */
    private fun keep(ctx: Context, f: File): Boolean { return try {
        val name = f.name
        val isVideo = f.extension.lowercase() in videoExt
        val cr = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val collection =
                if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val exists = cr.query(
                collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf("%$KEEP_REL%", name), null
            )?.use { it.count > 0 } ?: false
            if (!exists) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(name))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, KEEP_REL)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = cr.insert(collection, values) ?: return false
                cr.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } ?: return false
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0); cr.update(uri, values, null, null)
            }
            @Suppress("DEPRECATION") val root = Environment.getExternalStorageDirectory()
            lastThumbPath = File(File(root, KEEP_REL), name).absolutePath
            true
        } else {
            @Suppress("DEPRECATION")
            val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "SonyBridge")
            d.mkdirs()
            val dest = File(d, name)
            f.copyTo(dest, overwrite = true)
            try { MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), null, null) } catch (_: Exception) {}
            lastThumbPath = dest.absolutePath
            true
        }
    } catch (e: Exception) { Log.e(TAG, "keep ${f.name}: ${e.message}"); false } }

    /** Relocate anything left in the private sent/ or the legacy top-level /sdcard/SonyBridge into DCIM/SonyBridge. */
    private fun migrateLeftovers(ctx: Context, sentD: File) {
        (sentD.listFiles() ?: emptyArray()).forEach { if (it.isFile && keep(ctx, it)) it.delete() }
        try {
            @Suppress("DEPRECATION") val legacy = File(Environment.getExternalStorageDirectory(), "SonyBridge")
            if (legacy.isDirectory) {
                (legacy.listFiles() ?: emptyArray()).forEach { if (it.isFile && keep(ctx, it)) it.delete() }
                legacy.delete()
            }
        } catch (_: Exception) {}
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "heic", "heif" -> "image/heic"
        "png" -> "image/png"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "arw" -> "image/x-sony-arw"
        "dng" -> "image/x-adobe-dng"
        else -> "application/octet-stream"
    }

    private fun isoUtc(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ms))
    }
}
