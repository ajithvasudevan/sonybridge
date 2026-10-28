package dev.ajithv.sonybridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.DefaultFtplet
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.ftplet.FtpSession
import org.apache.ftpserver.ftplet.FtpletResult
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BridgeService : Service() {

    companion object {
        @Volatile var running = false
        @Volatile var status = "stopped"
        @Volatile var clients = 0                 // open FTP control connections (camera)
        @Volatile var lastClientAddr: String? = null
        @Volatile var lastClientAt = 0L
        private const val CH = "bridge"
        private const val NID = 1
        private const val TAG = "sonybridge"
    }

    private var server: FtpServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startFg(if (running) status else "starting…")
        if (server == null) startFtp()
        return START_STICKY
    }

    private fun inboxDir(): File {
        val base = getExternalFilesDir(null) ?: filesDir
        return File(base, "inbox").apply { mkdirs() }
    }

    private fun sentDir(): File {
        val base = getExternalFilesDir(null) ?: filesDir
        return File(base, "sent").apply { mkdirs() }
    }

    private fun clientIp(session: FtpSession): String? =
        (session.clientAddress as? java.net.InetSocketAddress)?.address?.hostAddress

    /** Watch the hotspot/Wi-Fi IP the camera uploads to; loud red alert on change. */
    private suspend fun ipWatch() {
        val prefs = Prefs(applicationContext)
        var lastIp = prefs.lastApIp
        while (running) {
            try {
                val cur = Net.scan().apIp
                if (cur != null && cur != lastIp) {
                    if (lastIp != null) Notifier.hotspotIpChanged(applicationContext, lastIp!!, cur)
                    lastIp = cur
                    prefs.lastApIp = cur
                }
            } catch (_: Exception) {}
            delay(5000)
        }
    }

    private fun startFtp() {
        try {
            acquireLocks()
            val sf = FtpServerFactory()
            val lf = ListenerFactory().apply { port = BuildConfig.FTP_PORT }
            val dcc = DataConnectionConfigurationFactory().apply { passivePorts = BuildConfig.PASV_PORTS }
            lf.dataConnectionConfiguration = dcc.createDataConnectionConfiguration()
            sf.addListener("default", lf.createListener())

            val um = PropertiesUserManagerFactory().createUserManager()
            um.save(BaseUser().apply {
                name = BuildConfig.FTP_USER
                password = BuildConfig.FTP_PASS
                homeDirectory = inboxDir().absolutePath
                authorities = listOf(WritePermission())
            })
            sf.userManager = um

            sf.ftplets = mapOf("hook" to object : DefaultFtplet() {
                override fun onConnect(session: FtpSession): FtpletResult {
                    clients++
                    lastClientAddr = clientIp(session)
                    lastClientAt = System.currentTimeMillis()
                    Log.i(TAG, "FTP connect from ${session.clientAddress} (clients=$clients)")
                    return FtpletResult.DEFAULT
                }
                override fun onDisconnect(session: FtpSession): FtpletResult {
                    if (clients > 0) clients--
                    lastClientAt = System.currentTimeMillis()
                    Log.i(TAG, "FTP disconnect (clients=$clients)")
                    return FtpletResult.DEFAULT
                }
                override fun onLogin(session: FtpSession, request: FtpRequest): FtpletResult {
                    lastClientAddr = clientIp(session)
                    lastClientAt = System.currentTimeMillis()
                    Log.i(TAG, "FTP login user='${request.argument}' from ${session.clientAddress}")
                    return FtpletResult.DEFAULT
                }
                override fun onUploadEnd(session: FtpSession, request: FtpRequest): FtpletResult {
                    lastClientAt = System.currentTimeMillis()
                    val name = request.argument
                    val f = File(inboxDir(), name ?: "")
                    Log.i(TAG, "STOR complete: $name (${f.length()} bytes)")
                    // mark complete so the uploader sends it immediately (no mid-write upload)
                    name?.let { Uploader.ready.add(it.substringAfterLast('/')) }
                    return FtpletResult.DEFAULT
                }
            })

            val s = sf.createServer()
            s.start()
            server = s
            running = true
            status = "FTP ${Net.localIp()}:${BuildConfig.FTP_PORT}  inbox=${inboxDir().absolutePath}"
            Log.i(TAG, status)
            startFg(status)
            scope.launch { Uploader.loop(applicationContext, inboxDir(), sentDir()) { running } }
            scope.launch { ipWatch() }
        } catch (e: Exception) {
            running = false
            status = "FTP error: ${e.message}"
            Log.e(TAG, status, e)
            startFg(status)
        }
    }

    private fun acquireLocks() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sonybridge:wifi")
            .also { it.setReferenceCounted(false); it.acquire() }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sonybridge:cpu")
            .also { it.setReferenceCounted(false); it.acquire() }
    }

    private fun releaseLocks() {
        try { wifiLock?.release() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        wifiLock = null; wakeLock = null
    }

    private fun createChannel() {
        val ch = NotificationChannel(CH, "Sony Bridge", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun notif(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CH)
            .setContentTitle("Sony Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startFg(text: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, notif(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NID, notif(text))
        }
    }

    override fun onDestroy() {
        running = false
        try { scope.cancel() } catch (_: Exception) {}
        try { server?.stop() } catch (_: Exception) {}
        server = null
        status = "stopped"
        releaseLocks()
        super.onDestroy()
    }
}
