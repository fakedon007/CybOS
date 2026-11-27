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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.cybx.os.agent.CybxAgentController
import com.cybx.os.agent.CybxAgentToolRunner
import com.cybx.os.agent.CybxAgentUiMessage
import com.cybx.os.agent.CybxPendingAction
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
    val agentToolRunner = remember { CybxAgentToolRunner(context, paths, repository, scanner, shell) }
    val agentController = remember { CybxAgentController(context, aiClient, agentToolRunner) }
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
    val agentChatMessages = remember { mutableStateListOf<CybxAgentUiMessage>().apply { addAll(agentController.snapshot()) } }
    var agentChatInput by remember { mutableStateOf("") }
    var agentChatBusy by remember { mutableStateOf(false) }
    var pendingToolApproval by remember { mutableStateOf<CybxPendingAction?>(null) }

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
                    Text("v0.6.1", color = Muted, fontSize = 11.sp)
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
                    Page.AGENT -> ConversationalAgentPage(
                        messages = agentChatMessages,
                        input = agentChatInput,
                        busy = agentChatBusy,
                        pending = pendingToolApproval,
                        onInput = { agentChatInput = it },
                        onSend = {
                            val message = agentChatInput.trim()
                            if (message.isNotEmpty() && !agentChatBusy && pendingToolApproval == null) {
                                agentChatInput = ""
                                scope.launch {
                                    agentChatBusy = true
                                    try {
                                        agentController.send(
                                            message,
                                            repository.loadAiConfig(),
                                            { item -> agentChatMessages.add(item) },
                                            { pendingToolApproval = it }
                                        )
                                    } catch (e: Exception) {
                                        agentChatMessages.add(CybxAgentUiMessage(role = "assistant", text = "AI Agent error: " + (e.message ?: "Unknown error")))
                                        repository.audit("Agent request failed", e.message ?: "Unknown error")
                                    } finally {
                                        agentChatBusy = false
                                    }
                                }
                            }
                        },
                        onResolve = { approve ->
                            if (!agentChatBusy && pendingToolApproval != null) {
                                scope.launch {
                                    agentChatBusy = true
                                    try {
                                        agentController.resolvePending(
                                            approve,
                                            repository.loadAiConfig(),
                                            { item -> agentChatMessages.add(item) },
                                            { pendingToolApproval = it }
                                        )
                                    } catch (e: Exception) {
                                        agentChatMessages.add(CybxAgentUiMessage(role = "assistant", text = "Action error: " + (e.message ?: "Unknown error")))
                                    } finally {
                                        agentChatBusy = false
                                    }
                                }
                            }
                        },
                        onNewChat = {
                            agentController.clearConversation()
                            agentChatMessages.clear()
                            pendingToolApproval = null
                            agentChatInput = ""
                        },
                        onReports = { history = repository.loadTasks(); page = Page.REPORTS }
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
                        aiClient = aiClient,
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
    val focusManager = LocalFocusManager.current
    var inputFocused by remember { mutableStateOf(false) }

    fun submitCommand() {
        if (input.isNotBlank()) {
            onSend()
            focusManager.clearFocus()
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!ready) {
            Text(problem ?: "Kali rootless runtime is not ready. Open Linux Setup first.", color = Amber, fontSize = 12.sp)
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = Green, modifier = Modifier.size(18.dp))
                    Text("KALI ROOTLESS SHELL", color = Green, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp)
                }
                TextButton(onClick = onRestart) { Text("Close session") }
            }
            Card(
                Modifier.weight(1f).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF050907)),
                shape = RoundedCornerShape(18.dp)
            ) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(lines.toList()) { line ->
                        Text(line, color = Color(0xFFD8E8DC), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF0D1711),
                border = BorderStroke(1.dp, if (inputFocused) Green.copy(alpha = 0.9f) else Color(0xFF294133))
            ) {
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Icon(Icons.Default.Code, contentDescription = null, tint = Green, modifier = Modifier.size(17.dp))
                            Text("COMMAND CONSOLE", color = Color(0xFFDCEBE0), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        }
                        Surface(shape = RoundedCornerShape(50.dp), color = Color(0xFF173322)) {
                            Row(
                                Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(Modifier.size(6.dp).background(Green, CircleShape))
                                Text("PRoot", color = Green, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF050B07),
                            border = BorderStroke(1.dp, if (inputFocused) Green.copy(alpha = 0.75f) else Color(0xFF20392B))
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("root@cybx", color = Green, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(":~#", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                Spacer(Modifier.width(9.dp))
                                Box(Modifier.weight(1f)) {
                                    if (input.isEmpty()) {
                                        Text(
                                            "Type a Kali command…",
                                            color = Color(0xFF718579),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp
                                        )
                                    }
                                    BasicTextField(
                                        value = input,
                                        onValueChange = onInput,
                                        modifier = Modifier.fillMaxWidth().onFocusChanged { inputFocused = it.isFocused },
                                        singleLine = true,
                                        textStyle = LocalTextStyle.current.copy(
                                            color = Color(0xFFE5F2E9),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp
                                        ),
                                        cursorBrush = SolidColor(Green),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                        keyboardActions = KeyboardActions(onSend = { submitCommand() })
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = { submitCommand() },
                            enabled = input.isNotBlank(),
                            modifier = Modifier.size(width = 56.dp, height = 56.dp),
                            shape = RoundedCornerShape(15.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Green,
                                contentColor = Bg,
                                disabledContainerColor = Color(0xFF1B3023),
                                disabledContentColor = Color(0xFF789080)
                            )
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Run command", modifier = Modifier.size(27.dp))
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("ENTER", color = Green, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text("to execute", color = Muted, fontSize = 9.sp)
                        }
                        Text("COMMANDS RUN IN KALI", color = Muted, fontSize = 9.sp, letterSpacing = 0.5.sp)
                    }
                }
            }

            Text(
                "Persistent PRoot session · Full-screen TUI apps such as nano and vim are not supported yet.",
                color = Muted,
                fontSize = 10.sp
            )
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
private fun SettingsPage(
    repository: CybxRepository,
    aiClient: CybxAiClient,
    onSaved: () -> Unit
) {
    var scopes by remember { mutableStateOf(repository.loadScope()) }
    val initial = remember { repository.loadAiConfig() }
    var provider by remember { mutableStateOf(initial.provider) }
    var ollamaUrl by remember { mutableStateOf(initial.ollamaUrl) }
    var ollamaModel by remember { mutableStateOf(initial.ollamaModel) }
    var cloudUrl by remember { mutableStateOf(initial.cloudUrl) }
    var cloudModel by remember { mutableStateOf(initial.cloudModel) }
    var cloudKey by remember { mutableStateOf(initial.cloudApiKey) }
    var message by remember { mutableStateOf("") }
    var testBusy by remember { mutableStateOf(false) }
    val settingsScope = rememberCoroutineScope()

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
            Text("For Ollama on a PC, enter its private LAN address, e.g. http://192.168.1.5:11434. The app only allows HTTP to localhost/private LAN addresses. Choose a model that supports tool/function calling.", color = Muted, fontSize = 10.sp)
        } else {
            OutlinedTextField(
                cloudUrl, { cloudUrl = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("HTTPS API endpoint") },
                placeholder = { Text("https://ollama.com/api/chat") }
            )
            OutlinedTextField(cloudModel, { cloudModel = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Cloud model") })
            OutlinedTextField(cloudKey, { cloudKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text("API key") }, visualTransformation = PasswordVisualTransformation())
            Text("Ollama Cloud: use https://ollama.com/api/chat with a model such as gemma4:31b. OpenAI-compatible providers use their /v1/chat/completions endpoint. API key is encrypted locally. Tool calling must be supported by the selected model.", color = Muted, fontSize = 10.sp)
        }
        Button(onClick = {
            try {
                scopes = LabScope.validateScopeText(scopes)
                repository.saveScope(scopes)
                repository.saveAiConfig(AiConfig(provider, ollamaUrl.trim(), ollamaModel.trim(), cloudUrl.trim(), cloudModel.trim(), cloudKey.trim()))
                message = "Settings saved. Lab scope and AI provider settings updated."
                onSaved()
            } catch (e: Exception) {
                message = e.message ?: "Settings could not be saved."
            }
        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text("Save settings") }

        OutlinedButton(
            onClick = {
                if (!testBusy) {
                    settingsScope.launch {
                        testBusy = true
                        message = "Testing AI connection…"
                        try {
                            val config = AiConfig(provider, ollamaUrl.trim(), ollamaModel.trim(), cloudUrl.trim(), cloudModel.trim(), cloudKey.trim())
                            message = aiClient.testConnection(config)
                            repository.audit("AI connection test succeeded", "provider=$provider; model=" + (if (provider == "ollama") ollamaModel else cloudModel))
                        } catch (e: Exception) {
                            message = e.message ?: "AI connection test failed."
                            repository.audit("AI connection test failed", message)
                        } finally {
                            testBusy = false
                        }
                    }
                }
            },
            enabled = !testBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (testBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.WifiTethering, null)
            Spacer(Modifier.width(6.dp))
            Text(if (testBusy) "Testing connection…" else "Test AI connection")
        }
        if (message.isNotBlank()) Text(message, color = if (message.contains("successful", true)) Green else Amber, fontSize = 11.sp)
        Divider(color = Color(0xFF294133))
        Text("PLATFORM LIMITATIONS", color = Amber, fontWeight = FontWeight.Bold)
        Text("CybX is a rootless Android userspace, not a bootable OS. USB Wi-Fi monitor mode, packet injection, HID and kernel-level features require compatible rooted/device kernels and are not promised on stock Xiaomi firmware.", color = Muted, fontSize = 11.sp)
        Text("AI model output is advisory. It never becomes a shell command. Only registered CybX tools can perform app actions, and sensitive actions require approval.", color = Muted, fontSize = 11.sp)
    }
}


@Composable
private fun ConversationalAgentPage(
    messages: List<CybxAgentUiMessage>,
    input: String,
    busy: Boolean,
    pending: CybxPendingAction?,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onResolve: (Boolean) -> Unit,
    onNewChat: () -> Unit,
    onReports: () -> Unit
) {
    val chatListState = rememberLazyListState()
    LaunchedEffect(messages.size, busy, pending) {
        if (messages.isNotEmpty()) chatListState.animateScrollToItem(messages.lastIndex)
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("CONVERSATIONAL AI OPERATOR", color = Green, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)
                Text("Talk naturally · CybX tools · approval-controlled actions", color = Muted, fontSize = 9.sp)
            }
            Row {
                IconButton(onClick = onReports) { Icon(Icons.Default.Assessment, contentDescription = "Reports", tint = Green) }
                IconButton(onClick = onNewChat, enabled = !busy && pending == null) { Icon(Icons.Default.AddComment, contentDescription = "New chat", tint = Green) }
            }
        }

        if (messages.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Hi! I'm CybX AI.", color = Green, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("বাংলা বা English-এ বলো কী করতে হবে। আমি app-এর supported tools ব্যবহার করে কাজ করব, ফলাফল দেখব এবং দরকার হলে পরের ধাপ নেব।", color = Color.White, fontSize = 12.sp)
                    Text("Try these:", color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("• Check my Kali setup and explain any problem.", color = Muted, fontSize = 11.sp)
                    Text("• Search for the kex package, then ask before installing.", color = Muted, fontSize = 11.sp)
                    Text("• Scan my approved private lab and prepare a report.", color = Muted, fontSize = 11.sp)
                    Text("• Create a notes file in the CybX workspace.", color = Muted, fontSize = 11.sp)
                }
            }
        }

        LazyColumn(
            state = chatListState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                val user = message.role == "user"
                val system = message.role == "system"
                val tool = message.role == "tool"
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (user) Arrangement.End else Arrangement.Start
                ) {
                    Card(
                        modifier = Modifier.widthIn(max = 340.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                user -> Color(0xFF173322)
                                system -> Color(0xFF18221C)
                                tool -> Color(0xFF111B24)
                                else -> Panel
                            }
                        ),
                        shape = RoundedCornerShape(if (user) 18.dp else 13.dp)
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                when {
                                    user -> "YOU"
                                    system -> "CYBX STATUS"
                                    tool -> "APP TOOL RESULT"
                                    else -> "CYBX AI"
                                },
                                color = if (user || system) Green else Muted,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Text(
                                message.text,
                                color = if (system) Muted else Color(0xFFE5F0E8),
                                fontSize = if (tool) 10.sp else 12.sp,
                                fontFamily = if (tool) FontFamily.Monospace else FontFamily.Default
                            )
                        }
                    }
                }
            }
            if (busy) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(15.dp), color = Green, strokeWidth = 2.dp)
                        Text("CybX is thinking / running an approved tool…", color = Muted, fontSize = 10.sp)
                    }
                }
            }
        }

        if (pending != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF241D12)),
                border = BorderStroke(1.dp, Amber.copy(alpha = 0.75f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = Amber)
                        Text("APPROVAL REQUIRED", color = Amber, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                    Text(pending.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(pending.description, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    Text("Nothing will run until you approve this action.", color = Amber, fontSize = 10.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onResolve(true) },
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Bg)
                        ) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(5.dp)); Text("Approve") }
                        OutlinedButton(
                            onClick = { onResolve(false) },
                            modifier = Modifier.weight(1f),
                            enabled = !busy
                        ) { Icon(Icons.Default.Close, null); Spacer(Modifier.width(5.dp)); Text("Decline") }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.weight(1f),
                enabled = !busy && pending == null,
                minLines = 1,
                maxLines = 4,
                placeholder = { Text("Tell CybX what to do…", fontSize = 12.sp) },
                shape = RoundedCornerShape(16.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
            )
            FilledIconButton(
                onClick = onSend,
                enabled = !busy && pending == null && input.isNotBlank(),
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Green, contentColor = Bg)
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
        Text("Actions that change Kali, write files or scan networks always ask for approval.", color = Muted, fontSize = 9.sp)
    }
}
