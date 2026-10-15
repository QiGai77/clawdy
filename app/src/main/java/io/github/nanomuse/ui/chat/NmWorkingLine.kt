package io.github.nanomuse.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import io.github.nanomuse.sync.ConversationSync
import io.github.nanomuse.sync.RemoteRows
import kotlinx.coroutines.delay

/**
 * "kwai is working…" under the last line of the chat, while the device that wrote that line
 * is still on it (contract C9). Shown only when the last row came from another device and
 * the relay's presence says that device is working on this chat, less than ten minutes ago;
 * gone when the reply lands, when that device says it is done, or when the ten minutes are
 * up. The same muted style as the "From kwai" caption, with a small pulsing dot. Nothing is
 * drawn otherwise — in particular never for this phone's own last line.
 */
@Composable
fun NmWorkingLine(sessionId: String, lastMessageId: String?, modifier: Modifier = Modifier) {
    val working by ConversationSync.working.collectAsState()
    val remoteRows by ConversationSync.remoteRows.collectAsState()
    val presence = working[sessionId] ?: return
    val writer = remoteRows[lastMessageId?.substringBefore('#') ?: return] ?: return
    if (writer != presence.from) return
    // the ten minutes run out without any new frame: look at the clock now and then
    val now by produceState(System.currentTimeMillis() / 1000, presence) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    if (!presence.live(now)) return
    val name = presence.deviceName.ifBlank { RemoteRows.deviceOf(lastMessageId) ?: return }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val pulse = rememberInfiniteTransition(label = "nm-working")
    val alpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "nm-working-dot",
    )
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(6.dp).alpha(alpha).background(muted, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.nm_sync_working, name),
            fontSize = 11.sp,
            color = muted,
        )
    }
}
