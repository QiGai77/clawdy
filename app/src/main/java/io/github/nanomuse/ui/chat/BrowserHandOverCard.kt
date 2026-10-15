package io.github.nanomuse.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.openBrowserSheetForUrl
import io.github.nanomuse.browser.BrowserHandOver
import io.github.nanomuse.ui.home.MuseTones

/**
 * "Your turn" above the composer while the browser is handed to the person: what the page asks
 * of them, Open (the agent's own tab, in the browser sheet) and Done (the agent continues from
 * the page as it is). Same shape as [ContinueAskHost]; the sheet shows a matching banner.
 */
@Composable
fun BrowserHandOverHost(viewModel: ChatViewModel) {
    val pending by BrowserHandOver.pending.collectAsState()
    AnimatedVisibility(
        visible = pending != null,
        enter = slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220)),
        exit = slideOutVertically(tween(160)) { it / 2 } + fadeOut(tween(160)),
    ) {
        val p = pending ?: return@AnimatedVisibility
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MuseTones.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .shadow(6.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.12f)),
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(MuseTones.fill, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Language, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.nm_handover_title),
                            fontSize = 17.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (p.url.isNotBlank()) {
                            Text(p.url, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (p.reason.isNotBlank()) stringResource(R.string.nm_handover_body, p.reason) else stringResource(R.string.nm_handover_body_generic),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    // The reason is the model's text; a paragraph must not push Open and Done off the screen.
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { viewModel.openBrowserSheetForUrl(p.url) },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MuseTones.action, contentColor = Color.White),
                ) { Text(stringResource(R.string.nm_handover_open), fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { BrowserHandOver.finish() },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MuseTones.fill, contentColor = MaterialTheme.colorScheme.onSurface),
                    elevation = null,
                ) { Text(stringResource(R.string.nm_handover_done), fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

/** The banner at the top of the browser sheet while a hand-over waits: one line and Done. */
@Composable
fun BrowserHandOverBanner(accent: Color) {
    val pending by BrowserHandOver.pending.collectAsState()
    val p = pending ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (p.reason.isNotBlank()) stringResource(R.string.nm_handover_banner, p.reason) else stringResource(R.string.nm_handover_banner_generic),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(10.dp))
        Button(
            onClick = { BrowserHandOver.finish() },
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) { Text(stringResource(R.string.nm_handover_done), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
    }
}
