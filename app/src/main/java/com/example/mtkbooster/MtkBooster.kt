package com.example.mtkbooster

import com.topjohnwu.superuser.Shell
import java.io.File

enum class Profile { PERFORMANCE, BALANCED, POWERSAVE }

/**
 * Kontrol CPU & GPU khusus MediaTek (Helio / Dimensity).
 * Semua path dideteksi dulu (exists), karena beda kernel beda node.
 */
object MtkBooster {

    // ---------- util root ----------
    private fun sh(cmd: String) = Shell.cmd(cmd).exec()
    private fun read(path: String): String =
        sh("cat $path 2>/dev/null").out.joinToString("\n").trim()
    private fun exists(path: String) = sh("[ -e $path ] && echo y").out.firstOrNull() == "y"
    private fun write(path: String, value: Any): Boolean {
        sh("chmod 644 $path")
        return sh("echo $value > $path").isSuccess
    }

    // ---------- CPU: cluster (policy) ----------
    class Cluster(val id: Int, val freqsKhz: List<Int>) {
        val base get() = "/sys/devices/system/cpu/cpufreq/policy$id"
    }

    fun clusters(): List<Cluster> =
        sh("ls /sys/devices/system/cpu/cpufreq/ | grep policy").out.mapNotNull { name ->
            val id = name.removePrefix("policy").toIntOrNull() ?: return@mapNotNull null
            val f = read("/sys/devices/system/cpu/cpufreq/$name/scaling_available_frequencies")
                .split(" ").mapNotNull { it.toIntOrNull() }.sorted()
            if (f.isEmpty()) null else Cluster(id, f)
        }

    fun cpuFreqs(c: Cluster) = c.freqsKhz

    fun setCluster(c: Cluster, minKhz: Int, maxKhz: Int, governor: String? = null) {
        // urutan: longgarkan dulu, lalu set. Naikkan max sebelum min.
        write("${c.base}/scaling_max_freq", maxKhz)
        write("${c.base}/scaling_min_freq", minKhz)
        write("${c.base}/scaling_max_freq", maxKhz)
        governor?.let { write("${c.base}/scaling_governor", it) }
    }

    // ---------- MediaTek power mode (/proc/cpufreq) ----------
    // 0 = normal, 1 = low power, 2 = just make, 3 = performance
    private const val MTK_POWER_MODE = "/proc/cpufreq/cpufreq_power_mode"
    private const val MTK_CCI_MODE = "/proc/cpufreq/cpufreq_cci_mode"

    fun setMtkPowerMode(mode: Int) {
        if (exists(MTK_POWER_MODE)) write(MTK_POWER_MODE, mode)
        if (exists(MTK_CCI_MODE)) write(MTK_CCI_MODE, if (mode == 3) 1 else 0)
    }

    // ---------- GPU ----------
    sealed class Gpu {
        /** Helio lama: /proc/gpufreq */
        object V1 : Gpu()
        /** Dimensity baru: /proc/gpufreqv2 */
        object V2 : Gpu()
        /** Mali devfreq standar */
        class Devfreq(val dir: String) : Gpu()
        object None : Gpu()
    }

    fun detectGpu(): Gpu {
        if (exists("/proc/gpufreqv2/gpu_working_opp_table") ||
            exists("/proc/gpufreqv2/fix_target_opp_index")) return Gpu.V2
        if (exists("/proc/gpufreq/gpufreq_opp_freq")) return Gpu.V1
        val dev = sh("ls -d /sys/class/devfreq/*mali* /sys/class/devfreq/*gpu* 2>/dev/null")
            .out.firstOrNull()
        if (!dev.isNullOrBlank()) return Gpu.Devfreq(dev)
        return Gpu.None
    }

    /** Daftar OPP GPU: pasangan (index, kHz). */
    fun gpuOpps(g: Gpu): List<Pair<Int, Int>> = when (g) {
        Gpu.V1 -> parseOpp(read("/proc/gpufreq/gpufreq_opp_dump"))
        Gpu.V2 -> parseOpp(read("/proc/gpufreqv2/gpu_working_opp_table"))
        is Gpu.Devfreq -> read("${g.dir}/available_frequencies").split(" ")
            .mapNotNull { it.toLongOrNull() }.sorted().reversed()
            .mapIndexed { i, hz -> i to (hz / 1000).toInt() }
        Gpu.None -> emptyList()
    }

    // contoh baris: "[00] freq = 900000, volt = 80000, ..."
    private fun parseOpp(raw: String): List<Pair<Int, Int>> {
        val re = Regex("""\[\s*(\d+)\].*?freq\s*=\s*(\d+)""")
        return raw.lines().mapNotNull { re.find(it) }
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    }

    /** Kunci GPU ke OPP index tertentu (0 = tertinggi di MediaTek). */
    fun lockGpuIndex(g: Gpu, index: Int) {
        when (g) {
            Gpu.V1 -> {
                val f = gpuOpps(g).firstOrNull { it.first == index }?.second ?: return
                write("/proc/gpufreq/gpufreq_opp_freq", f)
            }
            Gpu.V2 -> write("/proc/gpufreqv2/fix_target_opp_index", index)
            is Gpu.Devfreq -> {
                val f = gpuOpps(g).firstOrNull { it.first == index }?.second ?: return
                val hz = f.toLong() * 1000
                write("${g.dir}/max_freq", hz)
                write("${g.dir}/min_freq", hz)
            }
            Gpu.None -> {}
        }
    }

    /** Batasi GPU: maxIndex = OPP paling tinggi yang boleh dipakai (hemat daya). */
    fun limitGpuMaxIndex(g: Gpu, index: Int) {
        when (g) {
            Gpu.V1 -> lockGpuIndex(g, index)
            Gpu.V2 -> write("/proc/gpufreqv2/fix_target_opp_index", index)
            is Gpu.Devfreq -> {
                val f = gpuOpps(g).firstOrNull { it.first == index }?.second ?: return
                write("${g.dir}/max_freq", f.toLong() * 1000)
            }
            Gpu.None -> {}
        }
    }

    /** Lepas kunci GPU, kembali ke DVFS normal. */
    fun unlockGpu(g: Gpu) {
        when (g) {
            Gpu.V1 -> write("/proc/gpufreq/gpufreq_opp_freq", 0)
            Gpu.V2 -> write("/proc/gpufreqv2/fix_target_opp_index", -1)
            is Gpu.Devfreq -> {
                val f = gpuOpps(g).map { it.second }
                if (f.isNotEmpty()) {
                    write("${g.dir}/max_freq", f.max().toLong() * 1000)
                    write("${g.dir}/min_freq", f.min().toLong() * 1000)
                }
            }
            Gpu.None -> {}
        }
    }

    // ---------- backup / restore ----------
    private val backup = mutableMapOf<String, String>()
    private var backedUp = false

    fun backupDefaults() {
        if (backedUp) return
        for (c in clusters()) {
            for (n in listOf("scaling_governor", "scaling_min_freq", "scaling_max_freq")) {
                val p = "${c.base}/$n"
                backup[p] = read(p)
            }
        }
        if (exists(MTK_POWER_MODE)) backup[MTK_POWER_MODE] = "0"
        backedUp = true
    }

    fun restoreDefaults() {
        // max dulu, baru min
        backup.entries.sortedBy { if (it.key.endsWith("max_freq")) 0 else 1 }
            .forEach { (p, v) -> if (v.isNotBlank()) write(p, v) }
        unlockGpu(detectGpu())
        setMtkPowerMode(0)
    }

    // ---------- profil ----------
    fun apply(profile: Profile) {
        backupDefaults()
        val gpu = detectGpu()
        val cl = clusters()
        when (profile) {
            Profile.PERFORMANCE -> {
                setMtkPowerMode(3)
                cl.forEach { setCluster(it, it.freqsKhz.last(), it.freqsKhz.last(), "performance") }
                lockGpuIndex(gpu, 0) // OPP 0 = frekuensi tertinggi
            }
            Profile.BALANCED -> {
                setMtkPowerMode(0)
                cl.forEach { setCluster(it, it.freqsKhz.first(), it.freqsKhz.last(), "schedutil") }
                unlockGpu(gpu)
            }
            Profile.POWERSAVE -> {
                setMtkPowerMode(1)
                cl.forEach {
                    val cap = it.freqsKhz[(it.freqsKhz.size * 0.5).toInt().coerceAtMost(it.freqsKhz.lastIndex)]
                    setCluster(it, it.freqsKhz.first(), cap, "schedutil")
                }
                val opps = gpuOpps(gpu)
                if (opps.isNotEmpty()) limitGpuMaxIndex(gpu, opps.size / 2)
            }
        }
    }

    /** Atur manual per cluster (dari slider UI). */
    fun applyManual(cpuMaxKhzByCluster: Map<Int, Int>, gpuIndex: Int?) {
        backupDefaults()
        clusters().forEach { c ->
            val max = cpuMaxKhzByCluster[c.id] ?: c.freqsKhz.last()
            setCluster(c, c.freqsKhz.first(), max, "schedutil")
        }
        gpuIndex?.let { lockGpuIndex(detectGpu(), it) }
    }

    // ---------- info ----------
    fun cpuTempC(): Float? =
        read("/sys/class/thermal/thermal_zone0/temp").toFloatOrNull()
            ?.let { if (it > 1000) it / 1000f else it }

    fun curCpuFreqMhz(c: Cluster): Int =
        (read("${c.base}/scaling_cur_freq").toIntOrNull() ?: 0) / 1000

    fun hasRoot() = Shell.isAppGrantedRoot() == true
    @Suppress("unused") fun fileExists(p: String) = File(p).exists()
}
