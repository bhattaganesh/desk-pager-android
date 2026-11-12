package np.com.ganeshbhatt.deskpager

import org.json.JSONException
import org.json.JSONObject

/** One buzz received from the PC. */
data class Buzz(val id: String, val timeSecs: Long, val title: String, val text: String)

/** Pure parsing and validation for everything that arrives from outside the app. */
object Ntfy {
    const val SERVER = "https://ntfy.sh"
    const val PAIR_LINK_PREFIX = "deskpager://pair?topic="
    const val MAX_BUZZ_AGE_SECS = 10 * 60L
    private const val MAX_TITLE_CHARS = 100
    private const val MAX_TEXT_CHARS = 500
    private const val VISIBLE_SUFFIX_CHARS = 4

    // Exactly what the Windows app generates, so the topic is always safe inside a URL path.
    private val TOPIC = Regex("^deskpager-[0-9a-f]{32}$")

    /**
     * Extracts the topic from a pasted pairing code or a scanned `deskpager://` link.
     *
     * @return the topic, or null when the input is not a Desk Pager pairing code.
     */
    fun parsePairing(input: String): String? =
        input.trim().removePrefix(PAIR_LINK_PREFIX).takeIf { TOPIC.matches(it) }

    /**
     * Formats a valid topic for on-screen display without exposing the full secret.
     *
     * @return `deskpager-••••••••••••<last4>` so the user can verify the channel safely.
     */
    fun maskTopic(topic: String): String =
        if (topic.length > VISIBLE_SUFFIX_CHARS) {
            "deskpager-••••••••••••${topic.takeLast(VISIBLE_SUFFIX_CHARS)}"
        } else {
            "deskpager-••••"
        }

    /**
     * Parses one line of the ntfy JSON stream.
     *
     * @return a [Buzz] for a message event; null for keepalives, open events and malformed lines.
     */
    fun parseLine(line: String): Buzz? = try {
        val json = JSONObject(line)
        if (json.optString("event") != "message") {
            null
        } else {
            Buzz(
                id = json.getString("id"),
                timeSecs = json.getLong("time"),
                title = json.optString("title").take(MAX_TITLE_CHARS),
                text = json.optString("message").take(MAX_TEXT_CHARS),
            )
        }
    } catch (e: JSONException) {
        // A line we can't parse is not a buzz; the stream carries on.
        null
    }

    /** Whether a buzz is recent enough to still be worth showing (old ones arrive after reconnects). */
    fun isFresh(buzz: Buzz, nowSecs: Long): Boolean = nowSecs - buzz.timeSecs <= MAX_BUZZ_AGE_SECS
}
