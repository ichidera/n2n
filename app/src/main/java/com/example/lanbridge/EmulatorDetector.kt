package com.example.lanbridge

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
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

    private const val TAG = "EmulatorDetector"

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
        // Path 0: instruction-set architecture. Checked first, and
        // decisive on its own. Virtually every commercial PC "gaming"
        // emulator -- BlueStacks, MEmu, Nox, LDPlayer, GameLoop, MuMu,
        // and the rest -- runs x86/x86_64 Android specifically because
        // hardware-accelerated x86 virtualization (Intel HAXM/AMD-V/
        // Hyper-V) is what makes them fast. That's not a branding choice
        // that can be turned off; it's the entire reason a PC emulator
        // exists instead of just running real ARM Android in software
        // (which would be unusably slow). Build.MANUFACTURER/HARDWARE/
        // BOARD can be spoofed to claim a real Snapdragon/Exynos phone
        // (as seen: fingerprint claiming a Galaxy S22 Ultra); the actual
        // compiled instruction set can't be, without literally shipping
        // real ARM Android and giving up the speed advantage.
        //
        // This also matters because it doesn't touch PackageManager at
        // all -- unlike the package-based check below, which BlueStacks
        // appears to defeat by filtering its own packages out of
        // getInstalledPackages() results even under QUERY_ALL_PACKAGES
        // (com.bluestacks.launcher is visibly running in logcat but never
        // shows up in a package scan from this app). Reading
        // Build.SUPPORTED_ABIS can't be intercepted the same way.
        //
        // Caveat: a handful of real x86 Android phones/tablets existed
        // circa 2013-2015 (some Asus Zenfones, a few Lenovo/Motorola
        // models on Intel Atom). They're long discontinued and vanishingly
        // rare today, so this trade-off is accepted deliberately.
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val isX86 = primaryAbi.startsWith("x86")
        Log.d(TAG, "signal 0 (x86 ABI): abis=${Build.SUPPORTED_ABIS.joinToString()} hit=$isX86")
        if (isX86) {
            val vendor = identifyVendor()
            Log.d(TAG, "Path 0 hit: x86 ABI -> vendor=${vendor ?: "unrecognized"}")
            return Result(isEmulator = true, vendor = vendor, signalsHit = -2)
        }

        // Path A: a known vendor package present. This runs first and
        // unconditionally, and is decisive on its own -- com.microvirt.*
        // (or bluestacks.*, bignox.*, etc.) existing on the device is not
        // something a real phone would ever have installed, full stop.
        //
        // This has to come before the structural checks below, not after
        // them: commercial GAMING emulators (BlueStacks, MEmu, and most of
        // their competitors) run a full simulated telephony/telecom stack
        // and simulated sensors specifically because mobile games check
        // for those and refuse to launch without them. That means the
        // exact signals that look "structurally hard to fake" on a
        // bare-bones dev emulator are the ones a polished gaming emulator
        // has the strongest incentive to fake well. Gating the package
        // check behind those signals meant a real, decisive match
        // (com.microvirt.launcher2 sitting right there installed) never
        // even got checked, because the weaker signals failed first.
        identifyVendor()?.let { vendor ->
            Log.d(TAG, "Path A hit: recognized vendor package -> $vendor")
            return Result(isEmulator = true, vendor = vendor, signalsHit = -1)
        }
        Log.d(TAG, "Path A: no recognized vendor package found, falling back to Path B")

        // Path B: no recognized vendor package -- fall back to
        // vendor-agnostic structural signals, for anything not on that
        // list yet. Weaker by the same reasoning above (a good gaming
        // emulator can fake several of these), so this stays a secondary
        // path rather than the primary check.
        //
        // Every signal below is logged individually with its raw value.
        // If Path B ever misclassifies a device again, `adb logcat -s
        // EmulatorDetector` gives real evidence of exactly which checks
        // fired and which didn't, instead of another round of guessing.
        var hits = 0

        // 1. No real modem.
        val phoneType = appContext?.let { ctx ->
            (ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.phoneType
        }
        val noModem = phoneType == TelephonyManager.PHONE_TYPE_NONE
        Log.d(TAG, "signal 1 (no modem): phoneType=$phoneType hit=$noModem")
        if (noModem) hits++

        // 2. No real sensor hardware.
        val sensorCount = appContext?.let { ctx ->
            (ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
                ?.getSensorList(Sensor.TYPE_ALL)?.size
        } ?: -1
        val fewSensors = sensorCount in 0..1
        Log.d(TAG, "signal 2 (sparse sensors): sensorCount=$sensorCount hit=$fewSensors")
        if (fewSensors) hits++

        // 3. QEMU / hypervisor filesystem + property fingerprints.
        val qemuFile = fileExists("/dev/qemu_pipe") || fileExists("/system/bin/qemu-props") ||
            fileExists("/dev/socket/qemud")
        val qemuProp = getSystemProperty("ro.kernel.qemu") == "1" ||
            getSystemProperty("ro.boot.qemu") == "1"
        Log.d(TAG, "signal 3 (qemu fingerprints): file=$qemuFile prop=$qemuProp")
        if (qemuFile || qemuProp) hits++

        // 4. CPU-reported virtualization.
        val cpuVirt = cpuInfoIndicatesVirtualization()
        Log.d(TAG, "signal 4 (cpuinfo virtualization): hit=$cpuVirt")
        if (cpuVirt) hits++

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
        val buildHit = genericBuildSignals.any { it }
        Log.d(TAG, "signal 5 (generic build fields): fingerprint=${Build.FINGERPRINT} " +
            "hardware=${Build.HARDWARE} board=${Build.BOARD} hit=$buildHit")
        if (buildHit) hits++

        // Require at least two independent structural signals so a single
        // false positive (e.g. a real budget tablet missing one sensor)
        // can't misclassify a physical device. vendor is always null here
        // -- Path A above already checked and came back empty, or we
        // wouldn't have reached this line.
        Log.d(TAG, "Path B total hits=$hits (need >=2)")
        return Result(isEmulator = hits >= 2, vendor = null, signalsHit = hits)
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
        val pm = appContext?.packageManager ?: run {
            Log.w(TAG, "identifyVendor: no context set (init() not called yet?)")
            return null
        }
        val packages = try {
            pm.getInstalledPackages(0).map { it.packageName }
        } catch (e: Exception) {
            Log.w(TAG, "identifyVendor: getInstalledPackages() failed -- is " +
                "QUERY_ALL_PACKAGES actually in the installed APK's manifest? ($e)")
            return null
        }
        Log.d(TAG, "identifyVendor: scanned ${packages.size} installed packages")

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
            val match = packages.firstOrNull { it.contains(keyword, ignoreCase = true) }
            if (match != null) {
                Log.d(TAG, "identifyVendor: matched '$keyword' -> $name (package=$match)")
                return name
            }
        }

        // No exact keyword matched. Rather than fail silently and leave us
        // guessing again, surface anything that *might* be a miss on the
        // keyword list -- e.g. if BlueStacks' real internal package uses
        // "bst" instead of "bluestacks" (its sensor service log tag is
        // "bstsensor_*", suggesting exactly that). This log line is what
        // tells us the real name to add, instead of guessing a second time.
        val looseHints = listOf("blue", "stack", "bst", "emulator", "player", "virt", "sim")
        val candidates = packages.filter { pkg ->
            looseHints.any { hint -> pkg.contains(hint, ignoreCase = true) }
        }
        Log.d(TAG, "identifyVendor: no keyword matched. Loose candidates: $candidates")
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
