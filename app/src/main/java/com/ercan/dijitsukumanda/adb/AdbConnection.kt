package com.ercan.dijitsukumanda.adb

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Harici kütüphanesiz, TCP üzerinden ADB istemcisi (adb connect ip:5555 ile aynı protokol).
 */
class AdbConnection private constructor(private val socket: Socket) {

    companion object {
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val A_STLS = 0x534c5453
        const val VERSION = 0x01000001
        const val MAX_DATA = 256 * 1024
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        /**
         * Bağlanır ve el sıkışmayı tamamlar.
         * onNeedApproval: TV ekranında "USB hata ayıklamasına izin verilsin mi?" sorusu çıktığında çağrılır.
         */
        fun connect(host: String, port: Int, keys: KeyPair, onNeedApproval: () -> Unit): AdbConnection {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 4000)
            s.soTimeout = 10000
            val c = AdbConnection(s)
            try {
                c.handshake(keys, onNeedApproval)
            } catch (e: Exception) {
                try { s.close() } catch (_: Exception) {}
                throw e
            }
            s.soTimeout = 0
            c.startReader()
            return c
        }
    }

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output: OutputStream = socket.getOutputStream()
    private val writeLock = Any()
    private val nextId = AtomicInteger(1)
    private val streams = ConcurrentHashMap<Int, AdbStream>()
    @Volatile var alive = true
        private set
    var banner: String = ""
        private set
    var onDisconnected: (() -> Unit)? = null

    private fun handshake(keys: KeyPair, onNeedApproval: () -> Unit) {
        send(A_CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
        var sentSignature = false
        var sentPublicKey = false
        while (true) {
            val m = read()
            when (m.cmd) {
                A_CNXN -> {
                    banner = String(m.data).trimEnd('\u0000')
                    return
                }
                A_STLS -> throw IOException(
                    "TV, TLS'li kablosuz hata ayıklama istiyor. TV'de klasik 'ADB hata ayıklama' (ağ / 5555) seçeneğini aç."
                )
                A_AUTH -> {
                    if (m.arg0 != AUTH_TOKEN) continue
                    if (!sentSignature) {
                        send(A_AUTH, AUTH_SIGNATURE, 0, AdbCrypto.sign(keys, m.data))
                        sentSignature = true
                    } else if (!sentPublicKey) {
                        send(A_AUTH, AUTH_RSAPUBLICKEY, 0, AdbCrypto.androidPublicKey(keys))
                        sentPublicKey = true
                        socket.soTimeout = 60000 // kullanıcının TV'de onay vermesini bekle
                        onNeedApproval()
                    } else {
                        throw IOException("TV bağlantıyı onaylamadı.")
                    }
                }
                else -> {}
            }
        }
    }

    private fun startReader() {
        Thread({
            try {
                while (alive) {
                    val m = read()
                    val st = streams[m.arg1]
                    when (m.cmd) {
                        A_OKAY -> st?.onOkay(m.arg0)
                        A_WRTE -> {
                            if (st != null) {
                                st.onData(m.data)
                                send(A_OKAY, st.localId, m.arg0, ByteArray(0))
                            }
                        }
                        A_CLSE -> {
                            if (st != null) {
                                streams.remove(st.localId)
                                if (st.remoteId != 0) send(A_CLSE, st.localId, st.remoteId, ByteArray(0))
                                st.onClosed()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                close()
            }
        }, "adb-okuyucu").apply { isDaemon = true }.start()
    }

    fun open(destination: String): AdbStream {
        if (!alive) throw IOException("Bağlantı kopuk")
        val id = nextId.getAndIncrement()
        val st = AdbStream(this, id)
        streams[id] = st
        send(A_OPEN, id, 0, (destination + "\u0000").toByteArray())
        if (!st.awaitOpen(8000)) {
            streams.remove(id)
            throw IOException("Akış açılamadı: $destination")
        }
        return st
    }

    /** Komutu çalıştırıp çıktısının tamamını döndürür. */
    fun exec(cmd: String, timeoutMs: Long = 20000): String {
        val st = open("shell:$cmd")
        st.awaitClose(timeoutMs)
        return st.readAll()
    }

    internal fun writeStream(st: AdbStream, data: ByteArray) {
        send(A_WRTE, st.localId, st.remoteId, data)
    }

    internal fun closeStream(st: AdbStream) {
        if (streams.remove(st.localId) != null && st.remoteId != 0) {
            try { send(A_CLSE, st.localId, st.remoteId, ByteArray(0)) } catch (_: Exception) {}
        }
    }

    fun close() {
        val wasAlive = alive
        alive = false
        try { socket.close() } catch (_: Exception) {}
        for (s in streams.values) s.onClosed()
        streams.clear()
        if (wasAlive) onDisconnected?.invoke()
    }

    private fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray) {
        var sum = 0
        for (b in data) sum += (b.toInt() and 0xFF)
        val bb = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(sum).putInt(cmd.inv())
        bb.put(data)
        synchronized(writeLock) {
            output.write(bb.array())
            output.flush()
        }
    }

    private fun read(): Msg {
        val hdr = ByteArray(24)
        input.readFully(hdr)
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int
        val a0 = bb.int
        val a1 = bb.int
        val len = bb.int
        if (len < 0 || len > 16 * 1024 * 1024) throw IOException("Geçersiz ADB paketi")
        val data = ByteArray(len)
        if (len > 0) input.readFully(data)
        return Msg(cmd, a0, a1, data)
    }
}

class AdbStream internal constructor(private val conn: AdbConnection, val localId: Int) {
    @Volatile var remoteId = 0
        private set
    @Volatile var closed = false
        private set
    private val opened = CountDownLatch(1)
    private val closedLatch = CountDownLatch(1)
    private val canWrite = Semaphore(1)
    private val buffer = ByteArrayOutputStream()

    internal fun onOkay(remote: Int) {
        if (remoteId == 0) {
            remoteId = remote
            opened.countDown()
        } else {
            canWrite.release()
        }
    }

    internal fun onData(d: ByteArray) {
        synchronized(buffer) { buffer.write(d) }
    }

    internal fun onClosed() {
        closed = true
        opened.countDown()
        closedLatch.countDown()
        canWrite.release()
    }

    internal fun awaitOpen(ms: Long): Boolean {
        opened.await(ms, TimeUnit.MILLISECONDS)
        return remoteId != 0 && !closed
    }

    fun awaitClose(ms: Long) {
        closedLatch.await(ms, TimeUnit.MILLISECONDS)
    }

    fun write(s: String) {
        if (closed) throw IOException("Akış kapalı")
        if (!canWrite.tryAcquire(5, TimeUnit.SECONDS)) throw IOException("TV yanıt vermiyor")
        if (closed) throw IOException("Akış kapalı")
        conn.writeStream(this, s.toByteArray())
    }

    fun readAll(): String = synchronized(buffer) { buffer.toString("UTF-8") }

    fun clear() = synchronized(buffer) { buffer.reset() }

    fun close() {
        conn.closeStream(this)
        onClosed()
    }
}
