package com.cybx.os

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cybx.os.agent.CybxRepository
import com.cybx.os.agent.LabScope
import com.cybx.os.agent.SafeScanPlan
import com.cybx.os.agent.SafeTcpScanner
import com.cybx.os.agent.TaskRecord
import com.cybx.os.agent.newTaskRecord
import com.cybx.os.ai.AiConfig
import com.cybx.os.ai.CybxAiClient
import com.cybx.os.reports.ReportExporter
import com.cybx.os.runtime.CybxPaths
import com.cybx.os.runtime.CybxShell
import com.cybx.os.runtime.LocalRuntimeInstaller
import com.cybx.os.runtime.ProotAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val Bg = Color(0xFF080D0B)
private val Panel = Color(0xFF111A16)
private val Green = Color(0xFF72F59A)
private val Muted = Color(0xFF9AB0A1)
private val Amber = Color(0xFFFFC66D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CybXApp() }
    }
}

private enum class Page(val title: String) {
    DASHBOARD("Dashboard"), LINUX("NetHunter Rootless"), AGENT("AI Agent"),
    TERMINAL("Kali Shell"), REPORTS("Reports"), SETTINGS("Settings")
}

@Composable
private fun CybXApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val paths = remember { CybxPaths(context) }
    val repository = remember { CybxRepository(context) }
    val installer = remember { LocalRuntimeInstaller(context) }
    val shell = remember { CybxShell(context, paths) }
    val aiClient = remember { CybxAiClient() }
    val scanner = remember { SafeTcpScanner() }
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var page by remember { mutableStateOf(Page.DASHBOARD) }
    var refresh by remember { mutableIntStateOf(0) }
    var installBusy by remember { mutableStateOf(false) }
    var installStatus by remember { mutableStateOf("Import the verified Kali ARM64 rootfs archive to enable the shell.") }
    var terminalInput by remember { mutableStateOf("") }
    val terminalLines = remember { mutableStateListOf<String>() }
    val liveLogs = remember { mutableStateListOf<String>() }
    var request by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<SafeScanPlan?>(null) }
    var planExplanation by remember { mutableStateOf("") }
    var agentBusy by remember { mutableStateOf(false) }
    var agentError by remember { mutableStateOf<String?>(null) }
    var currentRecord by remember { mutableStateOf<TaskRecord?>(null) }
    var history by remember { mutableStateOf(repository.loadTasks()) }
    var exportRecord by remember { mutableStateOf<TaskRecord?>(null) }
    var exportMessage by remember { mutableStateOf("") }

    val rootfsReady = remember(refresh) { paths.isRootfsInstalled() }
    val runtimeProblem = remember(refresh) { ProotAdapter(context, paths).readinessError() }
    val runtimeReady = runtimeProblem == null

    DisposableEffect(shell) {
        onDispose { shell.close() }
    }

    LaunchedEffect(page, runtimeReady) {
        if (page == Page.TERMINAL && runtimeReady && !shell.isRunning()) {
            terminalLines.add("Starting Kali NetHunter Rootless userspace…")
            shell.start(
                onLine = { line -> if (terminalLines.size > 500) terminalLines.removeAt(0); terminalLines.add(line) },
                onExit = { code -> terminalLines.add("[shell exited: $code]") },
                onError = { message -> terminalLines.add("[startup error] $message") }
            )
        }
    }

    val rootfsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            installBusy = true
            installStatus = "Verifying and extracting Kali rootfs…"
            try {
                withContext(Dispatchers.IO) {
                    installer.importRootfs(uri) { message ->
                        mainHandler.post { installStatus = message }
                    }
                }
                installStatus = "Kali rootless userspace installed successfully."
                repository.audit("Rootfs imported", "ARM64 rootfs verification and extraction succeeded")
                refresh++
            } catch (e: Exception) {
                installStatus = e.message ?: "Rootfs import failed"
                repository.audit("Rootfs import failed", installStatus)
            } finally {
                installBusy = false
            }
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val record = exportRecord
        if (uri != null && record != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { ReportExporter.writePdf(record, it) }
                        ?: error("Cannot create the selected PDF file")
                }
                exportMessage = "PDF report exported."
                repository.audit("PDF exported", "task=${record.id}")
            } catch (e: Exception) {
                exportMessage = "PDF export failed: ${e.message}"
            }
        }
    }

    val htmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        val record = exportRecord
        if (uri != null && record != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(ReportExporter.html(record).toByteArray(Charsets.UTF_8))
                    } ?: error("Cannot create the selected HTML file")
                }
                exportMessage = "HTML report exported."
                repository.audit("HTML exported", "task=${record.id}")
            } catch (e: Exception) {
                exportMessage = "HTML export failed: ${e.message}"
            }
        }
    }

    fun exportPdf(record: TaskRecord) {
        exportRecord = record
        pdfLauncher.launch("CybX-report-${record.id.take(8)}.pdf")
    }
    fun exportHtml(record: TaskRecord) {
        exportRecord = record
        htmlLauncher.launch("CybX-report-${record.id.take(8)}.html")
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Green, background = Bg, surface = Panel,
        onBackground = Color.White, onSurface = Color.White, secondary = Amber
    )) {
        Scaffold(
            containerColor = Bg,
            topBar = {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF0B120F)).padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("CYBX MOBILE AI", color = Green, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        Text(page.title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("v0.5.0", color = Muted, fontSize = 11.sp)
                }
            },
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0B120F), tonalElevation = 0.dp) {
                    Page.values().forEach { item ->
                        NavigationBarItem(
                            selected = page == item,
                            onClick = { page = item },
                            icon = {
                                Icon(when (item) {
                                    Page.DASHBOARD -> Icons.Default.Dashboard
                                    Page.LINUX -> Icons.Default.Terminal
                                    Page.AGENT -> Icons.Default.AutoAwesome
                                    Page.TERMINAL -> Icons.Default.Code
                                    Page.REPORTS -> Icons.Default.Assessment
                                    Page.SETTINGS -> Icons.Default.Settings
                                }, contentDescription = item.title)
                            },
                            label = { Text(item.title, fontSize = 8.sp, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Green, selectedTextColor = Green)
                        )
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp, vertical = 10.dp)) {
                when (page) {
                    Page.DASHBOARD -> DashboardPage(
                        runtimeReady, rootfsReady, shell.isRunning(), history,
                        onLinux = { page = Page.LINUX }, onAgent = { page = Page.AGENT }, onTerminal = { page = Page.TERMINAL },
                        onReports = { page = Page.REPORTS }
                    )
                    Page.LINUX -> LinuxPage(
                        runtimeReady, runtimeProblem, installStatus, installBusy,
                        onPick = { rootfsPicker.launch(arrayOf("application/x-xz", "application/octet-stream", "*/*")) },
                        onDocs = { openUrl(context, "https://www.kali.org/docs/nethunter/nethunter-rootless/") },
                        onRefresh = { refresh++ }
                    )
                    Page.AGENT -> AgentPage(
                        request = request, onRequest = { request = it },
                        scopeText = repository.loadScope(),
                        plan = plan, explanation = planExplanation, logs = liveLogs,
                        busy = agentBusy, error = agentError,
                        onDraft = {
                            agentError = null
                            planExplanation = ""
                            try {
                                val draft = LabScope.draft(request, repository.loadScope())
                                plan = draft
                                repository.audit("Task drafted", "target=${draft.targetCidr}; hosts=${draft.hosts.size}; ports=${draft.ports.joinToString(",")}")
                                planExplanation = "Validated private-lab TCP service check. Only the displayed target and fixed safe port list will be scanned."
                                scope.launch {
                                    val aiText = runCatching { aiClient.explainTask(request, draft.targetCidr, repository.loadAiConfig()) }.getOrNull()
                                    if (!aiText.isNullOrBlank()) planExplanation = aiText
                                }
                            } catch (e: Exception) {
                                plan = null
                                agentError = e.message ?: "Could not create a safe task."
                                repository.audit("Task blocked", agentError ?: "Unknown validation error")
                            }
                        },
                        onRun = {
                            val approved = plan
                            if (approved != null) scope.launch {
                                agentBusy = true
                                agentError = null
                                currentRecord = null
                                liveLogs.clear()
                                liveLogs.add("User approved scan: ${approved.targetCidr}")
                                repository.audit("Scan approved", "target=${approved.targetCidr}; hosts=${approved.hosts.size}")
                                try {
                                    val findings = scanner.run(approved) { line ->
                                        mainHandler.post {
                                            if (liveLogs.size > 500) liveLogs.removeAt(0)
                                            liveLogs.add(line)
                                        }
                                    }
                                    val observed = findings.map { "${it.host} TCP/${it.port} ${it.service} open" }
                                    val aiSummary = runCatching {
                                        aiClient.analyseResults(approved.targetCidr, observed, repository.loadAiConfig())
                                    }.getOrNull()
                                    val fallback = if (findings.isEmpty()) {
                                        "No open ports were observed on the selected TCP ports. This does not prove the host is offline or secure."
                                    } else {
                                        "Observed ${findings.size} open TCP service(s). Review service exposure and confirm each service is expected. No exploitation was performed."
                                    }
                                    val summary = aiSummary?.takeIf { it.isNotBlank() } ?: fallback
                                    val record = newTaskRecord(request, approved, findings, summary, liveLogs.toList())
                                    repository.saveTask(record)
                                    currentRecord = record
                                    history = repository.loadTasks()
                                    liveLogs.add("Report ready. Open Reports to export PDF or HTML.")
                                    plan = null
                                } catch (e: Exception) {
                                    agentError = e.message ?: "Task execution failed"
                                    repository.audit("Scan failed", agentError ?: "Unknown error")
                                } finally {
                                    agentBusy = false
                                }
                            }
                        },
                        onCancel = { plan = null; planExplanation = "" },
                        onReports = { page = Page.REPORTS }
                    )
                    Page.TERMINAL -> TerminalPage(
                        ready = runtimeReady, problem = runtimeProblem,
                        lines = terminalLines, input = terminalInput,
                        onInput = { terminalInput = it },
                        onSend = {
                            val command = terminalInput.trim()
                            if (command.isNotEmpty()) {
                                terminalLines.add("root@cybx:~# $command")
                                if (!shell.send(command)) terminalLines.add("[shell is not ready; wait or reopen Terminal]")
                                terminalInput = ""
                            }
                        },
                        onRestart = { shell.close(); page = Page.DASHBOARD; terminalLines.add("[shell stopped; tap Terminal to start a fresh session]") }
                    )
                    Page.REPORTS -> ReportsPage(
                        history = history,
                        auditLines = repository.loadAuditLines(),
                        message = exportMessage,
                        onPdf = ::exportPdf,
                        onHtml = ::exportHtml,
                        onRefresh = { history = repository.loadTasks() }
                    )
                    Page.SETTINGS -> SettingsPage(
                        repository = repository,
                        onSaved = { repository.audit("Settings saved", "lab scope and AI provider settings updated") }
                    )
                }
            }
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
private fun DashboardPage(
    runtimeReady: Boolean,
    rootfsReady: Boolean,
    shellRunning: Boolean,
    history: List<TaskRecord>,
    onLinux: () -> Unit,
    onAgent: () -> Unit,
    onTerminal: () -> Unit,
    onReports: () -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, modifier = Modifier.size(54.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("ROOTLESS SECURITY WORKSTATION", color = Green, fontSize = 10.sp, letterSpacing = 1.sp)
                            Text("CybX Mobile AI", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text("Kali NetHunter Rootless userspace · ARM64 · Android host kernel", color = Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill("PRoot", runtimeReady)
                        StatusPill("Kali rootfs", rootfsReady)
                        StatusPill("Shell", shellRunning)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onAgent, modifier = Modifier.weight(1f)) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(5.dp)); Text("AI Agent") }
                        OutlinedButton(onClick = onLinux, modifier = Modifier.weight(1f)) { Text("Linux Setup") }
                    }
                    OutlinedButton(onClick = onTerminal, modifier = Modifier.fillMaxWidth(), enabled = runtimeReady) {
                        Icon(Icons.Default.Terminal, null); Spacer(Modifier.width(6.dp)); Text("Open Kali Shell")
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard(Modifier.weight(1f), "TASKS", history.size.toString())
                MetricCard(Modifier.weight(1f), "OPEN PORTS", history.sumOf { it.openCount }.toString())
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("SAFETY BOUNDARY", color = Amber, fontWeight = FontWeight.Bold)
                    Text("AI tasks are limited to explicit RFC1918 private lab scopes. Each scan needs user approval, is limited to 16 hosts and a fixed TCP port list, and is written to the audit log.", color = Muted, fontSize = 12.sp)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("RECENT TASKS", color = Green, fontWeight = FontWeight.Bold)
                TextButton(onClick = onReports) { Text("All reports") }
            }
        }
        if (history.isEmpty()) item { Text("No tasks yet. Configure an approved lab scope in Settings, then open AI Agent.", color = Muted) }
        items(history.take(5)) { record ->
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(12.dp)) {
                    Text(record.target, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("${record.timeLabel} · ${record.openCount} open service(s)", color = Muted, fontSize = 11.sp)
                    Text(record.summary, color = Muted, fontSize = 12.sp, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, ready: Boolean) {
    Surface(color = if (ready) Color(0xFF173A27) else Color(0xFF3B2C19), shape = RoundedCornerShape(40.dp)) {
        Text("$label: ${if (ready) "READY" else "SETUP"}", color = if (ready) Green else Amber, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
    }
}

@Composable
private fun MetricCard(modifier: Modifier, title: String, value: String) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(value, color = Green, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LinuxPage(
    ready: Boolean,
    problem: String?,
    status: String,
    busy: Boolean,
    onPick: () -> Unit,
    onDocs: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoCard("NET HUNTER ROOTLESS", "Stock Android / no root / ARM64 PRoot. This app runs a Kali userspace; it does not flash a kernel or replace Android.")
        StatusPill("Runtime", ready)
        if (problem != null) Text(problem, color = Amber, fontSize = 12.sp)
        Text(status, color = Muted, fontSize = 12.sp)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(onClick = onPick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(7.dp)); Text("Import verified Kali ARM64 rootfs")
        }
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Re-check runtime") }
        TextButton(onClick = onDocs) { Text("Official Kali NetHunter Rootless instructions ↗") }
        Divider(color = Color(0xFF294133))
        Text("The Kali NetHunter project repository you shared builds Recovery/Magisk installer packages for supported devices. An unrooted Xiaomi uses the separate Rootless userspace route; kernel-dependent Wi-Fi monitor mode and injection are not available from this app on stock Android.", color = Muted, fontSize = 12.sp)
        Text("Rootfs extraction is checksum-verified and staged before activation. Keep a copy of your .tar.xz archive outside CybX before uninstalling or resetting app data.", color = Amber, fontSize = 12.sp)
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, color = Green, fontWeight = FontWeight.Bold)
            Text(body, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AgentPage(
    request: String,
    onRequest: (String) -> Unit,
    scopeText: String,
    plan: SafeScanPlan?,
    explanation: String,
    logs: SnapshotStateList<String>,
    busy: Boolean,
    error: String?,
    onDraft: () -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onReports: () -> Unit
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("বাংলা বা English-এ task লিখুন। AI task বোঝাতে সাহায্য করবে; execution policy app-এর code enforce করে।", color = Muted, fontSize = 12.sp)
        OutlinedTextField(
            value = request, onValueChange = onRequest,
            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
            label = { Text("Security task / বাংলা বা English") },
            placeholder = { Text("Example: scan 192.168.1.10 / 192.168.1.10 পোর্ট স্ক্যান করো") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDraft, enabled = !busy && request.isNotBlank(), modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(5.dp)); Text("Create safe plan")
            }
            OutlinedButton(onClick = onCancel, enabled = plan != null) { Text("Clear") }
        }
        if (error != null) Text(error, color = Color(0xFFFF8A80), fontSize = 12.sp)
        if (plan != null) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("APPROVAL REQUIRED", color = Amber, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text("Target: ${plan.targetCidr}", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Hosts: ${plan.hosts.joinToString(", ")}", color = Muted, fontSize = 11.sp)
                    Text("TCP ports: ${plan.ports.joinToString(", ")}", color = Muted, fontSize = 11.sp)
                    Text(explanation, color = Color.White, fontSize = 12.sp)
                    Text("Approved scope: $scopeText", color = Green, fontSize = 10.sp)
                    Button(onClick = onRun, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(6.dp)); Text(if (busy) "Running authorised scan…" else "Approve & run scan")
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("LIVE TASK LOG", color = Green, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            TextButton(onClick = onReports) { Text("Reports") }
        }
        Card(Modifier.weight(1f).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF050907))) {
            LazyColumn(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (logs.isEmpty()) item { Text("Waiting for a task…", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                items(logs.toList()) { line ->
                    Text(line, color = if (line.contains("OPEN")) Green else Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun TerminalPage(
    ready: Boolean,
    problem: String?,
    lines: SnapshotStateList<String>,
    input: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onRestart: () -> Unit
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!ready) {
            Text(problem ?: "Kali rootless runtime is not ready. Open Linux Setup first.", color = Amber, fontSize = 12.sp)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("KALI ROOTLESS SHELL", color = Green, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                TextButton(onClick = onRestart) { Text("Close session") }
            }
            Card(Modifier.weight(1f).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF050907))) {
                LazyColumn(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(lines.toList()) { line ->
                        Text(line, color = Color(0xFFD8E8DC), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = input, onValueChange = onInput, modifier = Modifier.weight(1f),
                    singleLine = true, label = { Text("Kali command") },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                )
                IconButton(onClick = onSend, enabled = input.isNotBlank()) { Icon(Icons.Default.Send, "Run", tint = Green) }
            }
            Text("Persistent shell via PRoot / ProcessBuilder. Full-screen TUI applications are not supported in this phase.", color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ReportsPage(
    history: List<TaskRecord>,
    auditLines: List<String>,
    message: String,
    onPdf: (TaskRecord) -> Unit,
    onHtml: (TaskRecord) -> Unit,
    onRefresh: () -> Unit
) {
    var showAudit by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("TASK HISTORY", color = Green, fontWeight = FontWeight.Bold)
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
        if (message.isNotBlank()) Text(message, color = Amber, fontSize = 11.sp)
        if (history.isEmpty()) Text("No security task reports yet.", color = Muted)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(history) { record ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(record.target, color = Color.White, fontWeight = FontWeight.Bold)
                        Text("${record.timeLabel} · ${record.openCount} open service(s) · ${record.status}", color = Muted, fontSize = 10.sp)
                        Text(record.summary, color = Muted, fontSize = 11.sp, maxLines = 3)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onPdf(record) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(5.dp)); Text("PDF") }
                            OutlinedButton(onClick = { onHtml(record) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Code, null); Spacer(Modifier.width(5.dp)); Text("HTML") }
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = { showAudit = !showAudit }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showAudit) "Hide audit log" else "Show audit log (${auditLines.size})")
        }
        if (showAudit) {
            Card(Modifier.heightIn(max = 180.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF050907))) {
                LazyColumn(Modifier.fillMaxWidth().padding(8.dp)) {
                    items(auditLines) { line -> Text(line, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 9.sp) }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(repository: CybxRepository, onSaved: () -> Unit) {
    var scopes by remember { mutableStateOf(repository.loadScope()) }
    val initial = remember { repository.loadAiConfig() }
    var provider by remember { mutableStateOf(initial.provider) }
    var ollamaUrl by remember { mutableStateOf(initial.ollamaUrl) }
    var ollamaModel by remember { mutableStateOf(initial.ollamaModel) }
    var cloudUrl by remember { mutableStateOf(initial.cloudUrl) }
    var cloudModel by remember { mutableStateOf(initial.cloudModel) }
    var cloudKey by remember { mutableStateOf(initial.cloudApiKey) }
    var message by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("AUTHORISED LAB SCOPE", color = Green, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = scopes, onValueChange = { scopes = it }, modifier = Modifier.fillMaxWidth(),
            minLines = 2, label = { Text("Approved private CIDR(s)") },
            placeholder = { Text("192.168.1.0/24") }
        )
        Text("Only RFC1918 private ranges are accepted. Each task target must fit inside an approved range and contain at most 16 hosts.", color = Muted, fontSize = 10.sp)
        Divider(color = Color(0xFF294133))
        Text("AI PROVIDER", color = Green, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = provider == "ollama", onClick = { provider = "ollama" }, label = { Text("Local Ollama") })
            FilterChip(selected = provider == "cloud", onClick = { provider = "cloud" }, label = { Text("Cloud AI") })
        }
        if (provider == "ollama") {
            OutlinedTextField(ollamaUrl, { ollamaUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Ollama URL") })
            OutlinedTextField(ollamaModel, { ollamaModel = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Ollama model") })
            Text("For Ollama on a PC, enter its private LAN address, e.g. http://192.168.1.5:11434. The app only allows HTTP to localhost/private LAN addresses.", color = Muted, fontSize = 10.sp)
        } else {
            OutlinedTextField(cloudUrl, { cloudUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text("OpenAI-compatible HTTPS endpoint") })
            OutlinedTextField(cloudModel, { cloudModel = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Cloud model") })
            OutlinedTextField(cloudKey, { cloudKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text("API key") }, visualTransformation = PasswordVisualTransformation())
            Text("The API key is encrypted with Android Keystore before it is stored locally. Cloud requests are HTTPS-only.", color = Muted, fontSize = 10.sp)
        }
        Button(onClick = {
            try {
                scopes = LabScope.validateScopeText(scopes)
                repository.saveScope(scopes)
                repository.saveAiConfig(AiConfig(provider, ollamaUrl.trim(), ollamaModel.trim(), cloudUrl.trim(), cloudModel.trim(), cloudKey.trim()))
                message = "Settings saved. Lab scope and AI provider updated."
                onSaved()
            } catch (e: Exception) {
                message = e.message ?: "Settings could not be saved."
            }
        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text("Save settings") }
        if (message.isNotBlank()) Text(message, color = Amber, fontSize = 11.sp)
        Divider(color = Color(0xFF294133))
        Text("PLATFORM LIMITATIONS", color = Amber, fontWeight = FontWeight.Bold)
        Text("CybX is a rootless Android userspace, not a bootable OS. USB Wi-Fi monitor mode, packet injection, HID and kernel-level features require compatible rooted/device kernels and are not promised on stock Xiaomi firmware.", color = Muted, fontSize = 11.sp)
        Text("AI model output is advisory. It never becomes a shell command. Only the built-in bounded private-lab TCP scan task can be run by the Agent.", color = Muted, fontSize = 11.sp)
    }
}
