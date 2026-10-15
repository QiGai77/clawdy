package io.github.nanomuse.sandbox

import android.content.Context
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.RootfsManager
import java.io.File

/**
 * Sandbox paths the agent names (a hub `files` action, `nanomuse-media`, `nanomuse-pc put`)
 * become host files here, and only files inside the sandbox: the rootfs, a folder the person
 * bound into it, or a session's own `/var/minis/…`. A path with `..`, or a symlink made inside
 * the rootfs that points at the app's private files, resolves to nothing.
 */
object SandboxPaths {
    /** `/a/./b/../c//d` → `/a/c/d`; never climbs above `/`. */
    fun normalise(linux: String): String {
        val out = ArrayList<String>()
        for (seg in linux.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> out.removeLastOrNull()
                else -> out += seg
            }
        }
        return "/" + out.joinToString("/")
    }

    /**
     * The host file behind [linux] for [sessionId] (null: the shared sandbox), or null when the
     * sandbox is not set up or the path leads outside it. Per-session `/var/minis/attachments`
     * and the like are the session's folders, as the kernel maps them.
     */
    fun host(context: Context, linux: String, sessionId: String?): File? {
        val clean = normalise(linux)
        val file = if (sessionId != null) PRootKernel.resolveSessionHostPath(sessionId, clean, context)
        else PRootKernel.resolveHostPath(clean)
        return file?.takeIf { inside(context, it) }
    }

    /** Whether [file]'s real location is under one of the sandbox's host roots. */
    fun inside(context: Context, file: File): Boolean = inside(file, roots(context))

    /** Pure: [file] under one of [roots] once both are canonical (symlinks followed). */
    fun inside(file: File, roots: List<File>): Boolean {
        val real = runCatching { file.canonicalPath }.getOrNull() ?: return false
        return roots.any { root ->
            val base = runCatching { root.canonicalPath }.getOrNull() ?: return@any false
            real == base || real.startsWith(base.trimEnd('/') + "/")
        }
    }

    private fun roots(context: Context): List<File> = buildList {
        add(RootfsManager.getInstance(context).rootfsDir)
        add(File(context.filesDir, "minis-sessions"))
        PRootKernel.bindMounts.values.forEach { add(File(it)) }
    }
}
