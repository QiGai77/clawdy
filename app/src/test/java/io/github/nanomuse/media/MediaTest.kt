package io.github.nanomuse.media

import io.github.nanomuse.avatar.AvatarMotion
import io.github.nanomuse.ui.avatar.AgentMood
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTest {

    @Test fun `args take flag value, flag=value and positionals`() {
        val a = MediaOffloadHandler.Args(listOf("image", "--prompt", "a corgi", "--size=512x512", "--from", "/var/minis/attachments/x.png", "--dry"))
        assertEquals(listOf("image"), a.positional)
        assertEquals("a corgi", a.get("prompt"))
        assertEquals("512x512", a.get("size"))
        assertEquals("/var/minis/attachments/x.png", a.get("from"))
        assertEquals("true", a.get("dry"))
        assertNull(a.get("seconds"))
    }

    @Test fun `only dashscope-shaped hosts are offered for video`() {
        assertTrue(VideoGen.speaksDashScope("https://dashscope.aliyuncs.com/compatible-mode/v1"))
        assertTrue(VideoGen.speaksDashScope("https://llm-abc.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"))
        assertTrue(VideoGen.speaksDashScope("http://10.0.2.2:8823/dashscope/v1"))
        assertFalse(VideoGen.speaksDashScope("https://api.openai.com/v1"))
        assertFalse(VideoGen.speaksDashScope("https://openrouter.ai/api/v1"))
    }

    @Test fun `wan siblings and resolutions follow the model family`() {
        assertEquals("wan2.2-i2v-flash", MediaModels.DEFAULT_VIDEO_MODEL)
        assertEquals("wan2.2-i2v-flash", VideoGen.KNOWN_DASHSCOPE_MODELS.first())
        assertEquals("wan2.2-i2v-flash", VideoGen.modelFor("wan2.2-i2v-flash", fromImage = true))
        assertEquals("wan2.2-t2v-plus", VideoGen.modelFor("wan2.2-i2v-flash", fromImage = false)) // there is no 2.2 t2v flash
        assertEquals("wan2.6-t2v", VideoGen.modelFor("wan2.6-i2v", fromImage = false))
        assertEquals("wan2.6-i2v", VideoGen.modelFor("wan2.6-t2v", fromImage = true))
        assertEquals("MiniMax/MiniMax-H3", VideoGen.modelFor("MiniMax/MiniMax-H3", fromImage = false))
        assertEquals("480P", VideoGen.wanResolution("wan2.2-i2v-flash"))
        assertEquals("720P", VideoGen.wanResolution("wan2.6-i2v"))
    }

    @Test fun `failure messages are plain for the user`() {
        val notActivated = JSONObject().put("code", "InvalidParameter").put("message", "The product is not activated, please activate it first")
        assertTrue(VideoGen.failureMessage(notActivated).contains("activate"))
        assertFalse(VideoGen.failureMessage(notActivated).contains("InvalidParameter"))
        assertEquals("Throttling: too many requests", VideoGen.failureMessage(JSONObject().put("code", "Throttling").put("message", "too many requests")))
        assertEquals("Video task CANCELED", VideoGen.failureMessage(JSONObject().put("task_status", "CANCELED")))
        assertEquals("Video task failed", VideoGen.failureMessage(JSONObject()))
    }

    @Test fun `every animated mood has a distinct prompt on a white background`() {
        val prompts = AvatarMotion.animated.map { AvatarMotion.motionPrompt(it) }
        assertEquals(prompts.size, prompts.toSet().size)
        prompts.forEach { assertTrue(it, it.contains("white background", ignoreCase = true)) }
        assertTrue(AvatarMotion.motionPrompt(AgentMood.WAITING).contains("crystal ball"))
        assertTrue(AvatarMotion.motionPrompt(AgentMood.HAPPY).contains("star"))
        assertTrue(AvatarMotion.motionPrompt(AgentMood.WORKING).contains("laptop"))
    }

    @Test fun `the missing-model addendum points at the setting and forbids inventing`() {
        val text = MediaModels.missingImageAddendum()
        assertTrue(text.contains(MediaModels.DEEP_LINK))
        assertTrue(text.contains("Bailian") && text.contains("Gemini") && text.contains("OpenRouter"))
        assertTrue(text.contains("not a ChatGPT plan signed in through Codex"))
        assertTrue(text.contains("do not describe or invent"))
    }

    @Test fun `help names the three subcommands and the setting`() {
        listOf("status", "image", "video", "--prompt", "--from", "--seconds").forEach { assertTrue(it, MediaOffloadHandler.HELP.contains(it)) }
        assertTrue(MediaOffloadHandler.HELP.contains(MediaModels.DEEP_LINK))
    }
}
