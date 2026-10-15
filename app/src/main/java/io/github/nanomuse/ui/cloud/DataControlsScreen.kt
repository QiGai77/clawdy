package io.github.nanomuse.ui.cloud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.components.openExternalUrl
import io.github.nanomuse.cloud.NanoMuseCloud
import io.github.nanomuse.ui.home.MuseTones
import io.github.nanomuse.ui.muse.MuseCaption
import io.github.nanomuse.ui.muse.MuseCard
import io.github.nanomuse.ui.muse.MuseGap
import io.github.nanomuse.ui.muse.MuseRow
import io.github.nanomuse.ui.muse.MuseRowDivider
import io.github.nanomuse.ui.muse.MuseTopAppBar
import kotlinx.coroutines.launch

const val ROUTE_DATA_CONTROLS = "nanomuse_data_controls"

/**
 * Settings → Data controls: the one switch over what nanoMuse Cloud keeps of this account's
 * chats — "Help improve nanoMuse's AI models" —, what exactly that is, how new accounts
 * start, the privacy policy, and the way to delete what was kept; below it, the switch over the
 * conversations synced between the account's devices ([SyncConversationsCard]). The shape of
 * Muse's own page of the same name; nothing here touches the allowance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataControlsScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf(NanoMuseCloud.account(context)) }
    val signedIn = remember { NanoMuseCloud.isSignedIn(context) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    // The relay's view is the truth: read it once so the count and the default are current.
    LaunchedEffect(Unit) {
        if (signedIn) runCatching { account = NanoMuseCloud.refresh(context) }
    }

    fun flip(on: Boolean) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                account = NanoMuseCloud.setContribute(context, on)
                notice = context.getString(if (on) R.string.nm_data_on_toast else R.string.nm_data_off_toast)
            } catch (e: Exception) {
                error = NanoMuseCloud.describe(context, e)
            }
            busy = false
        }
    }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text(stringResource(R.string.nm_data_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            val a = account
            val on = signedIn && a?.contribute == true
            MuseCard {
                MuseRow(
                    title = stringResource(R.string.nm_data_improve),
                    chevron = false,
                    onClick = { if (signedIn && a != null) flip(!on) else if (!signedIn) onSignIn() },
                    trailing = {
                        Switch(checked = on, enabled = !busy && signedIn && a != null, onCheckedChange = { flip(it) })
                    },
                )
            }
            val privacy = a?.privacyUrl?.takeIf { it.isNotBlank() } ?: NanoMuseCloud.PRIVACY_URL
            val caption = buildString {
                append(stringResource(R.string.nm_data_why))
                when {
                    !signedIn -> append(' ').append(stringResource(R.string.nm_data_sign_in))
                    a?.contributeDefaultOn == true -> append(' ').append(stringResource(R.string.nm_data_default_on))
                    a?.contributeDefaultOn == false -> append(' ').append(stringResource(R.string.nm_data_default_off))
                }
                if (signedIn && (a?.samples ?: 0) > 0) append(' ').append(stringResource(R.string.nm_data_kept, a!!.samples))
            }
            MuseCaption(text = caption)
            Text(
                text = stringResource(R.string.nm_data_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MuseTones.action,
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .clickable { openExternalUrl(context, privacy) }
                    .padding(vertical = 4.dp),
            )

            if (signedIn && (a?.samples ?: 0) > 0) {
                MuseGap()
                MuseCard {
                    MuseRow(
                        title = stringResource(R.string.nm_data_delete),
                        icon = Icons.Outlined.DeleteOutline,
                        titleColor = MaterialTheme.colorScheme.error,
                        value = stringResource(R.string.nm_data_turns, a!!.samples),
                        chevron = false,
                        onClick = { if (!busy) confirmDelete = true },
                    )
                    MuseRowDivider(inset = 16.dp)
                    Text(
                        text = stringResource(R.string.nm_data_delete_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            notice?.let { MuseCaption(text = it) }
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
            }

            // the conversations synced between the account's devices (contract C7)
            MuseGap(height = 20.dp)
            SyncConversationsCard(signedIn = signedIn, onSignIn = onSignIn)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.nm_data_delete)) },
            text = { Text(stringResource(R.string.nm_data_delete_confirm)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val n = NanoMuseCloud.deleteSamples(context)
                                notice = context.getString(R.string.nm_data_deleted, n)
                                account = NanoMuseCloud.account(context)
                            } catch (e: Exception) {
                                error = NanoMuseCloud.describe(context, e)
                            }
                            busy = false
                            confirmDelete = false
                        }
                    },
                ) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
