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
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel

// Import UniFFI generated bindings
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
            .setContentText("Connecting to $serverIp")
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
                if (isActive) stopVpn()
            }
        }
    }

    private fun stopVpn() {
        try {
            Log.d("ToyVPN", "Stopping VPN...")
            vpnClient?.stop()

            interfacePfd?.close()
            interfacePfd = null

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

        // Detach TUN FD to pass ownership to Rust
        val tunFd = interfacePfd!!.detachFd()
        Log.d("ToyVPN", "VPN Interface TUN FD: $tunFd")
        interfacePfd = null

        // Create and PROTECT the UDP socket BEFORE connecting
        // Use DatagramChannel which allows us to get the FD via ParcelFileDescriptor
        Log.d("ToyVPN", "Creating and protecting UDP socket...")
        val channel = DatagramChannel.open()
        val socket = channel.socket()

        if (!protect(socket)) {
            Log.e("ToyVPN", "Failed to protect UDP socket!")
            throw IllegalStateException("Failed to protect UDP socket")
        }
        Log.d("ToyVPN", "UDP socket protected successfully")

        // Connect the channel to the server
        channel.connect(InetSocketAddress(serverIp, serverPort))
        Log.d("ToyVPN", "UDP socket connected to $serverIp:$serverPort")

        // Get the socket's FD using ParcelFileDescriptor
        val udpPfd = ParcelFileDescriptor.fromDatagramSocket(socket)
        val udpFd = udpPfd.detachFd()  // Detach to pass ownership to Rust
        Log.d("ToyVPN", "UDP socket FD: $udpFd")

        val startTime = System.currentTimeMillis()
        var lastTxBytes = 0L
        var lastRxBytes = 0L
        var lastUpdateTime = startTime

        // Create Callback
        val callback = object : VpnCallback {
            override fun onStatsUpdate(txBytes: ULong, rxBytes: ULong) {
                val now = System.currentTimeMillis()
                val duration = (now - startTime) / 1000
                val elapsed = (now - lastUpdateTime) / 1000.0

                val tx = txBytes.toLong()
                val rx = rxBytes.toLong()

                // Calculate rates (bytes per second)
                val txRate = if (elapsed > 0) ((tx - lastTxBytes) / elapsed).toLong() else 0L
                val rxRate = if (elapsed > 0) ((rx - lastRxBytes) / elapsed).toLong() else 0L

                lastTxBytes = tx
                lastRxBytes = rx
                lastUpdateTime = now

                val intent = Intent(ACTION_STATS_UPDATE).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_STATS_DURATION, duration)
                    putExtra(EXTRA_STATS_TX_BYTES, tx)
                    putExtra(EXTRA_STATS_RX_BYTES, rx)
                    putExtra(EXTRA_STATS_TX_RATE, txRate)
                    putExtra(EXTRA_STATS_RX_RATE, rxRate)
                }
                sendBroadcast(intent)
            }

            override fun onStop(reason: String) {
                Log.d("ToyVPN", "Rust client stopped: $reason")
                if (reason != "Stopped") {
                     Log.e("ToyVPN", "Rust reported error: $reason")
                }
            }
        }

        try {
            Log.d("ToyVPN", "Starting Rust client with tunFd=$tunFd, udpFd=$udpFd")
            vpnClient?.start(tunFd, udpFd, callback)
            Log.d("ToyVPN", "Rust client started")

            // Keep coroutine alive until cancelled
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
