package com.example.lanbridge

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Creates a TUN interface at VIRTUAL_IP and bridges every packet to/from
 * the hub relay over a single UDP socket. The hub (hub_relay.py) handles
 * unicast forwarding and broadcast flooding, so from this app's point of
 * view it's just "read from TUN, send to hub" and "receive from hub,
 * write to TUN".
 *
 * EDIT THESE THREE CONSTANTS PER DEVICE / SETUP:
 */
object Config {
    const val VIRTUAL_IP = "10.10.10.2"   // unique per device: .2, .3, .4, .5, .6 ...
    const val HUB_IP = "192.168.0.11"     // your PC's real LAN IP
    const val HUB_PORT = 7777
}

class TunLanService : VpnService() {

    @Volatile private var running = false
    private var tunInterface: ParcelFileDescriptor? = null
    private var udpSocket: DatagramSocket? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            Thread { startBridge() }.start()
        }
        return START_STICKY
    }

    private fun startBridge() {
        val builder = Builder()
            .addAddress(Config.VIRTUAL_IP, 24)
            .addRoute("10.10.10.0", 24)
            .setMtu(1400)
            .setSession("LAN Bridge")

        val iface = builder.establish() ?: return
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
    }

    override fun onDestroy() {
        running = false
        try { udpSocket?.close() } catch (_: Exception) {}
        try { tunInterface?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
