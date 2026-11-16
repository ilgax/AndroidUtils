package dev.ilgax.utils.speaker

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.ilgax.utils.core.CardMode
import dev.ilgax.utils.core.R as CoreR
import dev.ilgax.utils.core.asString
import dev.ilgax.utils.core.shizuku.ShizukuState
import dev.ilgax.utils.core.shizuku.ShizukuStatus
import dev.ilgax.utils.core.ui.components.DetailScaffold
import dev.ilgax.utils.core.ui.components.DiagnosticsBlock
import dev.ilgax.utils.core.ui.components.HeroSection
import dev.ilgax.utils.core.ui.components.SettingsGroup
import dev.ilgax.utils.core.ui.components.SettingsRow
import dev.ilgax.utils.core.ui.components.ShizukuBanner
import dev.ilgax.utils.core.ui.components.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class SpeakerUiState(
    val enabled: Boolean,
    val pauseOnDisconnect: Boolean,
    val shizuku: ShizukuStatus,
    val statusLine: String,
    val diagnostics: String,
    val redirectOn: Boolean = false,
    val redirectStatus: String = "",
    val wiredOn: Boolean = false,
    val wiredStatus: String = "",
    /** The "Mute on speaker" list: package name and label. */
    val muteList: List<MuteApp> = emptyList(),
)

data class MuteApp(val packageName: String, val label: String)

data class SpeakerActions(
    val onEnabledChange: (Boolean) -> Unit,
    val onPauseChange: (Boolean) -> Unit,
    val onReapply: () -> Unit,
    val onRedirectChange: (Boolean) -> Unit = {},
    val onWiredChange: (Boolean) -> Unit = {},
    val onMuteAdd: (String) -> Unit = {},
    val onMuteRemove: (String) -> Unit = {},
    val loadApps: suspend () -> List<MuteApp> = { emptyList() },
)

/** Stateful wrapper: wires [SpeakerScreen] to the controller and polls diagnostics while visible. */
@Composable
internal fun SpeakerDetail(contentPadding: PaddingValues, modifier: Modifier) {
    val context = LocalContext.current.applicationContext
    val enabled by SpeakerController.enabledFlow.collectAsStateWithLifecycle()
    val statusLine by SpeakerController.statusFlow.collectAsStateWithLifecycle()
    val shizuku by ShizukuState.status.collectAsStateWithLifecycle()
    var pause by remember { mutableStateOf(SpeakerController.pauseOnDisconnect(context)) }
    var diagnostics by remember { mutableStateOf("") }
    var redirectOn by remember { mutableStateOf(SpeakerExperiments.isRedirectOn(context)) }
    var wiredOn by remember { mutableStateOf(SpeakerExperiments.isWiredOn(context)) }
    var muteList by remember { mutableStateOf(muteApps(context)) }
    val redirectStatus by SpeakerExperiments.redirectStatus.collectAsStateWithLifecycle()
    val wiredStatus by SpeakerExperiments.wiredStatus.collectAsStateWithLifecycle()

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            SpeakerController.retryPendingRelease(context)
            while (true) {
                ShizukuState.refresh()
                diagnostics = withContext(Dispatchers.IO) { SpeakerController.diagnostics(context) }
                delay(2000)
            }
        }
    }

    SpeakerScreen(
        state = SpeakerUiState(enabled, pause, shizuku, statusLine, diagnostics, redirectOn, redirectStatus, wiredOn, wiredStatus, muteList),
        actions = SpeakerActions(
            onEnabledChange = { SpeakerController.setEnabled(context, it) },
            onPauseChange = { pause = it; SpeakerController.setPauseOnDisconnect(context, it) },
            onRedirectChange = { redirectOn = it; SpeakerExperiments.setRedirect(context, it) },
            onWiredChange = { wiredOn = it; SpeakerExperiments.setWired(context, it) },
            onMuteAdd = { SpeakerController.setMuteListed(context, it, true); muteList = muteApps(context) },
            onMuteRemove = { SpeakerController.setMuteListed(context, it, false); muteList = muteApps(context) },
            loadApps = { withContext(Dispatchers.IO) { launchableApps(context) } },
            onReapply = {
                SpeakerController.log(context, "manual re-apply")
                SpeakerController.enforce(context, "manual", true)
            },
        ),
        contentPadding = contentPadding,
        modifier = modifier,
    )
}

@Composable
fun SpeakerScreen(
    state: SpeakerUiState,
    actions: SpeakerActions,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    DetailScaffold(contentPadding, modifier) {
        ShizukuBanner(state.shizuku)
        HeroSection(
            iconRes = R.drawable.ic_speaker_off,
            headline = SpeakerText.headline(state.enabled).asString(),
            supporting = SpeakerText.supporting(state.enabled, state.shizuku).asString(),
            mode = if (state.enabled) CardMode.On else CardMode.Off,
            switchLabel = stringResource(R.string.speaker_title),
            onCheckedChange = actions.onEnabledChange,
        )
        SettingsGroup(title = stringResource(R.string.speaker_group_options)) {
            SwitchRow(
                title = stringResource(R.string.speaker_pause_title),
                supporting = stringResource(R.string.speaker_pause_summary),
                checked = state.pauseOnDisconnect,
                onCheckedChange = actions.onPauseChange,
                iconRes = CoreR.drawable.ic_pause,
            )
        }
        MuteListGroup(state.muteList, actions)
        SettingsGroup(title = stringResource(R.string.speaker_group_experimental)) {
            SwitchRow(
                title = stringResource(R.string.speaker_exp_redirect_title),
                supporting = experimentSupporting(stringResource(R.string.speaker_exp_redirect_summary), state.redirectStatus),
                checked = state.redirectOn,
                onCheckedChange = actions.onRedirectChange,
                iconRes = CoreR.drawable.ic_shield,
            )
            SwitchRow(
                title = stringResource(R.string.speaker_exp_wired_title),
                supporting = experimentSupporting(stringResource(R.string.speaker_exp_wired_summary), state.wiredStatus),
                checked = state.wiredOn,
                onCheckedChange = actions.onWiredChange,
                iconRes = CoreR.drawable.ic_tune,
            )
        }
        SettingsGroup(title = stringResource(R.string.speaker_group_status)) {
            SettingsRow(
                label = stringResource(R.string.speaker_shizuku_label),
                value = SpeakerText.shizukuLabel(state.shizuku).asString(),
                iconRes = CoreR.drawable.ic_info,
            )
            SettingsRow(
                label = stringResource(R.string.speaker_service_label),
                value = state.statusLine,
                iconRes = CoreR.drawable.ic_check,
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = actions.onReapply, enabled = state.enabled) {
                Text(stringResource(R.string.speaker_reapply))
            }
        }
        DiagnosticsBlock(state.diagnostics)
        Text(
            stringResource(R.string.speaker_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** The toggle's subtitle: what it does, then how it is going (including why it cannot work). */
internal fun experimentSupporting(summary: String, status: String) = if (status.isBlank()) summary else "$summary\n$status"

private fun muteApps(ctx: Context): List<MuteApp> {
    val pm = ctx.packageManager
    return SpeakerController.mutePackages(ctx).map { pkg ->
        val label = try { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() } catch (_: Throwable) { pkg }
        MuteApp(pkg, label)
    }
}

private fun launchableApps(ctx: Context): List<MuteApp> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val list = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
    return list.map { MuteApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { it.packageName != ctx.packageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

@Composable
private fun MuteListGroup(apps: List<MuteApp>, actions: SpeakerActions) {
    var picking by remember { mutableStateOf(false) }
    Column {
        SettingsGroup(title = stringResource(R.string.speaker_group_mute_list)) {
            apps.forEach { app ->
                SettingsRow(
                    label = app.label,
                    value = app.packageName,
                    trailing = {
                        TextButton(onClick = { actions.onMuteRemove(app.packageName) }) {
                            Text(stringResource(R.string.speaker_mute_list_remove))
                        }
                    },
                )
            }
            SettingsRow(label = stringResource(R.string.speaker_mute_list_add), value = "", onClick = { picking = true })
        }
        Text(
            stringResource(R.string.speaker_mute_list_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
    if (picking) {
        val candidates by produceState<List<MuteApp>?>(null) { value = actions.loadApps() }
        var query by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.speaker_mute_list_pick)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.speaker_mute_list_search)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val listed = apps.map { it.packageName }.toSet()
                    val shown = candidates?.filter { it.packageName !in listed && it.label.contains(query.trim(), ignoreCase = true) }
                    if (shown == null) {
                        CircularProgressIndicator(Modifier.padding(16.dp))
                    } else {
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(shown, key = { it.packageName }) { app ->
                                Column(Modifier.fillMaxWidth().clickable { actions.onMuteAdd(app.packageName); picking = false }.padding(vertical = 8.dp)) {
                                    Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.speaker_mute_list_close)) } },
        )
    }
}
