package com.example.lanbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID

/**
 * Creates a TUN interface and bridges every packet to/from the hub relay
 * over a single UDP socket. Zero configuration needed on any device: it
 * finds the hub (via broadcast, or a live-verified gateway guess informed
 * by emulator detection, or a manual override as a last resort), asks for
 * its own virtual IP + a private session token, registers itself
 * immediately, and forwards traffic -- including broadcast/multicast, so
 * LAN game discovery works.
 */
object Config {
    const val DISCOVERY_PORT = 7776
    const val RELAY_PORT = 7777
    const val ALLOC_PORT = 7778

    // Must match NETWORK_KEY in hub_relay.py exactly. Change this from the
    // default before using this on anything but a fully trusted, isolated
    // network -- it's the shared secret gating discovery/allocation/list.
    const val NETWORK_KEY = "changeme-shared-secret"

    const val TOKEN_LEN = 36 // length of a random UUID string
}

class TunLanService : VpnService() {

    @Volatile private var running = false
    private var tunInterface: ParcelFileDescriptor? = null
    private var udpSocket: DatagramSocket? = null

    companion object {
        const val ACTION_STATUS = "com.example.lanbridge.STATUS"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_HUB_IP = "hub_ip"
        private const val NOTIFICATION_CHANNEL_ID = "lan_bridge_channel"
        private const val NOTIFICATION_ID = 1
    }

    private var discoveredHubAddr: InetAddress? = null

    override fun onCreate() {
        super.onCreate()
        EmulatorDetector.init(this)
        createNotificationChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        if (!running) {
            Thread { startBridge() }.start()
        }
        return START_STICKY
    }

    /** Without this, the OS is free to kill this service once the app is
     *  backgrounded -- which would silently drop the tunnel mid-game. */
    private fun startForegroundWithNotification() {
        val notification: Notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("LAN Bridge")
            .setContentText("Bridge active -- keeping the tunnel alive")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+ requires an explicit foregroundServiceType at call time
            // in addition to the manifest declaration.
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "LAN Bridge",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun broadcastStatus(message: String) {
        val intent = Intent(ACTION_STATUS).putExtra(EXTRA_MESSAGE, message)
        discoveredHubAddr?.hostAddress?.let { intent.putExtra(EXTRA_HUB_IP, it) }
        sendBroadcast(intent)
    }

    /** A random ID generated once per device install, so the hub can
     *  always hand back the same virtual IP + session token to the
     *  same device. */
    private fun getOrCreateClientId(): String {
        val prefs = getSharedPreferences("lanbridge", MODE_PRIVATE)
        var id = prefs.getString("client_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("client_id", id).apply()
        }
        return id
    }

    /** Broadcasts on the local subnet asking "where's the hub?". Works
     *  well on real WiFi and on emulators with a fuller virtual switch
     *  (BlueStacks). Some emulators' NAT layers silently drop broadcast
     *  even though unicast works fine -- see discoverHubViaGatewayGuess
     *  for the fallback that handles those. */
    private fun discoverHubViaBroadcast(): InetAddress? {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.broadcast = true
        socket.soTimeout = 2000
        try {
            socket.bind(java.net.InetSocketAddress(0))
            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            val requestBytes = buildDiscoveryRequest()

            repeat(6) {
                try {
                    socket.send(DatagramPacket(requestBytes, requestBytes.size, broadcastAddr, Config.DISCOVERY_PORT))
                    val buffer = ByteArray(256)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    if (isValidHubReply(buffer, packet.length)) return packet.address
                } catch (_: Exception) {
                    // timed out this attempt, loop and retry
                }
            }
        } finally {
            socket.close()
        }
        return null
    }

    /** Derives this device's own IP/subnet (the same information you'd
     *  see for the relevant adapter in the host's own `ipconfig`, just
     *  read from the guest side instead), and guesses the gateway is the
     *  ".1" of that subnet -- which has held true for every BlueStacks and
     *  MEmu instance observed. Crucially, this guess is not trusted
     *  blindly: it's verified with a direct unicast probe, so a wrong
     *  guess just fails cleanly rather than connecting to the wrong hub. */
    private fun discoverHubViaGatewayGuess(): InetAddress? {
        val guess = guessGatewayFromOwnIp() ?: return null
        val socket = DatagramSocket()
        socket.soTimeout = 1500
        try {
            val requestBytes = buildDiscoveryRequest()
            repeat(3) {
                try {
                    socket.send(DatagramPacket(requestBytes, requestBytes.size, guess, Config.DISCOVERY_PORT))
                    val buffer = ByteArray(256)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    if (isValidHubReply(buffer, packet.length)) return packet.address
                } catch (_: Exception) {
                    // timed out, retry
                }
            }
        } finally {
            socket.close()
        }
        return null
    }

    private fun guessGatewayFromOwnIp(): InetAddress? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.interfaceAddresses) {
                    val ip = addr.address
                    if (ip is Inet4Address && !ip.isLoopbackAddress) {
                        val bytes = ip.address
                        // skip our own tunnel subnet if somehow already up
                        if (bytes[0] == 10.toByte() && bytes[1] == 10.toByte() && bytes[2] == 10.toByte()) continue
                        val guessBytes = byteArrayOf(bytes[0], bytes[1], bytes[2], 1)
                        return InetAddress.getByAddress(guessBytes)
                    }
                }
            }
        } catch (_: Exception) { /* fall through to null */ }
        return null
    }

    private fun buildDiscoveryRequest(): ByteArray =
        "{\"magic\":\"LANBRIDGE_DISCOVER\",\"key\":\"${Config.NETWORK_KEY}\"}".toByteArray()

    private fun isValidHubReply(buffer: ByteArray, length: Int): Boolean {
        val text = String(buffer, 0, length)
        return text.contains("LANBRIDGE_HUB")
    }

    /** Result of a successful allocation: the assigned virtual IP and the
     *  private session token that must accompany every relay packet. */
    private data class Allocation(val ip: String, val token: String)

    /** Asks the hub for a virtual IP + session token, sending along a
     *  friendly device name and the shared network key. Retries a few
     *  times. */
    private fun requestAllocation(hubAddr: InetAddress, clientId: String): Allocation? {
        val socket = DatagramSocket()
        socket.soTimeout = 3000
        try {
            val safeName = Build.MODEL.replace("\"", "").replace("\\", "")
            val requestBytes = ("{\"client_id\":\"$clientId\",\"name\":\"$safeName\"," +
                "\"key\":\"${Config.NETWORK_KEY}\"}").toByteArray()
            val ipPattern = Regex("\"ip\"\\s*:\\s*\"([^\"]+)\"")
            val tokenPattern = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"")

            repeat(5) {
                try {
                    socket.send(DatagramPacket(requestBytes, requestBytes.size, hubAddr, Config.ALLOC_PORT))
                    val buffer = ByteArray(512)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val response = String(buffer, 0, packet.length)
                    val ip = ipPattern.find(response)?.groupValues?.get(1)
                    val token = tokenPattern.find(response)?.groupValues?.get(1)
                    if (ip != null && token != null) return Allocation(ip, token)
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
        val emulatorLabel = EmulatorDetector.platform.label
        broadcastStatus("Detected platform: $emulatorLabel")

        val manualHubIp = getSharedPreferences("lanbridge", MODE_PRIVATE)
            .getString("manual_hub_ip", "")
            ?.trim()

        val hubAddr: InetAddress? = when {
            !manualHubIp.isNullOrEmpty() -> {
                broadcastStatus("Using manual hub IP: $manualHubIp")
                try {
                    InetAddress.getByName(manualHubIp)
                } catch (_: Exception) {
                    broadcastStatus("Manual hub IP is invalid: $manualHubIp")
                    null
                }
            }
            else -> {
                broadcastStatus("Searching for hub via broadcast...")
                discoverHubViaBroadcast() ?: run {
                    broadcastStatus("Broadcast found nothing -- trying a direct guess based on " +
                        "this device's own network ($emulatorLabel)...")
                    discoverHubViaGatewayGuess()
                }
            }
        }

        if (hubAddr == null) {
            broadcastStatus("No hub found. Is hub_relay.py running on your PC? " +
                "If auto-discovery doesn't work on this emulator, try entering its gateway IP manually.")
            stopSelf()
            return
        }
        discoveredHubAddr = hubAddr
        broadcastStatus("Found hub at ${hubAddr.hostAddress}, requesting IP...")

        val clientId = getOrCreateClientId()
        val allocation = requestAllocation(hubAddr, clientId)
        if (allocation == null) {
            broadcastStatus("Hub found but did not assign an IP. Try again.")
            stopSelf()
            return
        }
        val assignedIp = allocation.ip
        broadcastStatus("Assigned IP: $assignedIp")

        val builder = Builder()
            .addAddress(assignedIp, 24)
            .addRoute("10.10.10.0", 24)
            .addRoute("255.255.255.255", 32)  // capture global broadcast (game discovery)
            .addRoute("224.0.0.0", 4)          // capture multicast, just in case
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
        val tokenBytes = allocation.token.toByteArray()

        // TUN -> hub. Every packet is prefixed with our private session
        // token so the hub can verify we're actually authorized to speak
        // for this virtual IP before relaying/registering us.
        val tunToHub = Thread {
            val buffer = ByteArray(Config.TOKEN_LEN + 32767)
            System.arraycopy(tokenBytes, 0, buffer, 0, tokenBytes.size)
            try {
                while (running) {
                    val len = input.read(buffer, Config.TOKEN_LEN, buffer.size - Config.TOKEN_LEN)
                    if (len > 0) {
                        socket.send(DatagramPacket(buffer, Config.TOKEN_LEN + len, hubAddr, Config.RELAY_PORT))
                    }
                }
            } catch (_: Exception) { /* socket closed on stop */ }
        }

        // hub -> TUN. The hub already strips the token before forwarding,
        // so what arrives here is a plain raw IP packet ready for the TUN.
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

        // Proactively register with the hub immediately, and keep
        // re-announcing, so the hub knows we exist before any real traffic
        // has to pass through us.
        val myIpBytes = InetAddress.getByName(assignedIp).address
        val keepAlive = Thread {
            val regPacket = buildRegistrationPacket(tokenBytes, myIpBytes)
            while (running) {
                try {
                    socket.send(DatagramPacket(regPacket, regPacket.size, hubAddr, Config.RELAY_PORT))
                } catch (_: Exception) { /* ignore, will retry next loop */ }
                Thread.sleep(15000)
            }
        }
        keepAlive.start()

        broadcastStatus("Connected as $assignedIp (hub ${hubAddr.hostAddress}, $emulatorLabel)")
    }

    /** Builds token + minimal stand-in IPv4 header purely so the hub can
     *  authenticate and read the source address -- it doesn't need to be
     *  a real, checksummed packet since it's never delivered to any OS
     *  network stack, only parsed by our own Python hub. */
    private fun buildRegistrationPacket(tokenBytes: ByteArray, myIpBytes: ByteArray): ByteArray {
        val packet = ByteArray(Config.TOKEN_LEN + 20)
        System.arraycopy(tokenBytes, 0, packet, 0, tokenBytes.size)
        val base = Config.TOKEN_LEN
        packet[base + 12] = myIpBytes[0]
        packet[base + 13] = myIpBytes[1]
        packet[base + 14] = myIpBytes[2]
        packet[base + 15] = myIpBytes[3]
        packet[base + 16] = 10
        packet[base + 17] = 10
        packet[base + 18] = 10
        packet[base + 19] = 1
        return packet
    }

    /** Called if VPN permission is revoked from system settings mid-session
     *  (a distinct path from a normal stopService/onDestroy call). Without
     *  this override, state could dangle until onDestroy eventually fires. */
    override fun onRevoke() {
        cleanup()
        super.onRevoke()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        running = false
        try { udpSocket?.close() } catch (_: Exception) {}
        try { tunInterface?.close() } catch (_: Exception) {}
        udpSocket = null
        tunInterface = null
    }
}