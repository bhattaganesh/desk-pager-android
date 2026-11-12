package np.com.ganeshbhatt.deskpager

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date

/** The single dashboard screen: shows live connection readiness and pairs or unpairs this phone. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var channelInfo: TextView
    private lateinit var lastBuzz: TextView
    private lateinit var pairedActions: LinearLayout
    private lateinit var pairCard: LinearLayout
    private lateinit var error: TextView
    private lateinit var code: EditText
    private lateinit var cancelPair: Button
    private lateinit var notifBadge: TextView
    private lateinit var batteryBadge: TextView
    private lateinit var batteryHelp: TextView
    private lateinit var batteryBtn: Button

    /** True while a code filled in by a link is waiting for the user to confirm it. */
    private var linkPending = false

    /** True when the user tapped "Switch pairing code" while already paired. */
    private var editingCode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        channelInfo = findViewById(R.id.channel_info)
        lastBuzz = findViewById(R.id.last_buzz)
        pairedActions = findViewById(R.id.paired_actions)
        pairCard = findViewById(R.id.pair_card)
        error = findViewById(R.id.error)
        code = findViewById(R.id.code)
        cancelPair = findViewById(R.id.cancel_pair)
        notifBadge = findViewById(R.id.notif_badge)
        batteryBadge = findViewById(R.id.battery_badge)
        batteryHelp = findViewById(R.id.battery_help)
        batteryBtn = findViewById(R.id.battery)

        findViewById<Button>(R.id.pair).setOnClickListener { pair(code.text.toString()) }
        findViewById<Button>(R.id.paste).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.change_code).setOnClickListener {
            editingCode = true
            refresh()
        }
        cancelPair.setOnClickListener {
            editingCode = false
            linkPending = false
            code.text.clear()
            refresh()
        }
        findViewById<Button>(R.id.unpair).setOnClickListener {
            ListenerService.stop(this)
            Store.setTopic(this, null)
            linkPending = false
            editingCode = false
            code.text.clear()
            refresh()
        }
        batteryBtn.setOnClickListener { askForBackgroundUse() }

        // Not on recreation (rotation etc.): the launching link must not be replayed.
        if (savedInstanceState == null) {
            handleLink(intent)
        }
        if (Store.topic(this) != null && ListenerService.streamState == StreamState.STOPPED) {
            ListenerService.start(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    override fun onResume() {
        super.onResume()
        ListenerService.onUiUpdate = { refresh() }
        refresh()
    }

    override fun onPause() {
        ListenerService.onUiUpdate = null
        super.onPause()
    }

    /**
     * Fills the code field from a `deskpager://pair?topic=…` link; the user must still tap Pair.
     *
     * Any app or web page can fire this link, so it must never change the pairing by itself.
     */
    private fun handleLink(intent: Intent?) {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        val topic = Ntfy.parsePairing(link)
        if (topic == null) {
            showError(R.string.error_bad_code)
            return
        }
        code.setText(topic)
        linkPending = true
        editingCode = true
        refresh()
    }

    private fun pasteFromClipboard() {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        code.setText(text.trim())
        code.setSelection(code.text.length)
    }

    private fun pair(input: String) {
        val topic = Ntfy.parsePairing(input)
        if (topic == null) {
            showError(R.string.error_bad_code)
            return
        }
        Store.setTopic(this, topic)
        linkPending = false
        editingCode = false
        code.text.clear()
        // Restart so a re-pair switches to the new topic.
        ListenerService.stop(this)
        ListenerService.start(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        refresh()
    }

    private fun refresh() {
        val topic = Store.topic(this)
        val paired = topic != null

        if (!paired) {
            status.setText(R.string.status_unpaired)
            status.setBackgroundResource(R.drawable.bg_pill_warn)
            status.setTextColor(getColor(R.color.warn_fg))
            channelInfo.visibility = View.GONE
            lastBuzz.visibility = View.GONE
            pairedActions.visibility = View.GONE
            pairCard.visibility = View.VISIBLE
            cancelPair.visibility = View.GONE
        } else {
            when (ListenerService.streamState) {
                StreamState.CONNECTED -> {
                    status.setText(R.string.status_paired_connected)
                    status.setBackgroundResource(R.drawable.bg_pill_connected)
                    status.setTextColor(getColor(R.color.success_fg))
                }
                StreamState.RECONNECTING -> {
                    status.setText(R.string.status_paired_reconnecting)
                    status.setBackgroundResource(R.drawable.bg_pill_warn)
                    status.setTextColor(getColor(R.color.warn_fg))
                }
                else -> {
                    status.setText(R.string.status_paired_connecting)
                    status.setBackgroundResource(R.drawable.bg_pill_connected)
                    status.setTextColor(getColor(R.color.success_fg))
                }
            }
            channelInfo.text = getString(R.string.channel_label, Ntfy.maskTopic(topic ?: ""))
            channelInfo.visibility = View.VISIBLE

            val recent = Store.lastBuzz(this)
            if (recent != null) {
                val formattedTime = DateFormat.getTimeFormat(this).format(Date(recent.timeSecs * 1000L))
                lastBuzz.text = getString(R.string.last_buzz_format, formattedTime, recent.text)
            } else {
                lastBuzz.setText(R.string.last_buzz_none)
            }
            lastBuzz.visibility = View.VISIBLE

            val showForm = editingCode || linkPending
            pairedActions.visibility = if (showForm) View.GONE else View.VISIBLE
            pairCard.visibility = if (showForm) View.VISIBLE else View.GONE
            cancelPair.visibility = if (showForm) View.VISIBLE else View.GONE
        }

        val notificationsOn = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        notifBadge.setText(if (notificationsOn) R.string.perm_ok else R.string.perm_missing)
        notifBadge.setBackgroundResource(if (notificationsOn) R.drawable.bg_pill_connected else R.drawable.bg_pill_warn)
        notifBadge.setTextColor(getColor(if (notificationsOn) R.color.success_fg else R.color.warn_fg))

        val batteryUnrestricted =
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batteryBadge.setText(if (batteryUnrestricted) R.string.perm_ok else R.string.perm_missing)
        batteryBadge.setBackgroundResource(
            if (batteryUnrestricted) R.drawable.bg_pill_connected else R.drawable.bg_pill_warn,
        )
        batteryBadge.setTextColor(getColor(if (batteryUnrestricted) R.color.success_fg else R.color.warn_fg))
        batteryHelp.visibility = if (batteryUnrestricted) View.GONE else View.VISIBLE
        batteryBtn.visibility = if (batteryUnrestricted) View.GONE else View.VISIBLE

        if (linkPending) {
            showError(R.string.confirm_link)
        } else if (paired && !notificationsOn) {
            showError(R.string.error_notifications)
        } else {
            error.visibility = View.GONE
        }
    }

    private fun showError(textRes: Int) {
        error.setText(textRes)
        error.visibility = View.VISIBLE
    }

    private fun askForBackgroundUse() {
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"),
        )
        try {
            startActivity(request)
        } catch (e: ActivityNotFoundException) {
            // Some phones lack the direct dialog; the general list always exists.
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
    }
}
