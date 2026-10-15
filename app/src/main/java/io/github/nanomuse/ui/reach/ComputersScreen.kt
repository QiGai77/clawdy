package io.github.nanomuse.ui.reach

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.components.openExternalUrl
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import io.github.nanomuse.cloud.NanoMuseCloud
import io.github.nanomuse.hub.Hub
import io.github.nanomuse.reach.Computers
import io.github.nanomuse.ui.home.MuseTones
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

const val ROUTE_COMPUTERS = "nanomuse/computers"
const val DESKTOP_DOWNLOAD_URL = "https://nanomuse.cn/#download"

/**
 * Settings → Computers: the account's computers, as the hub knows them. One way in — nanoMuse
 * Desktop on the computer, signed in to the same account — so the page says which account this
 * phone uses (the usual reason a computer is missing is that it signed in to another one), lists
 * the computers with their system and when they were last seen, and a tap checks that one answers.
 */
@Composable
fun ComputersScreen(onBack: () -> Unit, onOpenAccount: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val devices by Hub.devices.collectAsState()
    val me = remember { Hub.deviceId(context) }
    val computers = devices.filter { it.id != me && it.isComputer }
    val signedIn by NanoMuseCloud.signedIn(context).collectAsState()
    val hint = remember(signedIn) { NanoMuseCloud.account(context)?.hint.orEmpty() }
    val checks = remember { mutableStateMapOf<String, String>() }

    SettingsScaffold(title = stringResource(R.string.nm_pc_title), onBack = onBack) {
        Text(
            text = stringResource(R.string.nm_pc_intro),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp),
        )

        // ── the one way in: the desktop app on the same account ──
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MuseTones.action.copy(alpha = 0.08f),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.nm_pc_hub_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.nm_pc_hub_body),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = if (signedIn == true && hint.isNotBlank()) stringResource(R.string.nm_pc_account_hint, hint) else stringResource(R.string.nm_pc_account_none),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (signedIn == true) MuseTones.action else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Button(
                        onClick = { openExternalUrl(context, DESKTOP_DOWNLOAD_URL) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MuseTones.action),
                        modifier = Modifier.weight(1f),
                    ) {
                        androidx.compose.material3.Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text(stringResource(R.string.nm_pc_hub_download), maxLines = 1)
                    }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = onOpenAccount, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                        androidx.compose.material3.Icon(Icons.Outlined.Devices, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text(stringResource(R.string.nm_pc_hub_account), maxLines = 1)
                    }
                }
            }
        }

        // ── the account's computers ──
        SettingsSection(
            header = stringResource(R.string.nm_pc_section_paired),
            footer = if (computers.isEmpty()) stringResource(R.string.nm_pc_none_footer) else stringResource(R.string.nm_pc_paired_footer),
        ) {
            if (computers.isEmpty()) {
                SettingsRow(title = stringResource(R.string.nm_pc_none), showDivider = false)
            }
            computers.forEachIndexed { i, d ->
                val seen = if (d.lastSeen > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(d.lastSeen)) else "·"
                val state = stringResource(if (d.online) R.string.nm_pc_online else R.string.nm_pc_offline)
                SettingsRow(
                    title = d.name,
                    subtitle = checks[d.id] ?: listOf(d.os.ifBlank { "?" }, state, stringResource(R.string.nm_pc_last_seen, seen)).joinToString(" · "),
                    icon = Icons.Outlined.Computer,
                    iconColor = if (d.online) MuseTones.action else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = {
                        checks[d.id] = context.getString(R.string.nm_pc_checking)
                        scope.launch {
                            val c = Computers.hubDevices(context, computersOnly = true).firstOrNull { it.hubId == d.id }
                            val up = c != null && withContext(Dispatchers.IO) { Computers.reachable(c) }
                            checks[d.id] = context.getString(if (up) R.string.nm_pc_answering else R.string.nm_pc_not_answering, d.name)
                        }
                    },
                    showChevron = false,
                    showDivider = i < computers.lastIndex,
                    minHeight = 64.dp,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
