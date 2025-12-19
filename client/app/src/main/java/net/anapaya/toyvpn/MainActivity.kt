package net.anapaya.toyvpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var layoutSetup: View
    private lateinit var layoutConnected: View

    private lateinit var etServerAddressFull: TextInputEditText
    private lateinit var etSnapAddress: TextInputEditText
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button

    private lateinit var tvConnectedServer: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvVolume: TextView
    private lateinit var tvAssignedIp: TextView
    private lateinit var tvRoutes: TextView

    private var isConnected = false
    private val VPN_REQUEST_CODE = 0x0F

    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ToyVpnService.ACTION_STATS_UPDATE) {
                val duration = intent.getLongExtra(ToyVpnService.EXTRA_STATS_DURATION, 0L)

                // Disconnect signal: duration=0 AND no TX/RX bytes present
                val hasBytesData = intent.hasExtra(ToyVpnService.EXTRA_STATS_TX_BYTES)
                if (duration == 0L && isConnected && !hasBytesData) {
                     // Disconnect signal
                     isConnected = false
                     updateUI()
                     return
                }

                val txBytes = intent.getLongExtra(ToyVpnService.EXTRA_STATS_TX_BYTES, 0L)
                val rxBytes = intent.getLongExtra(ToyVpnService.EXTRA_STATS_RX_BYTES, 0L)
                val txRate = intent.getLongExtra(ToyVpnService.EXTRA_STATS_TX_RATE, 0L)
                val rxRate = intent.getLongExtra(ToyVpnService.EXTRA_STATS_RX_RATE, 0L)

                val assignedIp = intent.getStringExtra(ToyVpnService.EXTRA_ASSIGNED_IP)
                val routes = intent.getStringExtra(ToyVpnService.EXTRA_ROUTES)

                tvDuration.text = formatDuration(duration)
                tvSpeed.text = "↓ ${formatBytes(rxRate)}/s   ↑ ${formatBytes(txRate)}/s"
                tvVolume.text = "↓ ${formatBytes(rxBytes)}   ↑ ${formatBytes(txBytes)}"

                if (assignedIp != null) {
                    tvAssignedIp.text = assignedIp
                }
                if (routes != null) {
                    tvRoutes.text = routes
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        layoutSetup = findViewById(R.id.layoutSetup)
        layoutConnected = findViewById(R.id.layoutConnected)

        etServerAddressFull = findViewById(R.id.etServerAddressFull)
        etSnapAddress = findViewById(R.id.etSnapAddress)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        tvConnectedServer = findViewById(R.id.tvConnectedServer)
        tvDuration = findViewById(R.id.tvDuration)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvVolume = findViewById(R.id.tvVolume)
        tvAssignedIp = findViewById(R.id.tvAssignedIp)
        tvRoutes = findViewById(R.id.tvRoutes)

        btnConnect.setOnClickListener {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, VPN_REQUEST_CODE)
            } else {
                onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null)
            }
        }

        btnDisconnect.setOnClickListener {
            startService(Intent(this, ToyVpnService::class.java).apply {
                action = ToyVpnService.ACTION_DISCONNECT
            })
            isConnected = false
            updateUI()
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ToyVpnService.ACTION_STATS_UPDATE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
             registerReceiver(statsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
             registerReceiver(statsReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statsReceiver)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            val fullAddress = etServerAddressFull.text.toString()

            if (fullAddress.isBlank()) {
                Toast.makeText(this, "Please enter server address", Toast.LENGTH_SHORT).show()
                return
            }

            val lastColonIndex = fullAddress.lastIndexOf(':')
            if (lastColonIndex == -1) {
                Toast.makeText(this, "Invalid format. Use IP:Port", Toast.LENGTH_SHORT).show()
                return
            }

            val serverIp = fullAddress.substring(0, lastColonIndex)
            val serverPortStr = fullAddress.substring(lastColonIndex + 1)
            val serverPort = serverPortStr.toIntOrNull()

            if (serverPort == null) {
                Toast.makeText(this, "Invalid port", Toast.LENGTH_SHORT).show()
                return
            }

            val clientIp = "10.0.0.2"

            val intent = Intent(this, ToyVpnService::class.java).apply {
                action = ToyVpnService.ACTION_CONNECT
                putExtra(ToyVpnService.EXTRA_SERVER_ADDRESS, serverIp)
                putExtra(ToyVpnService.EXTRA_SERVER_PORT, serverPort)
                putExtra(ToyVpnService.EXTRA_CLIENT_IP, clientIp)
            }
            startService(intent)
            isConnected = true

            tvConnectedServer.text = "Connected to $fullAddress"

            updateUI()
        }
    }

    private fun updateUI() {
        if (isConnected) {
            layoutSetup.visibility = View.GONE
            layoutConnected.visibility = View.VISIBLE
        } else {
            layoutSetup.visibility = View.VISIBLE
            layoutConnected.visibility = View.GONE
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024f)
            else -> String.format("%.1f MB", bytes / (1024f * 1024f))
        }
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }
}