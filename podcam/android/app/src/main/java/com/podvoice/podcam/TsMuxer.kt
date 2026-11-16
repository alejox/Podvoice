package com.podvoice.podcam

import java.io.ByteArrayOutputStream

/**
 * Muxer MPEG-TS minimo: H.264 (Annex B) + AAC (ADTS). Sin dependencias de Android,
 * para poder probarlo en la JVM. OBS, VLC y ffmpeg leen este flujo directamente.
 */
class TsMuxer(
    private val sampleRate: Int = 48000,
    private val channels: Int = 1,
) {
    companion object {
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x100
        const val AUDIO_PID = 0x101
        private const val PTS_OFFSET_90K = 90_000L // 1 s para que el audio previo al video no quede negativo
        private val AUD = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())
        private val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    }

    private val cc = HashMap<Int, Int>()
    private var baseUs = Long.MIN_VALUE
    private var videoConfig: ByteArray? = null // SPS + PPS en Annex B

    fun setVideoConfig(csd: ByteArray) { videoConfig = csd }

    @Synchronized
    fun muxVideo(au: ByteArray, ptsUs: Long, keyFrame: Boolean): ByteArray {
        val out = ByteArrayOutputStream(au.size + au.size / 160 * 8 + 1024)
        val pts = to90k(ptsUs)
        if (keyFrame) out.write(psi())
        val payload = ByteArrayOutputStream(au.size + 64)
        if (!startsWithAud(au)) payload.write(AUD)
        val cfg = videoConfig
        if (keyFrame && cfg != null && !containsNal(au, 7)) payload.write(cfg)
        payload.write(au)
        writePes(out, VIDEO_PID, 0xE0, payload.toByteArray(), pts, pcr = (pts - 9000).coerceAtLeast(0))
        return out.toByteArray()
    }

    /** [aac] es un frame AAC crudo (como lo entrega MediaCodec); se le anade cabecera ADTS. */
    @Synchronized
    fun muxAudio(aac: ByteArray, ptsUs: Long): ByteArray {
        val out = ByteArrayOutputStream(aac.size + 256)
        writePes(out, AUDIO_PID, 0xC0, adts(aac.size) + aac, to90k(ptsUs), pcr = null)
        return out.toByteArray()
    }

    @Synchronized
    fun psi(): ByteArray {
        val out = ByteArrayOutputStream(376)
        // PAT: programa 1 -> PMT
        out.write(psiPacket(0, section(0x00, 1, byteArrayOf(0, 1, (0xE0 or (PMT_PID shr 8)).toByte(), PMT_PID.toByte()))))
        // PMT: PCR en el video; 0x1B = H.264, 0x0F = AAC ADTS
        val pmt = byteArrayOf(
            (0xE0 or (VIDEO_PID shr 8)).toByte(), VIDEO_PID.toByte(), 0xF0.toByte(), 0,
            0x1B, (0xE0 or (VIDEO_PID shr 8)).toByte(), VIDEO_PID.toByte(), 0xF0.toByte(), 0,
            0x0F, (0xE0 or (AUDIO_PID shr 8)).toByte(), AUDIO_PID.toByte(), 0xF0.toByte(), 0,
        )
        out.write(psiPacket(PMT_PID, section(0x02, 1, pmt)))
        return out.toByteArray()
    }

    private fun to90k(ptsUs: Long): Long {
        if (baseUs == Long.MIN_VALUE) baseUs = ptsUs
        return ((ptsUs - baseUs) * 9 / 100 + PTS_OFFSET_90K) and 0x1FFFFFFFFL
    }

    private fun nextCc(pid: Int): Int {
        val v = cc[pid] ?: 0
        cc[pid] = (v + 1) and 0xF
        return v
    }

    private fun writePes(out: ByteArrayOutputStream, pid: Int, streamId: Int, payload: ByteArray, pts: Long, pcr: Long?) {
        val pesLen = 3 + 5 + payload.size
        val header = byteArrayOf(
            0, 0, 1, streamId.toByte(),
            (if (pesLen > 0xFFFF || streamId == 0xE0) 0 else pesLen shr 8).toByte(),
            (if (pesLen > 0xFFFF || streamId == 0xE0) 0 else pesLen).toByte(),
            0x80.toByte(), 0x80.toByte(), 5,
            (0x21 or ((pts shr 29) and 0x0E).toInt()).toByte(),
            (pts shr 22).toByte(),
            (((pts shr 14) and 0xFE) or 1).toByte(),
            (pts shr 7).toByte(),
            (((pts shl 1) and 0xFE) or 1).toByte(),
        )
        val data = header + payload
        var off = 0
        var first = true
        while (off < data.size) {
            off += packet(out, pid, first, if (first) pcr else null, data, off, data.size - off)
            first = false
        }
    }

    /** Escribe un paquete de 188 bytes y devuelve cuantos bytes de [data] consumio. */
    private fun packet(out: ByteArrayOutputStream, pid: Int, pusi: Boolean, pcr: Long?, data: ByteArray, off: Int, len: Int): Int {
        val payloadLen = minOf(len, 184 - (if (pcr != null) 8 else 0))
        val afTotal = 184 - payloadLen
        out.write(0x47)
        out.write((if (pusi) 0x40 else 0) or ((pid shr 8) and 0x1F))
        out.write(pid and 0xFF)
        out.write((if (afTotal > 0) 0x30 else 0x10) or nextCc(pid))
        if (afTotal > 0) {
            out.write(afTotal - 1)
            if (afTotal > 1) {
                var written = 1
                out.write(if (pcr != null) 0x10 else 0x00)
                if (pcr != null) {
                    out.write((pcr shr 25).toInt()); out.write((pcr shr 17).toInt())
                    out.write((pcr shr 9).toInt()); out.write((pcr shr 1).toInt())
                    out.write((((pcr and 1) shl 7) or 0x7E).toInt()); out.write(0)
                    written += 6
                }
                repeat(afTotal - 1 - written) { out.write(0xFF) }
            }
        }
        out.write(data, off, payloadLen)
        return payloadLen
    }

    private fun section(tableId: Int, idExt: Int, body: ByteArray): ByteArray {
        val len = 5 + body.size + 4
        val s = byteArrayOf(
            tableId.toByte(), (0xB0 or (len shr 8)).toByte(), len.toByte(),
            (idExt shr 8).toByte(), idExt.toByte(), 0xC1.toByte(), 0, 0,
        ) + body
        val crc = crc32(s)
        return s + byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte())
    }

    private fun psiPacket(pid: Int, section: ByteArray): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47; p[1] = (0x40 or (pid shr 8)).toByte(); p[2] = pid.toByte()
        p[3] = (0x10 or nextCc(pid)).toByte(); p[4] = 0 // pointer_field
        System.arraycopy(section, 0, p, 5, section.size)
        return p
    }

    private fun adts(aacLen: Int): ByteArray {
        val freqIdx = SAMPLE_RATES.indexOf(sampleRate).let { if (it < 0) 3 else it }
        val len = aacLen + 7
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            ((1 shl 6) or (freqIdx shl 2) or (channels shr 2)).toByte(), // perfil AAC-LC
            (((channels and 3) shl 6) or (len shr 11)).toByte(),
            (len shr 3).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    private fun crc32(d: ByteArray): Int {
        var crc = -1
        for (b in d) {
            crc = crc xor ((b.toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc and 0x80000000.toInt() != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc
    }

    private fun startsWithAud(au: ByteArray) = nalTypes(au).firstOrNull() == 9

    private fun containsNal(au: ByteArray, type: Int) = type in nalTypes(au)
}

/** Tipos de NAL presentes en un buffer Annex B. */
fun nalTypes(buf: ByteArray): List<Int> {
    val types = ArrayList<Int>()
    var i = 0
    while (i + 3 < buf.size) {
        if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1) {
            types.add(buf[i + 3].toInt() and 0x1F); i += 3
        } else i++
    }
    return types
}
