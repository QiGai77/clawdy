package io.github.nanomuse.chat

import com.openminis.app.provider.ImageBudget

/**
 * How many screenshots one model request may carry (contract C9, item 4).
 *
 * A computer-use turn takes a screenshot per step and keeps each as a tool result; after a
 * few dozen steps the request body passed the relay's 6 MiB cap and came back 413. The
 * phone's own Hands loop never does that — it sends the current screen and the one before,
 * 720 px wide — but screenshots reach the main conversation too (a browser step, `read_image`,
 * a computer's screen), and OpenMinis' request budget alone is 25 MiB. So, on top of the byte
 * budget: the four newest screenshots stay, every older one becomes the usual text
 * placeholder (the model can re-read it from its path if it must). The person's own
 * attachments are not screenshots and are never cut here.
 */
object ScreenshotBudget {
    /** UI-TARS keeps five; the contract says four. */
    const val MAX_SCREENSHOTS = 4

    /**
     * The indices, in [screenshotFlags] (oldest first; true = a tool-result image), of the
     * screenshots beyond the newest [MAX_SCREENSHOTS]. Empty when nothing has to go.
     */
    fun beyondNewest(screenshotFlags: List<Boolean>, keep: Int = MAX_SCREENSHOTS): List<Int> {
        val shots = screenshotFlags.indices.filter { screenshotFlags[it] }
        if (shots.size <= keep) return emptyList()
        return shots.dropLast(keep)
    }

    /** OpenMinis' byte plan with the screenshot rule folded in: one set of dropped ids for the providers. */
    fun apply(plan: ImageBudget.RequestBudgetPlan, images: List<ImageBudget.BudgetImage>, screenshotFlags: List<Boolean>): ImageBudget.RequestBudgetPlan {
        val extra = beyondNewest(screenshotFlags)
            .map { images[it] }
            .filter { ImageBudget.ImagePartId.of(it.data) !in plan.droppedIds }
        if (extra.isEmpty()) return plan
        val ids = extra.map { ImageBudget.ImagePartId.of(it.data) }
        val bytes = extra.sumOf { minOf(it.data.size.toLong(), ImageBudget.MAX_PER_IMAGE_BYTES) }
        return plan.copy(
            droppedIds = plan.droppedIds + ids,
            droppedPaths = plan.droppedPaths + extra.associate { ImageBudget.ImagePartId.of(it.data) to it.linuxPath },
            keptBytes = (plan.keptBytes - bytes).coerceAtLeast(0),
            elidedBytes = plan.elidedBytes + bytes,
            droppedCount = plan.droppedCount + ids.size,
        )
    }
}
