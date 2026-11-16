package com.podvoice.podcam

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Servidor TCP que entrega el flujo MPEG-TS a cada cliente (OBS, VLC, ffmpeg).
 * Sirve igual por WiFi (IP del celular) que por USB (adb forward tcp:4747 tcp:4747).
 * Sin dependencias de Android.
 */
class StreamServer(
    private val port: Int,
    private val onClientsChanged: (Int) -> Unit = {},
    private val onClientJoined: () -> Unit = {}, // p.ej. pedir un keyframe al encoder
) {
    class Chunk(val data: ByteArray, val keyFrame: Boolean)

    private inner class Client(val socket: Socket) {
        val queue = LinkedBlockingQueue<Chunk>()
        @Volatile var queuedBytes = 0L
        @Volatile var waitingKey = true
        @Volatile var alive = true

        fun offer(c: Chunk) {
            if (waitingKey) { if (!c.keyFrame) return; waitingKey = false }
            if (queuedBytes > MAX_QUEUE_BYTES) { // cliente lento: descarta hasta el siguiente keyframe
                queue.clear(); queuedBytes = 0; waitingKey = !c.keyFrame
                if (waitingKey) return
            }
            queuedBytes += c.data.size
            queue.offer(c)
        }

        fun run() {
            try {
                socket.tcpNoDelay = true
                socket.sendBufferSize = 512 * 1024
                val out = socket.getOutputStream()
                while (alive && running) {
                    val c = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    queuedBytes -= c.data.size
                    out.write(c.data)
                }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            } finally {
                alive = false
                try { socket.close() } catch (_: IOException) {}
                clients.remove(this)
                onClientsChanged(clients.size)
            }
        }
    }

    companion object { const val MAX_QUEUE_BYTES = 4L * 1024 * 1024 }

    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var running = false
    private var server: ServerSocket? = null

    val clientCount get() = clients.size

    fun start() {
        val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
        server = ss
        running = true
        Thread({
            while (running) {
                val s = try { ss.accept() } catch (_: IOException) { break }
                val c = Client(s)
                clients.add(c)
                onClientsChanged(clients.size)
                Thread(c::run, "podcam-client").start()
                onClientJoined()
            }
        }, "podcam-accept").start()
    }

    fun send(chunk: Chunk) { for (c in clients) c.offer(chunk) }

    fun stop() {
        running = false
        try { server?.close() } catch (_: IOException) {}
        for (c in clients) { c.alive = false; try { c.socket.close() } catch (_: IOException) {} }
        clients.clear()
    }
}
