package com.cruzzingt.browser

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object ListUpdater {
    private val LISTS = linkedMapOf(
        "easylist.txt" to "https://easylist.to/easylist/easylist.txt",
        "easyprivacy.txt" to "https://easylist.to/easylist/easyprivacy.txt",
        "peterlowe.txt" to "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext"
    )

    fun dir(ctx: Context) = File(ctx.filesDir, "lists").apply { mkdirs() }

    /** Descarga las listas (si cambiaron) y recarga el motor. Devuelve true si alguna se actualizó. */
    fun updateAll(ctx: Context): Boolean {
        var any = false
        for ((name, url) in LISTS) {
            try {
                val f = File(dir(ctx), name)
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 40000
                c.setRequestProperty("User-Agent", "cruzzingt/1.0 (Android)")
                if (f.exists()) c.ifModifiedSince = f.lastModified()
                if (c.responseCode == 200) {
                    val tmp = File(dir(ctx), "$name.tmp")
                    c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    val head = tmp.bufferedReader().use { it.readLine() } ?: ""
                    if (tmp.length() > 2000 && !head.startsWith("<")) {   // descarta páginas de error HTML
                        tmp.copyTo(f, overwrite = true)
                        f.setLastModified(System.currentTimeMillis())
                        any = true
                    }
                    tmp.delete()
                }
                c.disconnect()
            } catch (_: Exception) { /* sin internet: se conserva la última lista guardada */ }
        }
        if (any) AdBlock.load(ctx)
        return any
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("cz-lists", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class UpdateWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        withContext(Dispatchers.IO) { ListUpdater.updateAll(applicationContext) }
        return Result.success()
    }
}
