package com.cybx.os.agent

import android.content.Context
import com.cybx.os.runtime.CybxPRootRunner
import com.cybx.os.runtime.CybxPaths
import com.cybx.os.runtime.CybxShell
import com.cybx.os.runtime.ProotAdapter
import com.cybx.os.reports.ReportExporter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class CybxAgentToolCall(
    val id: String,
    val name: String,
    val arguments: JSONObject
)

data class CybxAgentUiMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String
)

data class CybxPendingAction(
    val call: CybxAgentToolCall,
    val title: String,
    val description: String
)

object CybxAgentToolRegistry {
    private fun stringField(description: String) = JSONObject()
        .put("type", "string").put("description", description)

    private fun objectSchema(properties: JSONObject = JSONObject(), required: JSONArray = JSONArray()) =
        JSONObject().put("type", "object").put("properties", properties).put("required", required)

    private fun tool(name: String, description: String, parameters: JSONObject) = JSONObject()
        .put("type", "function")
        .put("function", JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", parameters)
        )

    fun definitions(): JSONArray {
        val tools = JSONArray()
        tools.put(tool("get_app_status", "Read CybX runtime, Kali rootfs, AI provider and approved lab scope status.", objectSchema()))
        tools.put(tool("kali_diagnostics", "Run read-only diagnostics inside the installed Kali PRoot userspace.", objectSchema()))
        val commandProps = JSONObject().put("command", JSONObject()
            .put("type", "string")
            .put("enum", JSONArray(listOf("pwd", "uname", "os_release", "identity", "disk", "memory", "ip_addresses", "package_audit", "python_version", "git_version")))
            .put("description", "A supported read-only Kali diagnostic command.")
        )
        tools.put(tool("run_kali_diagnostic", "Run one item from the supported read-only Kali diagnostic command list.", objectSchema(commandProps, JSONArray().put("command"))))
        val searchProps = JSONObject().put("query", stringField("Package name or short search phrase, maximum 80 characters."))
        tools.put(tool("search_kali_packages", "Search package names in the current Kali package index; does not install anything.", objectSchema(searchProps, JSONArray().put("query"))))
        tools.put(tool("update_kali_package_index", "Run apt-get update inside Kali. This changes package metadata and requires user approval.", objectSchema()))
        val packagesSchema = JSONObject().put("type", "array").put("items", stringField("Kali package name."))
            .put("minItems", 1).put("maxItems", 8)
        val installProps = JSONObject().put("packages", packagesSchema)
        tools.put(tool("install_kali_packages", "Install one to eight package names from the configured Kali repositories. Requires user approval.", objectSchema(installProps, JSONArray().put("packages"))))
        val scanProps = JSONObject().put("target", stringField("Private IPv4 address or CIDR inside the user's already-approved lab scope. Maximum 16 target hosts."))
        tools.put(tool("scan_authorized_private_target", "Run the built-in bounded TCP service check against an RFC1918 target inside the saved approved scope. Requires user approval.", objectSchema(scanProps, JSONArray().put("target"))))
        val limitProps = JSONObject().put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 20))
        tools.put(tool("get_recent_tasks", "Read recent scan/task history and their summaries.", objectSchema(limitProps)))
        tools.put(tool("list_workspace_files", "List files in the private /root/cybx-workspace folder. The workspace is isolated inside the app's Kali rootfs.", objectSchema()))
        val readProps = JSONObject().put("path", stringField("Relative file path inside /root/cybx-workspace. Absolute paths and parent traversal are not allowed."))
        tools.put(tool("read_workspace_file", "Read a small text file from the private CybX workspace.", objectSchema(readProps, JSONArray().put("path"))))
        val writeProps = JSONObject()
            .put("path", stringField("Relative file path inside /root/cybx-workspace."))
            .put("content", stringField("Text content to write, maximum 100 KB."))
        tools.put(tool("write_workspace_file", "Create or replace a text file inside the private workspace. Requires user approval.", objectSchema(writeProps, JSONArray().put("path").put("content"))))
        tools.put(tool("save_latest_report_to_workspace", "Create a Markdown report file for the latest completed task inside /root/cybx-workspace. Requires user approval.", objectSchema()))
        tools.put(tool("export_latest_report_pdf", "Export the latest completed task report as a PDF file into /root/cybx-workspace. Requires user approval.", objectSchema()))
        tools.put(tool("export_latest_report_html", "Export the latest completed task report as an HTML file into /root/cybx-workspace. Requires user approval.", objectSchema()))
        return tools
    }

    fun requiresApproval(name: String): Boolean = name in setOf(
        "update_kali_package_index",
        "install_kali_packages",
        "scan_authorized_private_target",
        "write_workspace_file",
        "save_latest_report_to_workspace",
        "export_latest_report_pdf",
        "export_latest_report_html"
    )
}

class CybxAgentToolRunner(
    private val context: Context,
    private val paths: CybxPaths,
    private val repository: CybxRepository,
    private val scanner: SafeTcpScanner,
    private val shell: CybxShell
) {
    private val proot = CybxPRootRunner(context, paths)

    suspend fun execute(call: CybxAgentToolCall): String {
        return try {
            when (call.name) {
                "get_app_status" -> appStatus()
                "kali_diagnostics" -> diagnostics()
                "run_kali_diagnostic" -> runDiagnostic(call.arguments.optString("command"))
                "search_kali_packages" -> searchPackages(call.arguments.optString("query"))
                "update_kali_package_index" -> proot.run(listOf("/usr/bin/apt-get", "update"), 240)
                "install_kali_packages" -> installPackages(call.arguments.optJSONArray("packages"))
                "scan_authorized_private_target" -> scanTarget(call.arguments.optString("target"))
                "get_recent_tasks" -> recentTasks(call.arguments.optInt("limit", 10).coerceIn(1, 20))
                "list_workspace_files" -> listWorkspace()
                "read_workspace_file" -> readWorkspace(call.arguments.optString("path"))
                "write_workspace_file" -> writeWorkspace(call.arguments.optString("path"), call.arguments.optString("content"))
                "save_latest_report_to_workspace" -> saveLatestReport()
                "export_latest_report_pdf" -> exportLatestReport("pdf")
                "export_latest_report_html" -> exportLatestReport("html")
                else -> error("Unknown CybX tool: " + call.name)
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", (e.message ?: e.javaClass.simpleName).take(1200)).toString()
        }
    }

    private fun appStatus(): String {
        val problem = ProotAdapter(context, paths).readinessError()
        return JSONObject()
            .put("ok", true)
            .put("app", "CybX Mobile AI")
            .put("runtime", if (problem == null) "ready" else "not_ready")
            .put("runtime_message", problem ?: "Kali ARM64 PRoot runtime is ready")
            .put("rootfs_installed", paths.isRootfsInstalled())
            .put("terminal_running", shell.isRunning())
            .put("approved_lab_scope", repository.loadScope())
            .put("ai_provider", repository.loadAiConfig().provider)
            .put("recent_task_count", repository.loadTasks().size)
            .put("platform", "Android ARM64; rootless PRoot; Android host kernel")
            .toString()
    }

    private suspend fun diagnostics(): String {
        val pieces = listOf(
            "Architecture" to listOf("/usr/bin/uname", "-m"),
            "OS release" to listOf("/bin/cat", "/etc/os-release"),
            "Identity" to listOf("/usr/bin/id"),
            "Disk space" to listOf("/bin/df", "-h", "/"),
            "Package audit" to listOf("/usr/bin/dpkg", "--audit")
        )
        val output = JSONArray()
        for ((label, argv) in pieces) {
            output.put(JSONObject().put("check", label).put("result", proot.run(argv, 30, 5000)))
        }
        return JSONObject().put("ok", true).put("diagnostics", output).toString()
    }

    private suspend fun runDiagnostic(command: String): String {
        val argv = when (command) {
            "pwd" -> listOf("/bin/pwd")
            "uname" -> listOf("/usr/bin/uname", "-a")
            "os_release" -> listOf("/bin/cat", "/etc/os-release")
            "identity" -> listOf("/usr/bin/id")
            "disk" -> listOf("/bin/df", "-h")
            "memory" -> listOf("/usr/bin/free", "-h")
            "ip_addresses" -> listOf("/usr/sbin/ip", "-brief", "address")
            "package_audit" -> listOf("/usr/bin/dpkg", "--audit")
            "python_version" -> listOf("/usr/bin/python3", "--version")
            "git_version" -> listOf("/usr/bin/git", "--version")
            else -> error("That diagnostic is not on the supported read-only command list.")
        }
        return proot.run(argv, 30, 9000)
    }

    private suspend fun searchPackages(query: String): String {
        val clean = query.trim()
        require(clean.isNotEmpty() && clean.length <= 80) { "Enter a package search phrase up to 80 characters." }
        require(clean.matches(Regex("[A-Za-z0-9+_. -]+"))) { "Package search contains unsupported characters." }
        return proot.run(listOf("/usr/bin/apt-cache", "search", clean), 45, 10_000)
    }

    private suspend fun installPackages(array: JSONArray?): String {
        require(array != null && array.length() in 1..8) { "Choose between 1 and 8 package names." }
        val names = (0 until array.length()).map { array.optString(it).trim() }
        require(names.all { it.matches(Regex("[a-z0-9][a-z0-9+.-]{0,63}")) }) {
            "Only standard Kali package names are accepted; shell options and command fragments are blocked."
        }
        return proot.run(listOf("/usr/bin/apt-get", "install", "-y", "--") + names, 300, 20_000)
    }

    private suspend fun scanTarget(target: String): String {
        val plan = LabScope.draft("scan " + target.trim(), repository.loadScope())
        val logs = mutableListOf<String>()
        val findings = scanner.run(plan) { line -> if (logs.size < 500) logs.add(line) }
        val observed = findings.map { it.host + " TCP/" + it.port + " " + it.service + " open" }
        val summary = if (findings.isEmpty()) {
            "No open ports were observed on the selected TCP ports. This does not prove the host is offline or secure."
        } else {
            "Observed " + findings.size + " open TCP service(s). Review service exposure and confirm each service is expected. No exploitation was performed."
        }
        val record = newTaskRecord(plan.request, plan, findings, summary, logs)
        repository.saveTask(record)
        return JSONObject()
            .put("ok", true)
            .put("target", plan.targetCidr)
            .put("hosts_checked", plan.hosts.size)
            .put("ports_checked", JSONArray(plan.ports))
            .put("open_services", JSONArray(observed))
            .put("summary", summary)
            .put("task_id", record.id)
            .toString()
    }

    private fun recentTasks(limit: Int): String {
        val items = repository.loadTasks().take(limit).map { task ->
            JSONObject()
                .put("id", task.id)
                .put("time", task.timeLabel)
                .put("target", task.target)
                .put("status", task.status)
                .put("open_count", task.openCount)
                .put("summary", task.summary)
        }
        return JSONObject().put("ok", true).put("tasks", JSONArray(items)).toString()
    }

    private fun workspaceDir(): File {
        val root = File(paths.rootfs, "root/cybx-workspace").canonicalFile
        if (!root.exists()) check(root.mkdirs()) { "Could not create the private CybX workspace." }
        check(root.isDirectory) { "Workspace path is not a directory." }
        return root
    }

    private fun safeWorkspaceFile(relative: String): File {
        val clean = relative.trim()
        require(clean.isNotEmpty() && clean.length <= 180) { "Enter a relative workspace file path." }
        require(!clean.startsWith("/") && !clean.contains("\\") && clean.split("/").none { it == ".." || it.isBlank() }) {
            "Absolute paths, parent traversal and empty path segments are blocked."
        }
        val base = workspaceDir().canonicalFile
        val file = File(base, clean).canonicalFile
        require(file.path.startsWith(base.path + File.separator)) { "Path escapes the CybX workspace." }
        return file
    }

    private fun listWorkspace(): String {
        val base = workspaceDir()
        val rows = (base.listFiles() ?: emptyArray()).take(200).map { file ->
            JSONObject()
                .put("name", file.name)
                .put("type", if (file.isDirectory) "directory" else "file")
                .put("bytes", if (file.isFile) file.length() else 0)
        }
        return JSONObject().put("ok", true).put("workspace", "/root/cybx-workspace").put("entries", JSONArray(rows)).toString()
    }

    private fun readWorkspace(relative: String): String {
        val file = safeWorkspaceFile(relative)
        require(file.isFile) { "Workspace file does not exist." }
        require(file.length() <= 128_000) { "File is larger than the 128 KB text-read limit." }
        return JSONObject()
            .put("ok", true)
            .put("path", relative)
            .put("content", file.readText(Charsets.UTF_8))
            .toString()
    }

    private fun writeWorkspace(relative: String, content: String): String {
        require(content.length <= 100_000) { "Workspace text is larger than the 100 KB write limit." }
        val file = safeWorkspaceFile(relative)
        file.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "Could not create workspace folder." } }
        file.writeText(content, Charsets.UTF_8)
        repository.audit("Agent wrote workspace file", relative)
        return JSONObject().put("ok", true).put("path", relative).put("characters_written", content.length).toString()
    }

    private fun exportLatestReport(format: String): String {
        val latest = repository.loadTasks().firstOrNull() ?: error("No completed task report exists yet.")
        val extension = if (format == "pdf") "pdf" else "html"
        val fileName = "report-" + latest.id.take(8) + "." + extension
        val file = safeWorkspaceFile(fileName)
        if (format == "pdf") {
            java.io.FileOutputStream(file).use { ReportExporter.writePdf(latest, it) }
        } else {
            file.writeText(ReportExporter.html(latest), Charsets.UTF_8)
        }
        repository.audit("Agent exported " + extension.uppercase() + " report", fileName)
        return JSONObject()
            .put("ok", true)
            .put("format", extension)
            .put("path", "/root/cybx-workspace/" + fileName)
            .put("bytes", file.length())
            .toString()
    }

    private fun saveLatestReport(): String {
        val latest = repository.loadTasks().firstOrNull() ?: error("No completed task report exists yet.")
        val fileName = "report-" + latest.id.take(8) + ".md"
        val body = buildString {
            appendLine("# CybX Security Lab Report")
            appendLine()
            appendLine("- Task: " + latest.id)
            appendLine("- Time: " + latest.timeLabel)
            appendLine("- Target: " + latest.target)
            appendLine("- Status: " + latest.status)
            appendLine("- Open services: " + latest.openCount)
            appendLine()
            appendLine("## Summary")
            appendLine(latest.summary)
            appendLine()
            appendLine("## Findings")
            if (latest.findings.isEmpty()) appendLine("No open services observed on the fixed TCP port list.")
            latest.findings.forEach { finding ->
                appendLine("- " + finding.host + " TCP/" + finding.port + " " + finding.service + " " + finding.state)
            }
            appendLine()
            appendLine("## Execution log")
            latest.logLines.take(500).forEach { appendLine("- " + it) }
            appendLine()
            appendLine("This report contains bounded defensive observations only; it does not certify a host as secure.")
        }
        val file = safeWorkspaceFile(fileName)
        file.writeText(body, Charsets.UTF_8)
        repository.audit("Agent saved report", fileName)
        return JSONObject().put("ok", true).put("path", "/root/cybx-workspace/" + fileName).toString()
    }
}
