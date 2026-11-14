package np.com.ganeshbhatt.deskpager

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** A pairing link opened from outside the app, waiting for the user to confirm or cancel it. */
data class PendingLink(val topic: String, val name: String?)

/** The single screen: hosts the Compose UI and does the few things only an Activity can. */
class MainActivity : ComponentActivity() {
    private var pendingLink by mutableStateOf<PendingLink?>(null)
    private var resumeCount by mutableIntStateOf(0)
    private var askedForNotifications = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            handleLink(intent)
        } else {
            // A link already confirmed or cancelled was saved as absent, so it cannot come back here.
            pendingLink = savedInstanceState.getString(STATE_LINK_TOPIC)?.takeIf { Ntfy.isTopic(it) }
                ?.let { PendingLink(it, savedInstanceState.getString(STATE_LINK_NAME)) }
            askedForNotifications = savedInstanceState.getBoolean(STATE_ASKED_NOTIFICATIONS)
        }
        val host = HostActions(
            scan = ::scan,
            askForNotifications = ::askForNotifications,
            openNotificationSettings = ::openNotificationSettings,
            allowBackgroundUse = ::allowBackgroundUse,
        )
        setContent {
            DeskPagerTheme {
                DeskPagerApp(
                    pendingLink = pendingLink,
                    onLinkHandled = { pendingLink = null },
                    resumeCount = resumeCount,
                    versionName = BuildConfig.VERSION_NAME,
                    host = host,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    override fun onResume() {
        super.onResume()
        resumeCount += 1
        if (Store.topic(this) != null) {
            Pairing.startListener(this)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_LINK_TOPIC, pendingLink?.topic)
        outState.putString(STATE_LINK_NAME, pendingLink?.name)
        outState.putBoolean(STATE_ASKED_NOTIFICATIONS, askedForNotifications)
    }

    /**
     * Holds a `deskpager://pair?topic=…` link for the confirmation dialog.
     *
     * Any app or web page can fire this link, so it never changes the pairing here; only the dialog's
     * "Add this PC" button does.
     */
    private fun handleLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        // Reopening the app from Recents delivers the old link again; that is not a new request.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val parsed = intent.dataString?.let { Ntfy.parsePairingWithName(it) }
        if (parsed == null) {
            AppEvents.show(Notice(getString(R.string.error_bad_link), isError = true))
            return
        }
        pendingLink = PendingLink(parsed.first, parsed.second)
    }

    /**
     * Opens Google's QR scanner and pairs with what it reads. The user started this inside the app and
     * aimed the camera himself, so a valid code pairs at once; cancelling the scanner does nothing.
     */
    private fun scan() {
        // The callbacks use only the application context, so a recreated Activity is never held or called.
        val app = applicationContext
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode -> Pairing.pairInput(app, barcode.rawValue) }
            .addOnFailureListener { e ->
                Log.w(TAG, "scanner failed: ${e.javaClass.simpleName}")
                AppEvents.show(Notice(app.getString(R.string.error_scanner), isError = true))
            }
    }

    /** Shows Android's notification permission prompt, at most once per launch. */
    private fun askForNotifications() {
        if (askedForNotifications || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        askedForNotifications = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Some phones lack the per-app notification page; the app details page always exists.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()))
        }
    }

    private fun allowBackgroundUse() {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri())
        try {
            startActivity(request)
        } catch (e: ActivityNotFoundException) {
            // Some phones lack the direct dialog; the general list always exists.
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun packageUri(): Uri = Uri.fromParts(PACKAGE_SCHEME, packageName, null)

    private companion object {
        const val TAG = "DeskPager"
        const val REQUEST_NOTIFICATIONS = 1
        const val PACKAGE_SCHEME = "package"
        const val STATE_LINK_TOPIC = "link_topic"
        const val STATE_LINK_NAME = "link_name"
        const val STATE_ASKED_NOTIFICATIONS = "asked_notifications"
    }
}
