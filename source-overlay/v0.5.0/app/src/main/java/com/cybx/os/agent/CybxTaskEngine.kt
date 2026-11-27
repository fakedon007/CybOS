package com.cybx.os.agent

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.cybx.os.ai.AiConfig
import com.cybx.os.ai.ApiKeyVault

data class ScanFinding(
    val host: String,
    val port: Int,
    val service: String,
    val state: String = "open"
)

data class TaskRecord(
    val id: String,
    val createdAt: Long,
    val request: String,
    val target: String,
    val status: String,
    val findings: List<ScanFinding>,
    val summary: String,
    val logLines: List<String>
) {
    val openCount: Int get() = findings.count { it.state == "open" }
    val timeLabel: String get() =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(createdAt))
}

class CybxRepository(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("cybx_v050_settings", Context.MODE_PRIVATE)
    private val historyFile = File(app.filesDir, "cybx/task-history.json")
    private val auditFile = File(app.filesDir, "cybx/audit-log.json")

    fun loadScope(): String = prefs.getString("approved_scope", "") ?: ""
    fun saveScope(value: String) { prefs.edit().putString("approved_scope", value).apply() }

    fun loadAiConfig(): AiConfig = AiConfig(
        provider = prefs.getString("ai_provider", "ollama") ?: "ollama",
        ollamaUrl = prefs.getString("ollama_url", "http://127.0.0.1:11434") ?: "http://127.0.0.1:11434",
        ollamaModel = prefs.getString("ollama_model", "qwen2.5:3b") ?: "qwen2.5:3b",
        cloudUrl = prefs.getString("cloud_url", "https://api.openai.com/v1/chat/completions") ?: "https://api.openai.com/v1/chat/completions",
        cloudModel = prefs.getString("cloud_model", "gpt-4o-mini") ?: "gpt-4o-mini",
        cloudApiKey = ApiKeyVault.decrypt(prefs.getString("cloud_key_enc", "") ?: "")
    )

    fun saveAiConfig(config: AiConfig) {
        prefs.edit()
            .putString("ai_provider", config.provider)
            .putString("ollama_url", config.ollamaUrl)
            .putString("ollama_model", config.ollamaModel)
            .putString("cloud_url", config.cloudUrl)
            .putString("cloud_model", config.cloudModel)
            .putString("cloud_key_enc", ApiKeyVault.encrypt(config.cloudApiKey))
            .apply()
        audit("AI settings updated", "provider=${config.provider}")
    }

    @Synchronized
    fun saveTask(record: TaskRecord) {
        val rows = readArray(historyFile)
        rows.put(record.toJson())
        val trimmed = JSONArray()
        val start = maxOf(0, rows.length() - 50)
        for (i in start until rows.length()) trimmed.put(rows.getJSONObject(i))
        writeArray(historyFile, trimmed)
        audit("Task completed", "target=${record.target}; open_ports=${record.openCount}")
    }

    @Synchronized
    fun loadTasks(): List<TaskRecord> {
        val rows = readArray(historyFile)
        return (0 until rows.length()).mapNotNull { index ->
            runCatching { rows.getJSONObject(index).toTask() }.getOrNull()
        }.reversed()
    }

    @Synchronized
    fun audit(action: String, detail: String) {
        val rows = readArray(auditFile)
        rows.put(JSONObject()
            .put("time", System.currentTimeMillis())
            .put("action", action)
            .put("detail", detail.take(500)))
        val trimmed = JSONArray()
        val start = maxOf(0, rows.length() - 250)
        for (i in start until rows.length()) trimmed.put(rows.getJSONObject(i))
        writeArray(auditFile, trimmed)
    }

    @Synchronized
    fun loadAuditLines(): List<String> {
        val rows = readArray(auditFile)
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(row.optLong("time")))
            "$time  ${row.optString("action")}  ${row.optString("detail")}"
        }.reversed()
    }

    private fun readArray(file: File): JSONArray {
        return try { if (file.isFile) JSONArray(file.readText()) else JSONArray() }
        catch (e: Exception) {
            Log.w("CybX-Store", "Could not parse local history; starting an empty list", e)
            JSONArray()
        }
    }

    private fun writeArray(file: File, data: JSONArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(data.toString())
        if (file.exists()) file.delete()
        check(temp.renameTo(file)) { "Could not persist CybX local records" }
    }
}

private fun TaskRecord.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("createdAt", createdAt)
    .put("request", request)
    .put("target", target)
    .put("status", status)
    .put("summary", summary)
    .put("logs", JSONArray(logLines))
    .put("findings", JSONArray().apply {
        findings.forEach { f ->
            put(JSONObject().put("host", f.host).put("port", f.port).put("service", f.service).put("state", f.state))
        }
    })

private fun JSONObject.toTask(): TaskRecord {
    val resultArray = optJSONArray("findings") ?: JSONArray()
    val logsArray = optJSONArray("logs") ?: JSONArray()
    val findings = (0 until resultArray.length()).map { i ->
        val f = resultArray.getJSONObject(i)
        ScanFinding(f.optString("host"), f.optInt("port"), f.optString("service"), f.optString("state", "open"))
    }
    val logs = (0 until logsArray.length()).map { logsArray.optString(it) }
    return TaskRecord(
        optString("id"), optLong("createdAt"), optString("request"), optString("target"),
        optString("status"), findings, optString("summary"), logs
    )
}

class SafeTcpScanner {
    suspend fun run(
        plan: SafeScanPlan,
        onLog: (String) -> Unit
    ): List<ScanFinding> = withContext(Dispatchers.IO) {
        val findings = mutableListOf<ScanFinding>()
        onLog("Scope validated: ${plan.targetCidr}")
        onLog("Hosts: ${plan.hosts.size}; TCP ports: ${plan.ports.joinToString(", ")}")
        for ((hostIndex, host) in plan.hosts.withIndex()) {
            onLog("[${hostIndex + 1}/${plan.hosts.size}] Checking $host")
            for (port in plan.ports) {
                val open = try {
                    Socket().use { socket ->
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(host, port), 350)
                        true
                    }
                } catch (_: Exception) {
                    false
                }
                if (open) {
                    val service = serviceName(port)
                    findings.add(ScanFinding(host, port, service))
                    onLog("OPEN  $host:$port  $service")
                }
            }
        }
        onLog("Scan finished. ${findings.size} open TCP service(s) found.")
        findings
    }

    private fun serviceName(port: Int): String = when (port) {
        22 -> "SSH"
        80 -> "HTTP"
        443 -> "HTTPS"
        445 -> "SMB"
        8080 -> "HTTP-alt"
        8443 -> "HTTPS-alt"
        else -> "unknown"
    }
}

fun newTaskRecord(
    request: String,
    plan: SafeScanPlan,
    findings: List<ScanFinding>,
    summary: String,
    logs: List<String>
) = TaskRecord(
    id = UUID.randomUUID().toString(),
    createdAt = System.currentTimeMillis(),
    request = request,
    target = plan.targetCidr,
    status = "completed",
    findings = findings,
    summary = summary,
    logLines = logs
)
