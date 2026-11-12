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
            refresh()
        }
        findViewById<Button>(R.id.battery).setOnClickListener { askForBackgroundUse() }

        handleLink(intent)
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

    /** Pairs from a `deskpager://pair?topic=…` link opened by the camera or a browser. */
    private fun handleLink(intent: Intent?) {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        pair(link)
    }

    private fun pair(input: String) {
        val topic = Ntfy.parsePairing(input)
        if (topic == null) {
            showError(R.string.error_bad_code)
            return
        }
        Store.setTopic(this, topic)
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
        if (paired && notificationsOff) {
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
