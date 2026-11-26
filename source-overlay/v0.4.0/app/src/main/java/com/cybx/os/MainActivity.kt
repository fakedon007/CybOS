package com.cybx.os

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import com.cybx.os.runtime.CybxPaths
import com.cybx.os.runtime.CybxTerminalController
import com.cybx.os.runtime.LocalRuntimeInstaller
import com.cybx.os.runtime.ProotAdapter
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CybxApp() }
    }
}

enum class CybxPage(val label: String) {
    DASHBOARD("Dashboard"), INSTALL("Linux Setup"), TERMINAL("Terminal"),
    TOOLS("Tools"), FILES("Files"), SETTINGS("Settings")
}

@Composable
fun CybxApp() {
    val context = LocalContext.current
    val paths = remember { CybxPaths(context) }
    val installer = remember { LocalRuntimeInstaller(context) }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(CybxPage.DASHBOARD) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("CybX Mobile ready") }
    var terminalTitle by remember { mutableStateOf("root@cybx") }
    var terminalExit by remember { mutableStateOf<Int?>(null) }

    val terminal = remember(paths) {
        CybxTerminalController(context, paths, {}, { terminalExit = it }, { terminalTitle = it })
    }
    LaunchedEffect(page) { refresh++ }
    val rootfsStatus = remember(refresh) { paths.rootfsStatus() }
    val prootAvailable = remember(refresh) { ProotAdapter(paths).isAvailable() }
    val runtimeReady = rootfsStatus.installed && prootAvailable

    val rootfsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            busy = true
            terminal.stop()
            try {
                val result = installer.importRootfs(uri) { status = it }
                status = "${result.prettyName} installed · SHA-256 verified"
                terminalExit = null
                refresh++
            } catch (e: Exception) {
                status = e.message ?: "Kali rootfs install failed"
            } finally { busy = false }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = CYBX_GREEN, secondary = CYBX_AMBER, background = CYBX_BG, surface = CYBX_PANEL,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Scaffold(
            containerColor = CYBX_BG,
            topBar = {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), "CybX logo", Modifier.size(36.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("CYBX OS", color = CYBX_GREEN, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                        Text(page.label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = CYBX_BAR, tonalElevation = 0.dp) {
                    CybxPage.entries.forEach { item ->
                        NavigationBarItem(
                            selected = page == item, onClick = { page = item },
                            icon = { Icon(when (item) {
                                CybxPage.DASHBOARD -> Icons.Default.Dashboard
                                CybxPage.INSTALL -> Icons.Default.Download
                                CybxPage.TERMINAL -> Icons.Default.Terminal
                                CybxPage.TOOLS -> Icons.Default.Build
                                CybxPage.FILES -> Icons.Default.Folder
                                CybxPage.SETTINGS -> Icons.Default.Settings
                            }, item.label) },
                            label = { Text(item.label, fontSize = 9.sp) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = CYBX_GREEN, selectedTextColor = CYBX_GREEN)
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 10.dp)) {
                when (page) {
                    CybxPage.DASHBOARD -> DashboardPage(runtimeReady, prootAvailable, rootfsStatus, status,
                        { page = CybxPage.INSTALL }, { page = CybxPage.TERMINAL })
                    CybxPage.INSTALL -> LinuxSetupPage(prootAvailable, rootfsStatus.installed, rootfsStatus.reason,
                        busy, status) { rootfsPicker.launch(arrayOf("application/x-xz", "application/octet-stream", "*/*")) }
                    CybxPage.TERMINAL -> TerminalPage(runtimeReady,
                        if (!prootAvailable) "Bundled ARM64 PRoot is missing" else rootfsStatus.reason,
                        terminal, terminalTitle, terminalExit) { page = CybxPage.INSTALL }
                    CybxPage.TOOLS -> ToolsPage(runtimeReady)
                    CybxPage.FILES -> FilesPage(paths.rootfs, runtimeReady)
                    CybxPage.SETTINGS -> SettingsPage(status)
                }
            }
        }
    }
}
