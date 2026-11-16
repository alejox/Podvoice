package com.podvoice.podcam

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity(), StreamService.Listener {
    private lateinit var preview: TextureView
    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var startBtn: Button
    private lateinit var micBtn: Button
    private lateinit var camSpin: Spinner
    private lateinit var resSpin: Spinner
    private lateinit var bitSpin: Spinner
    private var service: StreamService? = null
    private var previewSurface: Surface? = null
    private val prefs by lazy { getSharedPreferences("podcam", Context.MODE_PRIVATE) }

    private val resOptions = listOf(1080, 720, 480)
    private val bitOptions = listOf(4, 8, 12, 16)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as StreamService.LocalBinder).service.also {
                it.listener = this@MainActivity
                it.setPreviewSurface(previewSurface)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        refreshInfo()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, StreamService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        service?.listener = null
        service?.setPreviewSurface(null)
        unbindService(conn); service = null
        super.onStop()
    }

    private fun settings() = StreamSettings(
        facingBack = camSpin.selectedItemPosition == 0,
        height = resOptions[resSpin.selectedItemPosition],
        bitrateMbps = bitOptions[bitSpin.selectedItemPosition],
        micEnabled = prefs.getBoolean("mic", true),
    )

    private fun startStream() {
        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), 1); return }
        // El servicio debe arrancarse (no solo enlazarse) para seguir vivo en segundo plano.
        startForegroundService(Intent(this, StreamService::class.java))
        service?.startStreaming(settings())
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        val camOk = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (camOk && micOk) startStream() else status.text = "Se necesitan permisos de camara y microfono."
    }

    override fun onStatus(streaming: Boolean, clients: Int, error: String?) {
        startBtn.text = if (streaming) "Detener" else "Iniciar"
        status.text = error ?: when {
            !streaming -> "Detenido"
            clients == 0 -> "Transmitiendo · esperando al PC"
            else -> "Transmitiendo · $clients conectado${if (clients > 1) "s" else ""}"
        }
        status.setTextColor(if (error != null) Color.rgb(255, 120, 120) else if (streaming && clients > 0) Color.rgb(80, 210, 140) else Color.LTGRAY)
        refreshInfo()
    }

    private fun refreshInfo() {
        val ips = StreamService.localIps().filter { !it.startsWith("10.0.2.") }
        val port = StreamService.PORT
        info.text = buildString {
            append("WiFi:  tcp://${ips.firstOrNull() ?: "(sin red)"}:$port\n")
            ips.drop(1).forEach { append("           tcp://$it:$port\n") }
            append("USB:   adb forward tcp:$port tcp:$port\n")
            append("           tcp://127.0.0.1:$port\n")
            append("\nOBS: Fuente multimedia, desmarca \"Archivo local\",\nentrada = la URL tcp://, formato = mpegts")
        }
    }

    // ---------------- UI (sin XML) ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun spinner(items: List<String>, key: String, def: Int) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(prefs.getInt(key, def).coerceIn(0, items.size - 1))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (prefs.getInt(key, def) == pos) return
                prefs.edit().putInt(key, pos).apply()
                if (service?.isStreaming == true) service?.startStreaming(settings()) // reinicia el video
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.BLACK) }
        preview = TextureView(this)
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                st.setDefaultBufferSize(1280, 720)
                previewSurface = Surface(st)
                service?.setPreviewSurface(previewSurface)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                service?.setPreviewSurface(null); previewSurface?.release(); previewSurface = null; return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        // Vista previa 16:9 centrada
        val previewBox = object : FrameLayout(this) {
            override fun onMeasure(wSpec: Int, hSpec: Int) {
                super.onMeasure(wSpec, hSpec)
                val w = MeasureSpec.getSize(wSpec); val h = MeasureSpec.getSize(hSpec)
                val (pw, ph) = if (w * 9 > h * 16) Pair(h * 16 / 9, h) else Pair(w, w * 9 / 16)
                preview.measure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ph, MeasureSpec.EXACTLY))
            }
        }
        previewBox.addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(previewBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.6f))

        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val title = TextView(this).apply { text = "Podcam"; textSize = 22f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }
        status = TextView(this).apply { textSize = 15f; setTextColor(Color.LTGRAY); text = "Detenido"; setPadding(0, dp(4), 0, dp(8)) }
        camSpin = spinner(listOf("Camara trasera", "Camara frontal"), "cam", 0)
        resSpin = spinner(resOptions.map { "${it}p" }, "res", 0)
        bitSpin = spinner(bitOptions.map { "$it Mbps" }, "bit", 1)
        startBtn = Button(this).apply {
            text = "Iniciar"; setBackgroundColor(Color.rgb(229, 72, 77)); setTextColor(Color.WHITE)
            setOnClickListener { if (service?.isStreaming == true) service?.stopStreaming() else startStream() }
        }
        micBtn = Button(this).apply {
            fun label() { text = if (prefs.getBoolean("mic", true)) "Microfono: si" else "Microfono: no" }
            label()
            setOnClickListener {
                val on = !prefs.getBoolean("mic", true)
                prefs.edit().putBoolean("mic", on).apply(); label(); service?.setMic(on)
            }
        }
        info = TextView(this).apply { textSize = 13f; setTextColor(Color.GRAY); typeface = Typeface.MONOSPACE; setPadding(0, dp(12), 0, 0) }
        listOf(title, status, camSpin, resSpin, bitSpin, startBtn, micBtn, info).forEach {
            panel.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        root.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        setContentView(root)
    }
}
