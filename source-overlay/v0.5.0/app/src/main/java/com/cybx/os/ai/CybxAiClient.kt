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
        if (usesNativeOllamaApi(config)) {
            message.put("tool_name", toolName)
        } else {
            message.put("tool_call_id", callId)
        }
        return message
    }

    private fun usesNativeOllamaApi(config: AiConfig): Boolean {
        if (config.provider == "ollama") return true
        return try {
            val uri = URI(config.cloudUrl.trim())
            uri.scheme.equals("https", true) &&
                uri.host.equals("ollama.com", true) &&
                uri.path.equals("/api/chat", true)
        } catch (_: Exception) {
            false
        }
    }

    private fun isOllamaOpenAiCloud(config: AiConfig): Boolean {
        return try {
            val uri = URI(config.cloudUrl.trim())
            uri.scheme.equals("https", true) &&
                uri.host.equals("ollama.com", true) &&
                uri.path.equals("/v1/chat/completions", true)
        } catch (_: Exception) {
            false
        }
    }

    private fun errorMessage(code: Int, body: String, provider: String): String {
        val jsonError = runCatching {
            val json = JSONObject(body)
            json.optString("error").ifBlank { json.optString("message") }
        }.getOrNull().orEmpty()
        if (code == 403 && (body.contains("<html", true) || body.contains("<!doctype", true))) {
            return "HTTP 403 from $provider: the cloud gateway denied this request. Verify that the key is an Ollama API key from Settings > API keys, confirm the account has model access/usage available, and check the endpoint. The server returned an HTML forbidden page rather than a model error."
        }
        return "AI provider HTTP $code: " + (jsonError.ifBlank { body.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim() }).take(450)
    }

    /**
     * Makes one tool-aware assistant turn. Ollama Cloud can use either its native
     * /api/chat API or its OpenAI-compatible /v1/chat/completions API.
     */
    suspend fun agentResponse(messages: List<JSONObject>, config: AiConfig): CybxModelTurn =
        withContext(Dispatchers.IO) {
            val useLocalOllama = config.provider == "ollama"
            val nativeOllama = usesNativeOllamaApi(config)
            val endpoint = when {
                useLocalOllama -> config.ollamaUrl.trimEnd('/') + "/api/chat"
                else -> config.cloudUrl.trim()
            }
            if (useLocalOllama) {
                require(isAllowedOllamaUrl(endpoint)) {
                    "Local Ollama endpoint must be localhost or a private LAN address."
                }
            } else {
                require(endpoint.startsWith("https://", ignoreCase = true)) {
                    "Cloud AI endpoint must use HTTPS."
                }
                require(config.cloudApiKey.isNotBlank()) { "Cloud AI API key is not configured." }
                require(isOllamaOpenAiCloud(config) || nativeOllama || endpoint.startsWith("https://")) {
                    "Unsupported cloud endpoint."
                }
            }
            val model = if (useLocalOllama) config.ollamaModel else config.cloudModel
            require(model.isNotBlank()) { "AI model name is empty." }

            val body = JSONObject()
                .put("model", model)
                .put("messages", JSONArray(messages))
                .put("tools", com.cybx.os.agent.CybxAgentToolRegistry.definitions())
                .put("stream", false)
            if (nativeOllama) {
                body.put("options", JSONObject().put("temperature", 0.15))
            } else {
                body.put("temperature", 0.15)
            }

            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "CybX-Mobile-AI/0.6.1")
                if (!useLocalOllama) setRequestProperty("Authorization", "Bearer " + config.cloudApiKey)
            }
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error(errorMessage(code, response, if (nativeOllama) "Ollama Cloud" else "cloud provider"))
                val root = JSONObject(response)
                val message = if (nativeOllama) {
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

                val protocolMessage = JSONObject(message.toString())
                if (toolCall != null) {
                    val protocolCalls = protocolMessage.optJSONArray("tool_calls")
                    val protocolCall = protocolCalls?.optJSONObject(0)
                    if (!nativeOllama) protocolCall?.put("id", toolCall.id)
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

    /** Performs a simple authenticated request without tools to verify endpoint, key and model. */
    suspend fun testConnection(config: AiConfig): String = withContext(Dispatchers.IO) {
        val useLocalOllama = config.provider == "ollama"
        val nativeOllama = usesNativeOllamaApi(config)
        val endpoint = if (useLocalOllama) config.ollamaUrl.trimEnd('/') + "/api/chat" else config.cloudUrl.trim()
        if (useLocalOllama) {
            require(isAllowedOllamaUrl(endpoint)) { "Local Ollama URL must be localhost or a private LAN address." }
        } else {
            require(endpoint.startsWith("https://", true)) { "Cloud endpoint must use HTTPS." }
            require(config.cloudApiKey.isNotBlank()) { "API key is empty." }
        }
        val model = if (useLocalOllama) config.ollamaModel.trim() else config.cloudModel.trim()
        require(model.isNotBlank()) { "Model name is empty." }

        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with exactly: CYBX_OK"))
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", false)
        if (nativeOllama) body.put("options", JSONObject().put("temperature", 0))
        else body.put("temperature", 0)

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "CybX-Mobile-AI/0.6.1")
            if (!useLocalOllama) setRequestProperty("Authorization", "Bearer " + config.cloudApiKey)
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error(errorMessage(code, response, if (nativeOllama) "Ollama Cloud" else "cloud provider"))
            val root = JSONObject(response)
            val reply = if (nativeOllama) root.optJSONObject("message")?.optString("content")
                else root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            "Connection successful · model $model · " + (reply?.take(160) ?: "API responded successfully")
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
