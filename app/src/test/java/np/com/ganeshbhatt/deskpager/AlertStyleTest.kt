package np.com.ganeshbhatt.deskpager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertStyleTest {
    @Test
    fun kindOfTheTitlesTheWindowsAppSends() {
        assertEquals(BuzzKind.STRETCH, Ntfy.kindOf("Quick stretch & eye rest"))
        assertEquals(BuzzKind.LONG_BREAK, Ntfy.kindOf("Time for a long break"))
        assertEquals(BuzzKind.OTHER, Ntfy.kindOf("Desk Pager test"))
    }

    @Test
    fun kindOfTheTitlesOfTheInAppTestBuzzes() {
        assertEquals(BuzzKind.STRETCH, Ntfy.kindOf("Quick stretch & eye rest (Test)"))
        assertEquals(BuzzKind.LONG_BREAK, Ntfy.kindOf("Time for a long break (Test)"))
    }

    @Test
    fun aTitleWithBothWordsIsALongBreak() {
        assertEquals(BuzzKind.LONG_BREAK, Ntfy.kindOf("Stretch, then take a long break"))
        assertEquals(BuzzKind.LONG_BREAK, Ntfy.kindOf("Long break: stretch your legs"))
    }

    @Test
    fun kindOfIgnoresCaseAndTreatsEverythingElseAsOther() {
        assertEquals(BuzzKind.LONG_BREAK, Ntfy.kindOf("LONG BREAK"))
        assertEquals(BuzzKind.STRETCH, Ntfy.kindOf("STRETCH now"))
        assertEquals(BuzzKind.OTHER, Ntfy.kindOf(""))
        assertEquals(BuzzKind.OTHER, Ntfy.kindOf("longbreak"))
        assertEquals(BuzzKind.OTHER, Ntfy.kindOf("deskpager-clear"))
    }

    @Test
    fun fromStoredReadsEachValueBack() {
        for (style in AlertStyle.entries) {
            assertEquals(style, AlertStyle.fromStored(style.name))
            assertEquals(style, AlertStyle.fromStored(style.name, AlertStyle.RING_UNTIL_STOPPED))
        }
    }

    @Test
    fun fromStoredFallsBackForUnknownOrMissingValues() {
        assertEquals(AlertStyle.QUICK, AlertStyle.fromStored(null))
        assertEquals(AlertStyle.QUICK, AlertStyle.fromStored(""))
        assertEquals(AlertStyle.QUICK, AlertStyle.fromStored("quick"))
        assertEquals(AlertStyle.QUICK, AlertStyle.fromStored("LOUD"))
        assertEquals(
            AlertStyle.VIBRATE_UNTIL_STOPPED,
            AlertStyle.fromStored(null, AlertStyle.VIBRATE_UNTIL_STOPPED),
        )
        assertEquals(
            AlertStyle.VIBRATE_UNTIL_STOPPED,
            AlertStyle.fromStored("LOUD", AlertStyle.VIBRATE_UNTIL_STOPPED),
        )
    }

    @Test
    fun onlyALongBreakUsesTheLongBreakSetting() {
        val stretch = AlertStyle.QUICK
        val longBreak = AlertStyle.RING_UNTIL_STOPPED
        assertEquals(longBreak, AlertStyle.forKind(BuzzKind.LONG_BREAK, stretch, longBreak))
        assertEquals(stretch, AlertStyle.forKind(BuzzKind.STRETCH, stretch, longBreak))
        assertEquals(stretch, AlertStyle.forKind(BuzzKind.OTHER, stretch, longBreak))
    }

    @Test
    fun onlyUntilStoppedStylesNeedAStopControlAndOnlyOneRings() {
        assertFalse(AlertStyle.QUICK.untilStopped)
        assertTrue(AlertStyle.VIBRATE_UNTIL_STOPPED.untilStopped)
        assertTrue(AlertStyle.RING_UNTIL_STOPPED.untilStopped)
        assertFalse(AlertStyle.QUICK.rings)
        assertFalse(AlertStyle.VIBRATE_UNTIL_STOPPED.rings)
        assertTrue(AlertStyle.RING_UNTIL_STOPPED.rings)
    }

    @Test
    fun spinnerOrderMatchesTheEnumOrder() {
        assertEquals(
            listOf(AlertStyle.QUICK, AlertStyle.VIBRATE_UNTIL_STOPPED, AlertStyle.RING_UNTIL_STOPPED),
            AlertStyle.entries.toList(),
        )
    }
}
