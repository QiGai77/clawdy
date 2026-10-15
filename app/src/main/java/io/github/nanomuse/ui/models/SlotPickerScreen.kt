package io.github.nanomuse.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.ui.settings.SettingsChoiceRow
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import io.github.nanomuse.cloud.Capabilities
import io.github.nanomuse.media.MediaModels
import io.github.nanomuse.models.ModelSlots
import io.github.nanomuse.models.ModelSlots.Slot
import io.github.nanomuse.models.PickerList
import io.github.nanomuse.ui.home.MuseTones

/**
 * The picker behind one row of Settings → Models: a group `nanoMuse Cloud` (when signed in)
 * with that lane's relay models, the recommended one first and marked; then one group per
 * configured own provider, listing only its models with the capability. Choosing sets the
 * slot and goes back; for chat that is the default for new chats. The three dependent slots
 * open with *Automatic* (no choice stored; the slot follows the order, and the row says what
 * that gives now), clips with *No video model* under it. With no option at all, the sentence
 * that says which provider would serve the slot, and *Add a provider*.
 *
 * A group shows eight rows until its *Show N more* row is tapped ([PickerList]: the catalogue
 * default first, then the chosen model, then the rest as listed); once the groups hold more
 * than eight rows in all, a search field under the *Automatic* row filters every group live
 * by model id or display name, with no cap while a query is present.
 */
@Composable
fun SlotPickerScreen(slot: Slot, onBack: () -> Unit, onAddProvider: () -> Unit) {
    val context = LocalContext.current
    val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull
    val config = repo?.config?.collectAsState()?.value
    var tick by remember { mutableIntStateOf(0) }
    val groups = remember(config, tick) { ModelSlots.groups(context, slot) }
    val current = remember(config, tick) { ModelSlots.current(context, slot) }
    val videoOff = remember(tick) { slot == Slot.VIDEO && ModelSlots.videoOff(context) }
    // the three dependent slots have an *Automatic* row: nothing stored, the slot follows the order
    val chosen = remember(config, tick) { ModelSlots.isChosen(context, slot) }
    val automatic = remember(config, tick) { if (slot == Slot.CHAT) null else ModelSlots.automatic(context, slot) }
    var checking by remember { mutableStateOf(false) }
    // the groups expanded past eight rows (by provider id), and the search field's text; both last as long as the picker is open
    // kept across a rotation and a process restart on the way back from Add a provider
    var expanded by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var query by rememberSaveable { mutableStateOf("") }

    // Model Studio does not list video models: the known ones are probed once a day per key.
    if (slot == Slot.VIDEO) {
        LaunchedEffect(groups.size) {
            val stale = groups.map { it.instance }.filter { MediaModels.availableVideoModels(context, it) == null || !MediaModels.videoCheckIsFresh(context, it) }
            if (stale.isEmpty()) return@LaunchedEffect
            checking = true
            stale.forEach { runCatching { MediaModels.checkVideoModels(context, it) } }
            checking = false
            tick++
        }
    }

    SettingsScaffold(title = stringResource(slotTitle(slot)), onBack = onBack) {
        Text(
            text = stringResource(slotSubtitle(slot)),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp),
        )
        if (slot != Slot.CHAT && groups.isNotEmpty()) {
            SettingsSection {
                AutomaticRow(
                    subtitle = automatic?.let { stringResource(R.string.nm_models_auto_currently, it.label(context)) }
                        ?: Capabilities.unavailableLine(context, slot.capability),
                    selected = !chosen,
                    onSelect = {
                        ModelSlots.clear(context, slot)
                        onBack()
                    },
                    showDivider = slot == Slot.VIDEO,
                )
                if (slot == Slot.VIDEO) {
                    SettingsChoiceRow(
                        title = stringResource(R.string.nm_media_video_off_option),
                        selected = videoOff,
                        onSelect = {
                            MediaModels.saveVideo(context, null, current?.modelId ?: MediaModels.DEFAULT_VIDEO_MODEL)
                            ModelSlots.lastChanged.value = Slot.VIDEO
                            onBack()
                        },
                        showDivider = false,
                    )
                }
            }
        }
        // a provider with hundreds of models: each group shows eight rows until expanded, and
        // past eight rows in all a field filters every group live by id or name
        val searchable = PickerList.searchable(groups.sumOf { it.options.size })
        if (searchable) SearchField(query = query, onChange = { query = it })
        val searching = searchable && PickerList.searching(query)
        val shownGroups = if (searching) PickerList.filter(
            groups, query,
            rowsOf = { it.options },
            idOf = { it.modelId },
            nameOf = { it.displayName },
            rebuild = { g, rows -> g.copy(options = rows) },
        ) else groups
        if (searching && shownGroups.isEmpty()) {
            Text(
                text = stringResource(R.string.nm_models_no_match),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp).padding(top = 24.dp),
            )
        }
        shownGroups.forEach { group ->
            val title = ModelSlots.providerLabel(context, group.instance)
            val shown = PickerList.collapse(group.options, expanded = searching || group.instance.id in expanded)
            SettingsSection(header = title) {
                shown.rows.forEachIndexed { i, option ->
                    SettingsChoiceRow(
                        title = option.modelId,
                        selected = chosen && !videoOff && current != null && current.instance.id == option.instance.id && current.modelId == option.modelId,
                        onSelect = {
                            ModelSlots.choose(context, slot, option)
                            onBack()
                        },
                        leading = if (option.recommended) ({ RecommendedMark() }) else null,
                        showDivider = shown.hidden > 0 || i < shown.rows.lastIndex,
                    )
                }
                if (shown.hidden > 0) {
                    SettingsRow(
                        title = stringResource(R.string.nm_models_show_more, shown.hidden),
                        titleColor = MuseTones.action,
                        onClick = { expanded = expanded + group.instance.id },
                        showChevron = false,
                        showDivider = false,
                    )
                }
            }
        }
        if (checking) {
            SettingsRow(
                title = stringResource(R.string.nm_media_checking),
                titleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                showDivider = false,
                trailing = { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MuseTones.action) },
            )
        }
        SettingsSection(
            footer = if (groups.isEmpty()) Capabilities.unavailableLine(context, slot.capability)
            else if (slot == Slot.CHAT) stringResource(R.string.nm_models_chat_applies) else null,
        ) {
            SettingsRow(
                title = stringResource(R.string.nm_media_add_provider),
                titleColor = MuseTones.action,
                onClick = onAddProvider,
                showChevron = false,
                showDivider = false,
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * The search field above the groups: the drawer's pill, a magnifier and a one-line field with
 * *Search models* as its placeholder; a cross at the end clears it and brings the collapsed view back.
 */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 24.dp)
            .height(44.dp)
            .clip(CircleShape)
            .background(MuseTones.fill)
            .padding(start = 14.dp, end = 6.dp),
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.nm_models_search), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onChange("") }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.nm_models_search_clear), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * The *Automatic* row: [SettingsChoiceRow]'s look with a second line that says what the order
 * gives right now (*Currently nanoMuse Cloud · qwen3.8-27b*), or the slot's one sentence on
 * which provider would serve it when nothing can.
 */
@Composable
private fun AutomaticRow(subtitle: String?, selected: Boolean, onSelect: () -> Unit, showDivider: Boolean) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.nm_models_auto), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                if (subtitle != null) {
                    Text(subtitle, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .then(
                        if (selected) Modifier.background(MaterialTheme.colorScheme.onSurface, CircleShape)
                        else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(14.dp))
            }
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 14.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}
