package io.github.nanomuse.ui.models

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.navigation.safePopBackStack
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsSwitchRow
import io.github.nanomuse.cloud.NanoMuseCloud
import io.github.nanomuse.models.ModelSlots
import io.github.nanomuse.models.ModelSlots.Slot
import io.github.nanomuse.ui.home.MuseTones
import kotlinx.coroutines.launch

const val ROUTE_USE_IT_FOR = "nanomuse/models/use/{instanceId}"
fun useItForRoute(instanceId: String): String = "nanomuse/models/use/$instanceId"

/**
 * Where the card comes from. After the provider form saves (a new provider, or a key added
 * to one that had none), the form pops and the card opens for that provider — never for the
 * relay's own instance, and never for a provider the catalogue says can do nothing here.
 */
object UseItFor {
    /** The provider form saved: back, then the card for the instance that was not there before. */
    fun afterProviderSaved(nav: NavController, repo: ProviderRepository, before: Set<String>) {
        val added = repo.config.value.instances.filter { it.id !in before }.maxByOrNull { it.createdAt }
        nav.safePopBackStack()
        // plain navigate: the entry underneath is not RESUMED yet, which safeNavigate would take as "wait"
        added?.let { open(nav, it.id) }
    }

    /** Opens the card for [instanceId] when there is something to offer. */
    fun open(nav: NavController, instanceId: String) {
        val context = nav.context
        val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return
        val inst = repo.config.value.instances.firstOrNull { it.id == instanceId } ?: return
        if (NanoMuseCloud.instance(context)?.id == inst.id) return
        if (ModelSlots.offeredSlots(context, inst).isEmpty()) return
        nav.navigate(useItForRoute(instanceId))
    }
}

/**
 * The "Use it for" card (0.1.41 "Choice", contract section 2): one switch per capability the
 * provider has, all on; *Use it* switches the ticked slots to this provider (the catalogue's
 * `defaults.<slot>`, else its first model with the capability), *Not now* changes nothing.
 * Unticked slots stay as they were, so a key added for pictures alone leaves the chat alone.
 */
@Composable
fun UseItForScreen(instanceId: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull
    // observed, not read once: the card can open before the saved config has landed
    val config = repo?.config?.collectAsState()
    val inst = config?.value?.instances?.firstOrNull { it.id == instanceId }
    val offered = remember(inst) { inst?.let { ModelSlots.offeredSlots(context, it) }.orEmpty() }
    if (inst == null || offered.isEmpty()) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    // "nanoMuse Cloud keeps the rest" holds only while the Cloud is a model source
    val signedIn = remember { NanoMuseCloud.modelsOn(context) }
    val ticked = remember { mutableStateOf(offered.toSet()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    SettingsScaffold(title = stringResource(R.string.nm_useit_title), onBack = onDone) {
        Text(
            text = stringResource(if (signedIn) R.string.nm_useit_body else R.string.nm_useit_body_signed_out),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp),
        )
        SettingsSection(header = ModelSlots.providerLabel(context, inst)) {
            offered.forEachIndexed { i, slot ->
                SettingsSwitchRow(
                    title = stringResource(slotTitle(slot)),
                    subtitle = stringResource(slotSubtitle(slot)),
                    checked = slot in ticked.value,
                    onCheckedChange = { on -> ticked.value = if (on) ticked.value + slot else ticked.value - slot },
                    enabled = !busy,
                    showDivider = i < offered.lastIndex,
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            MinisButton(
                onClick = {
                    if (busy) return@MinisButton
                    busy = true
                    scope.launch {
                        runCatching { ModelSlots.applyProvider(context, inst, ticked.value) }
                        busy = false
                        onDone()
                    }
                },
                enabled = !busy && ticked.value.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text(stringResource(R.string.nm_useit_use), fontWeight = FontWeight.Medium)
                }
            }
            MinisTextButton(onClick = onDone, enabled = !busy, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.nm_useit_not_now), color = MuseTones.action)
            }
            Text(
                text = stringResource(R.string.nm_useit_footnote),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}
