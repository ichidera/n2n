package com.example.lanbridge

import android.os.Build
import java.io.File

/**
 * Detects whether the app is running on an emulator, and which one, using
 * the same general approach production apps use for this: no single check
 * is reliable on its own (all of these are spoofable in principle), so we
 * combine several independent signals. This is inherently heuristic --
 * it's meant to inform automatic networking decisions (see
 * TunLanService.guessGatewayFromOwnIp), not to be a security boundary.
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

    /** Cached after first check since Build.* fields and file existence
     *  checks never change during a process's lifetime. */
    val platform: Platform by lazy { detect() }

    val isEmulator: Boolean get() = platform != Platform.PHYSICAL_DEVICE

    private fun detect(): Platform {
        // --- BlueStacks -----------------------------------------------
        // BlueStacks shares a Windows folder into the guest at this fixed
        // path (visible in its own system logs as well), and its Build
        // fields commonly reference the brand directly.
        if (fileExists("/mnt/windows/BstSharedFolder") ||
            Build.MANUFACTURER.contains("bluestacks", ignoreCase = true) ||
            Build.BRAND.contains("bluestacks", ignoreCase = true) ||
            Build.PRODUCT.contains("bluestacks", ignoreCase = true) ||
            Build.HARDWARE.contains("bluestacks", ignoreCase = true)
        ) {
            return Platform.BLUESTACKS
        }

        // --- MEmu -------------------------------------------------------
        if (Build.MANUFACTURER.contains("microvirt", ignoreCase = true) ||
            Build.BRAND.contains("microvirt", ignoreCase = true) ||
            Build.PRODUCT.contains("memu", ignoreCase = true) ||
            Build.MODEL.contains("memu", ignoreCase = true)
        ) {
            return Platform.MEMU
        }

        // --- Nox ---------------------------------------------------------
        if (Build.MANUFACTURER.contains("nox", ignoreCase = true) ||
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

    private fun fileExists(path: String): Boolean = try {
        File(path).exists()
    } catch (_: Exception) {
        false
    }
}