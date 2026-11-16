package com.podvoice.podcam

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlin.math.abs

data class StreamSettings(
    val facingBack: Boolean = true,
    val height: Int = 1080,
    val fps: Int = 30,
    val bitrateMbps: Int = 8,
    val micEnabled: Boolean = true,
)

/** Camara (Camera2) -> H.264 por hardware y microfono -> AAC; todo hacia [TsMuxer] y [StreamServer]. */
class Streamer(private val ctx: Context, private val server: StreamServer, private val mux: TsMuxer) {
    companion object {
        const val TAG = "Podcam"
        const val SAMPLE_RATE = 48000
    }

    @Volatile var settings = StreamSettings()
        private set
    var videoSize: Size = Size(1920, 1080)
        private set
    var onError: (String) -> Unit = {}

    private val camThread = HandlerThread("podcam-camera").apply { start() }
    private val camHandler = Handler(camThread.looper)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var previewSurface: Surface? = null
    @Volatile private var videoRunning = false
    @Volatile private var audioRunning = false
    @Volatile var micEnabled = true
    private var useRealtimeClock = false
    private var audioThread: Thread? = null

    /** Reloj comun para sincronizar audio con los timestamps de la camara. */
    private fun clockNanos() = if (useRealtimeClock) SystemClock.elapsedRealtimeNanos() else System.nanoTime()

    fun start(s: StreamSettings) {
        settings = s
        micEnabled = s.micEnabled
        startVideo()
        if (audioThread == null) startAudio()
    }

    /** Cambia camara/resolucion/bitrate sin cortar a los clientes conectados. */
    fun restartVideo(s: StreamSettings) {
        settings = s
        micEnabled = s.micEnabled
        camHandler.post { stopVideoInternal(); startVideoInternal() }
    }

    fun setPreviewSurface(surface: Surface?) {
        camHandler.post {
            previewSurface = surface
            if (camera != null) createSession()
        }
    }

    fun requestKeyFrame() {
        try { encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        catch (e: IllegalStateException) { }
    }

    fun stop() {
        audioRunning = false
        audioThread?.join(1000); audioThread = null
        val done = Object()
        camHandler.post { stopVideoInternal(); synchronized(done) { done.notifyAll() } }
        synchronized(done) { done.wait(2000) }
    }

    fun release() { stop(); camThread.quitSafely() }

    // ---------------- Video ----------------

    private fun startVideo() = camHandler.post { startVideoInternal() }

    private fun pickCamera(mgr: CameraManager, back: Boolean): String? {
        val wanted = if (back) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        return mgr.cameraIdList.firstOrNull { mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted }
            ?: mgr.cameraIdList.firstOrNull()
    }

    private fun pickSize(chars: CameraCharacteristics, height: Int): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty()
            .ifEmpty { map.getOutputSizes(android.graphics.SurfaceTexture::class.java).toList() }
        val wide = sizes.filter { abs(it.width.toDouble() / it.height - 16.0 / 9) < 0.02 }.ifEmpty { sizes }
        return wide.filter { it.height <= height }.maxByOrNull { it.height } ?: wide.minByOrNull { it.height }!!
    }

    @SuppressLint("MissingPermission")
    private fun startVideoInternal() {
        try {
            val mgr = ctx.getSystemService(CameraManager::class.java)
            val id = pickCamera(mgr, settings.facingBack) ?: return onError("No se encontro camara")
            val chars = mgr.getCameraCharacteristics(id)
            useRealtimeClock = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            videoSize = pickSize(chars, settings.height)
            startEncoder()
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) { camera = device; createSession() }
                override fun onDisconnected(device: CameraDevice) { device.close(); if (camera == device) camera = null }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); if (camera == device) camera = null
                    onError("Error de camara ($error). Otra app puede estar usandola.")
                }
            }, camHandler)
        } catch (e: Exception) {
            Log.e(TAG, "startVideo", e); onError("No se pudo iniciar la camara: ${e.message}")
        }
    }

    private fun createSession() {
        val cam = camera ?: return
        val enc = encoderSurface ?: return
        session?.close(); session = null
        val targets = listOfNotNull(enc, previewSurface)
        try {
            @Suppress("DEPRECATION")
            cam.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (camera != cam) { s.close(); return }
                    session = s
                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        targets.forEach { addTarget(it) }
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(settings.fps, settings.fps))
                    }
                    try { s.setRepeatingRequest(req.build(), null, camHandler) } catch (e: Exception) { Log.e(TAG, "repeat", e) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    // Algunos equipos no aceptan preview + encoder a esta resolucion: sigue sin preview.
                    if (previewSurface != null) { previewSurface = null; createSession() }
                    else onError("La camara no acepto la configuracion")
                }
            }, camHandler)
        } catch (e: Exception) { Log.e(TAG, "session", e); onError("Error de sesion: ${e.message}") }
    }

    private fun startEncoder() {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoSize.width, videoSize.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrateMbps * 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // tiempo real
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000) // mantiene el flujo si la escena no cambia
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = codec.createInputSurface()
        codec.start()
        encoder = codec
        videoRunning = true
        Thread({ drainVideo(codec) }, "podcam-venc").start()
    }

    private fun drainVideo(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (videoRunning && encoder === codec) {
                val idx = codec.dequeueOutputBuffer(info, 100_000)
                if (idx < 0) continue
                val buf = codec.getOutputBuffer(idx)!!
                val data = ByteArray(info.size)
                buf.position(info.offset); buf.get(data)
                codec.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { mux.setVideoConfig(data); continue }
                if (info.size == 0) continue
                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                server.send(StreamServer.Chunk(mux.muxVideo(data, info.presentationTimeUs, key), key))
            }
        } catch (e: IllegalStateException) { /* encoder detenido */ }
    }

    private fun stopVideoInternal() {
        videoRunning = false
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        val enc = encoder; encoder = null
        try { enc?.stop() } catch (_: Exception) {}
        try { enc?.release() } catch (_: Exception) {}
        encoderSurface?.release(); encoderSurface = null
    }

    // ---------------- Audio ----------------

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        audioRunning = true
        audioThread = Thread({
            var rec: AudioRecord? = null
            var codec: MediaCodec? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192))
                val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
                }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                rec.startRecording()
                val pcm = ByteArray(2048) // 1024 muestras = 1 frame AAC
                val info = MediaCodec.BufferInfo()
                while (audioRunning) {
                    var n = 0
                    while (n < pcm.size && audioRunning) {
                        val r = rec.read(pcm, n, pcm.size - n)
                        if (r < 0) throw IllegalStateException("AudioRecord $r")
                        n += r
                    }
                    val ptsUs = (clockNanos() / 1000) - 1024L * 1_000_000 / SAMPLE_RATE
                    if (!micEnabled) pcm.fill(0)
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        codec.getInputBuffer(inIdx)!!.apply { clear(); put(pcm, 0, n) }
                        codec.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                    }
                    while (true) {
                        val o = codec.dequeueOutputBuffer(info, 0)
                        if (o < 0) break
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            val buf = codec.getOutputBuffer(o)!!
                            val data = ByteArray(info.size)
                            buf.position(info.offset); buf.get(data)
                            if (videoRunning) server.send(StreamServer.Chunk(mux.muxAudio(data, info.presentationTimeUs), false))
                        }
                        codec.releaseOutputBuffer(o, false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "audio", e); onError("Microfono no disponible: ${e.message}")
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                rec?.release()
                try { codec?.stop() } catch (_: Exception) {}
                codec?.release()
            }
        }, "podcam-audio").apply { start() }
    }
}
