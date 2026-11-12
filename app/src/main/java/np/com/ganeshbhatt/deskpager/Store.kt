package np.com.ganeshbhatt.deskpager

import android.content.Context

/** Summary of the most recent buzz shown on this phone. */
data class LastBuzz(val text: String, val timeSecs: Long)

/** Keeps the pairing topic and last buzz summary in app-private storage (allowBackup is false). */
object Store {
    private const val PREFS = "desk_pager"
    private const val KEY_TOPIC = "topic"
    private const val KEY_LAST_TEXT = "last_buzz_text"
    private const val KEY_LAST_TIME = "last_buzz_time"

    /** The paired topic, or null when the phone is not paired. */
    fun topic(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOPIC, null)

    /** Saves the topic, or clears pairing and buzz history when [topic] is null. */
    fun setTopic(context: Context, topic: String?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (topic == null) {
            editor.clear()
        } else {
            editor.putString(KEY_TOPIC, topic)
        }
        editor.apply()
    }

    /** Records the latest buzz shown to the user. */
    fun recordBuzz(context: Context, buzz: Buzz) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_TEXT, buzz.text.ifBlank { buzz.title })
            .putLong(KEY_LAST_TIME, buzz.timeSecs)
            .apply()
    }

    /** Returns the most recent buzz recorded for this pairing, if any. */
    fun lastBuzz(context: Context): LastBuzz? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY_LAST_TEXT, null) ?: return null
        val time = prefs.getLong(KEY_LAST_TIME, 0L)
        return if (time > 0L) LastBuzz(text, time) else null
    }
}
