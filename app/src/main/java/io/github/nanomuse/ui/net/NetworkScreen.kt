package io.github.nanomuse.ui.net

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import io.github.nanomuse.net.OwnProviderProxy
import io.github.nanomuse.net.ProviderReach
import io.github.nanomuse.ui.home.MuseTones
import io.github.nanomuse.ui.muse.MuseCaption
import io.github.nanomuse.ui.muse.MuseCard
import io.github.nanomuse.ui.muse.MuseGap
import io.github.nanomuse.ui.muse.MuseRow
import io.github.nanomuse.ui.muse.MuseRowDivider
import io.github.nanomuse.ui.muse.MuseTopAppBar
import kotlinx.coroutines.launch

const val ROUTE_NETWORK = "nanomuse_network"

/** What the Test row fetches: the ChatGPT plan's host, the one people most often cannot reach. */
private const val TEST_URL = "https://chatgpt.com/"

/**
 * Settings → Network: the HTTP proxy for own providers and the ChatGPT plan
 * ([OwnProviderProxy]) — one switch, host, port, an optional user name and password, saved as
 * typed, and a *Test* row that fetches `https://chatgpt.com/` through the proxy as entered
 * and says what came back. The caption names what goes through it and what never does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cfg by remember { mutableStateOf(OwnProviderProxy.config(context)) }
    var portText by remember { mutableStateOf(if (cfg.port > 0) cfg.port.toString() else "") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(false) }

    fun persist(next: OwnProviderProxy.Config) {
        cfg = next
        OwnProviderProxy.save(context, next)
    }

    fun test() {
        if (testing) return
        val probeCfg = cfg
        if (probeCfg.host.isBlank() || probeCfg.port !in 1..65535) {
            result = context.getString(R.string.nm_network_invalid)
            resultOk = false
            return
        }
        testing = true
        result = null
        scope.launch {
            val p = OwnProviderProxy.probe(probeCfg, TEST_URL)
            resultOk = p.ok
            result = if (p.ok) {
                context.getString(R.string.nm_network_test_ok, p.host, p.status, p.millis)
            } else {
                val why = when (p.failure?.kind) {
                    ProviderReach.Kind.REGION_BLOCKED -> context.getString(R.string.nm_reach_region_title)
                    null -> context.getString(R.string.nm_network_test_status, p.status)
                    else -> p.failure.detail.ifBlank { context.getString(R.string.nm_network_test_status, p.status) }
                }
                context.getString(R.string.nm_network_test_fail, p.host, why)
            }
            testing = false
        }
    }

    Scaffold(
        containerColor = MuseTones.canvas,
        topBar = {
            MuseTopAppBar(
                title = { Text(stringResource(R.string.nm_network_title)) },
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
            MuseCaption(stringResource(R.string.nm_network_proxy_section))
            MuseCard {
                MuseRow(
                    title = stringResource(R.string.nm_network_proxy_switch),
                    chevron = false,
                    onClick = { persist(cfg.copy(enabled = !cfg.enabled)) },
                    trailing = { Switch(checked = cfg.enabled, onCheckedChange = { persist(cfg.copy(enabled = it)) }) },
                )
                MuseRowDivider(inset = 16.dp)
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = cfg.host,
                            onValueChange = { persist(cfg.copy(host = it.trim())) },
                            label = { Text(stringResource(R.string.nm_network_host)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { t ->
                                val digits = t.filter(Char::isDigit).take(5)
                                portText = digits
                                persist(cfg.copy(port = digits.toIntOrNull() ?: 0))
                            },
                            label = { Text(stringResource(R.string.nm_network_port)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(104.dp),
                        )
                    }
                    MuseGap(10.dp)
                    OutlinedTextField(
                        value = cfg.user,
                        onValueChange = { persist(cfg.copy(user = it)) },
                        label = { Text(stringResource(R.string.nm_network_user)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MuseGap(10.dp)
                    OutlinedTextField(
                        value = cfg.password,
                        onValueChange = { persist(cfg.copy(password = it)) },
                        label = { Text(stringResource(R.string.nm_network_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                MuseRowDivider(inset = 16.dp)
                MuseRow(
                    title = stringResource(R.string.nm_network_test),
                    value = if (testing) null else stringResource(R.string.nm_network_test_target, "chatgpt.com"),
                    chevron = false,
                    titleColor = MuseTones.action,
                    onClick = { test() },
                    trailing = { if (testing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) },
                )
                result?.let { r ->
                    Text(
                        r,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = if (resultOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
            MuseCaption(stringResource(R.string.nm_network_proxy_caption))
            val hosts = remember { OwnProviderProxy.routedHosts().sorted() }
            if (hosts.isNotEmpty()) {
                MuseCaption(stringResource(R.string.nm_network_hosts_caption, hosts.joinToString(", ")))
            }
            MuseGap(24.dp)
        }
    }
}
