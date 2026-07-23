package com.example.lanbridge

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val vpnRequestCode = 100
    private lateinit var statusText: TextView

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(TunLanService.EXTRA_MESSAGE) ?: return
            statusText.text = message
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
            text = "Not connected\nHub: ${Config.HUB_IP}"
            textSize = 16f
            setPadding(0, 24, 0, 24)
        }

        val startButton = Button(this).apply {
            text = "Start Bridge"
            setOnClickListener { startBridge() }
        }

        val stopButton = Button(this).apply {
            text = "Stop Bridge"
            setOnClickListener {
                stopService(Intent(this@MainActivity, TunLanService::class.java))
                statusText.text = "Stopped"
            }
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(startButton)
        layout.addView(stopButton)
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
}