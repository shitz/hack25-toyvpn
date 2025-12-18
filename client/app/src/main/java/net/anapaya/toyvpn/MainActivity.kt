package net.anapaya.toyvpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var etServerAddress: TextInputEditText
    private lateinit var etServerPort: TextInputEditText
    private lateinit var etClientIp: TextInputEditText
    private lateinit var btnConnect: Button
    private lateinit var layoutStats: LinearLayout
    private lateinit var tvDuration: TextView
    private lateinit var tvTx: TextView
    private lateinit var tvRx: TextView

    private var isConnected = false
    private val VPN_REQUEST_CODE = 0x0F

    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ToyVpnService.ACTION_STATS_UPDATE) {
                val duration = intent.getLongExtra(ToyVpnService.EXTRA_STATS_DURATION, 0L)

                // Disconnect signal: duration=0 AND no TX/RX bytes present
                // (Normal stats updates always include bytes, stop signal doesn't)
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

                tvDuration.text = "Duration: ${formatDuration(duration)}"
                tvTx.text = "Sent: ${formatBytes(txBytes)} (${formatBytes(txRate)}/s)"
                tvRx.text = "Recv: ${formatBytes(rxBytes)} (${formatBytes(rxRate)}/s)"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etServerAddress = findViewById(R.id.etServerAddress)
        etServerPort = findViewById(R.id.etServerPort)
        etClientIp = findViewById(R.id.etClientIp)
        btnConnect = findViewById(R.id.btnConnect)
        layoutStats = findViewById(R.id.layoutStats)
        tvDuration = findViewById(R.id.tvDuration)
        tvTx = findViewById(R.id.tvTx)
        tvRx = findViewById(R.id.tvRx)

        btnConnect.setOnClickListener {
            if (!isConnected) {
                // Connect
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    startActivityForResult(intent, VPN_REQUEST_CODE)
                } else {
                    onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null)
                }
            } else {
                // Disconnect
                startService(Intent(this, ToyVpnService::class.java).apply {
                    action = ToyVpnService.ACTION_DISCONNECT
                })
                isConnected = false
                updateUI()
            }
        }
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
            val serverIp = etServerAddress.text.toString()
            val serverPortStr = etServerPort.text.toString()
            val clientIp = etClientIp.text.toString()

            if (serverIp.isBlank() || serverPortStr.isBlank() || clientIp.isBlank()) {
                Toast.makeText(this, "Please check all fields", Toast.LENGTH_SHORT).show()
                return
            }

            val serverPort = serverPortStr.toIntOrNull() ?: 12345

            val intent = Intent(this, ToyVpnService::class.java).apply {
                action = ToyVpnService.ACTION_CONNECT
                putExtra(ToyVpnService.EXTRA_SERVER_ADDRESS, serverIp)
                putExtra(ToyVpnService.EXTRA_SERVER_PORT, serverPort)
                putExtra(ToyVpnService.EXTRA_CLIENT_IP, clientIp)
            }
            startService(intent)
            isConnected = true
            updateUI()
        }
    }

    private fun updateUI() {
        if (isConnected) {
            btnConnect.text = getString(R.string.disconnect)
            etServerAddress.isEnabled = false
            etServerPort.isEnabled = false
            etClientIp.isEnabled = false
            layoutStats.visibility = View.VISIBLE
        } else {
            btnConnect.text = getString(R.string.connect)
            etServerAddress.isEnabled = true
            etServerPort.isEnabled = true
            etClientIp.isEnabled = true
            layoutStats.visibility = View.GONE
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
