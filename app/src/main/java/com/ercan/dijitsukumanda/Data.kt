package com.ercan.dijitsukumanda

import android.content.Context
import android.net.ConnectivityManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Android tuş kodları ve makrolarda kullanılan Türkçe adları. */
object Keys {
    const val UP = 19; const val DOWN = 20; const val LEFT = 21; const val RIGHT = 22; const val OK = 23
    const val BACK = 4; const val HOME = 3; const val MENU = 82; const val POWER = 26; const val WAKEUP = 224
    const val VOL_UP = 24; const val VOL_DOWN = 25; const val MUTE = 164
    const val CH_UP = 166; const val CH_DOWN = 167; const val INPUT = 178; const val SETTINGS = 176
    const val PLAY_PAUSE = 85; const val REWIND = 89; const val FORWARD = 90; const val RECENTS = 187
    const val DEL = 67; const val ENTER = 66; const val INFO = 165; const val GUIDE = 172

    val names = mapOf(
        "YUKARI" to UP, "ASAGI" to DOWN, "SOL" to LEFT, "SAG" to RIGHT, "OK" to OK,
        "GERI" to BACK, "ANASAYFA" to HOME, "HOME" to HOME, "MENU" to MENU, "GUC" to POWER, "UYAN" to WAKEUP,
        "SESAC" to VOL_UP, "SESKIS" to VOL_DOWN, "SESSIZ" to MUTE, "KANALARTI" to CH_UP, "KANALEKSI" to CH_DOWN,
        "KAYNAK" to INPUT, "AYARLAR" to SETTINGS, "OYNAT" to PLAY_PAUSE, "GERISAR" to REWIND, "ILERISAR" to FORWARD,
        "SONUYG" to RECENTS, "SIL" to DEL, "ENTER" to ENTER, "BILGI" to INFO, "REHBER" to GUIDE
    )

    fun parse(s: String): Int? = s.trim().uppercase().let { u ->
        u.toIntOrNull() ?: names[Tv.turkceSadele(u).uppercase()]
    }
}

data class AppItem(val pkg: String, val component: String?) {
    val label: String get() = known[pkg] ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    companion object {
        val known = mapOf(
            "com.google.android.youtube.tv" to "YouTube",
            "com.google.android.youtube.tvkids" to "YouTube Kids",
            "com.google.android.youtube.tvmusic" to "YouTube Music",
            "com.netflix.ninja" to "Netflix",
            "com.amazon.amazonvideo.livingroom" to "Prime Video",
            "com.disney.disneyplus" to "Disney+",
            "com.spotify.tv.android" to "Spotify",
            "com.android.vending" to "Play Store",
            "com.google.android.tvlauncher" to "Ana ekran",
            "com.android.tv.settings" to "Ayarlar",
            "com.google.android.videos" to "Google TV",
            "com.mediatek.wwtv.tvcenter" to "TV (Canlı yayın)",
            "com.google.android.tv" to "Canlı TV",
            "com.android.chrome" to "Chrome"
        )

        /** query-activities --brief çıktısından "paket/sınıf" satırlarını çıkarır. */
        fun parse(out: String): List<AppItem> {
            val list = LinkedHashMap<String, AppItem>()
            for (line in out.lines()) {
                val m = Regex("""^\s*([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)\s*$""").find(line) ?: continue
                val pkg = m.groupValues[1]
                if (!list.containsKey(pkg)) list[pkg] = AppItem(pkg, "$pkg/${m.groupValues[2]}")
            }
            return list.values.toList()
        }
    }
}

data class Macro(val name: String, val script: String)

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)

    fun host(c: Context) = p(c).getString("host", "") ?: ""
    fun port(c: Context) = p(c).getInt("port", 5555)
    fun setHost(c: Context, h: String, port: Int) = p(c).edit().putString("host", h).putInt("port", port).apply()

    fun fastKeys(c: Context) = p(c).getBoolean("fastkeys", true)
    fun setFastKeys(c: Context, v: Boolean) = p(c).edit().putBoolean("fastkeys", v).apply()

    fun volumeKeys(c: Context) = p(c).getBoolean("volkeys", true)
    fun setVolumeKeys(c: Context, v: Boolean) = p(c).edit().putBoolean("volkeys", v).apply()

    fun hdmiId(c: Context, n: Int) = p(c).getString("hdmi$n", "") ?: ""
    fun setHdmiId(c: Context, n: Int, id: String) = p(c).edit().putString("hdmi$n", id).apply()

    fun inputs(c: Context): List<String> = strList(p(c).getString("inputs", "[]"))
    fun setInputs(c: Context, l: List<String>) = p(c).edit().putString("inputs", JSONArray(l).toString()).apply()

    fun apps(c: Context): List<AppItem> {
        val a = JSONArray(p(c).getString("apps", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            AppItem(o.getString("p"), o.optString("c").ifEmpty { null })
        }
    }
    fun setApps(c: Context, l: List<AppItem>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("p", it.pkg).put("c", it.component ?: "")) }
        p(c).edit().putString("apps", a.toString()).apply()
    }

    fun favorites(c: Context): List<String> = strList(p(c).getString("favs", null) ?: JSONArray(
        listOf("com.google.android.youtube.tv", "com.netflix.ninja")
    ).toString())
    fun setFavorites(c: Context, l: List<String>) = p(c).edit().putString("favs", JSONArray(l).toString()).apply()

    fun macros(c: Context): List<Macro> {
        val raw = p(c).getString("macros", null) ?: return defaultMacros
        val a = JSONArray(raw)
        return (0 until a.length()).map { Macro(a.getJSONObject(it).getString("n"), a.getJSONObject(it).getString("s")) }
    }
    fun setMacros(c: Context, l: List<Macro>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("n", it.name).put("s", it.script)) }
        p(c).edit().putString("macros", a.toString()).apply()
    }

    private fun strList(s: String?): List<String> {
        val a = JSONArray(s ?: "[]")
        return (0 until a.length()).map { a.getString(it) }
    }

    val defaultMacros = listOf(
        Macro("Akşam modu", "# TV'yi uyandır, YouTube'u aç, sesi 15 yap\ntus UYAN\nbekle 1500\nuygulama com.google.android.youtube.tv\nbekle 3000\nses 15"),
        Macro("Uydu / HDMI 1", "hdmi 1\nbekle 1500\nses 12"),
        Macro("YouTube'da ara", "uygulama com.google.android.youtube.tv\nbekle 4000\ntus SOL\nbekle 500\ntus YUKARI\nbekle 500\ntus OK")
    )
}

/** Makro betiğini satır satır çalıştırır. */
object MacroRunner {
    const val HELP = """Her satır bir komut (# ile başlayan satırlar açıklamadır):
tus ANASAYFA      → tuşa bas (YUKARI ASAGI SOL SAG OK GERI ANASAYFA MENU GUC UYAN SESAC SESKIS SESSIZ KANALARTI KANALEKSI KAYNAK AYARLAR OYNAT GERISAR ILERISAR SIL ENTER ya da sayı)
tus SAG 3         → tuşa 3 kez bas
yaz merhaba       → yazı yaz
uygulama com.netflix.ninja → uygulama aç
hdmi 2            → HDMI 2'ye geç
ses 15            → ses seviyesini ayarla
bekle 1000        → 1000 ms bekle
kabuk <komut>     → TV'de kabuk komutu çalıştır"""

    fun run(script: String, log: (String) -> Unit) {
        Thread {
            for ((i, raw) in script.lines().withIndex()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val cmd = Tv.turkceSadele(line.substringBefore(' ')).lowercase()
                val arg = line.substringAfter(' ', "").trim()
                try {
                    when (cmd) {
                        "tus" -> {
                            val parts = arg.split(Regex("\\s+"))
                            val code = Keys.parse(parts[0]) ?: throw IllegalArgumentException("Bilinmeyen tuş: ${parts[0]}")
                            val times = parts.getOrNull(1)?.toIntOrNull() ?: 1
                            repeat(times) { Tv.await { Tv.keySync(code) }; Thread.sleep(150) }
                        }
                        "yaz" -> Tv.await { Tv.textSync(arg) }
                        "uygulama" -> {
                            val pkg = arg.substringBefore('/')
                            val item = if (arg.contains('/')) AppItem(pkg, arg) else AppItem(pkg, null)
                            Tv.await { Tv.launchSync(item) }
                        }
                        "hdmi" -> Tv.await { Tv.hdmiSync(arg.toInt()) }
                        "ses" -> Tv.await { Tv.volumeSync(arg.toInt()) }
                        "bekle" -> Thread.sleep(arg.toLong())
                        "kabuk" -> Tv.await { Tv.execSync(arg) }
                        else -> throw IllegalArgumentException("Bilinmeyen komut: $cmd")
                    }
                } catch (e: Exception) {
                    log("Satır ${i + 1}: ${e.message ?: e.javaClass.simpleName}")
                    return@Thread
                }
            }
            log("Makro bitti")
        }.start()
    }
}

/** Aynı Wi-Fi ağında 5555 portu açık cihazları arar. */
object Scanner {
    fun localIp(c: Context): String? {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
    }

    fun scan(c: Context, port: Int = 5555): List<String> {
        val ip = localIp(c) ?: return emptyList()
        val prefix = ip.substringBeforeLast('.')
        val found = Collections.synchronizedList(ArrayList<String>())
        val pool = Executors.newFixedThreadPool(48)
        for (i in 1..254) {
            val host = "$prefix.$i"
            if (host == ip) continue
            pool.execute {
                try {
                    Socket().use { s -> s.connect(InetSocketAddress(host, port), 500); found.add(host) }
                } catch (_: Exception) {}
            }
        }
        pool.shutdown()
        pool.awaitTermination(20, TimeUnit.SECONDS)
        return found.sortedBy { it.substringAfterLast('.').toInt() }
    }
}
