package com.pusula.browser

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * İndirme bildirimleri: ilerleme SESSİZ kanalda canlı güncellenir (damlacık sesi yok),
 * ses yalnızca indirme bitince gelir.
 */
object DownloadNotifier {
    private const val CH_PROGRESS = "dl_progress"
    private const val CH_DONE = "dl_done"

    private fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val p = NotificationChannel(CH_PROGRESS, "İndirme ilerlemesi", NotificationManager.IMPORTANCE_LOW)
        p.setSound(null, null)
        p.enableVibration(false)
        nm.createNotificationChannel(p)
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "İndirme tamamlandı", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun post(ctx: Context, id: Int, n: Notification) {
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) { }
    }

    fun track(context: Context, dlId: Long, name: String) {
        val ctx = context.applicationContext
        ensureChannels(ctx)
        val nid = 7000 + (dlId % 100000).toInt()
        val doneId = nid + 1000000
        val open = PendingIntent.getActivity(
            ctx, nid,
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        Thread {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            var lastPct = -2
            while (true) {
                var found = false
                var status = -1
                var got = 0L
                var total = -1L
                try {
                    dm.query(DownloadManager.Query().setFilterById(dlId))?.use { c ->
                        if (c.moveToFirst()) {
                            found = true
                            status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        }
                    }
                } catch (_: Exception) { }

                if (!found) {
                    NotificationManagerCompat.from(ctx).cancel(nid)
                    return@Thread
                }
                if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                    NotificationManagerCompat.from(ctx).cancel(nid)
                    val ok = status == DownloadManager.STATUS_SUCCESSFUL
                    val n = NotificationCompat.Builder(ctx, CH_DONE)
                        .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                        .setContentTitle(name)
                        .setContentText(if (ok) "İndirme tamamlandı" else "İndirme başarısız")
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .build()
                    post(ctx, doneId, n)
                    return@Thread
                }

                val pct = if (total > 0) ((got * 100) / total).toInt().coerceIn(0, 100) else -1
                if (pct != lastPct) {
                    lastPct = pct
                    val b = NotificationCompat.Builder(ctx, CH_PROGRESS)
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setContentTitle(name)
                        .setContentText(if (pct >= 0) "İndiriliyor… %" + pct else "İndiriliyor…")
                        .setContentIntent(open)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setSilent(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setProgress(100, if (pct >= 0) pct else 0, pct < 0)
                    post(ctx, nid, b.build())
                }
                try { Thread.sleep(800) } catch (_: InterruptedException) { return@Thread }
            }
        }.start()
    }
}
