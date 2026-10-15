package com.openminis.app.clawdy

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Capability tags shown in the model sheet (工具调用 / 识图 / 推理). */
data class Caps(val tools: Boolean = true, val vision: Boolean = false, val thinking: Boolean = false)

data class PresetModel(val id: String, val name: String, val caps: Caps)

data class ProviderPreset(
    val key: String,
    val name: String,
    val type: ProviderType,
    /** Base URL as the user sees it; null = the provider type's official endpoint. */
    val baseUrl: String?,
    val displayBase: String,
    val models: List<PresetModel>,
    val useResponsesApi: Boolean = false,
    val editableBase: Boolean = false,
    val note: String? = null,
)

/**
 * BYOK provider catalog, model ids as of 2026-10-15 (from the Clawdy brief).
 * Every preset maps onto an existing provider type of the request layer; OpenAI-compatible
 * vendors use the openAI type with a custom base URL and no extra "/v1".
 */
object ClawdyPresets {
    const val DEFAULT_MODEL_ID = "deepseek-flash"
    const val DEFAULT_MODEL_NAME = "DeepSeek V4.1 Flash"

    private val ALL = Caps(tools = true, vision = true, thinking = true)
    private val TT = Caps(tools = true, thinking = true)

    val presets: List<ProviderPreset> = listOf(
        ProviderPreset(
            "deepseek", "DeepSeek", ProviderType.openAI, "https://api.deepseek.com", "https://api.deepseek.com",
            listOf(
                PresetModel("deepseek-flash", "DeepSeek V4.1 Flash", ALL),
                PresetModel("deepseek-v4-pro", "DeepSeek V4 Pro", TT),
            ),
        ),
        ProviderPreset(
            "openai", "OpenAI", ProviderType.openAI, null, "https://api.openai.com/v1",
            listOf(
                PresetModel("gpt-6-astra", "GPT-6 Astra", ALL),
                PresetModel("gpt-6.1-sol", "GPT-6.1 Sol", ALL),
                PresetModel("gpt-6-luna", "GPT-6 Luna", ALL),
            ),
            // GPT-6 tool calls require the Responses API; the request layer supports /v1/responses.
            useResponsesApi = true,
            note = "GPT-6 的工具调用走 Responses API（已自动开启）",
        ),
        ProviderPreset(
            "anthropic", "Anthropic", ProviderType.anthropic, null, "https://api.anthropic.com",
            listOf(
                PresetModel("claude-opus-5-5", "Claude Opus 5.5", ALL),
                PresetModel("claude-sonnet-5-5", "Claude Sonnet 5.5", ALL),
                PresetModel("claude-haiku-5-5", "Claude Haiku 5.5", ALL),
            ),
        ),
        ProviderPreset(
            "gemini", "Google Gemini", ProviderType.openAI,
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            listOf(
                PresetModel("gemini-3.8-flash", "Gemini 3.8 Flash", ALL),
                PresetModel("gemini-3.1-pro", "Gemini 3.1 Pro", ALL),
            ),
            note = "使用 Gemini 的 OpenAI 兼容端点",
        ),
        ProviderPreset(
            "qwen", "通义千问 DashScope", ProviderType.openAI,
            "https://dashscope.aliyuncs.com/compatible-mode/v1", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            listOf(
                PresetModel("qwen3.8-max", "Qwen3.8 Max", TT),
                PresetModel("qwen3.8-flash", "Qwen3.8 Flash", TT),
            ),
        ),
        ProviderPreset(
            "zhipu", "智谱 GLM", ProviderType.openAI,
            "https://open.bigmodel.cn/api/paas/v4", "https://open.bigmodel.cn/api/paas/v4",
            listOf(
                PresetModel("glm-5.3", "GLM-5.3", TT),
                PresetModel("glm-5.3-flash", "GLM-5.3 Flash", TT),
            ),
        ),
        ProviderPreset(
            "moonshot", "Moonshot Kimi", ProviderType.openAI, "https://api.moonshot.cn/v1", "https://api.moonshot.cn/v1",
            listOf(PresetModel("kimi-k3", "Kimi K3", TT)),
        ),
        ProviderPreset(
            "openrouter", "OpenRouter", ProviderType.openRouter, null, "https://openrouter.ai/api/v1",
            listOf(PresetModel("deepseek/deepseek-v4.1-flash", "DeepSeek V4.1 Flash (OpenRouter)", ALL)),
        ),
        ProviderPreset(
            "custom", "自定义 OpenAI 兼容", ProviderType.openAI, "https://", "https://",
            listOf(PresetModel("gpt-6-astra", "gpt-6-astra", ALL)),
            editableBase = true,
        ),
    )

    fun preset(key: String) = presets.firstOrNull { it.key == key }

    /** Caps for any configured entry: the preset's tags if we know the id, else inferred. */
    fun capsFor(entry: ModelEntry): Caps {
        presets.forEach { p -> p.models.firstOrNull { it.id == entry.model.id }?.let { return it.caps } }
        val m = entry.model
        return Caps(
            tools = true,
            vision = m.inputModalities?.contains("image") == true,
            thinking = m.supportsReasoning == true,
        )
    }

    /** Creates the provider, stores the key (encrypted prefs), seeds the preset models, kicks a /models refresh. */
    suspend fun addProvider(
        repo: ProviderRepository,
        preset: ProviderPreset,
        label: String,
        baseUrl: String,
        apiKey: String,
        modelIds: List<String>,
    ): Pair<ProviderInstance, List<ModelEntry>> {
        val base = baseUrl.trim().trimEnd('/')
        val official = preset.baseUrl == null && (base.isEmpty() || base == preset.displayBase)
        val instance = ProviderInstance(
            id = UUID.randomUUID().toString(),
            label = label.ifBlank { preset.name },
            providerType = preset.type,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = if (official) null else base,
            appendV1Suffix = false,
            useResponsesAPI = preset.useResponsesApi,
        )
        repo.addInstance(instance)
        if (apiKey.isNotBlank()) repo.saveApiKey(instance.id, apiKey.trim())
        val entries = modelIds.distinct().filter { it.isNotBlank() }.map { id ->
            val pm = preset.models.firstOrNull { it.id == id }
            val model = LLMModel(
                id = id,
                displayName = pm?.name ?: id,
                provider = preset.name,
                supportsReasoning = pm?.caps?.thinking,
                inputModalities = if (pm?.caps?.vision == true) listOf("text", "image") else listOf("text"),
            )
            ModelEntry(providerInstanceId = instance.id, baseModel = model, isCustom = true)
        }
        entries.forEach { repo.addEntry(it) }
        runCatching { withContext(Dispatchers.IO) { repo.refreshModels(instance) } }
        return instance to entries
    }

    data class TestResult(val ok: Boolean, val latencyMs: Long, val message: String, val models: List<String> = emptyList())

    /** Minimal request: GET /models with the key. Returns latency or a precise reason (401 / 网络 / 证书). */
    suspend fun testConnection(preset: ProviderPreset, baseUrl: String, apiKey: String): TestResult = withContext(Dispatchers.IO) {
        val base = (baseUrl.trim().ifEmpty { preset.displayBase }).trimEnd('/')
        val url = when (preset.type) {
            ProviderType.anthropic -> "$base/v1/models"
            else -> "$base/models"
        }
        val start = System.currentTimeMillis()
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 15_000; requestMethod = "GET"
                if (preset.type == ProviderType.anthropic) {
                    setRequestProperty("x-api-key", apiKey.trim())
                    setRequestProperty("anthropic-version", "2023-06-01")
                } else {
                    setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                }
            }
            val code = conn.responseCode
            val ms = System.currentTimeMillis() - start
            val body = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            conn.disconnect()
            when {
                code in 200..299 -> {
                    val ids = runCatching {
                        val arr = JSONObject(body).optJSONArray("data")
                        (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } }
                    }.getOrDefault(emptyList())
                    TestResult(true, ms, "连接成功 · ${ms}ms" + if (ids.isNotEmpty()) " · ${ids.size} 个模型" else "", ids)
                }
                code == 401 || code == 403 -> TestResult(false, ms, "$code：API Key 无效或没有权限")
                code == 404 -> TestResult(false, ms, "404：地址不对，检查 Base URL")
                code == 429 -> TestResult(false, ms, "429：请求过多或余额不足")
                else -> TestResult(false, ms, "HTTP $code：${body.take(120)}")
            }
        } catch (e: javax.net.ssl.SSLException) {
            TestResult(false, System.currentTimeMillis() - start, "证书错误：${e.message?.take(80)}")
        } catch (e: java.net.UnknownHostException) {
            TestResult(false, System.currentTimeMillis() - start, "网络错误：无法解析 ${e.message}")
        } catch (e: java.io.IOException) {
            TestResult(false, System.currentTimeMillis() - start, "网络错误：${e.message?.take(80)}")
        } catch (e: Exception) {
            TestResult(false, System.currentTimeMillis() - start, "失败：${e.message?.take(80)}")
        }
    }
}
