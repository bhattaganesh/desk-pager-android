package np.com.ganeshbhatt.deskpager

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PcListTest {
    private fun topic(n: Int) = "deskpager-" + n.toString(16).padStart(32, '0')

    private val five = (1..5).map { PairedPc(topic(it), "PC$it") }

    @Test
    fun encodeThenDecodeReturnsTheSameList() {
        assertEquals(five, PcList.decode(PcList.encode(five)))
        assertEquals(emptyList<PairedPc>(), PcList.decode(PcList.encode(emptyList())))
    }

    @Test
    fun decodeThrowsOnAnythingThatIsNotAJsonArray() {
        assertThrows(JSONException::class.java) { PcList.decode("") }
        assertThrows(JSONException::class.java) { PcList.decode("not json") }
        assertThrows(JSONException::class.java) { PcList.decode("""{"topic":"${topic(1)}"}""") }
        assertThrows(JSONException::class.java) { PcList.decode("""[{"topic":"${topic(1)}"}""") }
    }

    @Test
    fun decodeKeepsOnlyBareValidTopics() {
        val raw = """[
            {"topic":"deskpager://pair?topic=${topic(1)}&name=Evil","label":"link"},
            {"topic":"${topic(2)}/../x","label":"path"},
            {"topic":"${topic(3).uppercase()}","label":"upper"},
            {"label":"no topic"},
            "not an object",
            {"topic":"${topic(4)}","label":"Good"}
        ]"""
        assertEquals(listOf(PairedPc(topic(4), "Good")), PcList.decode(raw))
    }

    @Test
    fun decodeDropsRepeatedTopicsAndFillsMissingLabels() {
        val raw = """[
            {"topic":"${topic(1)}","label":"First"},
            {"topic":"${topic(1)}","label":"Again"},
            {"topic":"${topic(2)}"},
            {"topic":"${topic(3)}","label":"  "}
        ]"""
        assertEquals(
            listOf(PairedPc(topic(1), "First"), PairedPc(topic(2), "Laptop 3"), PairedPc(topic(3), "Laptop 4")),
            PcList.decode(raw),
        )
    }

    @Test
    fun addAppendsWithTheGivenOrDefaultLabel() {
        val one = PcList.add(emptyList(), topic(1), null)
        assertEquals(listOf(PairedPc(topic(1), "Laptop 1")), one)
        assertEquals(one!! + PairedPc(topic(2), "Office"), PcList.add(one, topic(2), "Office"))
        assertEquals(one + PairedPc(topic(2), "Laptop 2"), PcList.add(one, topic(2), " "))
    }

    @Test
    fun addRefusesASixthPcAndEvictsNothing() {
        assertEquals(5, PcList.MAX_PCS)
        assertNull(PcList.add(five, topic(6), "Sixth"))
        assertEquals(5, PcList.add(five.dropLast(1), topic(6), "Fifth")!!.size)
    }

    @Test
    fun addOfAnAlreadyPairedTopicOnlyUpdatesItsLabelEvenWhenFull() {
        val renamed = PcList.add(five, topic(2), "Renamed")!!
        assertEquals(five.map { it.topic }, renamed.map { it.topic })
        assertEquals("Renamed", renamed[1].label)
        assertEquals(five, PcList.add(five, topic(2), null))
        assertEquals(five, PcList.add(five, topic(2), "PC2"))
    }

    @Test
    fun addDisambiguatesALabelUsedByADifferentTopic() {
        val home = listOf(PairedPc(topic(1), "Home"))
        val two = PcList.add(home, topic(2), "Home")!!
        assertEquals("Home (2)", two[1].label)
        assertEquals("home (3)", PcList.add(two, topic(3), "home")!![2].label)
        assertEquals("home (3)", PcList.uniqueLabel(two, topic(3), "home"))
        assertEquals("Home", PcList.uniqueLabel(two, topic(1), "Home"))
    }

    @Test
    fun defaultLabelNeverCollidesAfterARemoval() {
        val two = PcList.add(PcList.add(emptyList(), topic(1), null)!!, topic(2), null)!!
        val afterRemoval = PcList.remove(two, topic(1))
        assertEquals(listOf(PairedPc(topic(2), "Laptop 2")), afterRemoval)
        assertEquals("Laptop 2 (2)", PcList.add(afterRemoval, topic(3), null)!![1].label)
    }

    @Test
    fun removeDropsOnlyTheNamedTopic() {
        assertEquals(five.drop(1), PcList.remove(five, topic(1)))
        assertEquals(five, PcList.remove(five, topic(9)))
        assertEquals(emptyList<PairedPc>(), PcList.remove(five.take(1), topic(1)))
    }
}
