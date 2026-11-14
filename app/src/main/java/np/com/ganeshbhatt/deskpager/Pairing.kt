package np.com.ganeshbhatt.deskpager

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A short message for the screen: how pairing went, or why it did not happen. */
data class Notice(val text: String, val isError: Boolean)

/** Process-wide signals the screen observes, so background work never holds a reference to the Activity. */
object AppEvents {
    private val mutableStoreChanges = MutableStateFlow(0)
    private val mutableNotice = MutableStateFlow<Notice?>(null)

    /** Changes every time stored data (paired PCs, history, counters) may have changed. */
    val storeChanges: StateFlow<Int> = mutableStoreChanges

    /** The message to show now, or null when there is none. */
    val notice: StateFlow<Notice?> = mutableNotice

    /** Tells the screen to read the stored data again. */
    fun storeChanged() {
        mutableStoreChanges.update { it + 1 }
    }

    /** Shows [notice], or clears the current one when null. */
    fun show(notice: Notice?) {
        mutableNotice.value = notice
    }
}

/** What a scanned, typed or linked pairing code leads to. */
sealed interface PairResult {
    /** A new PC is added under [label]; [requestedName] is the name the code asked for, if any. */
    data class Paired(val topic: String, val label: String, val requestedName: String?) : PairResult

    /** The PC was paired before; nothing is added and it keeps or takes [label]. */
    data class AlreadyPaired(val topic: String, val label: String, val requestedName: String?) : PairResult

    /** The list is full and this PC is not in it. */
    data object CapReached : PairResult

    /** The text is not a Desk Pager pairing code. */
    data object Invalid : PairResult
}

/** Pairing rules shared by the scanner, manual entry and the outside-link confirmation. */
object Pairing {
    private const val TAG = "DeskPager"

    /** Decides, without changing anything, what pairing with the text [input] would do to [pcs]. */
    fun decideInput(pcs: List<PairedPc>, input: String?): PairResult {
        val parsed = input?.let { Ntfy.parsePairingWithName(it) } ?: return PairResult.Invalid
        return decide(pcs, parsed.first, parsed.second)
    }

    /** Decides, without changing anything, what pairing [topic] under the optional [name] would do to [pcs]. */
    fun decide(pcs: List<PairedPc>, topic: String, name: String?): PairResult {
        if (!Ntfy.isTopic(topic)) return PairResult.Invalid
        val updated = PcList.add(pcs, topic, name) ?: return PairResult.CapReached
        val label = updated.first { it.topic == topic }.label
        return if (pcs.any { it.topic == topic }) {
            PairResult.AlreadyPaired(topic, label, name)
        } else {
            PairResult.Paired(topic, label, name)
        }
    }

    /**
     * Pairs with the code in [input] straight away and tells the screen how it went.
     *
     * Only for text the user supplied inside the app (scanner or manual entry), never for an outside link.
     */
    fun pairInput(context: Context, input: String?): PairResult =
        apply(context, decideInput(Store.pcs(context), input))

    /** Pairs [topic] after the user confirmed an outside link, and tells the screen how it went. */
    fun pairConfirmed(context: Context, topic: String, name: String?): PairResult =
        apply(context, decide(Store.pcs(context), topic, name))

    private fun apply(context: Context, result: PairResult): PairResult {
        val app = context.applicationContext
        val notice = when (result) {
            is PairResult.Paired -> {
                save(app, result.topic, result.label)
                Notice(app.getString(R.string.notice_paired, result.label), isError = false)
            }
            is PairResult.AlreadyPaired -> {
                save(app, result.topic, result.label)
                Notice(app.getString(R.string.notice_already_paired, result.label), isError = false)
            }
            PairResult.CapReached -> Notice(app.getString(R.string.error_max_pcs, PcList.MAX_PCS), isError = true)
            PairResult.Invalid -> Notice(app.getString(R.string.error_bad_code), isError = true)
        }
        AppEvents.show(notice)
        return result
    }

    private fun save(app: Context, topic: String, label: String) {
        Store.addPc(app, topic, label)
        AppEvents.storeChanged()
        startListener(app)
    }

    /**
     * Starts the listener, or lets a running one pick up the changed list of PCs.
     *
     * @return false when Android refused because the app is not in front; the screen retries on resume.
     */
    fun startListener(context: Context): Boolean = try {
        ListenerService.start(context)
        true
    } catch (e: IllegalStateException) {
        Log.w(TAG, "listener start refused: ${e.javaClass.simpleName}")
        false
    }
}

/** A setup step the phone still needs before reminders arrive reliably. */
enum class SetupProblem { NOTIFICATIONS_OFF, BATTERY_RESTRICTED }

/** Pure decisions about what the home screen shows. */
object HomeRules {
    /** Whether a Reconnect action helps: only when the stream is down, not while it is fine or already trying. */
    fun canReconnect(state: StreamState): Boolean =
        state == StreamState.STOPPED || state == StreamState.RECONNECTING

    /** The setup problems to show, most important first; empty when the phone is ready. */
    fun setupProblems(notificationsOn: Boolean, batteryUnrestricted: Boolean): List<SetupProblem> = buildList {
        if (!notificationsOn) add(SetupProblem.NOTIFICATIONS_OFF)
        if (!batteryUnrestricted) add(SetupProblem.BATTERY_RESTRICTED)
    }

    /** Whether the pairing code named a PC label that had to be changed because another PC uses it. */
    fun labelWasChanged(requestedName: String?, label: String): Boolean =
        requestedName != null && requestedName != label
}
