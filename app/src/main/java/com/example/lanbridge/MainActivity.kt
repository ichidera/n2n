package com.example.lanbridge

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val vpnRequestCode = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val label = TextView(this).apply {
            text = "LAN Bridge\nVirtual IP: ${Config.VIRTUAL_IP}\nHub: ${Config.HUB_IP}:${Config.HUB_PORT}"
            textSize = 16f
        }

        val startButton = Button(this).apply {
            text = "Start Bridge"
            setOnClickListener { startBridge() }
        }

        val stopButton = Button(this).apply {
            text = "Stop Bridge"
            setOnClickListener {
                stopService(Intent(this@MainActivity, TunLanService::class.java))
            }
        }

        layout.addView(label)
        layout.addView(startButton)
        layout.addView(stopButton)
        setContentView(layout)
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
}
