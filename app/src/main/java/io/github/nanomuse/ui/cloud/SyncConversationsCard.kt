package io.github.nanomuse.ui.cloud

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import io.github.nanomuse.sync.ConversationSync
import io.github.nanomuse.sync.SyncException
import io.github.nanomuse.ui.muse.MuseCaption
import io.github.nanomuse.ui.muse.MuseCard
import io.github.nanomuse.ui.muse.MuseGap
import io.github.nanomuse.ui.muse.MuseRow
import io.github.nanomuse.ui.muse.MuseRowDivider
import kotlinx.coroutines.launch

/**
 * Data controls → the conversations synced between the account's devices (contract C7): the
 * switch, what it means in one line, and the way to delete what nanoMuse Cloud keeps. Signed
 * out there is nothing to sync: the switch is off and disabled, the caption says to sign in.
 */
@Composable
fun SyncConversationsCard(signedIn: Boolean, onSignIn: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by ConversationSync.enabled.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // the relay's word on the switch, once, so a change made on another device shows here
    LaunchedEffect(signedIn) {
        if (signedIn) runCatching { ConversationSync.refreshState(context) }
    }

    fun describe(e: Throwable): String = when {
        e is SyncException && e.status == 0 -> context.getString(R.string.nm_cloud_err_unreachable)
        e is SyncException && e.status == 401 -> context.getString(R.string.nm_cloud_err_bad_key)
        else -> e.message ?: context.getString(R.string.nm_cloud_err_generic)
    }

    fun flip(on: Boolean) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                ConversationSync.setEnabled(context, on)
                notice = context.getString(if (on) R.string.nm_sync_on_toast else R.string.nm_sync_off_toast)
            } catch (e: Exception) {
                error = describe(e)
            }
            busy = false
        }
    }

    val on = signedIn && enabled
    MuseCard {
        MuseRow(
            title = stringResource(R.string.nm_sync_title),
            chevron = false,
            onClick = { if (signedIn) flip(!on) else onSignIn() },
            trailing = { Switch(checked = on, enabled = !busy && signedIn, onCheckedChange = { flip(it) }) },
        )
    }
    MuseCaption(
        text = buildString {
            append(stringResource(R.string.nm_sync_caption))
            if (!signedIn) append(' ').append(stringResource(R.string.nm_sync_sign_in))
        },
    )
    if (on) {
        // contract C9: main first — side chats stay on this phone unless asked for (per device, off by default)
        val sideChats by ConversationSync.sideChats.collectAsState()
        MuseGap()
        MuseCard {
            MuseRow(
                title = stringResource(R.string.nm_sync_side_title),
                chevron = false,
                onClick = { ConversationSync.setSideChats(context, !sideChats) },
                trailing = { Switch(checked = sideChats, enabled = !busy, onCheckedChange = { ConversationSync.setSideChats(context, it) }) },
            )
        }
        MuseCaption(text = stringResource(R.string.nm_sync_side_sub))
    }
    if (signedIn) {
        MuseGap()
        MuseCard {
            MuseRow(
                title = stringResource(R.string.nm_sync_delete),
                icon = Icons.Outlined.DeleteOutline,
                titleColor = MaterialTheme.colorScheme.error,
                chevron = false,
                onClick = { if (!busy) confirm = true },
            )
            MuseRowDivider(inset = 16.dp)
            Text(
                text = stringResource(R.string.nm_sync_delete_sub),
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

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.nm_sync_delete)) },
            text = { Text(stringResource(R.string.nm_sync_delete_confirm)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                ConversationSync.deleteSynced(context)
                                notice = context.getString(R.string.nm_sync_deleted)
                            } catch (e: Exception) {
                                error = describe(e)
                            }
                            busy = false
                            confirm = false
                        }
                    },
                ) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
