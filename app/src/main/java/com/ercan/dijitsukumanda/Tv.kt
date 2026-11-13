package com.ercan.dijitsukumanda

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.ercan.dijitsukumanda.adb.AdbConnection
import com.ercan.dijitsukumanda.adb.AdbCrypto
import com.ercan.dijitsukumanda.adb.AdbStream
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
                fastStream() // ilk tuşta beklememek için dinleyiciyi hemen başlat
            } catch (_: Exception) {}
        }
    }

    fun disconnect() {
        exec.execute {
            stopFastNow()
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
        monkeyTcp = null; monkeyShell = null; fastFailed = false
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
                        val m = fastStream()
                        if (m != null) {
                            try {
                                for (k in batch) m.write("press " + KeyEvent.keyCodeToString(k) + "\n")
                                continue
                            } catch (e: Exception) {
                                stopFastNow()
                            }
                        }
                        execSync("input keyevent " + batch.joinToString(" "))
                    }
                } finally {
                    keyWorker.set(false)
                    if (hasPendingKeys()) scheduleKeys()
                }
            }
        }
    }

    // ---------- Hızlı tuş modu ----------
    // "input keyevent" her seferinde TV'de yeni bir program başlatır (~0,9 sn).
    // monkey --port ise sürekli açık kalır; tuşları milisaniyeler içinde işler.
    // Yalnızca kumanda ekranı açıkken çalışır, uygulama arka plana geçince kapatılır.

    @Volatile private var monkeyShell: AdbStream? = null
    @Volatile private var monkeyTcp: AdbStream? = null
    @Volatile private var fastFailed = false
    @Volatile var fastWanted = false

    private fun fastStream(): AdbStream? {
        if (!fastWanted || fastFailed || !Prefs.fastKeys(app)) return null
        monkeyTcp?.let { if (!it.closed) return it }
        val c = ensure()
        return try {
            monkeyShell?.close()
            monkeyShell = c.open("shell:monkey --port 1080")
            var t: AdbStream? = null
            for (i in 0 until 10) {
                Thread.sleep(300)
                try { t = c.open("tcp:1080"); break } catch (_: Exception) {}
            }
            if (t == null) { fastFailed = true; stopFastNow(); null } else { monkeyTcp = t; t }
        } catch (e: Exception) {
            fastFailed = true
            stopFastNow()
            null
        }
    }

    private fun stopFastNow() {
        try { monkeyTcp?.let { if (!it.closed) it.write("quit\n") } } catch (_: Exception) {}
        try { monkeyTcp?.close() } catch (_: Exception) {}
        try { monkeyShell?.close() } catch (_: Exception) {}
        monkeyTcp = null
        monkeyShell = null
    }

    /** Uygulama ön plandayken hızlı modu aç/kapat. */
    fun setForeground(fg: Boolean) {
        fastWanted = fg
        if (fg) exec.execute { try { if (isConnected) fastStream() } catch (_: Exception) {} }
        if (!fg) exec.execute {
            stopFastNow()
            try { conn?.exec("pkill -f com.android.commands.monkey", 3000) } catch (_: Exception) {}
        }
    }

    val fastActive get() = monkeyTcp?.closed == false

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
            "media volume --stream 3 --get 2>&1 | grep 'volume is' || cmd media_session volume --stream 3 --get"
        )
        val m = Regex("""volume is (\d+) in range \[(\d+)\.\.(\d+)]""").find(out) ?: return
        volume = m.groupValues[1].toInt()
        volumeMax = m.groupValues[3].toInt().coerceAtLeast(1)
        main.post { onVolume?.invoke(volume, volumeMax) }
    }

    fun volumeSync(level: Int) {
        execSync(
            "if command -v media >/dev/null; then media volume --show --stream 3 --set $level; else cmd media_session volume --show --stream 3 --set $level; fi"
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

    /** Girişe geçmeyi dener; TV niyet (intent) ile geçişi desteklemiyorsa false döner. */
    fun switchInputSync(id: String): Boolean {
        val out = execSync("am start -a android.intent.action.VIEW -d 'content://android.media.tv/passthrough/${Uri.encode(id)}'")
        return !out.contains("Error")
    }

    fun hdmiSync(n: Int) {
        var id = Prefs.hdmiId(app, n)
        if (id.isEmpty() && Prefs.inputs(app).isEmpty()) {
            discoverInputsSync()
            id = Prefs.hdmiId(app, n)
        }
        if (id.isNotEmpty() && switchInputSync(id)) return
        keySync(242 + n) // KEYCODE_TV_INPUT_HDMI_1 = 243
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
