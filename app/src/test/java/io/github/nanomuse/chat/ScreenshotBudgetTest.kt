package io.github.nanomuse.chat

import com.openminis.app.provider.ImageBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** At most four screenshots per model request (C9 item 4), on top of OpenMinis' byte budget. */
class ScreenshotBudgetTest {
    private fun img(size: Int = 1000, path: String? = null) = ImageBudget.BudgetImage(ByteArray(size), path, "image/jpeg")

    @Test fun `up to four screenshots nothing goes`() {
        assertTrue(ScreenshotBudget.beyondNewest(listOf(true, true, true, true)).isEmpty())
        assertTrue(ScreenshotBudget.beyondNewest(listOf(false, true, false, true)).isEmpty())
        assertTrue(ScreenshotBudget.beyondNewest(emptyList()).isEmpty())
    }

    @Test fun `the oldest screenshots beyond four go, the person's pictures stay`() {
        // oldest first: shots at 0, 1, 3, 4, 6, 7; the person's pictures at 2 and 5
        val flags = listOf(true, true, false, true, true, false, true, true)
        assertEquals(listOf(0, 1), ScreenshotBudget.beyondNewest(flags))
        assertEquals(listOf(0, 1, 3), ScreenshotBudget.beyondNewest(flags, keep = 3))
    }

    @Test fun `the plan gains the extra drops once, and the byte counts follow`() {
        val images = List(6) { img(1000, "/tmp/shot$it.jpg") }
        val flags = List(6) { true }
        val base = ImageBudget.planRequestBudget(images)
        assertEquals(0, base.droppedCount)
        val plan = ScreenshotBudget.apply(base, images, flags)
        assertEquals(2, plan.droppedCount)
        assertEquals(setOf(ImageBudget.ImagePartId.of(images[0].data), ImageBudget.ImagePartId.of(images[1].data)), plan.droppedIds)
        assertEquals("/tmp/shot0.jpg", plan.droppedPaths[ImageBudget.ImagePartId.of(images[0].data)])
        assertEquals(4000L, plan.keptBytes)
        assertEquals(2000L, plan.elidedBytes)
        assertEquals(6, plan.totalCount)
        assertTrue(plan.mutated)
        // the newest four are still inlined
        assertFalse(ImageBudget.ImagePartId.of(images[5].data) in plan.droppedIds)
    }

    @Test fun `an image the byte budget dropped already is not counted twice`() {
        val images = List(5) { img(1000) }
        val base = ImageBudget.planRequestBudget(images)
        val already = base.copy(droppedIds = setOf(ImageBudget.ImagePartId.of(images[0].data)), droppedCount = 1, keptBytes = 4000, elidedBytes = 1000)
        val plan = ScreenshotBudget.apply(already, images, List(5) { true })
        assertEquals(1, plan.droppedCount)
        assertEquals(already.droppedIds, plan.droppedIds)
    }

    @Test fun `with nothing to cut the plan comes back as it was`() {
        val images = List(3) { img() }
        val base = ImageBudget.planRequestBudget(images)
        assertSame(base, ScreenshotBudget.apply(base, images, List(3) { true }))
    }
}
