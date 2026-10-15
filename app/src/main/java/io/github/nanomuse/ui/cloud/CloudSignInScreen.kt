package io.github.nanomuse.ui.cloud

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import io.github.nanomuse.cloud.NanoMuseCloud
import io.github.nanomuse.cloud.RelayAddress
import io.github.nanomuse.cloud.SignInIdentifier
import io.github.nanomuse.ui.home.MuseTones
import io.github.nanomuse.ui.muse.MuseTopAppBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val ROUTE_CLOUD_SIGN_IN = "nanomuse/cloud/sign-in"
const val ROUTE_CLOUD_ACCOUNT = "nanomuse/cloud/account"

private const val CLOUD_DOC_URL = "https://github.com/nano-muse/nanoMuse/blob/main/docs/cloud.md"

/**
 * Sign in to nanoMuse Cloud: a mainland phone number (the code comes by SMS) or an e-mail
 * address, then the code it receives — or the account's password. The code box is marked as a
 * one-time code so the system's autofill or the keyboard can offer the SMS it just saw.
 * On success the relay is a provider with a default model, and the caller decides where to
 * go (the first run continues to "Meet nanoMuse"; from Settings it returns to the account page).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSignInScreen(
    onBack: () -> Unit,
    onSignedIn: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var identifier by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    // A friend's invite code: counts for a new account only, so it sits behind a small link.
    var invite by remember { mutableStateOf("") }
    var inviteOpen by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var byPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    // Someone's own relay (0.1.38): the address lives in NanoMuseCloud once "Use this server"
    // is tapped; the form opens by itself when the phone is already pointed away from the default.
    var serverOpen by remember { mutableStateOf(!RelayAddress.isDefault(NanoMuseCloud.baseUrl(context))) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // a number the relay cannot text: said here, before the code is asked for (item 8)
    val phoneAbroad = SignInIdentifier.phoneOutsideMainland(identifier)

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown -= 1
        }
    }

    fun sendCode() {
        if (sending || identifier.isBlank() || phoneAbroad) return
        error = null
        sending = true
        scope.launch {
            try {
                NanoMuseCloud.requestCode(context, identifier)
                codeSent = true
                countdown = 60
            } catch (e: CancellationException) {
                throw e // the page was left: the request is cancelled, not failed (#336)
            } catch (e: Exception) {
                error = NanoMuseCloud.describe(context, e)
            }
            sending = false
        }
    }

    fun verify() {
        if (verifying || code.length < 4) return
        error = null
        verifying = true
        scope.launch {
            try {
                NanoMuseCloud.verify(context, identifier, code, invite)
                onSignedIn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = NanoMuseCloud.describe(context, e)
                verifying = false
            }
        }
    }

    fun login() {
        if (verifying || identifier.isBlank() || password.isEmpty()) return
        error = null
        verifying = true
        scope.launch {
            try {
                NanoMuseCloud.login(context, identifier, password)
                onSignedIn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = NanoMuseCloud.describe(context, e)
                verifying = false
            }
        }
    }

    val canSubmit = when {
        byPassword -> identifier.isNotBlank() && password.isNotEmpty() && !verifying
        codeSent -> code.length >= 4 && !verifying
        else -> identifier.isNotBlank() && !sending && !phoneAbroad
    }
    fun submit() = when {
        byPassword -> login()
        codeSent -> verify()
        else -> sendCode()
    }

    Scaffold(
        containerColor = MuseTones.surface,
        topBar = {
            MuseTopAppBar(
                title = {},
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            // the app's icon, not the face: this screen is about the account
            io.github.nanomuse.ui.muse.NmBrandMark(size = 64.dp)
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.nm_cloud_signin_title),
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.nm_cloud_signin_subtitle),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = muted,
                textAlign = TextAlign.Center,
            )
            if (NanoMuseCloud.signInEnded(context)) {
                // the relay refused the phone's key: the account's chats wait here for the same
                // account to sign in again (contract C12)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.nm_cloud_sign_in_ended),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MuseTones.fill, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            Spacer(Modifier.height(24.dp))

            // Two ways in: a code to the address, or the password set under Account.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MuseTones.fill, RoundedCornerShape(12.dp))
                    .padding(3.dp),
            ) {
                ModeTab(stringResource(R.string.nm_cloud_mode_code), selected = !byPassword, modifier = Modifier.weight(1f)) {
                    byPassword = false; error = null
                }
                ModeTab(stringResource(R.string.nm_cloud_mode_password), selected = byPassword, modifier = Modifier.weight(1f)) {
                    byPassword = true; error = null
                }
            }
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = identifier,
                onValueChange = { identifier = it; if (codeSent) { codeSent = false; code = "" } },
                label = { Text(stringResource(R.string.nm_cloud_identifier)) },
                placeholder = { Text(stringResource(R.string.nm_cloud_identifier_hint)) },
                singleLine = true,
                enabled = !verifying,
                keyboardOptions = KeyboardOptions(
                    // digits get the phone keypad, anything else the e-mail layout
                    keyboardType = if (identifier.trimStart().firstOrNull()?.let { it.isDigit() || it == '+' } == true) KeyboardType.Phone else KeyboardType.Email,
                    imeAction = if (byPassword) ImeAction.Next else ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { sendCode() }),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MuseTones.action,
                    cursorColor = MuseTones.action,
                    focusedLabelColor = MuseTones.action,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentType = ContentType.Username + ContentType.PhoneNumber + ContentType.EmailAddress },
            )
            if (phoneAbroad && !byPassword) {
                // the relay would answer `phone_region`; better said now, in the same words
                Text(
                    text = stringResource(R.string.nm_cloud_sms_region),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            if (byPassword) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.nm_cloud_password)) },
                    singleLine = true,
                    enabled = !verifying,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { login() }),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(if (showPassword) R.string.nm_a11y_hide_password else R.string.nm_a11y_show_password),
                                tint = muted,
                            )
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MuseTones.action,
                        cursorColor = MuseTones.action,
                        focusedLabelColor = MuseTones.action,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { byPassword = false; error = null }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.nm_cloud_forgot_password), color = MuseTones.action, fontSize = 13.sp)
                }
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                    label = { Text(stringResource(R.string.nm_cloud_code)) },
                    singleLine = true,
                    enabled = codeSent && !verifying,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { verify() }),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MuseTones.action,
                        cursorColor = MuseTones.action,
                        focusedLabelColor = MuseTones.action,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentType = ContentType.SmsOtpCode },
                )
                Spacer(Modifier.padding(horizontal = 6.dp))
                TextButton(
                    onClick = { sendCode() },
                    enabled = identifier.isNotBlank() && countdown == 0 && !sending && !verifying && !phoneAbroad,
                ) {
                    if (sending) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MuseTones.action)
                    } else {
                        Text(
                            text = when {
                                countdown > 0 -> stringResource(R.string.nm_cloud_resend_in, countdown)
                                codeSent -> stringResource(R.string.nm_cloud_resend)
                                else -> stringResource(R.string.nm_cloud_send_code)
                            },
                            color = if (identifier.isNotBlank() && countdown == 0 && !phoneAbroad) MuseTones.action else muted,
                            fontSize = 14.sp,
                        )
                    }
                }
            }

            if (!byPassword) {
                Spacer(Modifier.height(6.dp))
                if (!inviteOpen) {
                    TextButton(onClick = { inviteOpen = true }, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.nm_cloud_invite_have), color = MuseTones.action, fontSize = 13.sp)
                    }
                } else {
                    OutlinedTextField(
                        value = invite,
                        onValueChange = { v -> invite = v.uppercase().filter { it.isLetterOrDigit() }.take(8) },
                        label = { Text(stringResource(R.string.nm_cloud_invite_field)) },
                        singleLine = true,
                        enabled = !verifying,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MuseTones.action,
                            cursorColor = MuseTones.action,
                            focusedLabelColor = MuseTones.action,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Your own relay: a small link under the form, then the address, Check, Use this server.
            Spacer(Modifier.height(6.dp))
            if (!serverOpen) {
                TextButton(onClick = { serverOpen = true }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.nm_cloud_other_server), color = MuseTones.action, fontSize = 13.sp)
                }
            } else {
                RelayServerForm(enabled = !verifying && !sending, onClose = { serverOpen = false })
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { submit() },
                enabled = canSubmit,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = MuseTones.action, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                if (verifying) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text(
                        text = if (byPassword || codeSent) stringResource(R.string.nm_cloud_verify) else stringResource(R.string.nm_cloud_send_code),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.nm_cloud_fine_print),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = muted,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CLOUD_DOC_URL))) } }) {
                Text(stringResource(R.string.nm_setup_learn_more), color = MuseTones.action, fontSize = 12.sp)
            }
            // What signing in is, and is not: free, community, non-profit — said where the
            // decision is made, not only on the account page afterwards.
            CommunityNoticeCard(inset = 0.dp)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The relay's address for people who run their own (0.1.38): the field, *Check* (the relay's
 * `/healthz`, its version shown), *Use this server*, and a way back to nanoMuse Cloud. https
 * unless the host is on a private network. What is chosen here is what the key will belong
 * to — the account page shows it, and changing it later means signing out first.
 */
@Composable
private fun RelayServerForm(enabled: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current = NanoMuseCloud.baseUrl(context)
    var text by remember { mutableStateOf(if (RelayAddress.isDefault(current)) "" else current) }
    var checking by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf<String?>(null) }
    var bad by remember { mutableStateOf(false) }
    var inUse by remember { mutableStateOf(current) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    fun problemLine(p: RelayAddress.Problem): String = when (p) {
        RelayAddress.Problem.EMPTY, RelayAddress.Problem.NOT_A_URL -> context.getString(R.string.nm_cloud_server_bad_url)
        RelayAddress.Problem.HTTP_PUBLIC -> context.getString(R.string.nm_cloud_server_https)
    }

    fun check(then: ((String) -> Unit)? = null) {
        if (checking) return
        val problem = RelayAddress.problem(text)
        if (problem != null) {
            line = problemLine(problem); bad = true
            return
        }
        val url = RelayAddress.normalize(text) ?: return
        checking = true
        line = null
        scope.launch {
            try {
                val h = NanoMuseCloud.checkRelay(url)
                line = context.getString(R.string.nm_cloud_server_ok, RelayAddress.display(url), h.version)
                bad = false
                then?.invoke(url)
            } catch (e: Exception) {
                line = context.getString(R.string.nm_cloud_server_unreachable, RelayAddress.display(url))
                bad = true
            }
            checking = false
        }
    }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; line = null },
            label = { Text(stringResource(R.string.nm_cloud_server_url)) },
            placeholder = { Text(stringResource(R.string.nm_cloud_server_hint)) },
            singleLine = true,
            enabled = enabled && !checking,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { check() }),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MuseTones.action,
                cursorColor = MuseTones.action,
                focusedLabelColor = MuseTones.action,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.nm_cloud_server_now, RelayAddress.display(inUse)),
                fontSize = 12.sp,
                color = muted,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            TextButton(onClick = { check() }, enabled = enabled && !checking && text.isNotBlank()) {
                if (checking) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MuseTones.action)
                else Text(stringResource(R.string.nm_cloud_server_check), color = MuseTones.action, fontSize = 13.sp)
            }
            TextButton(
                onClick = {
                    check { url ->
                        NanoMuseCloud.setBaseUrl(context, url)
                        inUse = url
                    }
                },
                enabled = enabled && !checking && text.isNotBlank(),
            ) { Text(stringResource(R.string.nm_cloud_server_use), color = MuseTones.action, fontSize = 13.sp) }
        }
        line?.let {
            Text(
                text = it,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = if (bad) MaterialTheme.colorScheme.error else muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (!RelayAddress.isDefault(inUse)) {
            TextButton(
                onClick = {
                    NanoMuseCloud.setBaseUrl(context, null)
                    inUse = NanoMuseCloud.DEFAULT_BASE
                    text = ""
                    line = null
                    onClose()
                },
                enabled = enabled && !checking,
                modifier = Modifier.align(Alignment.End),
            ) { Text(stringResource(R.string.nm_cloud_server_default), color = MuseTones.action, fontSize = 13.sp) }
        }
    }
}

/** One half of the code / password switch: a pill that is white when chosen. */
@Composable
private fun ModeTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MuseTones.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
