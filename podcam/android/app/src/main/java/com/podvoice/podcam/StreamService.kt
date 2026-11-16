package com.podvoice.podcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Surface
import java.net.Inet4Address
import java.net.NetworkInterface

/** Servicio en primer plano: sigue transmitiendo con la pantalla apagada o con otra app abierta. */
class StreamService : Service() {
    companion object {
        const val PORT = 4747
        private const val CHANNEL = "podcam"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.podvoice.podcam.STOP"

        fun localIps(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }.filter { it.isNotEmpty() }
        } catch (_: Exception) { emptyList() }
    }

    inner class LocalBinder : Binder() { val service get() = this@StreamService }

    interface Listener { fun onStatus(streaming: Boolean, clients: Int, error: String?) }

    private val main = Handler(Looper.getMainLooper())
    private var server: StreamServer? = null
    private var streamer: Streamer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var previewSurface: Surface? = null
    var listener: Listener? = null
        set(v) { field = v; notifyStatus(null) }
    val isStreaming get() = streamer != null
    val clients get() = server?.clientCount ?: 0

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopStreaming(); stopSelf() }
        return START_NOT_STICKY
    }

    fun startStreaming(s: StreamSettings) {
        if (streamer != null) return streamer!!.restartVideo(s)
        goForeground()
        val mux = TsMuxer(Streamer.SAMPLE_RATE, 1)
        lateinit var st: Streamer
        val srv = StreamServer(PORT,
            onClientsChanged = { n -> main.post { updateNotification(n); notifyStatus(null) } },
            onClientJoined = { st.requestKeyFrame() })
        st = Streamer(this, srv, mux).apply { onError = { msg -> main.post { notifyStatus(msg) } } }
        try { srv.start() } catch (e: Exception) {
            notifyStatus("No se pudo abrir el puerto $PORT: ${e.message}"); stopForegroundCompat(); return
        }
        server = srv; streamer = st
        st.setPreviewSurface(previewSurface)
        st.start(s)
        acquireLocks()
        notifyStatus(null)
    }

    fun setMic(enabled: Boolean) { streamer?.micEnabled = enabled }

    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        streamer?.setPreviewSurface(surface)
    }

    fun stopStreaming() {
        streamer?.release(); streamer = null
        server?.stop(); server = null
        releaseLocks()
        stopForegroundCompat()
        notifyStatus(null)
    }

    override fun onDestroy() { stopStreaming(); super.onDestroy() }

    private fun notifyStatus(err: String?) { listener?.onStatus(isStreaming, clients, err) }

    private fun buildNotification(clients: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Transmision", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val ip = localIps().firstOrNull() ?: "USB"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Podcam transmitiendo")
            .setContentText("$ip:$PORT · ${if (clients == 1) "1 cliente" else "$clients clientes"}")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun goForeground() {
        val n = buildNotification(0)
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(NOTIF_ID, n)
    }

    private fun updateNotification(clients: Int) {
        if (streamer == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(clients))
    }

    private fun stopForegroundCompat() { stopForeground(STOP_FOREGROUND_REMOVE) }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "podcam:stream").apply { acquire() }
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = applicationContext.getSystemService(WifiManager::class.java).createWifiLock(mode, "podcam:wifi").apply { acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }; wifiLock = null
    }
}
