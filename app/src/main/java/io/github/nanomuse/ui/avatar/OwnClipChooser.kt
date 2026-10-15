package io.github.nanomuse.ui.avatar

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.openminis.app.R
import io.github.nanomuse.avatar.OwnClip
import io.github.nanomuse.ui.home.MuseTones
import io.github.nanomuse.ui.home.avatarDiscColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picking a clip of one's own as the face (#324): the system's video picker, the checks, and
 * the preview sheet with the clip playing in the face's own frame, *Use* and *Cancel*. The
 * screen that offers it calls [OwnClipChooserState.choose] from a button or a menu and places
 * [OwnClipChooserHost] once, the way a sheet is placed.
 */
class OwnClipChooserState internal constructor() {
    internal var request by mutableIntStateOf(0)
    internal var mood by mutableStateOf<AgentMood?>(null)

    /** Open the picker. */
    fun choose(mood: AgentMood? = null) { this.mood = mood; request += 1 }
}

@Composable
fun rememberOwnClipChooser(): OwnClipChooserState = remember { OwnClipChooserState() }

/** The one sentence a refused clip gets, with the limits in it. */
internal fun refusalText(context: Context, why: OwnClip.Refusal, probe: OwnClip.Probe): String = when (why) {
    OwnClip.Refusal.FORMAT -> context.getString(R.string.nm_own_clip_refused_format)
    OwnClip.Refusal.TOO_LONG -> context.getString(R.string.nm_own_clip_refused_long, ((probe.durationMs ?: 0L) / 1000L).toInt())
    OwnClip.Refusal.TOO_LARGE -> context.getString(
        R.string.nm_own_clip_refused_large,
        // a null size: the copy stopped at the limit, so all that is known is "more than that"
        probe.bytes?.let { android.text.format.Formatter.formatShortFileSize(context, it) }
            ?: ("> " + android.text.format.Formatter.formatShortFileSize(context, OwnClip.MAX_BYTES)),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnClipChooserHost(state: OwnClipChooserState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<OwnClip.Pending?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { OwnClip.import(context, uri) }
            busy = false
            when (outcome) {
                // Every pending copy lives at the same path, and the import just replaced it:
                // discarding the old one here would delete the new file.
                is OwnClip.Outcome.Ready -> pending = outcome.pending
                is OwnClip.Outcome.Refused -> message = refusalText(context, outcome.why, outcome.probe)
                is OwnClip.Outcome.Failed -> message = context.getString(R.string.nm_own_clip_unreadable)
            }
        }
    }
    LaunchedEffect(state.request) {
        if (state.request > 0) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    if (busy) {
        Dialog(onDismissRequest = {}) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(MuseTones.surface), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = MuseTones.action)
            }
        }
    }

    val p = pending
    if (p != null) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val cancel = { OwnClip.discard(p); pending = null }
        ModalBottomSheet(onDismissRequest = cancel, sheetState = sheet, containerColor = MuseTones.canvas) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.nm_own_clip_preview_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.nm_own_clip_preview_body),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                // The same disc the studio shows the face on: the first frame, then the clip over it.
                Box(Modifier.size(148.dp).clip(CircleShape).background(avatarDiscColor()), contentAlignment = Alignment.Center) {
                    val poster = p.poster
                    if (poster != null) {
                        Image(
                            bitmap = poster.asImageBitmap(),
                            contentDescription = stringResource(R.string.nm_a11y_own_clip_preview),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(148.dp),
                        )
                    }
                    LoopingClip(key = "pending:" + p.file.path, modifier = Modifier.size(148.dp)) { it.setDataSource(p.file.path) }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(
                        R.string.nm_own_clip_details,
                        ((p.durationMs + 500L) / 1000L).toInt(),
                        android.text.format.Formatter.formatShortFileSize(context, p.bytes),
                    ),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        // Two renames and the poster write: off the main thread, with the busy
                        // disc up so Cancel cannot delete the file half-way through.
                        val selectedMood = state.mood
                        busy = true
                        scope.launch {
                            withContext(Dispatchers.IO) { OwnClip.adopt(p, selectedMood) }
                            busy = false
                            pending = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MuseTones.action, contentColor = Color.White),
                ) {
                    Text(stringResource(R.string.nm_own_clip_use), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = cancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.nm_cancel), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.nm_own_clip_local),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    val m = message
    if (m != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(android.R.string.ok)) } },
            title = { Text(stringResource(R.string.nm_own_clip_title)) },
            text = { Text(m) },
        )
    }
}
