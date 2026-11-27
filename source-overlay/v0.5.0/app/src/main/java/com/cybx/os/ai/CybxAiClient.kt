package com.cybx.os.ai

import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AiConfig(
    val provider: String = "ollama",
    val ollamaUrl: String = "http://127.0.0.1:11434",
    val ollamaModel: String = "qwen2.5:3b",
    val cloudUrl: String = "https://api.openai.com/v1/chat/completions",
    val cloudModel: String = "gpt-4o-mini",
    val cloudApiKey: String = ""
)


data class CybxModelToolCall(
    val id: String,
    val name: String,
    val arguments: JSONObject
)

data class CybxModelTurn(
    val content: String,
    val toolCall: CybxModelToolCall?,
    val protocolMessage: JSONObject
)

class CybxAiClient {

    fun toolResultMessage(callId: String, toolName: String, result: String, config: AiConfig): JSONObject {
        val message = JSONObject().put("role", "tool").put("content", result)
        if (config.provider == "ollama") {
            message.put("tool_name", toolName)
        } else {
            message.put("tool_call_id", callId)
        }
        return message
    }

    /**
     * Makes one tool-aware assistant turn using Ollama /api/chat or an OpenAI-compatible
     * chat-completions endpoint. The app executes returned tools locally after policy checks.
     */
    suspend fun agentResponse(messages: List<JSONObject>, config: AiConfig): CybxModelTurn =
        withContext(Dispatchers.IO) {
            val useOllama = config.provider == "ollama"
            val endpoint = if (useOllama) config.ollamaUrl.trimEnd('/') + "/api/chat" else config.cloudUrl.trim()
            if (useOllama) {
                require(isAllowedOllamaUrl(endpoint)) {
                    "Ollama HTTP endpoint must be localhost or a private LAN address. Use HTTPS for public endpoints."
                }
            } else {
                require(endpoint.startsWith("https://", ignoreCase = true)) { "Cloud AI endpoint must use HTTPS." }
                require(config.cloudApiKey.isNotBlank()) { "Cloud AI API key is not configured." }
            }
            val model = if (useOllama) config.ollamaModel else config.cloudModel
            require(model.isNotBlank()) { "AI model name is empty." }

            val body = JSONObject()
                .put("model", model)
                .put("messages", JSONArray(messages))
                .put("tools", com.cybx.os.agent.CybxAgentToolRegistry.definitions())
                .put("stream", false)
            if (useOllama) {
                body.put("options", JSONObject().put("temperature", 0.15))
            } else {
                // Ollama Cloud supports tools on /v1/chat/completions but not tool_choice.
                body.put("temperature", 0.15)
            }

            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (!useOllama) setRequestProperty("Authorization", "Bearer " + config.cloudApiKey)
            }
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error("AI provider HTTP " + code + ": " + response.take(500))
                val root = JSONObject(response)
                val message = if (useOllama) {
                    root.optJSONObject("message")
                } else {
                    root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                } ?: error("AI provider returned no assistant message.")

                val calls = message.optJSONArray("tool_calls")
                val firstCall = calls?.optJSONObject(0)
                val toolCall = if (firstCall == null) null else {
                    val function = firstCall.optJSONObject("function") ?: JSONObject()
                    val name = function.optString("name").trim()
                    require(name.isNotEmpty()) { "AI returned a tool call without a function name." }
                    val rawArgs = function.opt("arguments")
                    val args = when (rawArgs) {
                        is JSONObject -> rawArgs
                        is String -> runCatching { JSONObject(rawArgs) }.getOrElse { JSONObject() }
                        else -> JSONObject()
                    }
                    CybxModelToolCall(
                        id = firstCall.optString("id").ifBlank { "cybx_call_" + java.util.UUID.randomUUID().toString() },
                        name = name,
                        arguments = args
                    )
                }

                // This phase deliberately serializes one tool call at a time.
                val protocolMessage = JSONObject(message.toString())
                if (toolCall != null) {
                    val protocolCalls = protocolMessage.optJSONArray("tool_calls")
                    val protocolCall = protocolCalls?.optJSONObject(0)
                    // Native Ollama /api/chat replays tool_name without an ID; OpenAI-compatible
                    // chat completions correlate tool replies using tool_call_id.
                    if (!useOllama) protocolCall?.put("id", toolCall.id)
                    if (protocolCall != null) protocolMessage.put("tool_calls", JSONArray().put(protocolCall))
                }
                CybxModelTurn(
                    content = message.optString("content").trim().take(6000),
                    toolCall = toolCall,
                    protocolMessage = protocolMessage
                )
            } finally {
                connection.disconnect()
            }
        }

    suspend fun explainTask(request: String, target: String, config: AiConfig): String? =
        ask(
            config,
            "You are CybX, a bilingual security-lab assistant. The app has already parsed and validated a private RFC1918 target: $target. Explain the planned bounded TCP service check in the user's language. Do not output shell commands, exploit steps, payloads, credential attacks, or additional targets. Keep it concise.",
            "User request: $request"
        )

    suspend fun analyseResults(target: String, results: List<String>, config: AiConfig): String? =
        ask(
            config,
            "You are CybX, a defensive security report assistant. Summarise only the supplied scan observations for the authorised private lab target $target. Do not propose exploitation, credential attacks, persistence, evasion, or actions against other hosts. Clearly distinguish observed facts from recommendations. Respond in Bengali if the request context is Bengali; otherwise English.",
            "Observed TCP results:\n" + results.joinToString("\n")
        )

    private suspend fun ask(config: AiConfig, system: String, user: String): String? =
        withContext(Dispatchers.IO) {
            val useOllama = config.provider == "ollama"
            val endpoint = if (useOllama) {
                config.ollamaUrl.trimEnd('/') + "/api/chat"
            } else {
                config.cloudUrl.trim()
            }
            if (useOllama) {
                require(isAllowedOllamaUrl(endpoint)) {
                    "Ollama HTTP endpoint must be localhost or a private LAN address. Use HTTPS for public endpoints."
                }
            } else {
                require(endpoint.startsWith("https://", ignoreCase = true)) {
                    "Cloud AI endpoint must use HTTPS."
                }
                require(config.cloudApiKey.isNotBlank()) { "Cloud AI API key is not configured." }
            }
            val model = if (useOllama) config.ollamaModel else config.cloudModel
            require(model.isNotBlank()) { "AI model name is empty." }

            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user))
            val body = if (useOllama) {
                JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("stream", false)
                    .put("options", JSONObject().put("temperature", 0.2))
            } else {
                JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("stream", false)
                    .put("temperature", 0.2)
            }

            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (!useOllama) setRequestProperty("Authorization", "Bearer " + config.cloudApiKey)
            }
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error("AI provider HTTP $code: " + response.take(400))
                val json = JSONObject(response)
                val text = if (useOllama) {
                    json.optJSONObject("message")?.optString("content")
                } else {
                    json.optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content")
                }
                text?.trim()?.takeIf { it.isNotEmpty() }?.take(4000)
            } finally {
                connection.disconnect()
            }
        }

    private fun isAllowedOllamaUrl(value: String): Boolean {
        return try {
            val uri = URI(value)
            if (uri.scheme != "http" && uri.scheme != "https") return false
            if (uri.scheme == "https") return true
            val host = uri.host ?: return false
            if (host.equals("localhost", true)) return true
            val address = InetAddress.getByName(host)
            address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress
        } catch (_: Exception) {
            false
        }
    }
}

/** Encrypts the cloud API key with an AES-GCM key held by Android Keystore. */
object ApiKeyVault {
    private const val KEY_ALIAS = "cybx_ai_cloud_key_v1"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        if (encoded.isBlank()) return ""
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val payload = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(payload), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
