package io.github.nanomuse.oauth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ProviderType
import com.openminis.app.ui.components.MinisSmallButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long a sign-in may wait for the browser before the paste fallback is offered. */
private const val SHOW_AFTER_MS = 10_000L

/**
 * Under a provider's *Sign in* button while the sign-in waits for the browser (#441). The
 * browser is sent to `http://localhost:<port>/…`; a proxy or VPN on the phone can route that
 * into its tunnel and show an error page instead, and the app would wait for ever. After
 * [SHOW_AFTER_MS] without the callback (at once when the system proxy settings would take
 * the loopback address) this shows one sentence, a box to paste the page's address into and
 * *Continue*; the code goes to the waiting server without a proxy and the sign-in goes on
 * as if the browser had called. Providers without a loopback redirect (Kimi) show nothing.
 */
@Composable
fun OAuthCallbackRescue(active: Boolean, providerType: ProviderType, modifier: Modifier = Modifier) {
    val target = remember(providerType) { LoopbackCallback.target(providerType) } ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(active, target) {
        if (!active) {
            shown = false
            text = ""
            error = null
            sending = false
            return@LaunchedEffect
        }
        val proxied = withContext(Dispatchers.IO) { LoopbackCallback.proxied(target.port) }
        if (!proxied) delay(SHOW_AFTER_MS)
        shown = true
    }
    if (!active || !shown) return

    fun submit() {
        when (val parsed = CallbackUrlParser.parse(text)) {
            CallbackUrlParser.Result.NoCode -> error = context.getString(R.string.nm_oauth_rescue_no_code)
            is CallbackUrlParser.Result.Ok -> {
                error = null
                sending = true
                scope.launch {
                    val delivered = LoopbackCallback.deliver(target, parsed.callback)
                    sending = false
                    // On success the sign-in resumes and [active] turns false, which clears this.
                    if (!delivered) error = context.getString(R.string.nm_oauth_rescue_failed)
                }
            }
        }
    }

    Column(modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            stringResource(R.string.nm_oauth_rescue_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.nm_oauth_rescue_placeholder), style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 4,
            isError = error != null,
            enabled = !sending,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        )
        error?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))
        MinisSmallButton(onClick = { submit() }, enabled = text.isNotBlank() && !sending) {
            if (sending) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.nm_oauth_rescue_continue))
            }
        }
    }
}
