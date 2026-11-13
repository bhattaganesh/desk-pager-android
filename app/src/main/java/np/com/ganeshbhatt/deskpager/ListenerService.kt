package np.com.ganeshbhatt.deskpager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
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
        stopAlert(this)
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
        private const val CHANNEL_ALERTS = "alerts"
        private val RETIRED_CHANNELS = listOf("stretch", "buzz")
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
        // An unattended phone must not ring or vibrate forever.
        private const val ALERT_CAP_MS = 10 * 60 * 1000L
        private const val RING_CHECK_MS = 1_000L
        private const val NO_REPEAT = -1
        private const val REPEAT_FROM_START = 0
        private val QUICK_VIBRATION = longArrayOf(0, 400, 200, 400, 200, 400)
        private val CONTINUOUS_VIBRATION = longArrayOf(0, 700, 400)
        private val mainHandler = Handler(Looper.getMainLooper())
        private val alertLock = Any()
        private var alertVibrator: Vibrator? = null
        private var alertRingtone: Ringtone? = null
        private var alertCap: Runnable? = null
        private var ringCheck: Runnable? = null

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

        /**
         * Ensures the status channel and the one alert channel exist. The alert channel is silent because
         * the app vibrates and rings by itself, in the style the user chose; the older channels are removed.
         */
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
                    CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                },
            )
            RETIRED_CHANNELS.forEach { manager.deleteNotificationChannel(it) }
        }

        /**
         * Shows the reminder and alerts in the style set for its kind, replacing any alert still going.
         *
         * Only an alert that really keeps going gets the Stop action and stops when swiped away.
         */
        fun postBuzzNotification(context: Context, buzz: Buzz) {
            createChannels(context)
            val appContext = context.applicationContext
            val style = Store.alertStyle(context, Ntfy.kindOf(buzz.title))
            synchronized(alertLock) {
                silence()
                val keepsGoing = style.untilStopped && startContinuousAlert(appContext, style)
                if (!style.untilStopped) {
                    vibrate(appContext, VibrationEffect.createWaveform(QUICK_VIBRATION, NO_REPEAT), alarm = false)
                }
                context.getSystemService(NotificationManager::class.java)
                    .notify(BUZZ_ID, buzzNotification(context, buzz, keepsGoing))
            }
            notifyUi()
        }

        private fun buzzNotification(context: Context, buzz: Buzz, keepsGoing: Boolean): Notification {
            val builder = Notification.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_buzz)
                .setContentTitle(buzz.title.ifBlank { context.getString(R.string.default_buzz_title) })
                .setContentText(buzz.text)
                .setStyle(Notification.BigTextStyle().bigText(buzz.text))
                .setCategory(if (keepsGoing) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
                .setContentIntent(openAppIntent(context))
                .setAutoCancel(!keepsGoing)
            if (keepsGoing) {
                val stop = ackBreakPendingIntent(context)
                builder.setDeleteIntent(stop).addAction(
                    Notification.Action.Builder(null, context.getString(R.string.ack_break_action), stop).build(),
                )
            }
            return builder.build()
        }

        /**
         * Starts the looping vibration, plus the looping alarm sound when [style] rings, and arms the cap.
         *
         * @return false, leaving nothing active, when neither vibration nor sound could start.
         */
        private fun startContinuousAlert(appContext: Context, style: AlertStyle): Boolean {
            alertVibrator = vibrate(
                appContext, VibrationEffect.createWaveform(CONTINUOUS_VIBRATION, REPEAT_FROM_START), alarm = true,
            )
            alertRingtone = if (style.rings) startRinging(appContext) else null
            if (alertVibrator == null && alertRingtone == null) {
                return false
            }
            val cap = object : Runnable {
                override fun run() {
                    // A cap already replaced by a newer alert must not stop that alert.
                    val current = synchronized(alertLock) { alertCap === this }
                    if (current) stopAlert(appContext)
                }
            }
            alertCap = cap
            mainHandler.postDelayed(cap, ALERT_CAP_MS)
            strictAlarmActive = true
            return true
        }

        /**
         * Vibrates with usage attributes, without which some phones mute vibration from the background.
         *
         * @return the vibrator now playing [effect], or null when the phone cannot or may not vibrate.
         */
        private fun vibrate(context: Context, effect: VibrationEffect, alarm: Boolean): Vibrator? {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            if (vibrator == null || !vibrator.hasVibrator()) {
                Log.w(TAG, "no vibrator available")
                return null
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val usage = if (alarm) VibrationAttributes.USAGE_ALARM else VibrationAttributes.USAGE_NOTIFICATION
                    vibrator.vibrate(effect, VibrationAttributes.createForUsage(usage))
                } else {
                    val usage = if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(usage).build())
                }
                vibrator
            } catch (e: SecurityException) {
                Log.w(TAG, "vibration refused: ${e.javaClass.simpleName}")
                null
            }
        }

        /**
         * Starts the default alarm sound (the notification sound when no alarm sound is set), looping.
         *
         * @return the playing ringtone, or null when there is no sound to play or it may not be played.
         */
        private fun startRinging(context: Context): Ringtone? {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = try {
                RingtoneManager.getRingtone(context, uri)
            } catch (e: SecurityException) {
                Log.w(TAG, "alarm sound refused: ${e.javaClass.simpleName}")
                null
            }
            if (ringtone == null) {
                Log.w(TAG, "no alarm sound available")
                return null
            }
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.isLooping = true
            } else {
                keepRinging(ringtone)
            }
            ringtone.play()
            return ringtone
        }

        /** Restarts [ringtone] whenever it ends, for Android versions that cannot loop a ringtone. */
        private fun keepRinging(ringtone: Ringtone) {
            val check = object : Runnable {
                override fun run() {
                    synchronized(alertLock) {
                        if (ringCheck !== this) return
                        if (!ringtone.isPlaying) ringtone.play()
                        mainHandler.postDelayed(this, RING_CHECK_MS)
                    }
                }
            }
            ringCheck = check
            mainHandler.postDelayed(check, RING_CHECK_MS)
        }

        /** Stops vibration and sound and clears the active flag; callers hold [alertLock]. */
        private fun silence() {
            alertCap?.let { mainHandler.removeCallbacks(it) }
            alertCap = null
            ringCheck?.let { mainHandler.removeCallbacks(it) }
            ringCheck = null
            alertVibrator?.cancel()
            alertVibrator = null
            alertRingtone?.stop()
            alertRingtone = null
            strictAlarmActive = false
        }

        /** Stops any alert that is still going and removes the reminder notification. */
        fun stopAlert(context: Context) {
            synchronized(alertLock) {
                silence()
                context.getSystemService(NotificationManager::class.java)?.cancel(BUZZ_ID)
            }
            notifyUi()
        }

        /** Stops the alert on the user's say-so: the Stop action, a swipe, the in-app button, or the PC's clear. */
        fun acknowledgeBreak(context: Context) = stopAlert(context)

        /** Fires a local quick-stretch buzz so the user can try the style chosen for quick stretches. */
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
        }

        /** Fires a local long-break buzz so the user can try the style chosen for long breaks. */
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
