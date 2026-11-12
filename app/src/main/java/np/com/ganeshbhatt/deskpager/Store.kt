package np.com.ganeshbhatt.deskpager

import android.content.Context

/** Keeps the pairing topic in app-private storage (not backed up: allowBackup is false). */
object Store {
    private const val PREFS = "desk_pager"
    private const val KEY_TOPIC = "topic"

    /** The paired topic, or null when the phone is not paired. */
    fun topic(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOPIC, null)

    /** Saves the topic, or clears it when [topic] is null. */
    fun setTopic(context: Context, topic: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TOPIC, topic).apply()
    }
}
