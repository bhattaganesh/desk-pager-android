package np.com.ganeshbhatt.deskpager

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** The only screen: pair or unpair this phone and allow background use. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var error: TextView
    private lateinit var code: EditText
    private lateinit var unpair: Button

    /** True while a code filled in by a link is waiting for the user to confirm it. */
    private var linkPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        error = findViewById(R.id.error)
        code = findViewById(R.id.code)
        unpair = findViewById(R.id.unpair)

        findViewById<Button>(R.id.pair).setOnClickListener { pair(code.text.toString()) }
        unpair.setOnClickListener {
            ListenerService.stop(this)
            Store.setTopic(this, null)
            linkPending = false
            code.text.clear()
            refresh()
        }
        findViewById<Button>(R.id.battery).setOnClickListener { askForBackgroundUse() }

        // Not on recreation (rotation etc.): the launching link must not be replayed.
        if (savedInstanceState == null) {
            handleLink(intent)
        }
        if (Store.topic(this) != null) {
            ListenerService.start(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
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
        refresh()
    }

    private fun pair(input: String) {
        val topic = Ntfy.parsePairing(input)
        if (topic == null) {
            showError(R.string.error_bad_code)
            return
        }
        Store.setTopic(this, topic)
        linkPending = false
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
        val paired = Store.topic(this) != null
        status.setText(if (paired) R.string.status_paired else R.string.status_unpaired)
        unpair.visibility = if (paired) View.VISIBLE else View.GONE
        val notificationsOff = !getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        if (linkPending) {
            showError(R.string.confirm_link)
        } else if (paired && notificationsOff) {
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
