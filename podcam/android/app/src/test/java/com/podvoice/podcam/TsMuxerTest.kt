package com.podvoice.podcam

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Socket

/**
 * Empaqueta un H.264 Annex B y un AAC ADTS (generados con ffmpeg) como lo haria la app
 * y escribe el resultado en un .ts y por TCP. La validacion la hace ffprobe/ffmpeg fuera.
 *   -Dpodcam.h264=in.h264 -Dpodcam.aac=in.aac -Dpodcam.out=out.ts [-Dpodcam.port=4747]
 */
class TsMuxerTest {
    private fun splitAccessUnits(buf: ByteArray): List<ByteArray> {
        // Corta en cada AUD (tipo 9) y lo quita: la app recibe AUs sin AUD de MediaCodec.
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 4 < buf.size) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 0 && buf[i + 3].toInt() == 1 &&
                (buf[i + 4].toInt() and 0x1F) == 9) { starts.add(i); i += 4 } else i++
        }
        starts.add(buf.size)
        return (0 until starts.size - 1).map { k ->
            val au = buf.copyOfRange(starts[k], starts[k + 1])
            au.copyOfRange(6, au.size) // 00 00 00 01 09 xx
        }
    }

    private fun aacFrames(buf: ByteArray): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        var i = 0
        while (i + 7 <= buf.size) {
            val len = ((buf[i + 3].toInt() and 3) shl 11) or ((buf[i + 4].toInt() and 0xFF) shl 3) or ((buf[i + 5].toInt() and 0xFF) shr 5)
            val hdr = if (buf[i + 1].toInt() and 1 == 0) 9 else 7
            frames.add(buf.copyOfRange(i + hdr, i + len)); i += len
        }
        return frames
    }

    private fun buildChunks(): List<Pair<Long, StreamServer.Chunk>> {
        val aus = splitAccessUnits(File(System.getProperty("podcam.h264")!!).readBytes())
        val aac = aacFrames(File(System.getProperty("podcam.aac")!!).readBytes())
        val mux = TsMuxer(48000, 1)
        // Como MediaCodec: el primer AU con SPS/PPS llega aparte como codec config.
        val first = aus[0]
        val idr = nalStart(first, 5)
        mux.setVideoConfig(first.copyOfRange(0, idr))
        val events = ArrayList<Triple<Long, Boolean, ByteArray>>() // (us, esVideo, datos)
        aus.forEachIndexed { k, au -> events.add(Triple(k * 1_000_000L / 30, true, if (k == 0) au.copyOfRange(idr, au.size) else au)) }
        aac.forEachIndexed { k, f -> events.add(Triple(k * 1024 * 1_000_000L / 48000 - 20_000, false, f)) }
        events.sortBy { it.first }
        val base = 5_000_000_000L // reloj del sistema arbitrario
        return events.map { (us, video, data) ->
            us to if (video) {
                val key = 5 in nalTypes(data)
                StreamServer.Chunk(mux.muxVideo(data, base + us, key), key)
            } else StreamServer.Chunk(mux.muxAudio(data, base + us), false)
        }
    }

    private fun nalStart(au: ByteArray, type: Int): Int {
        var i = 0
        while (i + 4 < au.size) {
            if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1 &&
                (au[i + 3].toInt() and 0x1F) == type) return if (i > 0 && au[i - 1].toInt() == 0) i - 1 else i
            i++
        }
        error("NAL $type no encontrado")
    }

    @Test
    fun muxToFile() {
        assumeTrue(System.getProperty("podcam.h264") != null)
        val chunks = buildChunks()
        File(System.getProperty("podcam.out")!!).outputStream().use { o -> chunks.forEach { o.write(it.second.data) } }
        chunks.forEach { check(it.second.data.size % 188 == 0) { "paquetes TS incompletos" } }
    }

    /** Sirve el flujo por TCP, a ritmo real, para que un cliente (ffmpeg) se conecte a mitad. */
    @Test
    fun serveOverTcp() {
        val port = System.getProperty("podcam.port")?.toInt()
        assumeTrue(port != null && System.getProperty("podcam.h264") != null)
        val chunks = buildChunks()
        val server = StreamServer(port!!)
        server.start()
        val t0 = System.nanoTime() / 1000
        for ((us, c) in chunks) { // a ritmo real
            val wait = (t0 + us) - System.nanoTime() / 1000
            if (wait > 0) Thread.sleep(wait / 1000)
            server.send(c)
        }
        Thread.sleep(500)
        server.stop()
    }
}
