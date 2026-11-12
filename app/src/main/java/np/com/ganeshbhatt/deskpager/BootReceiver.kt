package np.com.ganeshbhatt.deskpager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the listener after a reboot when the phone is paired. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Store.topic(context) != null) {
            ListenerService.start(context)
        }
    }
}
