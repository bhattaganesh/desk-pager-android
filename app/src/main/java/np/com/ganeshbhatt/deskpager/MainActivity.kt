package np.com.ganeshbhatt.deskpager

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date

/** The single dashboard screen: shows live connection readiness, reminder history, and pairing controls. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var pcList: LinearLayout
    private lateinit var lastBuzz: TextView
    private lateinit var latestSummary: TextView
    private lateinit var historyToggleLabel: TextView
    private lateinit var statStretches: TextView
    private lateinit var statLongBreaks: TextView
    private lateinit var stopAlarmBtn: Button
    private lateinit var pairedActions: LinearLayout
    private lateinit var pairCard: LinearLayout
    private lateinit var error: TextView
    private lateinit var code: EditText
    private lateinit var cancelPair: Button
    private lateinit var notifBadge: TextView
    private lateinit var batteryBadge: TextView
    private lateinit var batteryHelp: TextView
    private lateinit var batteryBtn: Button

    /** The topic a link put in the code field, until the user pairs, cancels, unpairs or edits it away. */
    private var linkedTopic: String? = null

    /** The PC name that came with [linkedTopic]; never applied to any other code. */
    private var pendingPcName: String? = null

    /** An error that stays on screen until the user's next action. */
    private var notice: String? = null

    /** True while Android puts the saved text back into the code field, which is not a user edit. */
    private var restoringViews = false
    private var shownPcs: List<PairedPc>? = null
    private var removeDialog: AlertDialog? = null

    /** True when the user tapped "+ Add PC" while already paired. */
    private var editingCode = false

    /** Whether the 5-item recent history accordion is expanded. */
    private var historyExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        pcList = findViewById(R.id.pc_list)
        lastBuzz = findViewById(R.id.last_buzz)
        latestSummary = findViewById(R.id.latest_summary)
        historyToggleLabel = findViewById(R.id.history_toggle_label)
        statStretches = findViewById(R.id.stat_stretches)
        statLongBreaks = findViewById(R.id.stat_long_breaks)
        stopAlarmBtn = findViewById(R.id.stop_alarm_btn)
        pairedActions = findViewById(R.id.paired_actions)
        pairCard = findViewById(R.id.pair_card)
        error = findViewById(R.id.error)
        code = findViewById(R.id.code)
        cancelPair = findViewById(R.id.cancel_pair)
        notifBadge = findViewById(R.id.notif_badge)
        batteryBadge = findViewById(R.id.battery_badge)
        batteryHelp = findViewById(R.id.battery_help)
        batteryBtn = findViewById(R.id.battery)

        // Restored first so the link warning comes back together with the code Android refills below.
        linkedTopic = savedInstanceState?.getString(STATE_LINKED_TOPIC)?.takeIf { Ntfy.isTopic(it) }
        pendingPcName = savedInstanceState?.getString(STATE_PENDING_NAME)
        notice = savedInstanceState?.getString(STATE_NOTICE)
        code.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) = onCodeEdited(s?.toString().orEmpty())
        })

        findViewById<View>(R.id.history_header).setOnClickListener {
            if (Store.recentBuzzes(this).isNotEmpty()) {
                historyExpanded = !historyExpanded
                refresh()
            }
        }

        findViewById<Button>(R.id.pair).setOnClickListener { pair(code.text.toString()) }
        findViewById<Button>(R.id.paste).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.reconnect).setOnClickListener {
            if (Store.topic(this) != null) {
                ListenerService.reconnect(this)
                refresh()
            }
        }
        findViewById<Button>(R.id.change_code).setOnClickListener {
            editingCode = true
            notice = null
            refresh()
        }
        cancelPair.setOnClickListener {
            closePairForm()
            refresh()
        }
        findViewById<Button>(R.id.unpair).setOnClickListener {
            ListenerService.stop(this)
            Store.setTopic(this, null)
            closePairForm()
            refresh()
        }
        stopAlarmBtn.setOnClickListener {
            ListenerService.acknowledgeBreak(this)
            refresh()
        }
        findViewById<Button>(R.id.test_local).setOnClickListener {
            ensureNotificationPermission()
            ListenerService.triggerLocalTest(this)
            refresh()
        }
        findViewById<Button>(R.id.test_strict).setOnClickListener {
            ensureNotificationPermission()
            ListenerService.triggerStrictTest(this)
            refresh()
        }
        findViewById<View>(R.id.notif_row).setOnClickListener { openNotificationSettings() }
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_LINKED_TOPIC, linkedTopic)
        outState.putString(STATE_PENDING_NAME, pendingPcName)
        outState.putString(STATE_NOTICE, notice)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        restoringViews = true
        try {
            super.onRestoreInstanceState(savedInstanceState)
        } finally {
            restoringViews = false
        }
    }

    override fun onDestroy() {
        removeDialog?.dismiss()
        super.onDestroy()
    }

    /** Ends the link-pending state once the user changes the code away from what the link filled in. */
    private fun onCodeEdited(text: String) {
        if (restoringViews) return
        val linkStillShown = linkedTopic == null || text.trim() == linkedTopic
        if (linkStillShown && notice == null) return
        if (!linkStillShown) {
            linkedTopic = null
            pendingPcName = null
        }
        notice = null
        refresh()
    }

    /** Leaves the pairing form and forgets everything a link or a failed attempt left in it. */
    private fun closePairForm() {
        editingCode = false
        linkedTopic = null
        pendingPcName = null
        notice = null
        code.text.clear()
    }

    /**
     * Fills the code field from a `deskpager://pair?topic=…` link; the user must still tap Pair.
     *
     * Any app or web page can fire this link, so it must never change the pairing by itself.
     */
    private fun handleLink(intent: Intent?) {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        val parsed = Ntfy.parsePairingWithName(link)
        if (parsed == null) {
            notice = getString(R.string.error_bad_code)
            refresh()
            return
        }
        // Set before the text so the edit watcher sees the field matching the link.
        linkedTopic = parsed.first
        pendingPcName = parsed.second
        notice = null
        editingCode = true
        code.setText(parsed.first)
        refresh()
    }

    private fun pasteFromClipboard() {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        code.setText(text.trim())
        code.setSelection(code.text.length)
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun pair(input: String) {
        val parsed = Ntfy.parsePairingWithName(input)
        if (parsed == null) {
            notice = getString(R.string.error_bad_code)
            refresh()
            return
        }
        val label = parsed.second ?: pendingPcName.takeIf { parsed.first == linkedTopic }
        if (!Store.addPc(this, parsed.first, label)) {
            notice = getString(R.string.error_max_pcs, PcList.MAX_PCS)
            refresh()
            return
        }
        closePairForm()
        // Restart so the listener subscribes to all paired PCs simultaneously.
        ListenerService.stop(this)
        ListenerService.start(this)
        ensureNotificationPermission()
        refresh()
    }

    private fun confirmRemove(pc: PairedPc) {
        removeDialog?.dismiss()
        removeDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.remove_pc_title, pc.label))
            .setMessage(getString(R.string.remove_pc_message, pc.label, Ntfy.maskTopic(pc.topic)))
            .setPositiveButton(R.string.remove_pc) { _, _ -> removePc(pc) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removePc(pc: PairedPc) {
        Store.removePc(this, pc.topic)
        notice = null
        if (Store.topic(this) == null) {
            ListenerService.stop(this)
        } else {
            // The service swaps its stream when the paired topics differ from the ones it is reading.
            ListenerService.start(this)
        }
        refresh()
    }

    private fun showPcs(pcs: List<PairedPc>) {
        if (pcs == shownPcs) return
        shownPcs = pcs
        pcList.removeAllViews()
        for (pc in pcs) {
            val row = layoutInflater.inflate(R.layout.row_pc, pcList, false)
            row.findViewById<TextView>(R.id.pc_label).text =
                getString(R.string.pc_row, pc.label, Ntfy.maskTopic(pc.topic))
            row.findViewById<Button>(R.id.pc_remove).apply {
                contentDescription = getString(R.string.remove_pc_description, pc.label)
                setOnClickListener { confirmRemove(pc) }
            }
            pcList.addView(row)
        }
    }

    /**
     * Describes what tapping Pair will do with the code a link filled in: the PC it adds, under which
     * name, next to how many others. Null when no link is waiting.
     */
    private fun linkWarning(pcs: List<PairedPc>): String? {
        val topic = linkedTopic ?: return null
        val intro = getString(R.string.confirm_link_intro, getString(R.string.pair))
        val masked = Ntfy.maskTopic(topic)
        val wanted = pendingPcName
        // The same rule Store.addPc applies, so the name shown is the name that gets saved.
        val label = PcList.add(pcs, topic, wanted)?.first { it.topic == topic }?.label
        val detail = when {
            label == null -> getString(R.string.confirm_link_full, pcs.size)
            pcs.any { it.topic == topic } -> getString(R.string.confirm_link_already_paired, masked, label)
            else -> getString(R.string.confirm_link_add, label, masked, pcs.size)
        }
        val clash = if (label != null && wanted != null && label != wanted) {
            getString(R.string.confirm_link_label_clash, wanted, label)
        } else {
            null
        }
        return listOfNotNull(intro, detail, clash).joinToString("\n\n")
    }

    private fun refresh() {
        val pcs = Store.pcs(this)
        val paired = pcs.isNotEmpty()

        if (!paired) {
            status.setText(R.string.status_unpaired)
            status.setBackgroundResource(R.drawable.bg_pill_warn)
            status.setTextColor(getColor(R.color.warn_fg))
            pcList.visibility = View.GONE
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
            showPcs(pcs)
            pcList.visibility = View.VISIBLE

            val showForm = editingCode || linkedTopic != null
            pairedActions.visibility = if (showForm) View.GONE else View.VISIBLE
            pairCard.visibility = if (showForm) View.VISIBLE else View.GONE
            cancelPair.visibility = if (showForm) View.VISIBLE else View.GONE
        }

        val stats = Store.stats(this)
        statStretches.text = stats.quickStretches.toString()
        statLongBreaks.text = stats.longBreaks.toString()
        stopAlarmBtn.visibility = if (ListenerService.strictAlarmActive) View.VISIBLE else View.GONE

        val history = Store.recentBuzzes(this)
        if (history.isNotEmpty()) {
            val timeFmt = DateFormat.getTimeFormat(this)
            val newest = history.first()
            val newestTime = timeFmt.format(Date(newest.timeSecs * 1000L))
            latestSummary.text = "Latest: [$newestTime] ${newest.title}"
            historyToggleLabel.text = getString(
                if (historyExpanded) R.string.history_toggle_expanded else R.string.history_toggle_collapsed,
                history.size,
            )
            historyToggleLabel.visibility = View.VISIBLE
            lastBuzz.text = history.joinToString("\n\n") { item ->
                val t = timeFmt.format(Date(item.timeSecs * 1000L))
                "• [$t] ${item.title}\n  ${item.text}"
            }
            lastBuzz.visibility = if (historyExpanded) View.VISIBLE else View.GONE
        } else {
            latestSummary.setText(R.string.last_buzz_none)
            historyToggleLabel.visibility = View.GONE
            lastBuzz.visibility = View.GONE
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

        // The link warning is always part of the banner while a link is waiting; nothing may replace it.
        val banner = listOfNotNull(notice, linkWarning(pcs)).joinToString("\n\n").ifEmpty {
            if (paired && !notificationsOn) getString(R.string.error_notifications) else ""
        }
        if (error.text.toString() != banner) {
            error.text = banner
        }
        error.visibility = if (banner.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
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
        const val STATE_LINKED_TOPIC = "linked_topic"
        const val STATE_PENDING_NAME = "pending_pc_name"
        const val STATE_NOTICE = "notice"
    }
}
