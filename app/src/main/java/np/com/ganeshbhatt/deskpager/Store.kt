package np.com.ganeshbhatt.deskpager

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One paired Windows PC (e.g. Home Laptop or Office Laptop). */
data class PairedPc(val topic: String, val label: String)

/** Summary of a buzz shown on this phone. */
data class LastBuzz(val title: String, val text: String, val timeSecs: Long)

/** Counts of quick stretches and long breaks received on this phone. */
data class BuzzStats(val quickStretches: Int, val longBreaks: Int)

/** Pure rules for the paired-PC list (stored form, cap, de-duplication, labels); plain values in and out. */
object PcList {
    /** Most PCs that can be paired at once; one more is refused, never swapped in for an older one. */
    const val MAX_PCS = 5
    private const val DEFAULT_LABEL_PREFIX = "Laptop "
    private const val FIELD_TOPIC = "topic"
    private const val FIELD_LABEL = "label"
    private const val FIRST_DUPLICATE_SUFFIX = 2

    /**
     * Reads the stored JSON list, keeping only entries whose topic is a bare valid topic (first one wins).
     *
     * @throws JSONException when [raw] is not a JSON array, so the caller can preserve it instead of overwriting.
     */
    @Throws(JSONException::class)
    fun decode(raw: String): List<PairedPc> {
        val arr = JSONArray(raw)
        val list = mutableListOf<PairedPc>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val topic = obj.optString(FIELD_TOPIC, "")
            if (Ntfy.isTopic(topic) && list.none { it.topic == topic }) {
                list.add(PairedPc(topic, obj.optString(FIELD_LABEL, "").ifBlank { defaultLabel(i) }))
            }
        }
        return list
    }

    /** Returns the JSON form of [pcs] that [decode] reads back. */
    fun encode(pcs: List<PairedPc>): String {
        val arr = JSONArray()
        for (pc in pcs) {
            arr.put(JSONObject().put(FIELD_TOPIC, pc.topic).put(FIELD_LABEL, pc.label))
        }
        return arr.toString()
    }

    /**
     * Returns [pcs] with [topic] added, or relabelled when it is already paired; a blank [label] keeps the
     * current label or falls back to the default, and a label another PC already uses gets a numeric suffix.
     *
     * @return the new list, or null when [topic] is new and [MAX_PCS] are already paired.
     */
    fun add(pcs: List<PairedPc>, topic: String, label: String?): List<PairedPc>? {
        val index = pcs.indexOfFirst { it.topic == topic }
        if (index < 0 && pcs.size >= MAX_PCS) {
            return null
        }
        val wanted = label?.takeIf { it.isNotBlank() }
            ?: if (index >= 0) pcs[index].label else defaultLabel(pcs.size)
        val pc = PairedPc(topic, uniqueLabel(pcs, topic, wanted))
        return if (index >= 0) pcs.toMutableList().also { it[index] = pc } else pcs + pc
    }

    /** Returns [pcs] without the PC paired under [topic]. */
    fun remove(pcs: List<PairedPc>, topic: String): List<PairedPc> = pcs.filter { it.topic != topic }

    /** Returns [label], or `label (2)`, `label (3)`… when a PC with a different topic already uses it. */
    fun uniqueLabel(pcs: List<PairedPc>, topic: String, label: String): String {
        val taken = pcs.filter { it.topic != topic }.map { it.label.lowercase() }
        var candidate = label
        var suffix = FIRST_DUPLICATE_SUFFIX
        while (candidate.lowercase() in taken) {
            candidate = "$label ($suffix)"
            suffix += 1
        }
        return candidate
    }

    /** Returns the label given to the PC at zero-based [position] when the pairing link names none. */
    fun defaultLabel(position: Int): String = "$DEFAULT_LABEL_PREFIX${position + 1}"
}

/** Keeps paired PCs (supporting multiple laptops simultaneously) and recent buzz history in private storage. */
object Store {
    private const val TAG = "DeskPager"
    private const val PREFS = "desk_pager"
    private const val KEY_TOPIC = "topic"
    private const val KEY_PCS = "paired_pcs"
    private const val KEY_PCS_UNREADABLE = "paired_pcs_unreadable_backup"
    private const val KEY_LAST_TITLE = "last_buzz_title"
    private const val KEY_LAST_TEXT = "last_buzz_text"
    private const val KEY_LAST_TIME = "last_buzz_time"
    private const val KEY_HISTORY = "buzz_history"
    private const val KEY_STRETCH_COUNT = "stretch_count"
    private const val KEY_LONG_COUNT = "long_count"
    private const val KEY_STRETCH_STYLE = "stretch_alert_style"
    private const val KEY_LONG_BREAK_STYLE = "long_break_alert_style"
    private val DEFAULT_STRETCH_STYLE = AlertStyle.QUICK
    private val DEFAULT_LONG_BREAK_STYLE = AlertStyle.VIBRATE_UNTIL_STOPPED
    private const val MAX_HISTORY = 5

    /** Returns all paired PCs (migrating a legacy single-topic entry if present). */
    fun pcs(context: Context): List<PairedPc> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PCS, null)
        if (raw != null) {
            try {
                val list = PcList.decode(raw)
                if (list.isNotEmpty()) return list
            } catch (e: JSONException) {
                keepUnreadablePcs(prefs, raw)
            }
        }
        val legacy = prefs.getString(KEY_TOPIC, null)
        return if (legacy != null && Ntfy.isTopic(legacy)) {
            listOf(PairedPc(legacy, PcList.defaultLabel(0)))
        } else {
            emptyList()
        }
    }

    /** Copies an unparseable stored list aside before any later write can replace it. */
    private fun keepUnreadablePcs(prefs: SharedPreferences, raw: String) {
        if (prefs.getString(KEY_PCS_UNREADABLE, null) == raw) return
        // No exception message or stored value here: both can contain a topic.
        Log.w(TAG, "paired PC list is unreadable; kept a backup copy and fell back to the last paired PC")
        prefs.edit().putString(KEY_PCS_UNREADABLE, raw).apply()
    }

    /**
     * Returns the comma-joined ntfy topic string for all paired PCs (e.g. `topic1,topic2`),
     * or null when no PC is paired.
     */
    fun topic(context: Context): String? {
        val list = pcs(context)
        return if (list.isEmpty()) null else list.joinToString(",") { it.topic }
    }

    /**
     * Adds a PC to the paired list (or updates its label if already paired), keeping up to [PcList.MAX_PCS]
     * laptops active simultaneously so the user never has to re-enter codes when switching between them.
     *
     * @return false, changing nothing, when the list is full and [topic] is not already in it.
     */
    fun addPc(context: Context, topic: String, label: String?): Boolean {
        val updated = PcList.add(pcs(context), topic, label) ?: return false
        savePcs(context, updated)
        return true
    }

    /** Removes one paired PC, leaving the other PCs, the history and the counters untouched. */
    fun removePc(context: Context, topic: String) {
        savePcs(context, PcList.remove(pcs(context), topic))
    }

    private fun savePcs(context: Context, pcs: List<PairedPc>) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (pcs.isEmpty()) {
            // The legacy key must go too, or pcs() would bring the removed PC back from it.
            editor.remove(KEY_PCS).remove(KEY_TOPIC)
        } else {
            editor.putString(KEY_PCS, PcList.encode(pcs)).putString(KEY_TOPIC, pcs.last().topic)
        }
        editor.apply()
    }

    /**
     * Clears all paired PCs and history when [topic] is null, or adds a PC when non-null.
     * The alert style settings are the user's preferences, not pairing data, so they are kept.
     */
    fun setTopic(context: Context, topic: String?) {
        if (topic == null) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // An editor applies clear() before its puts, whatever order they are written in.
            prefs.edit().clear()
                .putString(KEY_STRETCH_STYLE, prefs.getString(KEY_STRETCH_STYLE, null))
                .putString(KEY_LONG_BREAK_STYLE, prefs.getString(KEY_LONG_BREAK_STYLE, null))
                .apply()
        } else {
            addPc(context, topic, null)
        }
    }

    /** Records the latest buzz shown to the user and updates the 5-item history and tier counters. */
    fun recordBuzz(context: Context, buzz: Buzz) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val displayTitle = buzz.title.ifBlank { "Desk Pager" }
        val displayText = buzz.text.ifBlank { displayTitle }
        val kind = Ntfy.kindOf(displayTitle)

        val history = recentBuzzes(context).toMutableList()
        history.add(0, LastBuzz(displayTitle, displayText, buzz.timeSecs))
        val trimmed = history.take(MAX_HISTORY)
        val arr = JSONArray()
        for (item in trimmed) {
            arr.put(
                JSONObject()
                    .put("title", item.title)
                    .put("text", item.text)
                    .put("time", item.timeSecs),
            )
        }

        val editor = prefs.edit()
            .putString(KEY_LAST_TITLE, displayTitle)
            .putString(KEY_LAST_TEXT, displayText)
            .putLong(KEY_LAST_TIME, buzz.timeSecs)
            .putString(KEY_HISTORY, arr.toString())

        when (kind) {
            BuzzKind.STRETCH -> editor.putInt(KEY_STRETCH_COUNT, prefs.getInt(KEY_STRETCH_COUNT, 0) + 1)
            BuzzKind.LONG_BREAK -> editor.putInt(KEY_LONG_COUNT, prefs.getInt(KEY_LONG_COUNT, 0) + 1)
            BuzzKind.OTHER -> Unit
        }
        editor.apply()
    }

    /** Returns the alert style the user chose for buzzes of [kind]; anything but a long break uses the stretch one. */
    fun alertStyle(context: Context, kind: BuzzKind): AlertStyle {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AlertStyle.forKind(
            kind,
            stretch = AlertStyle.fromStored(prefs.getString(KEY_STRETCH_STYLE, null), DEFAULT_STRETCH_STYLE),
            longBreak = AlertStyle.fromStored(prefs.getString(KEY_LONG_BREAK_STYLE, null), DEFAULT_LONG_BREAK_STYLE),
        )
    }

    /** Saves [style] as the long-break setting when [kind] is a long break, otherwise as the stretch setting. */
    fun setAlertStyle(context: Context, kind: BuzzKind, style: AlertStyle) {
        val key = if (kind == BuzzKind.LONG_BREAK) KEY_LONG_BREAK_STYLE else KEY_STRETCH_STYLE
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, style.name).apply()
    }

    /** Returns the most recent buzz recorded for this pairing, if any. */
    fun lastBuzz(context: Context): LastBuzz? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY_LAST_TEXT, null) ?: return null
        val title = prefs.getString(KEY_LAST_TITLE, "Desk Pager") ?: "Desk Pager"
        val time = prefs.getLong(KEY_LAST_TIME, 0L)
        return if (time > 0L) LastBuzz(title, text, time) else null
    }

    /** Returns up to [MAX_HISTORY] recent buzzes, newest first. */
    fun recentBuzzes(context: Context): List<LastBuzz> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HISTORY, null)
            ?: return listOfNotNull(lastBuzz(context))
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val time = obj.optLong("time", 0L)
                    if (time > 0L) {
                        add(
                            LastBuzz(
                                obj.optString("title", "Desk Pager"),
                                obj.optString("text", ""),
                                time,
                            ),
                        )
                    }
                }
            }
        } catch (e: JSONException) {
            Log.w(TAG, "buzz history is unreadable; showing only the last buzz")
            listOfNotNull(lastBuzz(context))
        }
    }

    /** Returns total Quick Stretch and Long Break reminders received on this phone. */
    fun stats(context: Context): BuzzStats {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return BuzzStats(
            quickStretches = prefs.getInt(KEY_STRETCH_COUNT, 0),
            longBreaks = prefs.getInt(KEY_LONG_COUNT, 0),
        )
    }
}
