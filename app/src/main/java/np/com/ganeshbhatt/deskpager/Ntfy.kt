package np.com.ganeshbhatt.deskpager

import org.json.JSONException
import org.json.JSONObject

/** One buzz received from the PC. */
data class Buzz(val id: String, val timeSecs: Long, val title: String, val text: String)

/** What a buzz is about, decided from its title alone. */
enum class BuzzKind { LONG_BREAK, STRETCH, OTHER }

/** How the phone gets the user's attention for a buzz. */
enum class AlertStyle {
    QUICK,
    VIBRATE_UNTIL_STOPPED,
    RING_UNTIL_STOPPED,
    ;

    /** Whether this style keeps going, and so needs a Stop control, rather than ending by itself. */
    val untilStopped: Boolean get() = this != QUICK

    /** Whether this style plays the alarm sound as well as vibrating. */
    val rings: Boolean get() = this == RING_UNTIL_STOPPED

    companion object {
        /** Returns the style saved under its enum name, or [fallback] when [stored] is null or unknown. */
        fun fromStored(stored: String?, fallback: AlertStyle = QUICK): AlertStyle =
            entries.firstOrNull { it.name == stored } ?: fallback

        /** Picks the style for [kind]: long breaks use [longBreak], everything else uses [stretch]. */
        fun forKind(kind: BuzzKind, stretch: AlertStyle, longBreak: AlertStyle): AlertStyle =
            if (kind == BuzzKind.LONG_BREAK) longBreak else stretch
    }
}

/** Pure parsing and validation for everything that arrives from outside the app. */
object Ntfy {
    const val SERVER = "https://ntfy.sh"
    const val PAIR_LINK_PREFIX = "deskpager://pair?topic="
    const val MAX_BUZZ_AGE_SECS = 10 * 60L
    private const val MAX_TITLE_CHARS = 100
    private const val MAX_TEXT_CHARS = 500
    private const val VISIBLE_SUFFIX_CHARS = 4
    private const val LONG_BREAK_WORDS = "long break"
    private const val STRETCH_WORD = "stretch"

    // Exactly what the Windows app generates, so the topic is always safe inside a URL path.
    private val TOPIC = Regex("^deskpager-[0-9a-f]{32}$")
    private val SAFE_NAME = Regex("^[A-Za-z0-9 _.-]{1,28}$")

    /**
     * Classifies a buzz by the words the Windows app puts in its title; a title with both is a long break.
     *
     * @return [BuzzKind.OTHER] for anything else, such as the PC's test buzz.
     */
    fun kindOf(title: String): BuzzKind = when {
        title.contains(LONG_BREAK_WORDS, ignoreCase = true) -> BuzzKind.LONG_BREAK
        title.contains(STRETCH_WORD, ignoreCase = true) -> BuzzKind.STRETCH
        else -> BuzzKind.OTHER
    }

    /** Whether [value] is exactly a bare topic (never a link), the only form allowed into storage or a URL. */
    fun isTopic(value: String): Boolean = TOPIC.matches(value)

    /**
     * Extracts the topic and optional PC label from a pasted code or scanned `deskpager://` link.
     *
     * @return `Pair(topic, pcName?)`, or null when the input is not a valid Desk Pager pairing code.
     */
    fun parsePairingWithName(input: String): Pair<String, String?>? {
        val raw = input.trim()
        if (TOPIC.matches(raw)) {
            return Pair(raw, null)
        }
        if (!raw.startsWith(PAIR_LINK_PREFIX)) {
            return null
        }
        val query = raw.removePrefix(PAIR_LINK_PREFIX)
        val parts = query.split("&")
        val topicPart = parts.firstOrNull() ?: return null
        if (!TOPIC.matches(topicPart)) {
            return null
        }
        var pcName: String? = null
        for (extra in parts.drop(1)) {
            if (extra.startsWith("name=")) {
                val candidate = extra.removePrefix("name=").trim()
                if (SAFE_NAME.matches(candidate)) {
                    pcName = candidate
                }
            } else {
                return null
            }
        }
        return Pair(topicPart, pcName)
    }

    /**
     * Extracts the topic from a pasted pairing code or a scanned `deskpager://` link.
     *
     * @return the topic, or null when the input is not a Desk Pager pairing code.
     */
    fun parsePairing(input: String): String? = parsePairingWithName(input)?.first

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
