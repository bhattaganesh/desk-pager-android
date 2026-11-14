package np.com.ganeshbhatt.deskpager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    private fun topic(n: Int) = "deskpager-" + n.toString(16).padStart(32, '0')

    private fun link(n: Int, name: String? = null) =
        "deskpager://pair?topic=${topic(n)}" + if (name == null) "" else "&name=$name"

    private val home = listOf(PairedPc(topic(1), "Home"))
    private val five = (1..5).map { PairedPc(topic(it), "PC$it") }

    @Test
    fun aScannedLinkForANewPcPairsUnderItsName() {
        assertEquals(
            PairResult.Paired(topic(2), "YOGA-PC", "YOGA-PC"),
            Pairing.decideInput(home, link(2, "YOGA-PC")),
        )
        assertEquals(PairResult.Paired(topic(1), "Laptop 1", null), Pairing.decideInput(emptyList(), link(1)))
    }

    @Test
    fun aTypedBareCodePairsWithTheDefaultName() {
        assertEquals(PairResult.Paired(topic(2), "Laptop 2", null), Pairing.decideInput(home, topic(2)))
        assertEquals(PairResult.Paired(topic(2), "Laptop 2", null), Pairing.decideInput(home, "  ${topic(2)}\n"))
    }

    @Test
    fun scanningAnAlreadyPairedPcAddsNothing() {
        assertEquals(PairResult.AlreadyPaired(topic(1), "Home", null), Pairing.decideInput(home, link(1)))
        assertEquals(PairResult.AlreadyPaired(topic(1), "Den", "Den"), Pairing.decideInput(home, link(1, "Den")))
        assertEquals(PairResult.AlreadyPaired(topic(3), "PC3", null), Pairing.decideInput(five, topic(3)))
    }

    @Test
    fun aSixthPcIsRefused() {
        assertEquals(PairResult.CapReached, Pairing.decideInput(five, link(6, "Sixth")))
        assertEquals(PairResult.CapReached, Pairing.decide(five, topic(6), null))
    }

    @Test
    fun anythingThatIsNotAPairingCodeIsInvalid() {
        val notCodes = listOf(
            null,
            "",
            "https://example.com/",
            "WIFI:S:Home;T:WPA;P:secret;;",
            topic(2).uppercase(),
            "${link(2)}&foo=bar",
            "deskpager://pair?topic=deskpager-123",
        )
        for (text in notCodes) {
            assertEquals("input=$text", PairResult.Invalid, Pairing.decideInput(home, text))
        }
        assertEquals(PairResult.Invalid, Pairing.decide(home, link(2), null))
    }

    @Test
    fun aNameAnotherPcUsesIsChangedAndReported() {
        val result = Pairing.decideInput(home, link(2, "Home")) as PairResult.Paired
        assertEquals("Home (2)", result.label)
        assertTrue(HomeRules.labelWasChanged(result.requestedName, result.label))
        assertFalse(HomeRules.labelWasChanged("Office", "Office"))
        assertFalse(HomeRules.labelWasChanged(null, "Laptop 2"))
    }

    @Test
    fun reconnectIsOfferedOnlyWhenTheStreamIsDown() {
        assertTrue(HomeRules.canReconnect(StreamState.STOPPED))
        assertTrue(HomeRules.canReconnect(StreamState.RECONNECTING))
        assertFalse(HomeRules.canReconnect(StreamState.CONNECTING))
        assertFalse(HomeRules.canReconnect(StreamState.CONNECTED))
    }

    @Test
    fun setupProblemsAppearOnlyWhenPresent() {
        assertEquals(emptyList<SetupProblem>(), HomeRules.setupProblems(true, true))
        assertEquals(listOf(SetupProblem.NOTIFICATIONS_OFF), HomeRules.setupProblems(false, true))
        assertEquals(listOf(SetupProblem.BATTERY_RESTRICTED), HomeRules.setupProblems(true, false))
        assertEquals(
            listOf(SetupProblem.NOTIFICATIONS_OFF, SetupProblem.BATTERY_RESTRICTED),
            HomeRules.setupProblems(false, false),
        )
    }
}
