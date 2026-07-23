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
 * over a single UDP socket. This build needs ZERO configuration on any
 * device: it broadcasts to find the hub, then asks the hub for its own
 * virtual IP automatically. Install the exact same APK on every
 * BlueStacks/MEmu instance and your phone -- nothing to edit, ever.
 */
object Config {
    const val DISCOVERY_PORT = 7776
    const val RELAY_PORT = 7777
    const val ALLOC_PORT = 7778
    val DISCOVERY_MAGIC: ByteArray = "LANBRIDGE_DISCOVER".toByteArray()
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

    /** A random ID generated once per device install, so the hub can
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

    /** Broadcasts on the local subnet asking "where's the hub?" and
     *  returns whichever address answers -- that's automatically the
     *  correct hub IP for this specific device/network. Retries a few
     *  times since UDP broadcast can occasionally get dropped. */
    private fun discoverHub(): InetAddress? {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.broadcast = true
        socket.soTimeout = 2000
        try {
            socket.bind(java.net.InetSocketAddress(0))
            val broadcastAddr = InetAddress.getByName("255.255.255.255")

            repeat(6) {
                try {
                    socket.send(
                        DatagramPacket(
                            Config.DISCOVERY_MAGIC, Config.DISCOVERY_MAGIC.size,
                            broadcastAddr, Config.DISCOVERY_PORT
                        )
                    )
                    val buffer = ByteArray(64)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    if (String(buffer, 0, packet.length) == "LANBRIDGE_HUB") {
                        return packet.address
                    }
                } catch (_: Exception) {
                    // timed out this attempt, loop and retry
                }
            }
        } finally {
            socket.close()
        }
        return null
    }

    /** Asks the hub for a virtual IP. Retries a few times. */
    private fun requestVirtualIp(hubAddr: InetAddress, clientId: String): String? {
        val socket = DatagramSocket()
        socket.soTimeout = 3000
        try {
            val requestBytes = "{\"client_id\":\"$clientId\"}".toByteArray()
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
        val manualHubIp = getSharedPreferences("lanbridge", MODE_PRIVATE)
            .getString("manual_hub_ip", "")
            ?.trim()

        val hubAddr: InetAddress? = if (!manualHubIp.isNullOrEmpty()) {
            broadcastStatus("Using manual hub IP: $manualHubIp")
            try {
                InetAddress.getByName(manualHubIp)
            } catch (_: Exception) {
                broadcastStatus("Manual hub IP is invalid: $manualHubIp")
                null
            }
        } else {
            broadcastStatus("Searching for hub on the network...")
            discoverHub()
        }

        if (hubAddr == null) {
            broadcastStatus("No hub found. Is hub_relay.py running on your PC? " +
                "If auto-discovery doesn't work on this emulator, try entering its gateway IP manually.")
            stopSelf()
            return
        }
        broadcastStatus("Found hub at ${hubAddr.hostAddress}, requesting IP...")

        val clientId = getOrCreateClientId()
        val assignedIp = requestVirtualIp(hubAddr, clientId)
        if (assignedIp == null) {
            broadcastStatus("Hub found but did not assign an IP. Try again.")
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

        // TUN -> hub
        val tunToHub = Thread {
            val buffer = ByteArray(32767)
            try {
                while (running) {
                    val len = input.read(buffer)
                    if (len > 0) {
                        socket.send(DatagramPacket(buffer, len, hubAddr, Config.RELAY_PORT))
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

        // Proactively register with the hub immediately, and keep re-announcing.
        // Without this, the hub has no idea a device exists until it happens to
        // send real traffic first -- which means the very first ping to it
        // from another device would silently fail.
        val myIpBytes = InetAddress.getByName(assignedIp).address
        val keepAlive = Thread {
            val regPacket = buildRegistrationPacket(myIpBytes)
            while (running) {
                try {
                    socket.send(DatagramPacket(regPacket, regPacket.size, hubAddr, Config.RELAY_PORT))
                } catch (_: Exception) { /* ignore, will retry next loop */ }
                Thread.sleep(15000)
            }
        }
        keepAlive.start()

        broadcastStatus("Connected as $assignedIp (hub ${hubAddr.hostAddress})")
    }

    /** Builds a minimal 20-byte stand-in IPv4 header purely so the hub's
     *  relay can read the source address out of bytes 12-15 -- it doesn't
     *  need to be a real, checksummed packet since it's never delivered
     *  to any OS network stack, only parsed by our own Python hub. */
    private fun buildRegistrationPacket(myIpBytes: ByteArray): ByteArray {
        val packet = ByteArray(20)
        packet[12] = myIpBytes[0]
        packet[13] = myIpBytes[1]
        packet[14] = myIpBytes[2]
        packet[15] = myIpBytes[3]
        // destination doesn't matter for registration -- point it at the hub itself
        packet[16] = 10
        packet[17] = 10
        packet[18] = 10
        packet[19] = 1
        return packet
    }

    override fun onDestroy() {
        running = false
        try { udpSocket?.close() } catch (_: Exception) {}
        try { tunInterface?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}