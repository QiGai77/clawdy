package io.github.nanomuse.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The resolution order of 0.1.41 "Choice" (contract section 3) and the model a provider serves a slot with. */
class SlotOrderTest {

    @Test fun `an explicit choice always wins`() {
        val pick = SlotOrder.resolve(chosen = "mine", chatProvider = "chat", cloud = "cloud", firstOwn = "own")
        assertEquals(SlotOrder.Pick("mine", SlotOrder.Why.CHOSEN), pick)
    }

    @Test fun `the chat provider's own model comes before the Cloud`() {
        val pick = SlotOrder.resolve<String>(chosen = null, chatProvider = "qwen3-vl-plus", cloud = "cloud", firstOwn = "own")
        assertEquals(SlotOrder.Pick("qwen3-vl-plus", SlotOrder.Why.CHAT_PROVIDER), pick)
    }

    @Test fun `signed in and the chat provider has nothing for the slot, the Cloud serves it`() {
        val pick = SlotOrder.resolve<String>(chosen = null, chatProvider = null, cloud = "cloud", firstOwn = "own")
        assertEquals(SlotOrder.Pick("cloud", SlotOrder.Why.CLOUD), pick)
    }

    @Test fun `signed out, the first own provider with the capability`() {
        val pick = SlotOrder.resolve<String>(chosen = null, chatProvider = null, cloud = null, firstOwn = "own")
        assertEquals(SlotOrder.Pick("own", SlotOrder.Why.FIRST_OWN), pick)
    }

    @Test fun `clearing the choice returns the slot to the order`() {
        val chosen = SlotOrder.resolve(chosen = "mine", chatProvider = "qwen3-vl-plus", cloud = "cloud", firstOwn = "own")
        assertEquals(SlotOrder.Why.CHOSEN, chosen?.why)
        // Automatic: the stored choice is gone, everything else as it was
        val automatic = SlotOrder.resolve<String>(chosen = null, chatProvider = "qwen3-vl-plus", cloud = "cloud", firstOwn = "own")
        assertEquals(SlotOrder.Pick("qwen3-vl-plus", SlotOrder.Why.CHAT_PROVIDER), automatic)
        // and with no chat provider of one's own, the Cloud, then the first own model
        assertEquals(SlotOrder.Why.CLOUD, SlotOrder.resolve<String>(null, null, "cloud", "own")?.why)
        assertEquals(SlotOrder.Why.FIRST_OWN, SlotOrder.resolve<String>(null, null, null, "own")?.why)
    }

    @Test fun `nothing can serve the slot`() {
        assertNull(SlotOrder.resolve<String>(null, null, null, null))
    }

    @Test fun `the catalogue's default when the provider lists it, whatever the case`() {
        assertEquals("Qwen3-VL-Plus", SlotOrder.defaultModel("qwen3-vl-plus", listOf("qwen-vl-max", "Qwen3-VL-Plus")))
    }

    @Test fun `the first capable model when the default is not listed`() {
        assertEquals("qwen-vl-max", SlotOrder.defaultModel("qwen3-vl-plus", listOf("qwen-vl-max", "qwen-vl-ocr")))
    }

    @Test fun `the catalogue's word stands while the list is still empty`() {
        assertEquals("gpt-image-2.5-flare", SlotOrder.defaultModel("gpt-image-2.5-flare", emptyList()))
    }

    @Test fun `no default and no capable model is nothing`() {
        assertNull(SlotOrder.defaultModel(null, emptyList()))
        assertNull(SlotOrder.defaultModel("  ", emptyList()))
        assertEquals("a", SlotOrder.defaultModel(null, listOf("a", "b")))
    }

    @Test fun `the pick leads the group and the rest keep their order`() {
        assertEquals(listOf("c", "a", "b"), SlotOrder.leadWith(listOf("a", "b", "c"), "c"))
        assertEquals(listOf("x", "a", "b"), SlotOrder.leadWith(listOf("a", "b"), "x"))
    }

    @Test fun `a pick that already leads changes nothing`() {
        val members = listOf("a", "b")
        assertSame(members, SlotOrder.leadWith(members, "a"))
    }
}
