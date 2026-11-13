package np.com.ganeshbhatt.deskpager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtfyTest {
    private val topic = "deskpager-00112233445566778899aabbccddeeff"

    @Test
    fun pairingAcceptsCodeAndLink() {
        assertEquals(topic, Ntfy.parsePairing(topic))
        assertEquals(topic, Ntfy.parsePairing("  $topic\n"))
        assertEquals(topic, Ntfy.parsePairing("deskpager://pair?topic=$topic"))
        assertEquals(Pair(topic, "YOGA-PC"), Ntfy.parsePairingWithName("deskpager://pair?topic=$topic&name=YOGA-PC"))
    }

    @Test
    fun pairingRejectsAnythingElse() {
        assertNull(Ntfy.parsePairing(""))
        assertNull(Ntfy.parsePairing("deskpager-0011"))
        assertNull(Ntfy.parsePairing(topic.uppercase()))
        assertNull(Ntfy.parsePairing("$topic/../other"))
        assertNull(Ntfy.parsePairing("$topic?since=all"))
        assertNull(Ntfy.parsePairing("https://evil.example/$topic"))
    }

    @Test
    fun masksTopicPreservingLastFourHexChars() {
        assertEquals("deskpager-••••••••••••eeff", Ntfy.maskTopic(topic))
    }

    @Test
    fun maskNeverContainsTheHiddenPartOfTheTopic() {
        val distinct = "deskpager-0123456789abcdef0123456789abcdef"
        val hex = distinct.removePrefix("deskpager-")
        val masked = Ntfy.maskTopic(distinct)
        assertTrue(masked.endsWith(hex.takeLast(4)))
        val hiddenPart = masked.removePrefix("deskpager-").removeSuffix(hex.takeLast(4))
        assertFalse(hiddenPart.any { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(masked.contains(hex.take(28)))
        assertFalse(Ntfy.maskTopic(topic).contains(topic.removePrefix("deskpager-").take(28)))
        assertEquals("deskpager-••••", Ntfy.maskTopic("abc"))
    }

    @Test
    fun isTopicAcceptsOnlyABareTopic() {
        assertTrue(Ntfy.isTopic(topic))
        assertFalse(Ntfy.isTopic("deskpager://pair?topic=$topic"))
        assertFalse(Ntfy.isTopic("deskpager://pair?topic=$topic&name=YOGA-PC"))
        assertFalse(Ntfy.isTopic(topic.uppercase()))
        assertFalse(Ntfy.isTopic(topic.dropLast(1)))
        assertFalse(Ntfy.isTopic(topic + "0"))
        assertFalse(Ntfy.isTopic(" $topic"))
        assertFalse(Ntfy.isTopic("$topic\n"))
        assertFalse(Ntfy.isTopic("$topic,$topic"))
        assertFalse(Ntfy.isTopic(""))
    }

    @Test
    fun hostileNamesNeverBecomeTheLabel() {
        val link = "deskpager://pair?topic=$topic"
        val hostile = listOf(
            "x".repeat(29),
            "x".repeat(500),
            "<b>PC</b>",
            "a/b",
            "a,b",
            "My%20PC",
            "line\nbreak",
            "tab\there",
            "",
            "   ",
            "PC\u202Egnp",
        )
        for (name in hostile) {
            assertEquals("name=$name", Pair(topic, null), Ntfy.parsePairingWithName("$link&name=$name"))
        }
        assertEquals(Pair(topic, "x".repeat(28)), Ntfy.parsePairingWithName("$link&name=${"x".repeat(28)}"))
        assertEquals(Pair(topic, "My PC_1.2-a"), Ntfy.parsePairingWithName("$link&name=My PC_1.2-a"))
    }

    @Test
    fun duplicateNameParametersNeverYieldAnUnsafeLabel() {
        val link = "deskpager://pair?topic=$topic"
        assertEquals(Pair(topic, "Second"), Ntfy.parsePairingWithName("$link&name=First&name=Second"))
        assertEquals(Pair(topic, "Safe"), Ntfy.parsePairingWithName("$link&name=Safe&name=<script>"))
        assertEquals(Pair(topic, "Safe"), Ntfy.parsePairingWithName("$link&name=<script>&name=Safe"))
    }

    @Test
    fun unknownOrReorderedLinkParametersAreRejected() {
        val link = "deskpager://pair?topic=$topic"
        assertNull(Ntfy.parsePairingWithName("$link&foo=bar"))
        assertNull(Ntfy.parsePairingWithName("$link&name=PC&foo=bar"))
        assertNull(Ntfy.parsePairingWithName("deskpager://pair?name=PC&topic=$topic"))
        assertNull(Ntfy.parsePairingWithName("deskpager://pair?topic=$topic,$topic"))
        assertNull(Ntfy.parsePairingWithName("deskpager://pair?topic=$topic,deskpager-evil"))
        assertNull(Ntfy.parsePairingWithName("$link&"))
        assertNull(Ntfy.parsePairingWithName("$link&name=PC&"))
        assertNull(Ntfy.parsePairingWithName("$link#fragment"))
        assertNull(Ntfy.parsePairingWithName("$link&topic=$topic"))
        assertNull(Ntfy.parsePairingWithName("DESKPAGER://pair?topic=$topic"))
        assertEquals(Pair(topic, null), Ntfy.parsePairingWithName("$link&name=PC#fragment"))
    }

    @Test
    fun parsesAMessageEvent() {
        val line = """{"id":"abc123","time":1794480000,"event":"message","topic":"t","title":"Time to stand up","message":"45 minutes"}"""
        assertEquals(Buzz("abc123", 1794480000, "Time to stand up", "45 minutes"), Ntfy.parseLine(line))
    }

    @Test
    fun ignoresKeepalivesOpenEventsAndGarbage() {
        assertNull(Ntfy.parseLine("""{"id":"a","time":1,"event":"keepalive","topic":"t"}"""))
        assertNull(Ntfy.parseLine("""{"id":"a","time":1,"event":"open","topic":"t"}"""))
        assertNull(Ntfy.parseLine("not json"))
        assertNull(Ntfy.parseLine(""))
        assertNull(Ntfy.parseLine("""{"event":"message"}"""))
    }

    @Test
    fun truncatesOversizedText() {
        val long = "x".repeat(5000)
        val buzz = Ntfy.parseLine("""{"id":"a","time":1,"event":"message","title":"$long","message":"$long"}""")!!
        assertEquals(100, buzz.title.length)
        assertEquals(500, buzz.text.length)
    }

    @Test
    fun oldBuzzesAreNotFresh() {
        val buzz = Buzz("a", 1000, "t", "m")
        assertTrue(Ntfy.isFresh(buzz, 1000 + 600))
        assertFalse(Ntfy.isFresh(buzz, 1000 + 601))
    }
}
