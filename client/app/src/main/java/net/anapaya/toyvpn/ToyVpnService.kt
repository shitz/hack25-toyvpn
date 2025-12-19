package net.anapaya.toyvpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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

        const val EXTRA_SNAP_TOKEN = "snap_token"
        const val EXTRA_ENDHOST_API = "endhost_api"
        const val EXTRA_EDGETUN_HOST = "edgetun_host"

        const val EXTRA_STATS_DURATION = "stats_duration"
        const val EXTRA_STATS_TX_BYTES = "stats_tx_bytes"
        const val EXTRA_STATS_RX_BYTES = "stats_rx_bytes"
        const val EXTRA_STATS_TX_RATE = "stats_tx_rate"
        const val EXTRA_STATS_RX_RATE = "stats_rx_rate"
        const val EXTRA_ASSIGNED_IP = "assigned_ip"
        const val EXTRA_ROUTES = "routes"

        const val ACTION_VPN_ESTABLISHED = "net.anapaya.toyvpn.VPN_ESTABLISHED"
        const val ACTION_VPN_FAILED = "net.anapaya.toyvpn.VPN_FAILED"
        const val ACTION_PROBE = "net.anapaya.toyvpn.PROBE"
        const val EXTRA_ERROR_MESSAGE = "error_message"

        private const val CHANNEL_ID = "ToyVpnChannel"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            stopVpn()
            return START_NOT_STICKY
        } else if (intent?.action == ACTION_CONNECT) {
            val serverIp = intent.getStringExtra(EXTRA_SERVER_ADDRESS) ?: "163.172.171.48"
            val serverPort = intent.getIntExtra(EXTRA_SERVER_PORT, 12345)

            val snapToken = intent.getStringExtra(EXTRA_SNAP_TOKEN) ?: ""
            val endhostApi = intent.getStringExtra(EXTRA_ENDHOST_API) ?: "http://s01.choeg2.snap.anapaya.net:5001"
            val edgetunHost = intent.getStringExtra(EXTRA_EDGETUN_HOST) ?: "[64-2:0:a7,10.0.0.2]:9000"

            startVpn(snapToken, endhostApi, edgetunHost)
            return START_STICKY
        } else if (intent?.action == ACTION_PROBE) {
            if (job != null && job!!.isActive) {
                // If running, we will naturally send stats soon, but let's force one or send established
                sendBroadcast(Intent(ACTION_VPN_ESTABLISHED).apply {
                    setPackage(packageName)
                })
            } else {
                // Not running
                sendBroadcast(Intent(ACTION_STATS_UPDATE).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_STATS_DURATION, 0L)
                })
            }
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun startVpn(snapToken: String, endhostApi: String, edgetunHost: String) {
        if (job != null) return

        createNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ToyVPN")
            .setContentText("Connecting to $edgetunHost")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
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
                runVpn(snapToken, endhostApi, edgetunHost)
            } catch (e: CancellationException) {
                Log.d("ToyVPN", "VPN cancelled")
            } catch (e: Exception) {
                Log.e("ToyVPN", "VPN Error", e)
                sendBroadcast(Intent(ACTION_VPN_FAILED).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_ERROR_MESSAGE, e.message ?: "Unknown error")
                })
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

    private suspend fun runVpn(snapToken: String, endhostApi: String, edgetunHost: String) {
        Log.d("ToyVPN", "Performing handshake...")
        val config = try {
            vpnClient?.handshake(snapToken, endhostApi, edgetunHost)
        } catch (e: Exception) {
            Log.e("ToyVPN", "Handshake failed", e)
            throw e
        }

        if (config == null) {
             throw IllegalStateException("Handshake returned null config")
        }
        Log.d("ToyVPN", "Handshake successful. IP: ${config.clientIp}")

        // 3. Setup TUN interface
        Log.d("ToyVPN", "Setting up VPN interface")
        val builder = Builder()
        builder.setSession("ToyVPN")
        builder.addAddress(config.clientIp, 24)
        builder.addDisallowedApplication(packageName)

        for (route in config.routes) {
             builder.addRoute(route.destination, route.prefixLength)
        }
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
                    putExtra(EXTRA_ASSIGNED_IP, config.clientIp)
                    val routesStr = config.routes.joinToString("\n") { "${it.destination}/${it.prefixLength}" }
                    putExtra(EXTRA_ROUTES, routesStr)
                }
                sendBroadcast(intent)

                updateNotification(tx, rx, txRate, rxRate)
            }

            override fun onStop(reason: String) {
                Log.d("ToyVPN", "Rust client stopped: $reason")
                if (reason != "Stopped") {
                     Log.e("ToyVPN", "Rust reported error: $reason")
                }
            }
        }

        try {
            Log.d("ToyVPN", "Starting Rust client with tunFd=$tunFd")
            vpnClient?.start(tunFd, callback)
            Log.d("ToyVPN", "Rust client started")

            sendBroadcast(Intent(ACTION_VPN_ESTABLISHED).apply {
                setPackage(packageName)
            })

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

    private fun updateNotification(txBytes: Long, rxBytes: Long, txRate: Long, rxRate: Long) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Connected")
            .setContentText("↑ ${formatBytes(txRate)}/s   ↓ ${formatBytes(rxRate)}/s")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("↑ ${formatBytes(txRate)}/s (Total: ${formatBytes(txBytes)})\n" +
                        "↓ ${formatBytes(rxRate)}/s (Total: ${formatBytes(rxBytes)})"))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
        notificationManager.notify(1, notification)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
