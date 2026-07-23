package com.example.lanbridge

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

/**
 * Creates a TUN interface and bridges every packet to/from the hub relay
 * over a single UDP socket. Unlike the original version, this build asks
 * the hub for a virtual IP automatically -- no per-device editing needed.
 * Install the same APK on every BlueStacks/MEmu instance and your phone;
 * each one gets a unique IP the first time it connects and keeps it
 * afterward (the hub remembers it by a random ID stored on-device).
 *
 * EDIT ONLY THIS ONE CONSTANT, ONCE, FOR YOUR WHOLE SETUP:
 */
object Config {
    const val HUB_IP = "192.168.0.11"     // your PC's real LAN IP
    const val HUB_PORT = 7777             // data relay port
    const val ALLOC_PORT = 7778           // IP allocation port
}

class TunLanService : VpnService() {

    @Volatile private var running = false
    private var tunInterface: ParcelFileDescriptor? = null
    private var udpSocket: DatagramSocket? = null

    companion object {
        const val ACTION_STATUS = "com.example.lanbridge.STATUS"
        const val EXTRA_MESSAGE = "message"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            Thread { startBridge() }.start()
        }
        return START_STICKY
    }

    private fun broadcastStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).putExtra(EXTRA_MESSAGE, message))
    }

    /** A random ID generated once per device install, used so the hub can
     *  always hand back the same virtual IP to the same device. */
    private fun getOrCreateClientId(): String {
        val prefs = getSharedPreferences("lanbridge", MODE_PRIVATE)
        var id = prefs.getString("client_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("client_id", id).apply()
        }
        return id
    }

    /** Asks the hub for a virtual IP. Retries a few times in case the hub
     *  isn't up yet. Returns null if it never got an answer. */
    private fun requestVirtualIp(clientId: String): String? {
        val socket = DatagramSocket()
        socket.soTimeout = 3000
        try {
            val requestBytes = "{\"client_id\":\"$clientId\"}".toByteArray()
            val hubAddr = InetAddress.getByName(Config.HUB_IP)
            val ipPattern = Regex("\"ip\"\\s*:\\s*\"([^\"]+)\"")

            repeat(5) {
                try {
                    socket.send(DatagramPacket(requestBytes, requestBytes.size, hubAddr, Config.ALLOC_PORT))
                    val buffer = ByteArray(512)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val response = String(buffer, 0, packet.length)
                    ipPattern.find(response)?.let { return it.groupValues[1] }
                } catch (_: Exception) {
                    // timed out this attempt, loop and retry
                }
            }
        } finally {
            socket.close()
        }
        return null
    }

    private fun startBridge() {
        broadcastStatus("Requesting virtual IP from hub...")
        val clientId = getOrCreateClientId()
        val assignedIp = requestVirtualIp(clientId)

        if (assignedIp == null) {
            broadcastStatus("Could not reach hub at ${Config.HUB_IP}. Is hub_relay.py running?")
            stopSelf()
            return
        }
        broadcastStatus("Assigned IP: $assignedIp")

        val builder = Builder()
            .addAddress(assignedIp, 24)
            .addRoute("10.10.10.0", 24)
            .setMtu(1400)
            .setSession("LAN Bridge")

        val iface = builder.establish() ?: run {
            broadcastStatus("Failed to establish VPN interface")
            return
        }
        tunInterface = iface

        val input = FileInputStream(iface.fileDescriptor)
        val output = FileOutputStream(iface.fileDescriptor)

        val socket = DatagramSocket()
        protect(socket) // exclude this socket from the VPN's own routing, avoids a loop
        udpSocket = socket

        running = true
        val hubAddr = InetAddress.getByName(Config.HUB_IP)

        // TUN -> hub
        val tunToHub = Thread {
            val buffer = ByteArray(32767)
            try {
                while (running) {
                    val len = input.read(buffer)
                    if (len > 0) {
                        socket.send(DatagramPacket(buffer, len, hubAddr, Config.HUB_PORT))
                    }
                }
            } catch (_: Exception) { /* socket closed on stop */ }
        }

        // hub -> TUN
        val hubToTun = Thread {
            val buffer = ByteArray(32767)
            try {
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    output.write(buffer, 0, packet.length)
                }
            } catch (_: Exception) { /* socket closed on stop */ }
        }

        tunToHub.start()
        hubToTun.start()
        broadcastStatus("Connected as $assignedIp")
    }

    override fun onDestroy() {
        running = false
        try { udpSocket?.close() } catch (_: Exception) {}
        try { tunInterface?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}