package np.com.ganeshbhatt.deskpager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
    private var activeTopic: String? = null
    @Volatile private var currentStateRes: Int = R.string.state_listening
    private var worker: StreamWorker? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ACK_BREAK) {
            acknowledgeBreak(this)
        }
        val topic = Store.topic(this)
        if (topic == null || !enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val forceReconnect = intent?.getBooleanExtra(EXTRA_RECONNECT, false) == true
        if (worker == null || activeTopic != topic || forceReconnect) {
            worker?.stop()
            activeTopic = topic
            if (wakeLock?.isHeld != true) {
                // Keeps the socket reader alive across Doze when no FCM backend is used.
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                    .apply { acquire() }
            }
            publishStreamState(StreamState.CONNECTING)
            worker = StreamWorker(topic).also { it.start() }
        }
        return START_STICKY
    }

    /**
     * Puts the service in the foreground with the status notification.
     *
     * @return false when Android refuses, which leaves nothing this service is allowed to keep running for.
     */
    private fun enterForeground(): Boolean {
        createChannels(this)
        val notification = listeningNotification(currentStateRes)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(LISTENING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(LISTENING_ID, notification)
            }
            true
        } catch (e: IllegalStateException) {
            // Android 12+ throws this when a background start (e.g. a notification dismissal) may not go foreground.
            Log.w(TAG, "foreground start refused: ${e.javaClass.simpleName}")
            worker != null
        }
    }

    override fun onDestroy() {
        stopStrictAlarm()
        worker?.stop()
        worker = null
        activeTopic = null
        wakeLock?.takeIf { it.isHeld }?.release()
        publishStreamState(StreamState.STOPPED)
        super.onDestroy()
    }

    /**
     * One reader of the JSON stream for [topic], with its own thread, connection and stop flag.
     *
     * Nothing here is shared with the worker that replaces it, so a replaced worker cannot close its
     * successor's connection, and after [stop] returns it cannot show a buzz or publish a state.
     */
    private inner class StreamWorker(private val topic: String) {
        @Volatile private var stopped = false
        @Volatile private var connection: HttpURLConnection? = null
        private val reader = thread(start = false, name = "desk-pager-listener") { listen() }

        /** Starts reading the stream on this worker's own thread. */
        fun start() {
            reader.start()
        }

        /** Ends this worker for good and closes its connection so a blocked read returns. */
        fun stop() {
            synchronized(this) { stopped = true }
            connection?.disconnect()
            reader.interrupt()
        }

        /** Runs [action] unless this worker was stopped; [stop] waits for an action already under way. */
        private inline fun ifLive(action: () -> Unit): Boolean = synchronized(this) {
            if (!stopped) action()
            !stopped
        }

        /** Reads the topic's JSON stream until this worker is stopped, reconnecting with backoff. */
        private fun listen() {
            var backoffMs = MIN_BACKOFF_MS
            var since = (System.currentTimeMillis() / 1000).toString()
            while (!stopped) {
                try {
                    val conn = URL("${Ntfy.SERVER}/$topic/json?since=$since").openConnection() as HttpURLConnection
                    connection = conn
                    // stop() may have looked for the connection just before it was stored above.
                    if (stopped) break
                    conn.useCaches = false
                    conn.connectTimeout = CONNECT_TIMEOUT_MS
                    conn.readTimeout = READ_TIMEOUT_MS
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                        throw IOException("HTTP ${conn.responseCode}")
                    }
                    backoffMs = MIN_BACKOFF_MS
                    conn.inputStream.bufferedReader().useLines { lines ->
                        var live = ifLive { showConnected() }
                        val iterator = lines.iterator()
                        while (live && iterator.hasNext()) {
                            val buzz = Ntfy.parseLine(iterator.next())
                            live = ifLive {
                                showConnected()
                                if (buzz != null) {
                                    since = buzz.id
                                    deliver(buzz)
                                }
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
                val live = ifLive {
                    setState(R.string.state_reconnecting)
                    publishStreamState(StreamState.RECONNECTING)
                }
                if (!live) break
                try {
                    Thread.sleep(backoffMs)
                } catch (e: InterruptedException) {
                    break
                }
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }

        private fun showConnected() {
            setState(R.string.state_listening)
            publishStreamState(StreamState.CONNECTED)
        }

        private fun deliver(buzz: Buzz) {
            if (!Ntfy.isFresh(buzz, System.currentTimeMillis() / 1000)) return
            if (buzz.title.equals(CLEAR_TITLE, ignoreCase = true)) {
                acknowledgeBreak(this@ListenerService)
            } else {
                Store.recordBuzz(this@ListenerService, buzz)
                postBuzzNotification(this@ListenerService, buzz)
                notifyUi()
            }
        }
    }

    private fun setState(textRes: Int) {
        if (currentStateRes == textRes) return
        currentStateRes = textRes
        getSystemService(NotificationManager::class.java).notify(LISTENING_ID, listeningNotification(textRes))
    }

    private fun listeningNotification(textRes: Int): Notification =
        Notification.Builder(this, CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_buzz)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(textRes))
            .setContentIntent(openAppIntent(this))
            .setShowWhen(false)
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "DeskPager"
        private const val WAKE_LOCK_TAG = "DeskPager:listener"
        private const val CHANNEL_LISTENING = "listening"
        private const val CHANNEL_STRETCH = "stretch"
        private const val CHANNEL_BUZZ = "buzz"
        private const val EXTRA_RECONNECT = "reconnect"
        private const val ACTION_ACK_BREAK = "np.com.ganeshbhatt.deskpager.ACTION_ACK_BREAK"
        private const val CLEAR_TITLE = "deskpager-clear"
        private const val LISTENING_ID = 1
        private const val BUZZ_ID = 2
        private const val CONNECT_TIMEOUT_MS = 15_000
        // ntfy sends a keepalive roughly every 45 s; two missed ones mean the link is dead.
        private const val READ_TIMEOUT_MS = 120_000
        private const val MIN_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val STRICT_INTERVAL_MS = 3_500L
        private const val STRICT_MAX_CYCLES = 40
        private val STRETCH_VIBRATION = longArrayOf(0, 260, 150, 260)
        private val BUZZ_VIBRATION = longArrayOf(0, 500, 180, 500, 180, 550)
        private val mainHandler = Handler(Looper.getMainLooper())
        private var strictRunnable: Runnable? = null
        private var activeRingtone: Ringtone? = null

        @Volatile
        var streamState: StreamState = StreamState.STOPPED
            private set

        @Volatile
        var strictAlarmActive: Boolean = false
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

        private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )

        private fun ackBreakPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, ListenerService::class.java).apply {
                action = ACTION_ACK_BREAK
            }
            return PendingIntent.getService(
                context,
                10,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Ensures notification channels exist for background status, quick stretches, and long breaks. */
        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_LISTENING, context.getString(R.string.channel_listening), NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setShowBadge(false)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_STRETCH, context.getString(R.string.channel_stretch), NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    enableVibration(true)
                    vibrationPattern = STRETCH_VIBRATION
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_BUZZ, context.getString(R.string.channel_buzz), NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    enableVibration(true)
                    vibrationPattern = BUZZ_VIBRATION
                },
            )
        }

        /** Posts a high-priority reminder notification and starts strict repeating alarm if it is a Long Break. */
        fun postBuzzNotification(context: Context, buzz: Buzz) {
            createChannels(context)
            val isLongBreak = buzz.title.contains("long break", ignoreCase = true)
            val isQuickStretch = buzz.title.contains("stretch", ignoreCase = true)
            val channel = if (isQuickStretch) CHANNEL_STRETCH else CHANNEL_BUZZ

            val ackAction = Notification.Action.Builder(
                null,
                context.getString(R.string.ack_break_action),
                ackBreakPendingIntent(context),
            ).build()

            val notification = Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_buzz)
                .setContentTitle(buzz.title.ifBlank { context.getString(R.string.default_buzz_title) })
                .setContentText(buzz.text)
                .setStyle(Notification.BigTextStyle().bigText(buzz.text))
                .setCategory(if (isLongBreak) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
                .setContentIntent(openAppIntent(context))
                .setDeleteIntent(ackBreakPendingIntent(context))
                .addAction(ackAction)
                .setOngoing(isLongBreak)
                .setAutoCancel(!isLongBreak)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(BUZZ_ID, notification)

            if (isLongBreak) {
                startStrictAlarm(context.applicationContext)
            }
        }

        /** Starts a repeating strong vibration + tone loop for Long Breaks until acknowledged or cleared by PC idle. */
        private fun startStrictAlarm(appContext: Context) {
            stopStrictAlarm()
            strictAlarmActive = true
            notifyUi()
            var cycles = 0
            val task = object : Runnable {
                override fun run() {
                    if (!strictAlarmActive || cycles >= STRICT_MAX_CYCLES) {
                        stopStrictAlarm()
                        return
                    }
                    cycles += 1
                    pulseStrictFeedback(appContext)
                    mainHandler.postDelayed(this, STRICT_INTERVAL_MS)
                }
            }
            strictRunnable = task
            mainHandler.post(task)
        }

        private fun pulseStrictFeedback(context: Context) {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Vibrator::class.java)
                }
                vibrator?.vibrate(VibrationEffect.createWaveform(BUZZ_VIBRATION, -1))
            } catch (_: Exception) {
            }
            try {
                if (activeRingtone?.isPlaying != true) {
                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    activeRingtone = RingtoneManager.getRingtone(context, uri)?.also { it.play() }
                }
            } catch (_: Exception) {
            }
        }

        /** Stops any active repeating Long Break alarm loop. */
        fun stopStrictAlarm() {
            strictAlarmActive = false
            strictRunnable?.let { mainHandler.removeCallbacks(it) }
            strictRunnable = null
            try {
                activeRingtone?.stop()
            } catch (_: Exception) {
            }
            activeRingtone = null
            notifyUi()
        }

        /** Stops the strict alarm and dismisses the break notification (called from phone tap or PC idle clear). */
        fun acknowledgeBreak(context: Context) {
            stopStrictAlarm()
            context.getSystemService(NotificationManager::class.java)?.cancel(BUZZ_ID)
            notifyUi()
        }

        /** Fires a local Quick Stretch test buzz on the phone to verify notification and vibration settings. */
        fun triggerLocalTest(context: Context) {
            val nowSecs = System.currentTimeMillis() / 1000
            val buzz = Buzz(
                id = "local-$nowSecs",
                timeSecs = nowSecs,
                title = context.getString(R.string.local_test_title),
                text = context.getString(R.string.local_test_body),
            )
            Store.recordBuzz(context, buzz)
            postBuzzNotification(context, buzz)
            notifyUi()
        }

        /** Fires a local Strict Long-Break test alarm so the user can test repeating vibration + stop controls. */
        fun triggerStrictTest(context: Context) {
            val nowSecs = System.currentTimeMillis() / 1000
            val buzz = Buzz(
                id = "strict-$nowSecs",
                timeSecs = nowSecs,
                title = context.getString(R.string.strict_test_title),
                text = context.getString(R.string.strict_test_body),
            )
            Store.recordBuzz(context, buzz)
            postBuzzNotification(context, buzz)
            notifyUi()
        }

        /** Starts the listener as a foreground service. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, ListenerService::class.java))
        }

        /** Forces the listener service to reconnect its stream immediately. */
        fun reconnect(context: Context) {
            val intent = Intent(context, ListenerService::class.java).putExtra(EXTRA_RECONNECT, true)
            context.startForegroundService(intent)
        }

        /** Stops the listener. */
        fun stop(context: Context) {
            context.stopService(Intent(context, ListenerService::class.java))
        }
    }
}
