package com.example.lanbridge

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.telephony.TelephonyManager
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * Detects whether the app is running on an emulator -- and, best-effort,
 * which one -- WITHOUT depending on a static list of known vendor package
 * names or Build fields. That approach is a losing game: every new clone
 * (Gameloop, MuMu, SmartGaGa, XePlayer, and the dozens of other
 * Android-emulator vendors mostly out of China) is just another entry to
 * chase, and any vendor that starts spoofing Build.* (as MEmu did, to
 * impersonate a real Pixel) silently breaks the naive checks with no
 * warning.
 *
 * Instead this asks: "what does *every* PC-hosted Android environment
 * structurally have to get wrong, compared to a real phone?" Those signals
 * generalize to emulators nobody has heard of yet, because they're
 * consequences of running Android on a PC rather than branding choices:
 *
 *  1. No real baseband/radio. A desktop has no modem, so TelephonyManager
 *     reports PHONE_TYPE_NONE. Faking this convincingly would mean
 *     implementing an entire fake RIL (radio interface layer) -- far more
 *     work than editing a Build string, so nobody bothers.
 *  2. Missing/near-empty sensor list. There's no physical accelerometer,
 *     gyroscope, or light sensor on a desktop to read from.
 *  3. QEMU/goldfish/ranchu low-level fingerprints. The overwhelming
 *     majority of "gaming" emulators (LDPlayer, Nox, MEmu, MuMu, GameLoop,
 *     and most white-label clones) are commercial forks of Android-x86 or
 *     the AOSP emulator running on QEMU, because building a from-scratch
 *     ARM Android stack for PC is prohibitively expensive. QEMU leaves
 *     fingerprints (/dev/qemu_pipe, ro.kernel.qemu, pipe/socket files)
 *     that are much harder to strip than a cosmetic Build field, because
 *     removing them risks breaking the emulator's own virtual drivers.
 *  4. CPU-level hypervisor flag / vendor string in /proc/cpuinfo. This
 *     comes from the actual virtualized CPU the kernel is talking to --
 *     an app-layer spoof can't reach down and rewrite what the processor
 *     reports.
 *
 * None of this is a security boundary (nothing client-side ever is); it's
 * a heuristic that informs automatic networking decisions (see
 * TunLanService.guessGatewayFromOwnIp). We only attach a specific vendor
 * name for the UI on a best-effort basis (see [identifyVendor]) -- a miss
 * there just shows "Unknown emulator" instead of a name, it never affects
 * whether [isEmulator] fires.
 */
object EmulatorDetector {

    data class Result(val isEmulator: Boolean, val vendor: String?, val signalsHit: Int) {
        val label: String
            get() = when {
                !isEmulator -> "Physical device"
                vendor != null -> vendor
                else -> "Unknown emulator"
            }
    }

    private var appContext: Context? = null

    /** Must be called once (Activity/Service onCreate) before [platform]
     *  is first read. Safe to call more than once. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Cached after first check -- none of these signals change during a
     *  process's lifetime. */
    val platform: Result by lazy { detect() }

    val isEmulator: Boolean get() = platform.isEmulator

    private fun detect(): Result {
        var hits = 0

        // 1. No real modem.
        appContext?.let { ctx ->
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm?.phoneType == TelephonyManager.PHONE_TYPE_NONE) hits++
        }

        // 2. No real sensor hardware.
        appContext?.let { ctx ->
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val sensorCount = sm?.getSensorList(Sensor.TYPE_ALL)?.size ?: -1
            if (sensorCount in 0..1) hits++
        }

        // 3. QEMU / hypervisor filesystem + property fingerprints.
        if (fileExists("/dev/qemu_pipe") ||
            fileExists("/system/bin/qemu-props") ||
            fileExists("/dev/socket/qemud") ||
            getSystemProperty("ro.kernel.qemu") == "1" ||
            getSystemProperty("ro.boot.qemu") == "1"
        ) {
            hits++
        }

        // 4. CPU-reported virtualization.
        if (cpuInfoIndicatesVirtualization()) hits++

        // 5. Classic AVD/goldfish/ranchu Build fields -- still useful for
        //    the stock Android Studio emulator and its direct rebadges,
        //    kept as a supplementary (not load-bearing) signal.
        val genericBuildSignals = listOf(
            Build.FINGERPRINT.startsWith("generic"),
            Build.FINGERPRINT.startsWith("unknown"),
            Build.FINGERPRINT.contains("test-keys"),
            Build.HARDWARE.contains("goldfish", ignoreCase = true),
            Build.HARDWARE.contains("ranchu", ignoreCase = true),
            Build.HARDWARE.contains("vbox", ignoreCase = true),
            Build.BOARD == "unknown",
        )
        if (genericBuildSignals.any { it }) hits++

        // Require at least two independent structural signals so a single
        // false positive (e.g. a real budget tablet missing one sensor)
        // can't misclassify a physical device.
        val isEmu = hits >= 2
        val vendor = if (isEmu) identifyVendor() else null
        return Result(isEmu, vendor, hits)
    }

    /**
     * Best-effort vendor name for the UI. Scans every installed package
     * (see AndroidManifest's QUERY_ALL_PACKAGES for why, and its cost)
     * for a short list of distinguishing substrings, rather than checking
     * for a fixed set of exact package names. A new emulator that isn't
     * in this list yet still gets correctly flagged as *an* emulator by
     * [detect] above -- it just shows up as "Unknown emulator" until this
     * list is extended, instead of being missed entirely.
     */
    private fun identifyVendor(): String? {
        val pm = appContext?.packageManager ?: return null
        val packages = try {
            pm.getInstalledPackages(0).map { it.packageName }
        } catch (_: Exception) {
            return null
        }
        // Substrings chosen to avoid false-matching real apps a physical
        // device owner might legitimately have installed -- plain
        // "tencent" would false-positive on WeChat/QQ, so we key on
        // emulator-internal package fragments instead.
        val vendorKeywords = linkedMapOf(
            "microvirt" to "MEmu",
            "bluestacks" to "BlueStacks",
            "bignox" to "Nox Player",
            "vphone.launcher" to "Nox Player",
            "leidian" to "LDPlayer",
            "mumu" to "MuMu Player",
            "netease.mumu" to "MuMu Player",
            "tencent.tmgp.speed" to "GameLoop",
            "smartgaga" to "SmartGaGa",
            "xeplayer" to "XePlayer",
            "genymotion" to "Genymotion",
            "andy.emulator" to "Andy",
            "koplayer" to "KOPlayer",
        )
        for ((keyword, name) in vendorKeywords) {
            if (packages.any { it.contains(keyword, ignoreCase = true) }) return name
        }
        return null
    }

    private fun cpuInfoIndicatesVirtualization(): Boolean = try {
        File("/proc/cpuinfo").takeIf { it.canRead() }?.let { f ->
            BufferedReader(FileReader(f)).use { reader ->
                reader.lineSequence().any { line ->
                    val l = line.lowercase()
                    (l.startsWith("flags") && l.contains("hypervisor")) ||
                        l.contains("qemu") ||
                        l.contains("virtualbox")
                }
            }
        } ?: false
    } catch (_: Exception) {
        false
    }

    private fun getSystemProperty(key: String): String? = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, key) as? String
    } catch (_: Exception) {
        null
    }

    private fun fileExists(path: String): Boolean = try {
        File(path).exists()
    } catch (_: Exception) {
        false
    }
}
