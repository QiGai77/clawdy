package io.github.nanomuse.cloud

import android.content.Context
import com.openminis.app.R
import io.github.nanomuse.avatar.AvatarMotion
import io.github.nanomuse.avatar.AvatarStudio
import io.github.nanomuse.avatar.ImageGen
import io.github.nanomuse.media.MediaModels
import io.github.nanomuse.ui.avatar.AgentMood
import java.util.Locale

/**
 * What a new face costs when the pictures and clips are paid from the nanoMuse Cloud
 * allowance — asked of the relay before anything is drawn, so the person sees the number
 * next to what they have left today and says yes first. A face drawn with the user's own
 * key has no allowance and is not asked about.
 */
object FaceCost {
    /** Pictures and clips a job takes. [clips] is 0 when the clips would not come from the Cloud. */
    data class Job(val images: Int, val clips: Int)

    /** True when the image model is the Cloud's: the pictures come out of the allowance. */
    fun onCloud(context: Context): Boolean {
        val cloud = NanoMuseCloud.instance(context) ?: return false
        val image = ImageGen.endpoint(context) ?: return false
        return image.instanceId == cloud.id
    }

    private fun clipsOnCloud(context: Context): Boolean {
        val cloud = NanoMuseCloud.instance(context) ?: return false
        if (!AvatarMotion.enabled(context)) return false
        return MediaModels.videoEndpoint(context)?.instanceId == cloud.id
    }

    /** A new face: the candidates, one pose per mood, and the clips when animation is on. */
    fun newFace(context: Context): Job = Job(
        images = AvatarStudio.CANDIDATES + AgentMood.entries.count { it != AgentMood.IDLE },
        clips = if (clipsOnCloud(context)) AvatarMotion.animated.size else 0,
    )

    /** Another set of candidates while choosing: four pictures, no clips yet. */
    fun candidates(): Job = Job(images = AvatarStudio.CANDIDATES, clips = 0)

    /** Redrawing the poses of the current face. */
    fun poses(): Job = Job(images = AgentMood.entries.count { it != AgentMood.IDLE }, clips = 0)

    /** The relay's estimate, or null when it could not be asked (offline, an older relay). */
    suspend fun estimate(context: Context, job: Job): NanoMuseCloud.Estimate? =
        runCatching { NanoMuseCloud.estimate(context, job.images, job.clips) }.getOrNull()

    /** True when the estimate says the job cannot be paid for as it stands. */
    fun blocked(est: NanoMuseCloud.Estimate?): Boolean = est != null && !est.affordable

    /** The sentences of the confirmation, in the person's language. */
    fun describe(context: Context, job: Job, est: NanoMuseCloud.Estimate?): String {
        val what = if (job.clips > 0) context.getString(R.string.nm_face_cost_what_clips, job.images, job.clips)
        else context.getString(R.string.nm_face_cost_what, job.images)
        if (est == null) return context.getString(R.string.nm_face_cost_unknown, what)
        val sb = StringBuilder(context.getString(R.string.nm_face_cost_about, what, money(est.cny)))
        val left = est.leftCny
        if (left != null) sb.append(' ').append(context.getString(R.string.nm_face_cost_left, money(left)))
        if (!est.affordable) sb.append(' ').append(context.getString(R.string.nm_face_cost_short, NanoMuseCloud.inviteBonusText(context)))
        return sb.toString()
    }

    private fun money(cny: Double): String =
        if (cny >= 10) String.format(Locale.ROOT, "%.0f", cny) else String.format(Locale.ROOT, "%.2f", cny).trimEnd('0').trimEnd('.')
}
