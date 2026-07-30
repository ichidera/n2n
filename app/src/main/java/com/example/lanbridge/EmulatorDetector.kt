package com.example.lanbridge

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Detects whether the app is running on an emulator, and which one, using
 * the same general approach production apps use for this: no single check
 * is reliable on its own (all of these are spoofable in principle), so we
 * combine several independent signals. This is inherently heuristic --
 * it's meant to inform automatic networking decisions (see
 * TunLanService.guessGatewayFromOwnIp), not to be a security boundary.
 *
 * Vendors like MEmu have started spoofing Build.MANUFACTURER/BRAND/
 * FINGERPRINT to impersonate a real device (observed impersonating a
 * Google Pixel), so those fields alone are no longer trustworthy for MEmu.
 * The stronger signal is the presence of the emulator's own system
 * packages (e.g. com.microvirt.launcher2) -- internal plumbing the vendor
 * has no reason to hide, since removing it would break their own product.
 * The same package-based check is applied preemptively to BlueStacks and
 * Nox in case they start spoofing Build fields too. Build.* fields are
 * kept as a fallback for whichever of these hasn't (yet) started spoofing.
 */
object EmulatorDetector {

    enum class Platform(val label: String) {
        BLUESTACKS("BlueStacks"),
        MEMU("MEmu"),
        NOX("Nox Player"),
        LDPLAYER("LDPlayer"),
        GENYMOTION("Genymotion"),
        GENERIC_AVD("Generic Android Emulator (AVD/Goldfish/Ranchu)"),
        UNKNOWN_EMULATOR("Unknown emulator"),
        PHYSICAL_DEVICE("Physical device"),
    }

    // Known system packages for each vendor. Declared in AndroidManifest.xml
    // under <queries> so PackageManager.getPackageInfo() can actually see
    // them on Android 11+ (package-visibility rules hide everything not
    // explicitly declared there, otherwise these checks would silently
    // report "not found" regardless of what's really installed).
    private val MEMU_PACKAGES = listOf(
        "com.microvirt.launcher2",
        "com.microvirt.tools",
        "com.microvirt.installer",
        "com.microvirt.download",
        "com.microvirt.memuime",
    )
    private val BLUESTACKS_PACKAGES = listOf(
        "com.bluestacks.home",
        "com.bluestacks.settings",
        "com.bluestacks.appmart",
        "com.bluestacks.BstCommandProcessor",
    )
    private val NOX_PACKAGES = listOf(
        "com.bignox.app",
        "com.vphone.launcher",
        "org.bignox.tools",
    )

    private var appContext: Context? = null

    /**
     * Must be called once (e.g. from Application/Activity/Service onCreate)
     * before [platform] is first read, so the package-based checks have a
     * PackageManager to query. Safe to call more than once.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Cached after first check since Build.* fields and file/package
     *  existence checks never change during a process's lifetime. */
    val platform: Platform by lazy { detect() }

    val isEmulator: Boolean get() = platform != Platform.PHYSICAL_DEVICE

    private fun detect(): Platform {
        // --- BlueStacks -----------------------------------------------
        // BlueStacks shares a Windows folder into the guest at this fixed
        // path (visible in its own system logs as well). Package check
        // added preemptively alongside the existing Build-field checks.
        if (hasAnyPackage(BLUESTACKS_PACKAGES) ||
            fileExists("/mnt/windows/BstSharedFolder") ||
            Build.MANUFACTURER.contains("bluestacks", ignoreCase = true) ||
            Build.BRAND.contains("bluestacks", ignoreCase = true) ||
            Build.PRODUCT.contains("bluestacks", ignoreCase = true) ||
            Build.HARDWARE.contains("bluestacks", ignoreCase = true)
        ) {
            return Platform.BLUESTACKS
        }

        // --- MEmu -------------------------------------------------------
        // Checked via its own system packages first: current MEmu builds
        // spoof Build.MANUFACTURER/BRAND/FINGERPRINT to impersonate a real
        // Pixel, so those fields alone no longer detect it. The package
        // check catches it regardless; the Build-field checks stay as a
        // fallback for older/other builds that don't spoof.
        if (hasAnyPackage(MEMU_PACKAGES) ||
            Build.MANUFACTURER.contains("microvirt", ignoreCase = true) ||
            Build.BRAND.contains("microvirt", ignoreCase = true) ||
            Build.PRODUCT.contains("memu", ignoreCase = true) ||
            Build.MODEL.contains("memu", ignoreCase = true)
        ) {
            return Platform.MEMU
        }

        // --- Nox ---------------------------------------------------------
        if (hasAnyPackage(NOX_PACKAGES) ||
            Build.MANUFACTURER.contains("nox", ignoreCase = true) ||
            Build.BRAND.contains("nox", ignoreCase = true) ||
            fileExists("/system/bin/noxd")
        ) {
            return Platform.NOX
        }

        // --- LDPlayer ------------------------------------------------------
        if (Build.MANUFACTURER.contains("ldplayer", ignoreCase = true) ||
            Build.PRODUCT.contains("ldplayer", ignoreCase = true)
        ) {
            return Platform.LDPLAYER
        }

        // --- Genymotion -----------------------------------------------------
        if (Build.MANUFACTURER.contains("genymotion", ignoreCase = true) ||
            Build.PRODUCT.contains("vbox", ignoreCase = true)
        ) {
            return Platform.GENYMOTION
        }

        // --- Generic AVD / QEMU-based signals (stock Android Studio emulator,
        // and the QEMU/VirtualBox base several third-party emulators share) --
        val genericSignals = listOf(
            Build.FINGERPRINT.startsWith("generic"),
            Build.FINGERPRINT.startsWith("unknown"),
            Build.FINGERPRINT.contains("vbox", ignoreCase = true),
            Build.FINGERPRINT.contains("test-keys"),
            Build.MODEL.contains("google_sdk", ignoreCase = true),
            Build.MODEL.contains("Emulator", ignoreCase = true),
            Build.MODEL.contains("Android SDK built for x86", ignoreCase = true),
            Build.HARDWARE.contains("goldfish", ignoreCase = true),
            Build.HARDWARE.contains("ranchu", ignoreCase = true),
            Build.PRODUCT.contains("sdk", ignoreCase = true),
            Build.BOARD == "unknown",
            fileExists("/dev/qemu_pipe"),
            fileExists("/system/bin/qemu-props"),
        )
        val hits = genericSignals.count { it }

        if (hits >= 2) return Platform.GENERIC_AVD
        if (hits == 1) return Platform.UNKNOWN_EMULATOR

        return Platform.PHYSICAL_DEVICE
    }

    private fun hasAnyPackage(packages: List<String>): Boolean {
        val pm = appContext?.packageManager ?: return false
        return packages.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun fileExists(path: String): Boolean = try {
        File(path).exists()
    } catch (_: Exception) {
        false
    }
}
