package com.ercan.dijitsukumanda

import android.content.Context

/** Türkçe sesli komutları TV komutlarına çevirir. Anlaşılmayan cümle TV'ye yazı olarak gönderilir. */
object Voice {

    private val numberWords = mapOf(
        "sifir" to 0, "bir" to 1, "iki" to 2, "uc" to 3, "dort" to 4, "bes" to 5, "alti" to 6,
        "yedi" to 7, "sekiz" to 8, "dokuz" to 9, "on" to 10, "onbir" to 11, "oniki" to 12,
        "onuc" to 13, "ondort" to 14, "onbes" to 15, "yirmi" to 20, "otuz" to 30, "kirk" to 40,
        "elli" to 50, "altmis" to 60, "yetmis" to 70, "seksen" to 80, "doksan" to 90, "yuz" to 100
    )

    private fun norm(s: String) = Tv.turkceSadele(s.lowercase()).lowercase().replace("'", " ")

    private fun number(t: String): Int? {
        Regex("""\d+""").find(t)?.let { return it.value.toInt() }
        val words = t.split(Regex("\\s+"))
        // "on beş" gibi iki kelimelik sayılar
        for (i in words.indices) {
            val a = numberWords[words[i]] ?: continue
            val b = words.getOrNull(i + 1)?.let { numberWords[it] }
            return if (b != null && b < 10 && a >= 10) a + b else a
        }
        return null
    }

    /** Komutu uygular ve kullanıcıya gösterilecek açıklamayı döndürür. */
    fun handle(ctx: Context, spoken: String, log: (String) -> Unit): String {
        val t = norm(spoken)
        val w = t.split(Regex("\\s+")).toSet()
        fun has(vararg k: String) = k.any { t.contains(it) }

        // 1) Makro adı
        Prefs.macros(ctx).firstOrNull { norm(it.name).isNotBlank() && t.contains(norm(it.name)) }?.let { m ->
            MacroRunner.run(m.script, log)
            return "Makro: ${m.name}"
        }

        // 2) HDMI
        if (has("hdmi", "h d m i", "giris", "kaynak")) {
            val n = number(t)
            if (n != null && n in 1..4) { Tv.hdmi(n); return "HDMI $n" }
            if (has("kaynak")) { Tv.key(Keys.INPUT); return "Kaynak" }
        }

        // 3) Ses
        if (has("ses", "volum")) {
            val n = number(t)
            when {
                n != null -> { Tv.setVolume(n); return "Ses $n" }
                has("artir", "yukselt", "ac biraz", "arttir") -> { Tv.volumeStep(2); return "Ses +2" }
                has("azalt", "kis", "dusur", "alcalt") -> { Tv.volumeStep(-2); return "Ses −2" }
                has("kapat", "sustur", "ac") -> { Tv.key(Keys.MUTE); return "Sessiz aç/kapat" }
            }
        }
        if (has("sessiz", "sustur")) { Tv.key(Keys.MUTE); return "Sessiz aç/kapat" }

        // 4) Uygulama aç
        if (has(" ac", "baslat", "calistir", "gir") || t.endsWith("ac")) {
            val apps = (Prefs.apps(ctx) + AppItem.known.keys.map { AppItem(it, null) }).distinctBy { it.pkg }
            val hit = apps.firstOrNull { a ->
                val l = norm(a.label)
                l.length >= 3 && t.contains(l)
            }
            if (hit != null) {
                Thread { try { Tv.await { Tv.launchSync(hit) } } catch (_: Exception) {} }.start()
                return "${hit.label} açılıyor"
            }
        }

        // 5) Güç ve gezinme
        when {
            has("televizyonu kapat", "tv yi kapat", "tvyi kapat", "kapat televizyon") || t == "kapat" -> { Tv.key(Keys.POWER); return "Güç" }
            has("ana sayfa", "anasayfa", "ana ekran", "eve don") -> { Tv.key(Keys.HOME); return "Ana sayfa" }
            w.contains("geri") || has("geri don") -> { Tv.key(Keys.BACK); return "Geri" }
            has("durdur", "duraklat", "oynat", "devam et", "baslat") -> { Tv.key(Keys.PLAY_PAUSE); return "Oynat/Duraklat" }
            has("kanal") && has("yukari", "ileri", "sonraki", "artir") -> { Tv.key(Keys.CH_UP); return "Kanal +" }
            has("kanal") && has("asagi", "geri", "onceki", "azalt") -> { Tv.key(Keys.CH_DOWN); return "Kanal −" }
            has("ayarlar") -> { Tv.key(Keys.SETTINGS); return "Ayarlar" }
            has("menu") -> { Tv.key(Keys.MENU); return "Menü" }
            w.contains("tamam") || w.contains("sec") -> { Tv.key(Keys.OK); return "OK" }
        }

        // 6) Anlaşılmadı: yazı olarak gönder
        Tv.text(spoken)
        return "Yazı olarak gönderildi: $spoken"
    }

    const val HELP = "Örnekler: \"HDMI 2\", \"sesi 10 yap\", \"sesi azalt\", \"sessiz\", \"YouTube aç\", " +
        "\"Netflix aç\", \"ana sayfa\", \"geri\", \"durdur\", \"kanal yukarı\", \"televizyonu kapat\", " +
        "ya da bir makronun adı. Anlaşılmayan cümle TV'ye yazı olarak gider."
}
