package com.example.mtkbooster

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class GameService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var current: Profile? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotif("Game Booster aktif"))
        scope.launch { loop() }
        return START_STICKY
    }

    private suspend fun loop() {
        val prefs = getSharedPreferences("booster", MODE_PRIVATE)
        while (scope.isActive) {
            val games = prefs.getStringSet("games", emptySet()) ?: emptySet()
            val gameProfile = Profile.valueOf(prefs.getString("game_profile", "PERFORMANCE")!!)
            val idleProfile = Profile.valueOf(prefs.getString("idle_profile", "BALANCED")!!)

            val fg = foregroundPackage()
            val target = if (fg != null && fg in games) gameProfile else idleProfile
            if (target != current) {
                MtkBooster.apply(target)
                current = target
                getSystemService(NotificationManager::class.java)
                    .notify(1, buildNotif("Mode: $target"))
            }
            delay(2000)
        }
    }

    // Butuh izin Usage Access (Settings > Special app access)
    private fun foregroundPackage(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        return usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 10_000, end)
            ?.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    private fun buildNotif(text: String): Notification {
        val ch = "booster"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ch, "Game Booster", NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(this, ch)
            .setContentTitle("MTK Game Booster")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        MtkBooster.restoreDefaults() // kembalikan ke default saat service berhenti
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
