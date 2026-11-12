package np.com.ganeshbhatt.deskpager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Live state of the background ntfy stream. */
enum class StreamState {
    STOPPED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}

/** Foreground service that holds a streaming connection to the ntfy topic and shows each buzz. */
class ListenerService : Service() {
    @Volatile private var running = false
    @Volatile private var activeTopic: String? = null
    @Volatile private var currentStateRes: Int = R.string.state_listening
    @Volatile private var connection: HttpURLConnection? = null
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val topic = Store.topic(this)
        if (topic == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannels()
        val notification = listeningNotification(currentStateRes)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(LISTENING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(LISTENING_ID, notification)
        }
        if (worker == null || activeTopic != topic) {
            running = false
            connection?.disconnect()
            worker?.interrupt()
            running = true
            activeTopic = topic
            if (wakeLock?.isHeld != true) {
                // Keeps the socket reader alive across Doze when no FCM backend is used.
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                    .apply { acquire() }
            }
            publishStreamState(StreamState.CONNECTING)
            worker = thread(name = "desk-pager-listener") { listen(topic) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        activeTopic = null
        connection?.disconnect()
        worker?.interrupt()
        worker = null
        wakeLock?.takeIf { it.isHeld }?.release()
        publishStreamState(StreamState.STOPPED)
        super.onDestroy()
    }

    /** Reads the topic's JSON stream until the service stops, reconnecting with backoff. */
    private fun listen(topic: String) {
        var backoffMs = MIN_BACKOFF_MS
        var since = (System.currentTimeMillis() / 1000).toString()
        while (running && activeTopic == topic) {
            try {
                val conn = (URL("${Ntfy.SERVER}/$topic/json?since=$since").openConnection() as HttpURLConnection)
                connection = conn
                conn.useCaches = false
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP ${conn.responseCode}")
                }
                setState(R.string.state_listening)
                publishStreamState(StreamState.CONNECTED)
                backoffMs = MIN_BACKOFF_MS
                conn.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!running || activeTopic != topic) break
                        setState(R.string.state_listening)
                        publishStreamState(StreamState.CONNECTED)
                        val buzz = Ntfy.parseLine(line) ?: continue
                        since = buzz.id
                        if (Ntfy.isFresh(buzz, System.currentTimeMillis() / 1000)) {
                            Store.recordBuzz(this, buzz)
                            showBuzz(buzz)
                            notifyUi()
                        }
                    }
                }
            } catch (e: IOException) {
                // Class name only: the exception message can contain the URL, which holds the topic.
                Log.w(TAG, "stream dropped: ${e.javaClass.simpleName}")
            } finally {
                connection?.disconnect()
                connection = null
            }
            if (!running || activeTopic != topic) break
            setState(R.string.state_reconnecting)
            publishStreamState(StreamState.RECONNECTING)
            try {
                Thread.sleep(backoffMs)
            } catch (e: InterruptedException) {
                break
            }
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun setState(textRes: Int) {
        if (currentStateRes == textRes) return
        currentStateRes = textRes
        getSystemService(NotificationManager::class.java).notify(LISTENING_ID, listeningNotification(textRes))
    }

    private fun showBuzz(buzz: Buzz) {
        val notification = Notification.Builder(this, CHANNEL_BUZZ)
            .setSmallIcon(R.drawable.ic_buzz)
            .setContentTitle(buzz.title.ifBlank { getString(R.string.default_buzz_title) })
            .setContentText(buzz.text)
            .setStyle(Notification.BigTextStyle().bigText(buzz.text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        // One fixed id: a repeat buzz replaces the previous one and alerts again.
        getSystemService(NotificationManager::class.java).notify(BUZZ_ID, notification)
    }

    private fun listeningNotification(textRes: Int): Notification =
        Notification.Builder(this, CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_buzz)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(textRes))
            .setContentIntent(openAppIntent())
            .setShowWhen(false)
            .setOngoing(true)
            .build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LISTENING, getString(R.string.channel_listening), NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BUZZ, getString(R.string.channel_buzz), NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                enableVibration(true)
                vibrationPattern = BUZZ_VIBRATION
            },
        )
    }

    companion object {
        private const val TAG = "DeskPager"
        private const val WAKE_LOCK_TAG = "DeskPager:listener"
        private const val CHANNEL_LISTENING = "listening"
        private const val CHANNEL_BUZZ = "buzz"
        private const val LISTENING_ID = 1
        private const val BUZZ_ID = 2
        private const val CONNECT_TIMEOUT_MS = 15_000
        // ntfy sends a keepalive roughly every 45 s; two missed ones mean the link is dead.
        private const val READ_TIMEOUT_MS = 120_000
        private const val MIN_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private val BUZZ_VIBRATION = longArrayOf(0, 400, 200, 400, 200, 400)
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        var streamState: StreamState = StreamState.STOPPED
            private set

        @Volatile
        var onUiUpdate: (() -> Unit)? = null

        private fun publishStreamState(newState: StreamState) {
            if (streamState == newState) return
            streamState = newState
            notifyUi()
        }

        private fun notifyUi() {
            mainHandler.post { onUiUpdate?.invoke() }
        }

        /** Starts the listener as a foreground service. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, ListenerService::class.java))
        }

        /** Stops the listener. */
        fun stop(context: Context) {
            context.stopService(Intent(context, ListenerService::class.java))
        }
    }
}
