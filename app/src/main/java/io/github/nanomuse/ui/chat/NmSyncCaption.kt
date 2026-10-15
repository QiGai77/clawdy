package io.github.nanomuse.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import io.github.nanomuse.sync.ConversationSync

/**
 * "From Pixel 8" over a turn that was written on another device of the account (contract C8:
 * the caption is per message; a chat carries no badge). Nothing for this phone's own rows.
 * [messageId] is the database row's id; [end] puts it on the person's side of the chat.
 */
@Composable
fun NmSyncCaption(messageId: String, end: Boolean, modifier: Modifier = Modifier) {
    val captions by ConversationSync.captions.collectAsState()
    val device = captions[messageId] ?: return
    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        Text(
            text = stringResource(R.string.nm_sync_from, device),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(if (end) Alignment.CenterEnd else Alignment.CenterStart),
        )
    }
}
