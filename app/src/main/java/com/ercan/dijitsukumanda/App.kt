package com.ercan.dijitsukumanda

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val time = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("tr")).format(Date())
                crashFile().writeText("Zaman: $time\nİş parçacığı: ${t.name}\nSürüm: ${BuildInfo.VERSION}\n\n$sw")
            } catch (_: Exception) {
            }
            previous?.uncaughtException(t, e)
        }
        Tv.init(this)
    }

    fun crashFile() = File(filesDir, "cokme.txt")
}

object BuildInfo {
    const val VERSION = "1.0"
}
