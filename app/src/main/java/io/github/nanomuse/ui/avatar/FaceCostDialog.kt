package io.github.nanomuse.ui.avatar

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import io.github.nanomuse.cloud.FaceCost
import io.github.nanomuse.cloud.NanoMuseCloud
import io.github.nanomuse.ui.home.MuseTones

/**
 * Before pictures are paid from the Cloud allowance: what the job costs, what is left
 * today, and a yes. The estimate is fetched while the dialog is up; "Draw it" waits for it
 * so nobody agrees to a number they have not seen. Not shown for a user's own key — the
 * caller checks [FaceCost.onCloud] first.
 */
@Composable
fun FaceCostDialog(job: FaceCost.Job, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var estimate by remember(job) { mutableStateOf<NanoMuseCloud.Estimate?>(null) }
    var loaded by remember(job) { mutableStateOf(false) }
    LaunchedEffect(job) {
        estimate = FaceCost.estimate(context, job)
        loaded = true
    }
    val blocked = FaceCost.blocked(estimate)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nm_face_cost_title)) },
        text = {
            if (!loaded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MuseTones.action)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.nm_face_cost_checking), fontSize = 14.sp)
                }
            } else {
                Text(FaceCost.describe(context, job, estimate), fontSize = 14.sp, lineHeight = 20.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = loaded) {
                Text(
                    stringResource(if (blocked) R.string.nm_face_cost_anyway else R.string.nm_face_cost_go),
                    color = if (blocked) MaterialTheme.colorScheme.onSurfaceVariant else MuseTones.action,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.nm_face_cost_not_now)) } },
    )
}
