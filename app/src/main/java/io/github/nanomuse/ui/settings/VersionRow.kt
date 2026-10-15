package io.github.nanomuse.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.ui.components.openExternalUrl
import io.github.nanomuse.community.UpdateCheck
import io.github.nanomuse.ui.home.MuseTones

/** The second line under the installed version: checking, the latest, or that the check failed. */
@Composable
private fun statusLine(s: UpdateCheck.State): String = when {
    s.checking -> stringResource(R.string.nm_version_checking)
    s.failed || s.latest == null -> stringResource(R.string.nm_version_failed)
    s.newer -> stringResource(R.string.nm_version_out, s.latest.orEmpty())
    else -> stringResource(R.string.nm_version_have, s.latest.orEmpty())
}

/** The tap: off to the download page when a newer build is out, otherwise a fresh check. */
private fun onTap(context: Context, s: UpdateCheck.State) {
    if (s.newer) openExternalUrl(context, UpdateCheck.DOWNLOAD_URL) else if (!s.checking) UpdateCheck.checkNow(context)
}

/** Reads the kept state and runs the daily check the first time the row is on screen. */
@Composable
private fun rememberVersionState(): UpdateCheck.State {
    val context = LocalContext.current
    val state by UpdateCheck.state.collectAsState()
    LaunchedEffect(Unit) { UpdateCheck.refreshIfStale(context) }
    return state ?: UpdateCheck.load(context)
}

/**
 * Settings → About: "Version — nanoMuse 0.1.35 (36)" with the latest release under it and an
 * Update pill when a newer one is out. A Muse row, so it sits in the card with the others.
 */
@Composable
fun VersionRow() {
    val context = LocalContext.current
    val s = rememberVersionState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap(context, s) }
            .heightIn(min = 54.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.nm_version_title),
                fontSize = 16.sp,
                lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = statusLine(s),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (s.newer) {
            Surface(shape = CircleShape, color = MuseTones.action) {
                Text(
                    text = stringResource(R.string.nm_version_update),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        } else {
            Text(
                text = stringResource(R.string.nm_settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                fontSize = 14.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * The same two lines, centred, for the About page's head: the installed build, then the
 * latest (tap to check again, or to go and get it).
 */
@Composable
fun VersionLines(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val s = rememberVersionState()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.nm_settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (s.newer) stringResource(R.string.nm_version_out_update, s.latest.orEmpty()) else statusLine(s),
            style = MaterialTheme.typography.bodyMedium,
            color = if (s.newer) MuseTones.action else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.clickable { onTap(context, s) }.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
