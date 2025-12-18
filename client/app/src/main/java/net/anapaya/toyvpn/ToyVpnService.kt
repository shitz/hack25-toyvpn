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
import java.util.concurrent.atomic.AtomicLong

class ToyVpnService : VpnService() {

    private var interfacePfd: ParcelFileDescriptor? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Stats (Thread safe)
    private var totalSentBytes = AtomicLong(0)
    private var totalReceivedBytes = AtomicLong(0)
    private var startTime = 0L

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

        startTime = System.currentTimeMillis()
        totalSentBytes.set(0)
        totalReceivedBytes.set(0)

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

            val currentTx = totalSentBytes.get()
            val currentRx = totalReceivedBytes.get()

            val txRate = (currentTx - lastTx) // bytes per second
            val rxRate = (currentRx - lastRx) // bytes per second

            lastTx = currentTx
            lastRx = currentRx

            Log.d("ToyVPN", "Stats: Tx=$currentTx, Rx=$currentRx, Dur=$duration")

            val intent = Intent(ACTION_STATS_UPDATE).apply {
                setPackage(packageName)
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

            sendBroadcast(Intent(ACTION_STATS_UPDATE).apply {
                putExtra(EXTRA_STATS_DURATION, 0L)
            })

        } catch (e: Exception) {
            Log.e("ToyVPN", "Error stopping VPN", e)
        }
    }

    private suspend fun runVpn(serverIp: String, serverPort: Int, clientIp: String) {
        Log.d("ToyVPN", "Starting VPN to $serverIp:$serverPort as $clientIp")
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
             Log.e("ToyVPN", "Builder establish returned null")
             throw IllegalStateException("Could not establish VPN")
        }
        Log.d("ToyVPN", "VPN Interface established: ${interfacePfd?.fileDescriptor}")

        val tunnel = DatagramChannel.open()
        if (!protect(tunnel.socket())) {
            throw IllegalStateException("Cannot protect the tunnel")
        }

        Log.d("ToyVPN", "Connecting tunnel to $serverIp:$serverPort")
        tunnel.connect(InetSocketAddress(serverIp, serverPort))
        tunnel.configureBlocking(true)
        Log.d("ToyVPN", "Tunnel connected")

        val tunInputStream = FileInputStream(interfacePfd!!.fileDescriptor)
        val tunOutputStream = FileOutputStream(interfacePfd!!.fileDescriptor)

        coroutineScope {
            // 1. Upstream: TUN -> UDP
            val upstream = launch {
                Log.d("ToyVPN", "Upstream started")
                val buffer = ByteBuffer.allocate(4096)
                while (isActive) {
                    try {
                        // FIX: Explicitly check for -1. Handle 0 by continuing.
                        val read = tunInputStream.read(buffer.array())
                        if (read > 0) {
                             totalSentBytes.addAndGet(read.toLong())
                             if (totalSentBytes.get() < 50000) Log.v("ToyVPN", "Upstream: read $read bytes from TUN")

                            buffer.limit(read)
                            buffer.position(0)
                            tunnel.write(buffer)
                            buffer.clear()
                        } else if (read < 0) {
                            Log.d("ToyVPN", "Upstream: EOF from TUN (read returned $read)")
                            break
                        } else {
                            // read == 0
                           // Log.v("ToyVPN", "Upstream: read 0 bytes")
                        }
                    } catch (e: Exception) {
                        if (isActive) Log.e("ToyVPN", "Upstream error", e)
                        break
                    }
                }
            }

            // 2. Downstream: UDP -> TUN
            val downstream = launch {
                Log.d("ToyVPN", "Downstream started")
                val buffer = ByteBuffer.allocate(4096)
                while (isActive) {
                    try {
                        val read = tunnel.read(buffer)
                        if (read > 0) {
                            totalReceivedBytes.addAndGet(read.toLong())
                            if (totalReceivedBytes.get() < 50000) Log.v("ToyVPN", "Downstream: read $read bytes from UDP")
                            buffer.flip()
                            tunOutputStream.write(buffer.array(), 0, buffer.limit())
                            buffer.clear()
                        } else if (read < 0) {
                             Log.d("ToyVPN", "Downstream: EOF from UDP (read returned $read)")
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
