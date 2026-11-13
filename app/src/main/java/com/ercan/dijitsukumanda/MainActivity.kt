package com.ercan.dijitsukumanda

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class MainActivity : Activity() {

    private val BG = Color.parseColor("#101114")
    private val CARD = Color.parseColor("#24262D")
    private val ACCENT = Color.parseColor("#3D7EFF")
    private val RED = Color.parseColor("#C8384A")
    private val GREEN = Color.parseColor("#2E9E5B")
    private val TEXT = Color.parseColor("#ECEDEF")
    private val DIM = Color.parseColor("#9A9DA6")

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var connectBtn: TextView
    private lateinit var content: FrameLayout
    private val tabs = ArrayList<TextView>()
    private var currentTab = 0
    private val volumeBars = ArrayList<VolumeBar>()
    private var lastTextInput: EditText? = null

    private val REQ_DICTATE = 11
    private val REQ_COMMAND = 12

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            fitsSystemWindows = true
        }

        // Üst durum çubuğu
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(6))
        }
        statusText = TextView(this).apply { setTextColor(DIM); textSize = 14f }
        top.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        connectBtn = btn("Bağlan", ACCENT) {
            if (Tv.isConnected) Tv.disconnect()
            else if (Prefs.host(this).isBlank()) showTab(4)
            else Tv.connect()
        }
        top.addView(connectBtn, LinearLayout.LayoutParams(dp(110), dp(42)))
        root.addView(top)

        // Sekmeler
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), 0, dp(6), dp(4)) }
        listOf("Kumanda", "Touchpad", "Uygulama", "Makro", "Ayarlar").forEachIndexed { i, name ->
            val t = TextView(this).apply {
                text = name; gravity = Gravity.CENTER; textSize = 13f
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { showTab(i) }
            }
            tabs.add(t)
            tabRow.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabRow)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        Tv.onState = { s, msg ->
            updateStatus(s, msg)
            if (s == Tv.State.BAGLI) Tv.refreshVolume()
        }
        Tv.onVolume = { v, m -> volumeBars.forEach { it.set(v, m) } }
        updateStatus(Tv.state, "")
        showTab(0)

        showCrashIfAny()
        if (!Tv.isConnected && Prefs.host(this).isNotBlank()) Tv.connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        Tv.onState = null
        Tv.onVolume = null
    }

    // Telefonun ses tuşları → TV sesi
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if ((code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            Prefs.volumeKeys(this) && Tv.isConnected
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                Tv.volumeStep(if (code == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun updateStatus(s: Tv.State, msg: String) {
        val host = Prefs.host(this)
        when (s) {
            Tv.State.BAGLI -> {
                statusText.text = "● Bağlı  $host"; statusText.setTextColor(GREEN)
                connectBtn.text = "Kes"; setBg(connectBtn, CARD)
            }
            Tv.State.BAGLANIYOR -> {
                statusText.text = "Bağlanıyor… $msg"; statusText.setTextColor(DIM)
            }
            Tv.State.ONAY_BEKLENIYOR -> {
                statusText.text = "TV'de onay bekleniyor"; statusText.setTextColor(Color.parseColor("#E0B040"))
                AlertDialog.Builder(this).setTitle("TV ekranına bak")
                    .setMessage(msg).setPositiveButton("Tamam", null).show()
            }
            Tv.State.KOPUK -> {
                statusText.text = if (host.isBlank()) "TV ayarlanmadı" else "○ Bağlı değil"
                statusText.setTextColor(DIM)
                connectBtn.text = "Bağlan"; setBg(connectBtn, ACCENT)
                if (msg.isNotEmpty()) toast(msg)
            }
        }
    }

    private fun showTab(i: Int) {
        currentTab = i
        tabs.forEachIndexed { idx, t ->
            t.setTextColor(if (idx == i) TEXT else DIM)
            t.typeface = if (idx == i) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        content.removeAllViews()
        volumeBars.clear()
        val page = when (i) {
            0 -> remotePage()
            1 -> touchPage()
            2 -> appsPage()
            3 -> macrosPage()
            else -> settingsPage()
        }
        content.addView(page)
        if (Tv.isConnected && (i == 0 || i == 1)) Tv.refreshVolume()
    }

    // ===================== KUMANDA =====================

    private fun remotePage(): View {
        val c = col()
        c.addView(row(
            btn("⏻ Güç", RED) { Tv.key(Keys.POWER) },
            btn("Kaynak") { Tv.key(Keys.INPUT) },
            btn("Girişler") { showInputsDialog() }
        ))
        c.addView(row(
            btn("HDMI 1") { Tv.hdmi(1) },
            btn("HDMI 2") { Tv.hdmi(2) },
            btn("HDMI 3") { Tv.hdmi(3) }
        ))
        c.addView(gap(10))
        val h = dp(68)
        c.addView(row(h, spacer(), keyBtn("▲", Keys.UP, repeat = true), spacer()))
        c.addView(row(h, keyBtn("◀", Keys.LEFT, repeat = true), keyBtn("OK", Keys.OK, color = ACCENT), keyBtn("▶", Keys.RIGHT, repeat = true)))
        c.addView(row(h, spacer(), keyBtn("▼", Keys.DOWN, repeat = true), spacer()))
        c.addView(gap(10))
        c.addView(row(keyBtn("↩ Geri", Keys.BACK), keyBtn("⌂ Ana", Keys.HOME), keyBtn("☰ Menü", Keys.MENU), keyBtn("⚙", Keys.SETTINGS)))
        c.addView(gap(6))
        c.addView(volumeBar())
        c.addView(row(holdBtn("Ses −") { Tv.volumeStep(-1) }, keyBtn("🔇 Sessiz", Keys.MUTE), holdBtn("Ses +") { Tv.volumeStep(1) }))
        c.addView(row(keyBtn("Kanal −", Keys.CH_DOWN), keyBtn("Son uyg.", Keys.RECENTS), keyBtn("Kanal +", Keys.CH_UP)))
        c.addView(row(keyBtn("⏪", Keys.REWIND, repeat = true), keyBtn("⏯", Keys.PLAY_PAUSE), keyBtn("⏩", Keys.FORWARD, repeat = true)))

        // Klavye
        c.addView(label("Klavye"))
        val input = edit("TV'ye yazılacak metin")
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setOnEditorActionListener { _, _, _ ->
            sendText(input); true
        }
        c.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        lastTextInput = input
        c.addView(row(
            btn("Gönder", ACCENT) { sendText(input) },
            keyBtn("⌫ Sil", Keys.DEL, repeat = true),
            keyBtn("Enter", Keys.ENTER)
        ))
        c.addView(row(
            btn("🎤 Sesle yaz") { listen(REQ_DICTATE, "TV'ye yazılacak metni söyle") },
            btn("🎤 Sesli komut", ACCENT) { listen(REQ_COMMAND, "Komutu söyle (ör. HDMI 2, sesi 10 yap, YouTube aç)") }
        ))
        c.addView(hint(Voice.HELP))
        c.addView(hint("Türkçe harfler (ş, ğ, ı…) TV'ye düz harf olarak gider; adb'nin yazı komutu yalnızca İngilizce karakterleri destekliyor."))

        // Favoriler
        val favs = Prefs.favorites(this)
        if (favs.isNotEmpty()) {
            c.addView(label("Favori uygulamalar"))
            val items = favs.map { pkg -> Prefs.apps(this).firstOrNull { it.pkg == pkg } ?: AppItem(pkg, null) }
            items.chunked(3).forEach { chunk ->
                val views = chunk.map { a -> btn(a.label) { launch(a) } }.toMutableList<View>()
                while (views.size < 3) views.add(spacer())
                c.addView(row(*views.toTypedArray()))
            }
        }
        return scroll(c)
    }

    private fun sendText(input: EditText) {
        val t = input.text.toString()
        if (t.isNotEmpty()) {
            Tv.text(t)
            input.setText("")
        }
    }

    private fun launch(a: AppItem) {
        toast("${a.label} açılıyor")
        Thread { try { Tv.await { Tv.launchSync(a) } } catch (_: Exception) {} }.start()
    }

    // ===================== TOUCHPAD =====================

    private fun touchPage(): View {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(10)) }
        val pad = TouchPad(this)
        c.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        c.addView(volumeBar())
        c.addView(hint("Kaydır: yön tuşları • Dokun: OK • Uzun bas: Geri • Telefonun ses tuşları TV'nin sesini değiştirir"))
        c.addView(row(keyBtn("↩ Geri", Keys.BACK), keyBtn("⌂ Ana", Keys.HOME), keyBtn("⏯", Keys.PLAY_PAUSE), keyBtn("🔇", Keys.MUTE)))
        return c
    }

    @SuppressLint("ViewConstructor")
    private inner class TouchPad(ctx: Context) : TextView(ctx) {
        private var lastX = 0f
        private var lastY = 0f
        private var accX = 0f
        private var accY = 0f
        private var moved = false
        private var downTime = 0L
        private val step = dp(45).toFloat()
        private val longPress = Runnable {
            if (!moved) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                Tv.key(Keys.BACK)
                moved = true
            }
        }

        init {
            text = "Touchpad"
            gravity = Gravity.CENTER
            setTextColor(DIM)
            textSize = 16f
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(20).toFloat() }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y; accX = 0f; accY = 0f; moved = false
                    downTime = System.currentTimeMillis()
                    postDelayed(longPress, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    accX += e.x - lastX
                    accY += e.y - lastY
                    lastX = e.x; lastY = e.y
                    if (abs(accX) > step || abs(accY) > step) {
                        moved = true
                        removeCallbacks(longPress)
                        if (abs(accX) > abs(accY)) {
                            Tv.key(if (accX > 0) Keys.RIGHT else Keys.LEFT)
                        } else {
                            Tv.key(if (accY > 0) Keys.DOWN else Keys.UP)
                        }
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        accX = 0f; accY = 0f
                    }
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    if (!moved && System.currentTimeMillis() - downTime < 400) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        Tv.key(Keys.OK)
                    }
                }
                MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
            }
            return true
        }
    }

    // ===================== UYGULAMALAR =====================

    private fun appsPage(): View {
        val c = col()
        val listBox = col().apply { setPadding(0, 0, 0, 0) }
        c.addView(btn("TV'deki uygulamaları getir", ACCENT) { loadApps(listBox) })
        c.addView(hint("Dokun: aç • Uzun bas: favorilere ekle/çıkar (favoriler Kumanda sekmesinde görünür)"))
        c.addView(listBox)
        fillApps(listBox, Prefs.apps(this))
        return scroll(c)
    }

    private fun loadApps(box: LinearLayout) {
        toast("Uygulamalar okunuyor…")
        Tv.run(
            "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER"
        ) { out ->
            var apps = AppItem.parse(out)
            if (apps.isEmpty()) {
                Tv.run("pm list packages -3") { out2 ->
                    apps = out2.lines().filter { it.startsWith("package:") }
                        .map { AppItem(it.removePrefix("package:").trim(), null) }
                    Prefs.setApps(this, apps)
                    fillApps(box, apps)
                }
            } else {
                Prefs.setApps(this, apps)
                fillApps(box, apps)
            }
        }
    }

    private fun fillApps(box: LinearLayout, apps: List<AppItem>) {
        box.removeAllViews()
        if (apps.isEmpty()) {
            box.addView(hint("Henüz liste yok. TV'ye bağlıyken yukarıdaki düğmeye bas."))
            return
        }
        val favs = Prefs.favorites(this)
        apps.sortedBy { it.label.lowercase() }.chunked(2).forEach { chunk ->
            val views = chunk.map { a ->
                val star = if (favs.contains(a.pkg)) "★ " else ""
                btn(star + a.label, onLong = {
                    val f = Prefs.favorites(this).toMutableList()
                    if (f.contains(a.pkg)) { f.remove(a.pkg); toast("Favorilerden çıkarıldı") }
                    else { f.add(a.pkg); toast("Favorilere eklendi") }
                    Prefs.setFavorites(this, f)
                    fillApps(box, apps)
                }) { launch(a) }
            }.toMutableList<View>()
            while (views.size < 2) views.add(spacer())
            box.addView(row(*views.toTypedArray()))
        }
    }

    // ===================== MAKROLAR =====================

    private fun macrosPage(): View {
        val c = col()
        val log = hint("")
        c.addView(btn("+ Yeni makro", ACCENT) { editMacro(-1) })
        Prefs.macros(this).forEachIndexed { i, m ->
            c.addView(row(
                btn("▶ ${m.name}", weight = 3f) {
                    if (!Tv.isConnected && Prefs.host(this).isBlank()) { toast("Önce TV'ye bağlan"); return@btn }
                    log.text = "${m.name} çalışıyor…"
                    MacroRunner.run(m.script) { msg -> ui.post { log.text = msg } }
                },
                btn("Düzenle") { editMacro(i) }
            ))
        }
        c.addView(log)
        c.addView(label("Makro dili"))
        c.addView(hint(MacroRunner.HELP))
        return scroll(c)
    }

    private fun editMacro(index: Int) {
        val list = Prefs.macros(this).toMutableList()
        val m = list.getOrNull(index) ?: Macro("", "")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val name = EditText(this).apply { hint = "Makro adı"; setText(m.name) }
        val script = EditText(this).apply {
            hint = "tus ANASAYFA\nbekle 1000\nhdmi 1"
            setText(m.script)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 6
            gravity = Gravity.TOP
            typeface = Typeface.MONOSPACE
        }
        box.addView(name)
        box.addView(script)
        val d = AlertDialog.Builder(this)
            .setTitle(if (index < 0) "Yeni makro" else "Makroyu düzenle")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Kaydet") { _, _ ->
                val nm = name.text.toString().trim().ifEmpty { "Makro" }
                val nw = Macro(nm, script.text.toString())
                if (index < 0) list.add(nw) else list[index] = nw
                Prefs.setMacros(this, list)
                showTab(3)
            }
            .setNegativeButton("İptal", null)
        if (index >= 0) d.setNeutralButton("Sil") { _, _ ->
            list.removeAt(index)
            Prefs.setMacros(this, list)
            showTab(3)
        }
        d.show()
    }

    // ===================== AYARLAR =====================

    private fun settingsPage(): View {
        val c = col()
        c.addView(label("TV bağlantısı"))
        val ip = edit("TV'nin IP adresi (ör. 192.168.1.35)").apply {
            setText(Prefs.host(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val port = edit("Port").apply {
            setText(Prefs.port(this@MainActivity).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val ipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ipRow.addView(ip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f))
        ipRow.addView(port, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(ipRow)
        val found = col().apply { setPadding(0, 0, 0, 0) }
        c.addView(row(
            btn("Bağlan", ACCENT) {
                val h = ip.text.toString().trim()
                val p = port.text.toString().toIntOrNull() ?: 5555
                Prefs.setHost(this, h, p)
                Tv.connect(h, p)
            },
            btn("Ağda TV'yi ara") {
                found.removeAllViews()
                found.addView(hint("Aranıyor… (en fazla 20 sn)"))
                Thread {
                    val list = Scanner.scan(this, port.text.toString().toIntOrNull() ?: 5555)
                    ui.post {
                        found.removeAllViews()
                        if (list.isEmpty()) found.addView(hint("5555 portu açık cihaz bulunamadı. TV'de ADB hata ayıklamanın açık olduğundan emin ol."))
                        list.forEach { h ->
                            found.addView(btn("📺 $h  → bağlan") {
                                ip.setText(h)
                                Prefs.setHost(this, h, port.text.toString().toIntOrNull() ?: 5555)
                                Tv.connect(h, Prefs.port(this))
                            })
                        }
                    }
                }.start()
            }
        ))
        c.addView(found)
        c.addView(hint(
            "TV'de ilk kurulum:\n" +
                "1) Ayarlar > Cihaz Tercihleri > Hakkında > Yapı (Build) satırına 7 kez bas.\n" +
                "2) Geliştirici seçenekleri > USB hata ayıklama ve (varsa) Ağ/ADB hata ayıklamayı aç.\n" +
                "3) Ayarlar > Ağ'dan TV'nin IP adresini öğren ya da 'Ağda TV'yi ara'ya bas.\n" +
                "4) İlk bağlantıda TV ekranında çıkan soruda 'Her zaman izin ver'i işaretleyip Tamam'a bas."
        ))

        c.addView(label("Ses tuşları"))
        val sw = Switch(this).apply {
            text = "Telefonun ses tuşları TV'nin sesini kontrol etsin"
            setTextColor(TEXT)
            isChecked = Prefs.volumeKeys(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setVolumeKeys(this@MainActivity, v) }
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        c.addView(sw)

        c.addView(label("HDMI girişleri"))
        val hdmiInfo = hint(hdmiSummary())
        c.addView(hdmiInfo)
        c.addView(btn("Girişleri TV'den bul / ata") { showInputsDialog(assign = true) { hdmiInfo.text = hdmiSummary() } })
        c.addView(hint("HDMI düğmeleri, TV'nin giriş listesinden bulunan kimliklere göre çalışır. Yanlış girişe geçiyorsa buradan doğrusunu ata. Kimlik bulunamazsa standart HDMI tuş kodları denenir."))

        c.addView(label("Diğer"))
        c.addView(row(
            btn("Son çökme kaydı") { showCrash(force = true) },
            btn("TV bilgisi") {
                Tv.run("getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release") { out ->
                    AlertDialog.Builder(this).setTitle("TV bilgisi").setMessage(out.trim()).setPositiveButton("Tamam", null).show()
                }
            }
        ))
        c.addView(hint("Dijitsu Kumanda ${BuildInfo.VERSION}"))
        return scroll(c)
    }

    private fun hdmiSummary(): String = (1..3).joinToString("\n") { n ->
        val id = Prefs.hdmiId(this, n)
        "HDMI $n: " + (if (id.isEmpty()) "atanmadı (tuş kodu kullanılacak)" else id.substringAfterLast('/').let { "$it  ($id)" })
    }

    private fun showInputsDialog(assign: Boolean = false, after: (() -> Unit)? = null) {
        toast("Girişler okunuyor…")
        Thread {
            val list = try {
                var l: List<String> = emptyList()
                Tv.await { l = Tv.discoverInputsSync() }
                l
            } catch (e: Exception) { emptyList() }
            ui.post {
                if (list.isEmpty()) {
                    AlertDialog.Builder(this).setTitle("Girişler")
                        .setMessage("TV giriş listesi okunamadı. Bağlantıyı kontrol et; HDMI düğmeleri yine de standart tuş kodlarıyla çalışmayı dener.")
                        .setPositiveButton("Tamam", null).show()
                    after?.invoke()
                    return@post
                }
                val labels = list.map { id -> id.substringAfterLast('/') + "  •  " + id.substringBefore('/') }.toTypedArray()
                AlertDialog.Builder(this).setTitle(if (assign) "Bir giriş seç" else "Girişe geç")
                    .setItems(labels) { _, which ->
                        val id = list[which]
                        if (!assign) {
                            Thread { try { Tv.await { Tv.switchInputSync(id) } } catch (_: Exception) {} }.start()
                        } else {
                            AlertDialog.Builder(this).setTitle(id.substringAfterLast('/'))
                                .setItems(arrayOf("Şimdi bu girişe geç", "HDMI 1 olarak ata", "HDMI 2 olarak ata", "HDMI 3 olarak ata")) { _, w ->
                                    if (w == 0) Thread { try { Tv.await { Tv.switchInputSync(id) } } catch (_: Exception) {} }.start()
                                    else { Prefs.setHdmiId(this, w, id); toast("HDMI $w atandı") }
                                    after?.invoke()
                                }.show()
                        }
                        after?.invoke()
                    }
                    .setNegativeButton("Kapat", null).show()
            }
        }.start()
    }

    // ===================== ÇÖKME KAYDI =====================

    private fun showCrashIfAny() = showCrash(force = false)

    private fun showCrash(force: Boolean) {
        val f = (application as App).crashFile()
        if (!f.exists()) {
            if (force) toast("Çökme kaydı yok")
            return
        }
        val txt = f.readText()
        AlertDialog.Builder(this).setTitle("Uygulama son açılışta çöktü")
            .setMessage(txt.take(4000))
            .setPositiveButton("Kopyala") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("cokme", txt))
                toast("Kopyalandı — bana yapıştırabilirsin")
            }
            .setNeutralButton("Sil") { _, _ -> f.delete() }
            .setNegativeButton("Kapat", null)
            .show()
    }

    // ===================== YARDIMCILAR =====================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun setBg(v: View, color: Int) {
        v.background = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
    }

    private fun col() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(4), dp(10), dp(16))
    }

    private fun scroll(v: View) = ScrollView(this).apply { addView(v) }

    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun spacer() = View(this)

    private fun label(s: String) = TextView(this).apply {
        text = s; setTextColor(TEXT); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(4), dp(18), dp(4), dp(6))
    }

    private fun hint(s: String) = TextView(this).apply {
        text = s; setTextColor(DIM); textSize = 12.5f
        setPadding(dp(4), dp(4), dp(4), dp(6))
    }

    private fun edit(h: String) = EditText(this).apply {
        hint = h; setTextColor(TEXT); setHintTextColor(DIM); textSize = 15f
        setSingleLine(true)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(12).toFloat() }
    }

    private fun row(vararg views: View) = row(dp(52), *views)

    private fun row(height: Int, vararg views: View): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in views) {
            val w = (v.tag as? Float) ?: 1f
            r.addView(v, LinearLayout.LayoutParams(0, height, w).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        return r
    }

    private fun btn(
        text: String,
        color: Int = CARD,
        weight: Float = 1f,
        onLong: (() -> Unit)? = null,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        this.text = text
        tag = weight
        gravity = Gravity.CENTER
        setTextColor(TEXT)
        textSize = 15f
        maxLines = 2
        setBg(this, color)
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
        if (onLong != null) setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLong(); true
        }
    }

    /** Basılı tutulunca tekrar eden TV tuşu. */
    @SuppressLint("ClickableViewAccessibility")
    private fun keyBtn(text: String, code: Int, color: Int = CARD, repeat: Boolean = false): TextView {
        val b = btn(text, color) {}
        b.setOnClickListener(null)
        val repeater = object : Runnable {
            override fun run() {
                // Önceki basışlar gitmeden yenisini ekleme: bırakınca hemen durur
                if (!Tv.hasPendingKeys()) Tv.key(code)
                b.postDelayed(this, 120)
            }
        }
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    v.alpha = 0.6f
                    Tv.key(code)
                    if (repeat) v.postDelayed(repeater, 450)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    v.removeCallbacks(repeater)
                    if (repeat) Tv.clearPendingKeys()
                }
            }
            true
        }
        return b
    }

    /** Basılı tutulunca işlemi tekrar eden düğme (ses +/- için). */
    @SuppressLint("ClickableViewAccessibility")
    private fun holdBtn(text: String, action: () -> Unit): TextView {
        val b = btn(text) {}
        b.setOnClickListener(null)
        val repeater = object : Runnable {
            override fun run() {
                action()
                b.postDelayed(this, 130)
            }
        }
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    v.alpha = 0.6f
                    action()
                    v.postDelayed(repeater, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    v.removeCallbacks(repeater)
                }
            }
            true
        }
        return b
    }

    private fun volumeBar(): VolumeBar {
        val bar = VolumeBar(this, ACCENT, CARD, TEXT)
        bar.set(Tv.volume, Tv.volumeMax)
        bar.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        volumeBars.add(bar)
        return bar
    }

    // ===================== SES TANIMA =====================

    private fun listen(req: Int, prompt: String) {
        if (!Tv.isConnected && Prefs.host(this).isBlank()) { toast("Önce TV'ye bağlan"); return }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, req)
        } catch (_: ActivityNotFoundException) {
            toast("Telefonda ses tanıma bulunamadı (Google uygulaması gerekli)")
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val spoken = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        when (requestCode) {
            REQ_DICTATE -> {
                Tv.text(spoken)
                toast("Yazıldı: $spoken")
            }
            REQ_COMMAND -> {
                val r = Voice.handle(this, spoken) { msg -> ui.post { toast(msg) } }
                toast("“$spoken” → $r")
            }
        }
    }
}
