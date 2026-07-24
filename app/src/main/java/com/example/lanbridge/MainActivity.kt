package com.example.lanbridge

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class MainActivity : Activity() {

    private val vpnRequestCode = 100
    private lateinit var statusText: TextView
    private lateinit var peersText: TextView
    private lateinit var manualHubField: EditText
    private var lastKnownHubIp: String? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(TunLanService.EXTRA_MESSAGE) ?: return
            statusText.text = message
            intent.getStringExtra(TunLanService.EXTRA_HUB_IP)?.let { lastKnownHubIp = it }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val title = TextView(this).apply {
            text = "LAN Bridge"
            textSize = 20f
        }

        statusText = TextView(this).apply {
            text = "Not connected"
            textSize = 16f
            setPadding(0, 24, 0, 24)
        }

        val prefs = getSharedPreferences("lanbridge", MODE_PRIVATE)

        val manualHubLabel = TextView(this).apply {
            text = "Manual hub IP (optional -- leave blank for auto-discovery)"
            textSize = 12f
            setPadding(0, 24, 0, 8)
        }

        manualHubField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "e.g. 172.17.48.1"
            setText(prefs.getString("manual_hub_ip", ""))
        }

        val startButton = Button(this).apply {
            text = "Start Bridge"
            setOnClickListener {
                val manualIp = manualHubField.text.toString().trim()
                prefs.edit().putString("manual_hub_ip", manualIp).apply()
                startBridge()
            }
        }

        val stopButton = Button(this).apply {
            text = "Stop Bridge"
            setOnClickListener {
                stopService(Intent(this@MainActivity, TunLanService::class.java))
                statusText.text = "Stopped"
                peersText.text = ""
            }
        }

        val peersLabel = TextView(this).apply {
            text = "Connected devices"
            textSize = 14f
            setPadding(0, 32, 0, 8)
        }

        peersText = TextView(this).apply {
            text = ""
            textSize = 14f
        }

        val refreshButton = Button(this).apply {
            text = "Refresh Peer List"
            setOnClickListener { refreshPeers() }
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(manualHubLabel)
        layout.addView(manualHubField)
        layout.addView(startButton)
        layout.addView(stopButton)
        layout.addView(peersLabel)
        layout.addView(refreshButton)
        layout.addView(peersText)
        setContentView(layout)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(TunLanService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(statusReceiver)
    }

    private fun startBridge() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, vpnRequestCode)
        } else {
            onActivityResult(vpnRequestCode, Activity.RESULT_OK, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnRequestCode && resultCode == Activity.RESULT_OK) {
            startService(Intent(this, TunLanService::class.java))
        }
    }

    /** Asks the hub who else is currently online and updates the on-screen
     *  list. Runs on a background thread since it's network I/O. */
    private fun refreshPeers() {
        val hubIp = lastKnownHubIp
        if (hubIp == null) {
            peersText.text = "Not connected yet -- start the bridge first."
            return
        }
        peersText.text = "Loading..."
        Thread {
            val result = queryPeerList(hubIp)
            runOnUiThread { peersText.text = result }
        }.start()
    }

    private fun queryPeerList(hubIp: String): String {
        val socket = DatagramSocket()
        socket.soTimeout = 3000
        try {
            val hubAddr = InetAddress.getByName(hubIp)
            val requestBytes = "{\"action\":\"list\"}".toByteArray()
            socket.send(DatagramPacket(requestBytes, requestBytes.size, hubAddr, Config.ALLOC_PORT))

            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val response = String(buffer, 0, packet.length)

            val entryPattern = Regex("\"ip\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"name\"\\s*:\\s*\"([^\"]*)\"")
            val matches = entryPattern.findAll(response).toList()
            if (matches.isEmpty()) return "No other devices online right now."

            return matches.joinToString("\n") { m ->
                val ip = m.groupValues[1]
                val name = m.groupValues[2]
                if (name.isNotBlank() && name != ip) "$name  ($ip)" else ip
            }
        } catch (e: Exception) {
            return "Could not reach hub: ${e.message}"
        } finally {
            socket.close()
        }
    }
}