package com.example.mtkbooster

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER))
        setContent { MaterialTheme { Screen() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Screen() {
        val prefs = remember { getSharedPreferences("booster", MODE_PRIVATE) }
        var root by remember { mutableStateOf<Boolean?>(null) }
        var clusters by remember { mutableStateOf(listOf<MtkBooster.Cluster>()) }
        var gpuOpps by remember { mutableStateOf(listOf<Pair<Int, Int>>()) }
        var gpuName by remember { mutableStateOf("-") }
        val cpuSel = remember { mutableStateMapOf<Int, Float>() }
        var gpuSel by remember { mutableStateOf(0f) }
        var games by remember { mutableStateOf(prefs.getStringSet("games", emptySet())!!.toSet()) }
        val apps = remember { launchableApps() }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                root = MtkBooster.hasRoot()
                if (root == true) {
                    clusters = MtkBooster.clusters()
                    val g = MtkBooster.detectGpu()
                    gpuName = g::class.simpleName ?: "-"
                    gpuOpps = MtkBooster.gpuOpps(g)
                    clusters.forEach { cpuSel[it.id] = (it.freqsKhz.size - 1).toFloat() }
                }
            }
        }

        LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("MTK Game Booster", style = MaterialTheme.typography.headlineSmall) }
            item { Text("Root: ${root ?: "memeriksa..."}  |  GPU node: $gpuName") }

            // ---- profil cepat ----
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Thread { MtkBooster.apply(Profile.PERFORMANCE) }.start() }) { Text("Max") }
                    OutlinedButton(onClick = { Thread { MtkBooster.apply(Profile.BALANCED) }.start() }) { Text("Normal") }
                    OutlinedButton(onClick = { Thread { MtkBooster.apply(Profile.POWERSAVE) }.start() }) { Text("Hemat") }
                    TextButton(onClick = { Thread { MtkBooster.restoreDefaults() }.start() }) { Text("Reset") }
                }
            }

            // ---- manual CPU ----
            items(clusters) { c ->
                val idx = (cpuSel[c.id] ?: (c.freqsKhz.size - 1).toFloat())
                Column {
                    Text("CPU cluster ${c.id} max: ${c.freqsKhz[idx.toInt()] / 1000} MHz")
                    Slider(
                        value = idx,
                        onValueChange = { cpuSel[c.id] = it },
                        valueRange = 0f..(c.freqsKhz.size - 1).toFloat(),
                        steps = (c.freqsKhz.size - 2).coerceAtLeast(0)
                    )
                }
            }

            // ---- manual GPU (index 0 = tertinggi) ----
            if (gpuOpps.isNotEmpty()) item {
                val i = gpuSel.toInt().coerceIn(0, gpuOpps.lastIndex)
                Column {
                    Text("GPU: ${gpuOpps[i].second / 1000} MHz")
                    Slider(
                        value = gpuSel,
                        onValueChange = { gpuSel = it },
                        valueRange = 0f..gpuOpps.lastIndex.toFloat(),
                        steps = (gpuOpps.size - 2).coerceAtLeast(0)
                    )
                    Text("(kiri = paling tinggi, kanan = paling rendah)",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Button(onClick = {
                    val map = clusters.associate { it.id to it.freqsKhz[(cpuSel[it.id] ?: 0f).toInt()] }
                    Thread { MtkBooster.applyManual(map, gpuSel.toInt()) }.start()
                }) { Text("Terapkan manual") }
            }

            // ---- service ----
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }) { Text("Izin Usage") }
                    Button(onClick = {
                        startForegroundService(Intent(this@MainActivity, GameService::class.java))
                    }) { Text("Start") }
                    OutlinedButton(onClick = {
                        stopService(Intent(this@MainActivity, GameService::class.java))
                    }) { Text("Stop") }
                }
            }

            // ---- pilih game ----
            item { Text("Pilih game:", style = MaterialTheme.typography.titleMedium) }
            items(apps) { (label, pkg) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = pkg in games, onCheckedChange = { on ->
                        games = if (on) games + pkg else games - pkg
                        prefs.edit().putStringSet("games", games).apply()
                    })
                    Text(label)
                }
            }
        }
    }

    private fun launchableApps(): List<Pair<String, String>> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(i, PackageManager.MATCH_ALL)
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }
}
