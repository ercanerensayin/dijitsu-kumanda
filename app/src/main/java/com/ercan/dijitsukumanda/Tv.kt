package com.ercan.dijitsukumanda

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.ercan.dijitsukumanda.adb.AdbConnection
import com.ercan.dijitsukumanda.adb.AdbCrypto
import java.security.KeyPair
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** TV ile tüm iletişim buradan geçer. Komutlar tek bir sırada (kuyrukta) çalışır. */
object Tv {
    enum class State { KOPUK, BAGLANIYOR, ONAY_BEKLENIYOR, BAGLI }

    private lateinit var app: Context
    private lateinit var keys: KeyPair
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var conn: AdbConnection? = null

    @Volatile var state = State.KOPUK
        private set
    var lastError = ""
        private set
    var onState: ((State, String) -> Unit)? = null

    // Ses durumu (TV'den okunur)
    @Volatile var volume = -1
        private set
    @Volatile var volumeMax = 15
        private set
    var onVolume: ((Int, Int) -> Unit)? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        exec.execute { keys = AdbCrypto.loadOrCreate(app) }
    }

    val isConnected get() = state == State.BAGLI && conn?.alive == true

    private fun setState(s: State, msg: String = "") {
        state = s
        if (s == State.KOPUK && msg.isNotEmpty()) lastError = msg
        main.post { onState?.invoke(s, msg) }
    }

    fun connect(host: String = Prefs.host(app), port: Int = Prefs.port(app)) {
        exec.execute {
            try {
                connectNow(host, port)
                readVolumeSync()
            } catch (_: Exception) {}
        }
    }

    fun disconnect() {
        exec.execute {
            conn?.close()
            conn = null
            setState(State.KOPUK)
        }
    }

    private fun connectNow(host: String, port: Int) {
        if (host.isBlank()) {
            setState(State.KOPUK, "Önce Ayarlar'dan TV'nin IP adresini gir.")
            throw IllegalStateException("IP yok")
        }
        conn?.close()
        conn = null
        setState(State.BAGLANIYOR, "$host:$port")
        try {
            val c = AdbConnection.connect(host, port, keys) {
                setState(State.ONAY_BEKLENIYOR, "TV ekranındaki izin sorusunu onayla (\"Her zaman izin ver\" kutusunu da işaretle).")
            }
            c.onDisconnected = {
                if (conn === c) {
                    conn = null
                    setState(State.KOPUK, "Bağlantı koptu")
                }
            }
            conn = c
            Prefs.setHost(app, host, port)
            setState(State.BAGLI, c.banner)
        } catch (e: Exception) {
            setState(State.KOPUK, hataMesaji(e))
            throw e
        }
    }

    private fun hataMesaji(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("ECONNREFUSED") || m.contains("refused", true) ->
                "TV bağlantıyı reddetti. TV'de Geliştirici seçenekleri > ADB / Ağ hata ayıklaması açık mı?"
            m.contains("timed out", true) || m.contains("EHOSTUNREACH") || m.contains("ENETUNREACH") ->
                "TV'ye ulaşılamadı. IP doğru mu, telefon ve TV aynı Wi-Fi'da mı, TV açık mı?"
            else -> m
        }
    }

    private fun ensure(): AdbConnection {
        val c = conn
        if (c != null && c.alive) return c
        connectNow(Prefs.host(app), Prefs.port(app))
        return conn!!
    }

    private fun submit(block: () -> Unit): Future<*> = exec.submit {
        try {
            block()
        } catch (e: Exception) {
            try {
                conn?.close(); conn = null
                block()
            } catch (e2: Exception) {
                setState(State.KOPUK, hataMesaji(e2))
            }
        }
    }

    /** Makrolar için: komutu kuyruğa atar ve bitmesini bekler. */
    fun await(block: () -> Unit) {
        submit(block).get(30, TimeUnit.SECONDS)
    }

    // ---------- Tuşlar: birikmeyi önleyen kuyruk ----------
    // Her basış ayrı komut olursa TV yetişemez ve bırakınca bile devam eder.
    // Bunun yerine bekleyen basışlar toplanıp tek komutta gönderilir
    // ve önceki komut bitmeden yenisi gönderilmez.

    private val pendingKeys = ArrayDeque<Int>()
    private val keyWorker = AtomicBoolean(false)

    fun key(code: Int) {
        synchronized(pendingKeys) { if (pendingKeys.size < 20) pendingKeys.addLast(code) }
        scheduleKeys()
    }

    fun hasPendingKeys(): Boolean = synchronized(pendingKeys) { pendingKeys.isNotEmpty() }

    fun clearPendingKeys() = synchronized(pendingKeys) { pendingKeys.clear() }

    private fun scheduleKeys() {
        if (keyWorker.compareAndSet(false, true)) {
            submit {
                try {
                    while (true) {
                        val batch = synchronized(pendingKeys) {
                            val b = ArrayList<Int>()
                            while (pendingKeys.isNotEmpty() && b.size < 10) b.add(pendingKeys.removeFirst())
                            b
                        }
                        if (batch.isEmpty()) break
                        execSync("input keyevent " + batch.joinToString(" "))
                    }
                } finally {
                    keyWorker.set(false)
                    if (hasPendingKeys()) scheduleKeys()
                }
            }
        }
    }

    // ---------- Ses: yalnızca en son istenen değer uygulanır ----------

    private val pendingVolume = AtomicInteger(-1)
    private val volWorker = AtomicBoolean(false)

    /** Sesi doğrudan bu seviyeye ayarlar (art arda çağrılarda aradakiler atlanır). */
    fun setVolume(level: Int) {
        val v = level.coerceIn(0, volumeMax)
        volume = v
        main.post { onVolume?.invoke(v, volumeMax) }
        pendingVolume.set(v)
        if (volWorker.compareAndSet(false, true)) {
            submit {
                try {
                    while (true) {
                        val target = pendingVolume.getAndSet(-1)
                        if (target < 0) break
                        volumeSync(target)
                    }
                } finally {
                    volWorker.set(false)
                    if (pendingVolume.get() >= 0) setVolume(pendingVolume.get())
                }
            }
        }
    }

    /** Ses +/- : seviye biliniyorsa doğrudan ayarla, bilinmiyorsa tuş gönder. */
    fun volumeStep(delta: Int) {
        if (volume >= 0) setVolume(volume + delta)
        else key(if (delta > 0) Keys.VOL_UP else Keys.VOL_DOWN)
    }

    fun refreshVolume() = submit { readVolumeSync() }

    private fun readVolumeSync() {
        val out = execSync(
            "cmd media_session volume --stream 3 --get 2>/dev/null || media volume --stream 3 --get"
        )
        val m = Regex("""volume is (\d+) in range \[(\d+)\.\.(\d+)]""").find(out) ?: return
        volume = m.groupValues[1].toInt()
        volumeMax = m.groupValues[3].toInt().coerceAtLeast(1)
        main.post { onVolume?.invoke(volume, volumeMax) }
    }

    fun volumeSync(level: Int) {
        execSync(
            "cmd media_session volume --show --stream 3 --set $level 2>/dev/null || media volume --show --stream 3 --set $level"
        )
    }

    // ---------- Diğer komutlar ----------

    fun text(t: String) = submit { textSync(t) }
    fun hdmi(n: Int) = submit { hdmiSync(n) }

    fun run(cmd: String, done: ((String) -> Unit)? = null) {
        submit {
            val out = execSync(cmd)
            if (done != null) main.post { done(out) }
        }
    }

    fun keySync(code: Int) {
        execSync("input keyevent $code")
    }

    fun textSync(t: String) {
        val clean = turkceSadele(t)
        if (clean.isEmpty()) return
        val arg = clean.replace(" ", "%s").replace("'", "'\\''")
        execSync("input text '$arg'")
    }

    fun execSync(cmd: String): String = ensure().exec(cmd)

    fun launchSync(app: AppItem) {
        if (app.component != null) {
            execSync("am start -n ${app.component}")
        } else {
            execSync(
                "monkey -p ${app.pkg} -c android.intent.category.LEANBACK_LAUNCHER 1 2>&1 | grep -q aborted && " +
                    "monkey -p ${app.pkg} -c android.intent.category.LAUNCHER 1"
            )
        }
    }

    // ---------- HDMI girişleri ----------

    fun discoverInputsSync(): List<String> {
        val out = execSync("dumpsys tv_input")
        val ids = LinkedHashSet<String>()
        Regex("""id=([^,\s}]+)""").findAll(out).forEach { ids.add(it.groupValues[1]) }
        Regex("""([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+/HW\d+)""").findAll(out).forEach { ids.add(it.groupValues[1]) }
        val list = ids.filter { it.contains("/") }.toList()
        val hdmis = list.filter { it.contains("hdmi", true) }
            .sortedBy { Regex("""HW(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 999 }
        Prefs.setInputs(app, list)
        for (i in 0 until minOf(3, hdmis.size)) {
            if (Prefs.hdmiId(app, i + 1).isEmpty()) Prefs.setHdmiId(app, i + 1, hdmis[i])
        }
        return list
    }

    fun switchInputSync(id: String) {
        execSync("am start -a android.intent.action.VIEW -d 'content://android.media.tv/passthrough/${Uri.encode(id)}'")
    }

    fun hdmiSync(n: Int) {
        var id = Prefs.hdmiId(app, n)
        if (id.isEmpty() && Prefs.inputs(app).isEmpty()) {
            discoverInputsSync()
            id = Prefs.hdmiId(app, n)
        }
        if (id.isNotEmpty()) switchInputSync(id)
        else keySync(242 + n) // KEYCODE_TV_INPUT_HDMI_1 = 243
    }

    fun turkceSadele(s: String): String {
        val map = mapOf(
            'ı' to 'i', 'İ' to 'I', 'ş' to 's', 'Ş' to 'S', 'ğ' to 'g', 'Ğ' to 'G',
            'ü' to 'u', 'Ü' to 'U', 'ö' to 'o', 'Ö' to 'O', 'ç' to 'c', 'Ç' to 'C'
        )
        val sb = StringBuilder()
        for (ch in s) {
            val c = map[ch] ?: ch
            if (c.code in 32..126) sb.append(c)
        }
        return sb.toString()
    }
}
