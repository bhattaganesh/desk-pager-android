package np.com.ganeshbhatt.deskpager

import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.os.PowerManager
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private val ScreenPadding = 16.dp
private val CardPadding = 16.dp
private val SectionGap = 16.dp
private val ItemGap = 8.dp
private val LargeButtonHeight = 56.dp
private val RowMinHeight = 56.dp
private val StatusIconSize = 28.dp
private const val MILLIS_PER_SECOND = 1000L

/** What the screen asks the Activity to do, because only an Activity can do it. */
class HostActions(
    val scan: () -> Unit,
    val askForNotifications: () -> Unit,
    val openNotificationSettings: () -> Unit,
    val allowBackgroundUse: () -> Unit,
)

/**
 * The whole app: onboarding while no PC is paired, the home screen afterwards, and the dialogs over either.
 *
 * [pendingLink] is a pairing link opened from outside the app; it is only ever acted on through its dialog.
 * [resumeCount] changes whenever the user comes back to the app, so settings changed elsewhere are re-read.
 */
@Composable
fun DeskPagerApp(
    pendingLink: PendingLink?,
    onLinkHandled: () -> Unit,
    resumeCount: Int,
    versionName: String,
    host: HostActions,
) {
    val context = LocalContext.current
    val storeChanges by AppEvents.storeChanges.collectAsState()
    val notice by AppEvents.notice.collectAsState()
    val pcs = remember(storeChanges) { Store.pcs(context) }
    var addingPc by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(pcs.isEmpty()) {
        if (pcs.isEmpty()) addingPc = false else host.askForNotifications()
    }

    val scan = {
        addingPc = false
        AppEvents.show(null)
        host.scan()
    }
    val pairCode = { code: String ->
        val result = Pairing.pairInput(context, code)
        if (result is PairResult.Paired || result is PairResult.AlreadyPaired) addingPc = false
    }

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(insets)
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(SectionGap),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (!addingPc) {
                notice?.let { NoticeBanner(it) }
            }
            if (pcs.isEmpty()) {
                Onboarding(onScan = scan, onPairCode = pairCode)
            } else {
                Home(
                    pcs = pcs,
                    storeChanges = storeChanges,
                    resumeCount = resumeCount,
                    onAddPc = {
                        AppEvents.show(null)
                        addingPc = true
                    },
                    host = host,
                )
            }
            Text(
                text = stringResource(R.string.version_label, versionName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (addingPc) {
        AlertDialog(
            onDismissRequest = { addingPc = false },
            title = { Text(stringResource(R.string.add_pc_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(ItemGap),
                ) {
                    notice?.let { NoticeBanner(it) }
                    PairingControls(onScan = scan, onPairCode = pairCode)
                }
            },
            confirmButton = {
                TextButton(onClick = { addingPc = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (pendingLink != null) {
        LinkDialog(
            decision = Pairing.decide(pcs, pendingLink.topic, pendingLink.name),
            pairedCount = pcs.size,
            onConfirm = {
                Pairing.pairConfirmed(context, pendingLink.topic, pendingLink.name)
                onLinkHandled()
            },
            onCancel = onLinkHandled,
        )
    }
}

@Composable
private fun Onboarding(onScan: () -> Unit, onPairCode: (String) -> Unit) {
    Text(
        text = stringResource(R.string.onboarding_intro),
        style = MaterialTheme.typography.titleMedium,
    )
    PairingControls(onScan = onScan, onPairCode = onPairCode)
}

/** The two ways to pair from inside the app: the scanner first, typing or pasting the code second. */
@Composable
private fun PairingControls(onScan: () -> Unit, onPairCode: (String) -> Unit) {
    val context = LocalContext.current
    var manual by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    val nothingToPaste = stringResource(R.string.error_clipboard_empty)

    Column(verticalArrangement = Arrangement.spacedBy(ItemGap)) {
        Button(
            onClick = onScan,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = LargeButtonHeight),
        ) {
            Icon(painterResource(R.drawable.ic_qr_scan), contentDescription = null)
            Spacer(Modifier.width(ItemGap))
            Text(stringResource(R.string.scan_qr), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = stringResource(R.string.scan_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!manual) {
            TextButton(onClick = { manual = true }) { Text(stringResource(R.string.enter_code)) }
        } else {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text(stringResource(R.string.code_field_label)) },
                supportingText = { Text(stringResource(R.string.code_field_hint)) },
                singleLine = true,
                // The password keyboard keeps the code out of suggestions and the typing history.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(ItemGap)) {
                OutlinedButton(
                    onClick = {
                        val pasted = clipboardText(context)
                        if (pasted == null) {
                            AppEvents.show(Notice(nothingToPaste, isError = true))
                        } else {
                            code = pasted
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.paste)) }
                Button(
                    onClick = { onPairCode(code) },
                    enabled = code.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.pair)) }
            }
        }
    }
}

private fun clipboardText(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
    return text?.takeIf { it.isNotEmpty() }
}

@Composable
private fun NoticeBanner(notice: Notice) {
    val colors = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (notice.isError) colors.errorContainer else colors.primaryContainer,
            contentColor = if (notice.isError) colors.onErrorContainer else colors.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = CardPadding, top = CardPadding, end = CardPadding)) {
            Text(
                text = notice.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
            TextButton(
                onClick = { AppEvents.show(null) },
                colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                modifier = Modifier.align(Alignment.End),
            ) { Text(stringResource(R.string.dismiss)) }
        }
    }
}

@Composable
private fun Home(
    pcs: List<PairedPc>,
    storeChanges: Int,
    resumeCount: Int,
    onAddPc: () -> Unit,
    host: HostActions,
) {
    val context = LocalContext.current
    val streamState by ListenerService.streamState.collectAsState()
    val alertActive by ListenerService.alertActive.collectAsState()
    val problems = remember(resumeCount, storeChanges) {
        HomeRules.setupProblems(
            notificationsOn = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
            batteryUnrestricted = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
        )
    }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var unpairing by rememberSaveable { mutableStateOf(false) }

    if (alertActive) {
        ActiveAlertCard(onStop = { ListenerService.acknowledgeBreak(context) })
    }
    ConnectionCard(streamState, onReconnect = { ListenerService.reconnect(context) })
    if (problems.isNotEmpty()) {
        SetupCard(problems, host)
    }
    ActivityCard(
        stats = remember(storeChanges) { Store.stats(context) },
        history = remember(storeChanges) { Store.recentBuzzes(context) },
    )
    PcsCard(pcs, onRemove = { removing = it.topic }, onAddPc = onAddPc)
    AlertSettingsCard(storeChanges)
    OutlinedButton(
        onClick = { unpairing = true },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.unpair_all)) }

    pcs.firstOrNull { it.topic == removing }?.let { pc ->
        ConfirmDialog(
            title = stringResource(R.string.remove_pc_title, pc.label),
            message = stringResource(R.string.remove_pc_message, pc.label, Ntfy.maskTopic(pc.topic)),
            confirmLabel = stringResource(R.string.remove_pc),
            onConfirm = {
                removing = null
                AppEvents.show(null)
                Store.removePc(context, pc.topic)
                AppEvents.storeChanged()
                if (Store.topic(context) == null) ListenerService.stop(context) else Pairing.startListener(context)
            },
            onCancel = { removing = null },
        )
    }
    if (unpairing) {
        ConfirmDialog(
            title = stringResource(R.string.unpair_title),
            message = stringResource(R.string.unpair_message),
            confirmLabel = stringResource(R.string.unpair_confirm),
            onConfirm = {
                unpairing = false
                AppEvents.show(null)
                ListenerService.stop(context)
                Store.setTopic(context, null)
                AppEvents.storeChanged()
            },
            onCancel = { unpairing = false },
        )
    }
}

@Composable
private fun SectionCard(title: String?, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(ItemGap)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            content()
        }
    }
}

@Composable
private fun ActiveAlertCard(onStop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = colors.errorContainer,
            contentColor = colors.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(ItemGap)) {
            Text(
                text = stringResource(R.string.alert_active_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = LargeButtonHeight),
            ) { Text(stringResource(R.string.stop_alert), style = MaterialTheme.typography.titleLarge) }
        }
    }
}

@Composable
private fun ConnectionCard(state: StreamState, onReconnect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (icon, text) = when (state) {
        StreamState.CONNECTED -> R.drawable.ic_status_ok to R.string.status_connected
        StreamState.CONNECTING -> R.drawable.ic_status_sync to R.string.status_connecting
        StreamState.RECONNECTING -> R.drawable.ic_status_sync to R.string.status_reconnecting
        StreamState.STOPPED -> R.drawable.ic_status_problem to R.string.status_stopped
    }
    val (container, content) = when (state) {
        StreamState.CONNECTED -> colors.primaryContainer to colors.onPrimaryContainer
        StreamState.CONNECTING -> colors.secondaryContainer to colors.onSecondaryContainer
        StreamState.RECONNECTING -> colors.tertiaryContainer to colors.onTertiaryContainer
        StreamState.STOPPED -> colors.errorContainer to colors.onErrorContainer
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(ItemGap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(StatusIconSize))
                Spacer(Modifier.width(ItemGap))
                Text(
                    text = stringResource(text),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (HomeRules.canReconnect(state)) {
                OutlinedButton(
                    onClick = onReconnect,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = content),
                ) { Text(stringResource(R.string.reconnect)) }
            }
        }
    }
}

@Composable
private fun SetupCard(problems: List<SetupProblem>, host: HostActions) {
    SectionCard(stringResource(R.string.setup_title)) {
        for (problem in problems) {
            val (text, action, onClick) = when (problem) {
                SetupProblem.NOTIFICATIONS_OFF -> Triple(
                    R.string.setup_notifications, R.string.setup_notifications_action, host.openNotificationSettings,
                )
                SetupProblem.BATTERY_RESTRICTED -> Triple(
                    R.string.setup_battery, R.string.setup_battery_action, host.allowBackgroundUse,
                )
            }
            Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onClick) { Text(stringResource(action)) }
        }
    }
}

@Composable
private fun ActivityCard(stats: BuzzStats, history: List<LastBuzz>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionCard(stringResource(R.string.activity_title)) {
        val latest = history.firstOrNull()
        if (latest == null) {
            Text(stringResource(R.string.last_reminder_none), style = MaterialTheme.typography.bodyLarge)
        } else {
            ReminderLine(latest)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(SectionGap)) {
            Counter(stats.quickStretches, R.string.stat_stretches_label, Modifier.weight(1f))
            Counter(stats.longBreaks, R.string.stat_long_breaks_label, Modifier.weight(1f))
        }
        val older = history.drop(1)
        if (older.isNotEmpty()) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    if (expanded) {
                        stringResource(R.string.history_hide)
                    } else {
                        stringResource(R.string.history_show, older.size)
                    },
                )
                Icon(
                    painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                    contentDescription = null,
                )
            }
            if (expanded) {
                for (item in older) {
                    HorizontalDivider()
                    ReminderLine(item)
                }
            }
        }
    }
}

@Composable
private fun ReminderLine(buzz: LastBuzz) {
    val context = LocalContext.current
    val time = remember(buzz.timeSecs) {
        DateUtils.formatDateTime(
            context,
            buzz.timeSecs * MILLIS_PER_SECOND,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
        )
    }
    Column {
        Text(buzz.title, style = MaterialTheme.typography.titleSmall)
        if (buzz.text != buzz.title) {
            Text(buzz.text, style = MaterialTheme.typography.bodyMedium)
        }
        Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Counter(count: Int, labelRes: Int, modifier: Modifier) {
    Column(modifier) {
        Text(
            String.format(LocalLocale.current.platformLocale, "%d", count),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PcsCard(pcs: List<PairedPc>, onRemove: (PairedPc) -> Unit, onAddPc: () -> Unit) {
    SectionCard(stringResource(R.string.pcs_title)) {
        for (pc in pcs) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pc.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        Ntfy.maskTopic(pc.topic),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onRemove(pc) }) {
                    Icon(
                        painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.remove_pc_description, pc.label),
                    )
                }
            }
        }
        if (pcs.size < PcList.MAX_PCS) {
            OutlinedButton(onClick = onAddPc) { Text(stringResource(R.string.add_pc)) }
        }
    }
}

private fun styleLabel(style: AlertStyle): Int = when (style) {
    AlertStyle.QUICK -> R.string.alert_style_quick
    AlertStyle.VIBRATE_UNTIL_STOPPED -> R.string.alert_style_vibrate
    AlertStyle.RING_UNTIL_STOPPED -> R.string.alert_style_ring
}

@Composable
private fun AlertSettingsCard(storeChanges: Int) {
    val context = LocalContext.current
    var choosing by rememberSaveable { mutableStateOf<BuzzKind?>(null) }
    SectionCard(stringResource(R.string.alerts_title)) {
        AlertSettingRow(
            label = stringResource(R.string.stretch_style_label),
            style = remember(storeChanges) { Store.alertStyle(context, BuzzKind.STRETCH) },
            tryDescription = stringResource(R.string.try_stretch_description),
            onChoose = { choosing = BuzzKind.STRETCH },
            onTry = { ListenerService.triggerLocalTest(context) },
        )
        HorizontalDivider()
        AlertSettingRow(
            label = stringResource(R.string.long_break_style_label),
            style = remember(storeChanges) { Store.alertStyle(context, BuzzKind.LONG_BREAK) },
            tryDescription = stringResource(R.string.try_long_break_description),
            onChoose = { choosing = BuzzKind.LONG_BREAK },
            onTry = { ListenerService.triggerStrictTest(context) },
        )
    }
    choosing?.let { kind ->
        StylePickerDialog(
            title = stringResource(
                if (kind == BuzzKind.LONG_BREAK) R.string.long_break_style_label else R.string.stretch_style_label,
            ),
            current = Store.alertStyle(context, kind),
            onPick = { style ->
                Store.setAlertStyle(context, kind, style)
                AppEvents.storeChanged()
                choosing = null
            },
            onCancel = { choosing = null },
        )
    }
}

@Composable
private fun AlertSettingRow(
    label: String,
    style: AlertStyle,
    tryDescription: String,
    onChoose: () -> Unit,
    onTry: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = RowMinHeight)
                .clickable(role = Role.Button, onClick = onChoose),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(styleLabel(style)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onTry,
            modifier = Modifier.semantics { contentDescription = tryDescription },
        ) { Text(stringResource(R.string.try_it)) }
    }
}

@Composable
private fun StylePickerDialog(
    title: String,
    current: AlertStyle,
    onPick: (AlertStyle) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            ) {
                for (style in AlertStyle.entries) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = RowMinHeight)
                            .selectable(
                                selected = style == current,
                                role = Role.RadioButton,
                                onClick = { onPick(style) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = style == current, onClick = null)
                        Spacer(Modifier.width(ItemGap))
                        Text(stringResource(styleLabel(style)), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(message, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The only way a pairing link from outside the app can change anything: the user reads this and confirms. */
@Composable
private fun LinkDialog(decision: PairResult, pairedCount: Int, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val details = when (decision) {
        is PairResult.Paired -> listOfNotNull(
            stringResource(R.string.link_dialog_add, decision.label, Ntfy.maskTopic(decision.topic), pairedCount),
            decision.requestedName?.takeIf { HomeRules.labelWasChanged(it, decision.label) }?.let {
                stringResource(R.string.link_dialog_label_changed, it, decision.label)
            },
        )
        is PairResult.AlreadyPaired -> listOf(
            stringResource(R.string.link_dialog_already_paired, decision.label, Ntfy.maskTopic(decision.topic)),
        )
        PairResult.CapReached -> listOf(stringResource(R.string.link_dialog_full, pairedCount))
        PairResult.Invalid -> listOf(stringResource(R.string.error_bad_link))
    }
    val canAdd = decision is PairResult.Paired
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.link_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ItemGap),
            ) {
                Text(stringResource(R.string.link_dialog_intro))
                for (line in details) {
                    Text(line)
                }
            }
        },
        confirmButton = {
            if (canAdd) {
                TextButton(onClick = onConfirm) { Text(stringResource(R.string.add_this_pc)) }
            } else {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = {
            if (canAdd) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
