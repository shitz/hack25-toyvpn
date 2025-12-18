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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

class ToyVpnService : VpnService() {

    private var interfacePfd: ParcelFileDescriptor? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Stats
    private var totalSentBytes = 0L
    private var totalReceivedBytes = 0L
    private var startTime = 0L

    companion object {
        const val ACTION_CONNECT = "net.anapaya.toyvpn.CONNECT"
        const val ACTION_DISCONNECT = "net.anapaya.toyvpn.DISCONNECT"
        const val ACTION_STATS_UPDATE = "net.anapaya.toyvpn.STATS_UPDATE"

        const val EXTRA_SERVER_ADDRESS = "server_address"
        const val EXTRA_SERVER_PORT = "server_port"
        const val EXTRA_CLIENT_IP = "client_ip" // Configurable client IP

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
            val serverIp = intent.getStringExtra(EXTRA_SERVER_ADDRESS) ?: "10.0.0.1"
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

        startTime = System.currentTimeMillis()
        totalSentBytes = 0
        totalReceivedBytes = 0

        job = scope.launch {
            try {
                // Launch Stats Reporter
                launch {
                    reportStatsLoop()
                }

                runVpn(serverIp, serverPort, clientIp)
            } catch (e: Exception) {
                Log.e("ToyVPN", "VPN Error", e)
            } finally {
                stopVpn()
            }
        }
    }

    private suspend fun reportStatsLoop() {
        var lastTx = 0L
        var lastRx = 0L
        while (currentCoroutineContext().isActive) {
            delay(1000)
            val now = System.currentTimeMillis()
            val duration = (now - startTime) / 1000

            val currentTx = totalSentBytes
            val currentRx = totalReceivedBytes

            val txRate = (currentTx - lastTx) // bytes per second
            val rxRate = (currentRx - lastRx) // bytes per second

            lastTx = currentTx
            lastRx = currentRx

            val intent = Intent(ACTION_STATS_UPDATE).apply {
                putExtra(EXTRA_STATS_DURATION, duration)
                putExtra(EXTRA_STATS_TX_BYTES, currentTx)
                putExtra(EXTRA_STATS_RX_BYTES, currentRx)
                putExtra(EXTRA_STATS_TX_RATE, txRate)
                putExtra(EXTRA_STATS_RX_RATE, rxRate)
            }
            sendBroadcast(intent)
        }
    }

    private fun stopVpn() {
        try {
            interfacePfd?.close()
            interfacePfd = null
            job?.cancel()
            job = null
            stopForeground(true)
            stopSelf()

            // Send final Disconnect update
            sendBroadcast(Intent(ACTION_STATS_UPDATE).apply {
                putExtra(EXTRA_STATS_DURATION, 0L) // Signal reset
            })

        } catch (e: Exception) {
            Log.e("ToyVPN", "Error stopping VPN", e)
        }
    }

    private suspend fun runVpn(serverIp: String, serverPort: Int, clientIp: String) {
        val builder = Builder()
        builder.setSession("ToyVPN")
        builder.addAddress(clientIp, 24)
        builder.addRoute("0.0.0.0", 0)
        builder.setMtu(1500)

        interfacePfd = builder.establish() ?: throw IllegalStateException("Could not establish VPN")

        val tunnel = DatagramChannel.open()
        if (!protect(tunnel.socket())) {
            throw IllegalStateException("Cannot protect the tunnel")
        }
        tunnel.connect(InetSocketAddress(serverIp, serverPort))
        tunnel.configureBlocking(true)

        val tunInputStream = FileInputStream(interfacePfd!!.fileDescriptor)
        val tunOutputStream = FileOutputStream(interfacePfd!!.fileDescriptor)

        coroutineScope {
            // 1. Upstream: TUN -> UDP
            val upstream = launch {
                val buffer = ByteBuffer.allocate(4096)
                while (isActive) {
                    try {
                        val read = tunInputStream.read(buffer.array())
                        if (read > 0) {
                             totalSentBytes += read
                            buffer.limit(read)
                            buffer.position(0)
                            tunnel.write(buffer)
                            buffer.clear()
                        } else {
                            break
                        }
                    } catch (e: Exception) {
                        if (isActive) Log.e("ToyVPN", "Upstream error", e)
                        break
                    }
                }
            }

            // 2. Downstream: UDP -> TUN
            val downstream = launch {
                val buffer = ByteBuffer.allocate(4096)
                while (isActive) {
                    try {
                        val read = tunnel.read(buffer)
                        if (read > 0) {
                            totalReceivedBytes += read
                            buffer.flip()
                            tunOutputStream.write(buffer.array(), 0, buffer.limit())
                            buffer.clear()
                        } else {
                            break
                        }
                    } catch (e: Exception) {
                         if (isActive) Log.e("ToyVPN", "Downstream error", e)
                         break
                    }
                }
            }
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
