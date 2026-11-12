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
