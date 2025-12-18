package net.anapaya.toyvpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

// Import UniFFI generated bindings
// Note: Package name depends on uniffi generation defaults.
// We assume 'uniffi.toyvpn_client' based on crate name.
import uniffi.toyvpn_client.ToyVpnClient
import uniffi.toyvpn_client.VpnCallback

class ToyVpnService : VpnService() {

    private var interfacePfd: ParcelFileDescriptor? = null
    private var job: Job? = null
    private var vpnClient: ToyVpnClient? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        const val ACTION_CONNECT = "net.anapaya.toyvpn.CONNECT"
        const val ACTION_DISCONNECT = "net.anapaya.toyvpn.DISCONNECT"
        const val ACTION_STATS_UPDATE = "net.anapaya.toyvpn.STATS_UPDATE"

        const val EXTRA_SERVER_ADDRESS = "server_address"
        const val EXTRA_SERVER_PORT = "server_port"
        const val EXTRA_CLIENT_IP = "client_ip"

        const val EXTRA_STATS_DURATION = "stats_duration"
        const val EXTRA_STATS_TX_BYTES = "stats_tx_bytes"
        const val EXTRA_STATS_RX_BYTES = "stats_rx_bytes"
        const val EXTRA_STATS_TX_RATE = "stats_tx_rate"
        const val EXTRA_STATS_RX_RATE = "stats_rx_rate"

        private const val CHANNEL_ID = "ToyVpnChannel"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            stopVpn()
            return START_NOT_STICKY
        } else if (intent?.action == ACTION_CONNECT) {
            val serverIp = intent.getStringExtra(EXTRA_SERVER_ADDRESS) ?: "163.172.171.48"
            val serverPort = intent.getIntExtra(EXTRA_SERVER_PORT, 12345)
            val clientIp = intent.getStringExtra(EXTRA_CLIENT_IP) ?: "10.0.0.2"
            startVpn(serverIp, serverPort, clientIp)
            return START_STICKY
        }
        return START_NOT_STICKY
    }

    private fun startVpn(serverIp: String, serverPort: Int, clientIp: String) {
        if (job != null) return

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ToyVPN")
            .setContentText("Connected to $serverIp")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notification)

        // Initialize Rust Client
        try {
            vpnClient = ToyVpnClient()
        } catch (e: Exception) {
            Log.e("ToyVPN", "Failed to load Rust client", e)
            stopSelf()
            return
        }

        job = scope.launch {
            try {
                runVpn(serverIp, serverPort, clientIp)
            } catch (e: Exception) {
                Log.e("ToyVPN", "VPN Error", e)
            } finally {
                // cleanup handled in stopVpn or unexpected crash
                if (isActive) stopVpn()
            }
        }
    }

    private fun stopVpn() {
        try {
            Log.d("ToyVPN", "Stopping VPN...")
            vpnClient?.stop()
            // We don't nullify vpnClient immediately as it might still be in callback

            interfacePfd?.close() // Ensure FD is closed if Rust didn't (though Rust took ownership of the dup?)
            // Actually detachFd() invalidates interfacePfd, so we don't need to close it here if we detached.
            // But if we failed before detach, we should close.
            if (interfacePfd != null) {
                interfacePfd?.close()
                interfacePfd = null
            }

            job?.cancel()
            job = null
            stopForeground(true)
            stopSelf()

            sendBroadcast(Intent(ACTION_STATS_UPDATE).apply {
                setPackage(packageName)
                putExtra(EXTRA_STATS_DURATION, 0L)
            })

        } catch (e: Exception) {
            Log.e("ToyVPN", "Error stopping VPN", e)
        }
    }

    private suspend fun runVpn(serverIp: String, serverPort: Int, clientIp: String) {
        Log.d("ToyVPN", "Setting up VPN interface")
        val builder = Builder()
        builder.setSession("ToyVPN")
        builder.addAddress(clientIp, 24)
        builder.addRoute("0.0.0.0", 0)
        builder.setMtu(1500)

        try {
            interfacePfd = builder.establish()
        } catch (e: Exception) {
            Log.e("ToyVPN", "Builder establish failed", e)
            throw e
        }

        if (interfacePfd == null) {
             throw IllegalStateException("Could not establish VPN")
        }

        // Detach FD to pass ownership to Rust
        // Note: detachFd() returns the int FD and closes the ParcelFileDescriptor container (but not the FD itself)
        val fd = interfacePfd!!.detachFd()
        Log.d("ToyVPN", "VPN Interface FD: $fd") // Will be passed to Rust.

        // Clear kotlin reference as it's now invalid/detached
        interfacePfd = null

        val startTime = System.currentTimeMillis()

        // Create Callback
        val callback = object : VpnCallback {
            override fun onStatsUpdate(txBytes: ULong, rxBytes: ULong) {
                 val duration = (System.currentTimeMillis() - startTime) / 1000
                 // Calculate rates? We need state.
                 // Simple implementation: just pass totals. UI calculates rate?
                 // MainActivity expects totals. It calculates rates itself (it keeps `lastTx`? No, MainActivity logic needs checking).
                 // MainActivity DOES calculate rates from totals. Good.

                 val intent = Intent(ACTION_STATS_UPDATE).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_STATS_DURATION, duration)
                    putExtra(EXTRA_STATS_TX_BYTES, txBytes.toLong())
                    putExtra(EXTRA_STATS_RX_BYTES, rxBytes.toLong())
                    // Rate defaults to 0 if not calculated here.
                }
                sendBroadcast(intent)
            }

            override fun onStop(reason: String) {
                Log.d("ToyVPN", "Rust client stopped: $reason")
                // We should stop the service if not already stopping?
                // Avoid infinite loop if stopVpn called this.
                if (reason != "Stopped") {
                     // Error case
                     Log.e("ToyVPN", "Rust reported error: $reason")
                }
            }
        }

        try {
            Log.d("ToyVPN", "Starting Rust client...")
            // start is blocking? No, I spawned thread in Rust.
            // But start method in Rust returns Result.
            // U16 -> UShort
            vpnClient?.start(fd, serverIp, serverPort.toUShort(), clientIp, callback)
            Log.d("ToyVPN", "Rust client started")

            // Keep coroutine alive?
            // The Rust thread runs independently.
            // But if this coroutine scope (job) finishes, does it matter?
            // The Service job keeps the service context alive?
            // Actually, we parked the thread in runVpn previously.
            // Now we return immediately.
            // Is that okay?
            // If `runVpn` returns, `job` completes?
            // If `job` completes, what happens?
            // Nothing, unless we call `stopVpn` in `finally`.
            // In `startVpn`: `try { runVpn() } finally { stopVpn() }`
            // So if `runVpn` returns, `stopVpn` is called!
            // We CANNOT return from `runVpn`. We must suspend until stopped.

            awaitCancellation()

        } catch (e: Exception) {
             Log.e("ToyVPN", "Rust Start Error", e)
             throw e
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "ToyVPN Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
