package np.com.ganeshbhatt.deskpager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One paired Windows PC (e.g. Home Laptop or Office Laptop). */
data class PairedPc(val topic: String, val label: String)

/** Summary of a buzz shown on this phone. */
data class LastBuzz(val title: String, val text: String, val timeSecs: Long)

/** Counts of quick stretches and long breaks received on this phone. */
data class BuzzStats(val quickStretches: Int, val longBreaks: Int)

/** Keeps paired PCs (supporting multiple laptops simultaneously) and recent buzz history in private storage. */
object Store {
    private const val PREFS = "desk_pager"
    private const val KEY_TOPIC = "topic"
    private const val KEY_PCS = "paired_pcs"
    private const val KEY_LAST_TITLE = "last_buzz_title"
    private const val KEY_LAST_TEXT = "last_buzz_text"
    private const val KEY_LAST_TIME = "last_buzz_time"
    private const val KEY_HISTORY = "buzz_history"
    private const val KEY_STRETCH_COUNT = "stretch_count"
    private const val KEY_LONG_COUNT = "long_count"
    private const val MAX_HISTORY = 5
    private const val MAX_PCS = 5

    /** Returns all paired PCs (migrating a legacy single-topic entry if present). */
    fun pcs(context: Context): List<PairedPc> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PCS, null)
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                val list = buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val t = obj.optString("topic", "")
                        val l = obj.optString("label", "PC ${i + 1}")
                        if (Ntfy.parsePairing(t) != null) {
                            add(PairedPc(t, l))
                        }
                    }
                }
                if (list.isNotEmpty()) return list
            } catch (_: Exception) {
            }
        }
        val legacy = prefs.getString(KEY_TOPIC, null)
        return if (legacy != null && Ntfy.parsePairing(legacy) != null) {
            listOf(PairedPc(legacy, "Laptop 1"))
        } else {
            emptyList()
        }
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
     * Adds a PC to the paired list (or updates its label if already paired), keeping up to [MAX_PCS] laptops
     * active simultaneously so the user never has to re-enter codes when switching between Home and Office.
     */
    fun addPc(context: Context, topic: String, label: String?) {
        val existing = pcs(context).toMutableList()
        val existingIdx = existing.indexOfFirst { it.topic == topic }
        val resolvedLabel = label?.takeIf { it.isNotBlank() }
            ?: if (existingIdx >= 0) existing[existingIdx].label else "Laptop ${existing.size + 1}"
        if (existingIdx >= 0) {
            existing[existingIdx] = PairedPc(topic, resolvedLabel)
        } else {
            existing.add(PairedPc(topic, resolvedLabel))
        }
        val trimmed = existing.takeLast(MAX_PCS)
        val arr = JSONArray()
        for (pc in trimmed) {
            arr.put(JSONObject().put("topic", pc.topic).put("label", pc.label))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PCS, arr.toString())
            .putString(KEY_TOPIC, trimmed.last().topic)
            .apply()
    }

    /** Clears all paired PCs and history when [topic] is null, or adds a PC when non-null. */
    fun setTopic(context: Context, topic: String?) {
        if (topic == null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        } else {
            addPc(context, topic, null)
        }
    }

    /** Records the latest buzz shown to the user and updates the 5-item history and tier counters. */
    fun recordBuzz(context: Context, buzz: Buzz) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val displayTitle = buzz.title.ifBlank { "Desk Pager" }
        val displayText = buzz.text.ifBlank { displayTitle }
        val lower = displayTitle.lowercase()
        val isQuick = lower.contains("stretch")
        val isLong = lower.contains("long break")

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

        if (isQuick) {
            editor.putInt(KEY_STRETCH_COUNT, prefs.getInt(KEY_STRETCH_COUNT, 0) + 1)
        } else if (isLong) {
            editor.putInt(KEY_LONG_COUNT, prefs.getInt(KEY_LONG_COUNT, 0) + 1)
        }
        editor.apply()
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
        } catch (_: Exception) {
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
