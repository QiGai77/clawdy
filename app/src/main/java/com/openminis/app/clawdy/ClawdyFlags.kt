package com.openminis.app.clawdy

/**
 * Clawdy build-time switches.
 *
 * [SANDBOX_IN_AGENT]: the Alpine/PRoot sandbox (`shell_execute`) is no longer offered to the
 * model — the user's own Termux is the Linux ("不需要linux沙箱 就是termux"). The code and the
 * payloads stay in the APK because other subsystems (file tools, offload server) still boot it;
 * see design/shell-progress.md.
 */
object ClawdyFlags {
    const val SANDBOX_IN_AGENT = false
}
