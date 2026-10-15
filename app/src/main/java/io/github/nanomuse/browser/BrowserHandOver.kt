package io.github.nanomuse.browser

import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.openBrowserSheetForUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The browser handed to the person. A page that needs them — a login, a verification code, a
 * payment, a CAPTCHA — used to end in prose ("you need to do this yourself") with no way in; now
 * `browser_use` has a `hand_over` action: the agent's own tab (same WebView, same cookies) opens
 * in the browser sheet with the agent's hold on it released, a card in the chat says whose turn
 * it is, and the tool call waits until the person taps Done — or gives up after [TIMEOUT_MS].
 * The model then continues from the page as it is, never asking for the credentials.
 */
object BrowserHandOver {
    const val TIMEOUT_MS: Long = 15 * 60 * 1000L

    data class Pending(val reason: String, val url: String)

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    @Volatile private var waiter: CompletableDeferred<Boolean>? = null

    /** The person pressed Done: the waiting tool call returns and the agent goes on. */
    fun finish() {
        waiter?.complete(true)
    }

    /** Waits for Done; false when the time ran out or the hand-over was replaced. */
    internal suspend fun wait(reason: String, url: String): Boolean {
        val mine = CompletableDeferred<Boolean>()
        waiter?.complete(false)
        waiter = mine
        _pending.value = Pending(reason, url)
        return try {
            withTimeoutOrNull(TIMEOUT_MS) { mine.await() } ?: false
        } finally {
            if (waiter === mine) {
                waiter = null
                _pending.value = null
            }
        }
    }
}

/** The `hand_over` action of `browser_use`, run by the chat instead of the tab pool. */
internal suspend fun ChatViewModel.nmBrowserHandOver(input: BrowserActionInput, argsJson: String): ToolExecutionResult {
    val reason = runCatching { JSONObject(argsJson).optString("reason", "") }.getOrDefault("").ifBlank { input.text.orEmpty() }.trim()
    val selected = browserTabPool.selectedTabId.value
    val url = input.url?.takeIf { it.isNotBlank() }
        ?: browserTabPool.tabs.value.firstOrNull { it.id == selected }?.manager?.currentURL?.value.orEmpty()
    withContext(Dispatchers.Main) {
        browserTabPool.releaseAllTabs()
        openBrowserSheetForUrl(url)
    }
    val done = BrowserHandOver.wait(reason, url)
    val what = reason.ifBlank { "what the page asked of them" }
    return if (done) {
        ToolExecutionResult(
            "The person finished the hand-over ($what) and gave the browser back. Continue from the page " +
                "as it is now — take a screenshot or read it first; do not ask for or type any credentials.",
            true,
        )
    } else {
        ToolExecutionResult(
            "The person has not finished the hand-over ($what) yet. Stop here and tell them in one line " +
                "that you will go on once they are done — do not ask for credentials, do not retry the page.",
            true,
        )
    }
}
