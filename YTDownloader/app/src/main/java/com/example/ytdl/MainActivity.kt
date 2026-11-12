package com.example.ytdl

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

const val BRAND = "亗𝐷𝑒𝑣. 𝐻𝑎𝑠𝑖𝑏 𝐵𝑟𝑜亗"

// ─────────────────────────── Home ViewModel ───────────────────────────

class HomeVM(app: Application) : AndroidViewModel(app) {
    var url by mutableStateOf("")
    var info by mutableStateOf<Info?>(null)
    var fmtSel by mutableStateOf<Fmt?>(null)      // null → use the default format from Settings
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var crash by mutableStateOf<String?>(null)

    init {
        File(app.filesDir, "crash.txt").let { if (it.exists()) { crash = it.readText().take(1500); it.delete() } }
    }

    fun setUrlText(s: String) { if (s != url) { url = s; info = null } }

    fun fetch() = viewModelScope.launch(Dispatchers.IO) {
        busy = true; status = "Fetching info…"
        try {
            info = DM.fetchInfo(url.trim())
            status = "Info loaded"
        } catch (e: Throwable) {
            status = "Error: " + (e.message?.lineSequence()?.lastOrNull { it.isNotBlank() }?.take(150) ?: e.toString())
        }
        busy = false
    }
}

class MainActivity : ComponentActivity() {
    private val vm: HomeVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DM.init(this)
        val v = vm
        setContent { Root(v, this) }
    }
}

@Composable
fun Root(vm: HomeVM, act: ComponentActivity) {
    val cfg by DM.cfgFlow.collectAsState()
    val pal = palette(cfg.theme, isSystemInDarkTheme())
    SideEffect {
        val t = android.graphics.Color.TRANSPARENT
        val style = if (pal.dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
        act.enableEdgeToEdge(style, style)
    }
    MaterialTheme(colorScheme = if (pal.dark) darkColorScheme(primary = Pink) else lightColorScheme(primary = Pink)) {
        CompositionLocalProvider(LocalPal provides pal) { Screen(vm) }
    }
}

// ─────────────────────────── Scaffold + bottom nav ───────────────────────────

@Composable
fun Screen(vm: HomeVM) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val n = DM.tasks.size

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Box(Modifier.fillMaxSize().background(pal.bg)) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(containerColor = pal.nav) {
                    val cols = NavigationBarItemDefaults.colors(
                        selectedIconColor = Neon, selectedTextColor = Neon, indicatorColor = Neon.copy(alpha = 0.2f),
                        unselectedIconColor = pal.sub, unselectedTextColor = pal.sub
                    )
                    NavigationBarItem(
                        selected = tab == 0, onClick = { tab = 0 }, colors = cols,
                        icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") }
                    )
                    NavigationBarItem(
                        selected = tab == 1, onClick = { tab = 1 }, colors = cols,
                        icon = {
                            BadgedBox(badge = { if (n > 0) Badge(containerColor = Neon, contentColor = Color.White) { Text("$n") } }) {
                                Icon(Ic.Download, null)
                            }
                        },
                        label = { Text("Downloads") }
                    )
                    NavigationBarItem(
                        selected = tab == 2, onClick = { tab = 2 }, colors = cols,
                        icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") }
                    )
                }
            }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (tab) {
                    0 -> HomeTab(vm) { tab = 1 }
                    1 -> DownloadsTab()
                    else -> SettingsTab()
                }
            }
        }
    }
}

// ─────────────────────────── Home tab ───────────────────────────

@Composable
fun HomeTab(vm: HomeVM, goDownloads: () -> Unit) {
    val p = LocalPal.current
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    val cfg by DM.cfgFlow.collectAsState()
    val fmt = vm.fmtSel ?: cfg.fmt

    fun enqueue() {
        DM.enqueue(vm.url.trim(), fmt, vm.info)
        vm.status = "Added to the download queue ✅"
        goDownloads()
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) enqueue() else vm.status = "Storage permission is required to save files"
    }
    val engineOk = DM.engineReady || cfg.engine == EngineMode.FLASK

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Image(painterResource(R.drawable.ic_logo), null, Modifier.size(64.dp))
            Column {
                Text("YT Downloader", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = p.text)
                Text("Powered by $BRAND", color = p.sub, fontSize = 13.sp)
            }
        }

        Glass {
            OutlinedTextField(
                value = vm.url, onValueChange = { vm.setUrlText(it) }, singleLine = true,
                placeholder = { Text("Paste video link…") }, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = p.text, unfocusedTextColor = p.text,
                    focusedBorderColor = Color(0xFFE100FF), unfocusedBorderColor = p.sub.copy(alpha = 0.4f),
                    focusedPlaceholderColor = p.sub, unfocusedPlaceholderColor = p.sub, cursorColor = Neon
                )
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GradBtn("📋 Paste", Grad2, Modifier.weight(1f)) { clip.getText()?.let { vm.setUrlText(it.text.trim()) } }
                GradBtn("🔍 Fetch", Grad, Modifier.weight(1f), engineOk && !vm.busy && vm.url.isNotBlank()) { vm.fetch() }
            }
        }

        vm.info?.let { i ->
            Glass {
                AsyncImage(
                    model = i.thumb, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(18.dp))
                )
                Text(i.title, color = p.text, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 2)
                Text("${i.author ?: ""} • ${i.duration / 60}:${"%02d".format(i.duration % 60)}", color = p.sub)
            }
        }

        Glass {
            Text("Format", color = p.text, fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Fmt.values().forEach { f ->
                    FilterChip(
                        selected = fmt == f, onClick = { vm.fmtSel = f }, label = { Text(f.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            labelColor = p.text, selectedContainerColor = Pink.copy(alpha = 0.35f), selectedLabelColor = p.text
                        )
                    )
                }
            }
            GradBtn("⬇  Download", Grad, Modifier.fillMaxWidth(), vm.url.isNotBlank()) {
                if (Build.VERSION.SDK_INT <= 28 &&
                    ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ) permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else enqueue()
            }
        }

        vm.crash?.let { c ->
            Glass {
                Text("⚠ Last crash report", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
                Text(c, color = p.text, fontSize = 10.sp)
                GradBtn("Dismiss", Grad2, Modifier.fillMaxWidth()) { vm.crash = null }
            }
        }

        Glass {
            val st = when {
                DM.engineError != null -> "Engine init failed: ${DM.engineError}"
                vm.status.isNotBlank() -> vm.status
                DM.engineReady -> "Ready ✅"
                else -> "Starting engine…"
            }
            Text(st, color = p.text)
        }
    }
}

// ─────────────────────────── Downloads tab ───────────────────────────

@Composable
fun DownloadsTab() {
    val p = LocalPal.current
    var q by remember { mutableStateOf("") }
    val hist = DM.history.value
    val active = DM.tasks.toList()
    val shown = remember(hist, q) {
        if (q.isBlank()) hist else hist.filter { it.title.contains(q, true) || it.channel.contains(q, true) }
    }
    var delTarget by remember { mutableStateOf<HItem?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Download History", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.text)
            if (active.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Box(Modifier.clip(CircleShape).background(Neon).padding(horizontal = 10.dp, vertical = 2.dp)) {
                    Text("${active.size}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search history…") }, shape = RoundedCornerShape(20.dp),
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Default.Close, "Clear") } },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = p.text, unfocusedTextColor = p.text,
                focusedBorderColor = Neon, unfocusedBorderColor = p.sub.copy(alpha = 0.35f),
                focusedPlaceholderColor = p.sub, unfocusedPlaceholderColor = p.sub, cursorColor = Neon,
                focusedLeadingIconColor = p.sub, unfocusedLeadingIconColor = p.sub,
                focusedTrailingIconColor = p.sub, unfocusedTrailingIconColor = p.sub
            )
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (active.isEmpty() && hist.isEmpty()) item { EmptyState() }
            if (active.isNotEmpty()) {
                item { Text("Active", color = p.sub, fontWeight = FontWeight.SemiBold) }
                items(active, key = { it.id }) { ActiveCard(it) }
            }
            if (hist.isNotEmpty()) {
                item { Text("History", color = p.sub, fontWeight = FontWeight.SemiBold) }
                if (shown.isEmpty()) item { Text("No results for “$q”", color = p.sub) }
                else items(shown, key = { it.id }) { h -> HistoryCard(h) { delTarget = h } }
            }
        }
    }

    delTarget?.let { h ->
        var also by remember(h.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { delTarget = null },
            title = { Text("Delete from history?") },
            text = {
                Column {
                    Text(h.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth().clickable { also = !also }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = also, onCheckedChange = { also = it })
                        Text("Also delete file from storage")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { DM.deleteHistory(h, also); delTarget = null }) { Text("Delete", color = Color(0xFFFF3B5C)) }
            },
            dismissButton = { TextButton(onClick = { delTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun EmptyState() {
    val p = LocalPal.current
    Column(Modifier.fillMaxWidth().padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(120.dp).clip(CircleShape).background(p.card), contentAlignment = Alignment.Center) {
            Icon(Ic.Download, null, tint = p.sub, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("No active downloads", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Paste a link on the Home tab to start downloading your favorite videos and audio.",
            color = p.sub, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

@Composable
fun ActiveCard(t: DlTask) {
    val p = LocalPal.current
    val prog by animateFloatAsState(t.progress, animationSpec = tween(500), label = "progress")
    val pct = (t.progress * 100).toInt()
    var showCk by remember { mutableStateOf(false) }
    val needLogin = t.status == Status.FAILED && (t.error ?: "").lowercase().let {
        listOf("cookies", "registered users", "log in", "login", "private", "authentication").any { k -> it.contains(k) }
    }
    if (showCk) CookiesDialog(onDismiss = { showCk = false }, onSaved = { DM.retry(t) })
    Glass {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(112.dp).height(66.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.35f))) {
                AsyncImage(model = t.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(
                    Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 6.dp, vertical = 2.dp)
                ) { Text(t.fmt.short, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
            Column(Modifier.weight(1f)) {
                Text(t.title.ifBlank { t.url }, color = p.text, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (t.channel.isNotBlank()) Text(t.channel, color = p.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        LinearProgressIndicator(
            progress = { prog }, color = Pink, trackColor = p.sub.copy(alpha = 0.25f),
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp))
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            val line = when (t.status) {
                Status.RUNNING ->
                    if (t.stage == "Processing…" || t.stage == "Saving…") t.stage
                    else listOf("$pct%", t.speed, if (t.eta.isNotBlank()) "ETA ${t.eta}" else "").filter { it.isNotBlank() }.joinToString("  •  ")
                Status.QUEUED -> "Waiting in queue…"
                Status.PAUSED -> if (t.autoPaused) "Paused — waiting for Wi-Fi ($pct%)" else "Paused ($pct%)"
                Status.FAILED -> t.error ?: "Failed"
            }
            Text(
                line, modifier = Modifier.weight(1f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (t.status == Status.FAILED) Color(0xFFFF6B6B) else p.sub
            )
            when (t.status) {
                Status.RUNNING -> IconButton(onClick = { DM.pause(t) }) { Icon(Ic.Pause, "Pause", tint = p.text) }
                Status.PAUSED -> IconButton(onClick = { DM.resume(t) }) { Icon(Icons.Default.PlayArrow, "Resume", tint = p.text) }
                Status.FAILED -> IconButton(onClick = { DM.retry(t) }) { Icon(Icons.Default.Refresh, "Retry", tint = p.text) }
                Status.QUEUED -> {}
            }
            IconButton(onClick = { DM.cancel(t) }) { Icon(Icons.Default.Close, "Cancel", tint = Pink) }
        }
        if (needLogin) GradBtn("🍪  Add Cookies", Grad, Modifier.fillMaxWidth()) { showCk = true }
    }
}

fun playItem(ctx: Context, h: HItem) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(h.uri), h.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        Toast.makeText(ctx, "Can't open this file (moved or deleted?)", Toast.LENGTH_SHORT).show()
    }
}

fun shareItem(ctx: Context, h: HItem) {
    try {
        val i = Intent(Intent.ACTION_SEND).setType(h.mime)
            .putExtra(Intent.EXTRA_STREAM, Uri.parse(h.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Toast.makeText(ctx, "Can't share this file", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryCard(h: HItem, onDelete: () -> Unit) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v == SwipeToDismissBoxValue.EndToStart) { DM.deleteHistory(h, false); true } else false
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val c by animateColorAsState(
                if (state.targetValue == SwipeToDismissBoxValue.EndToStart) Color(0xFFD50032) else Color(0xFFD50032).copy(alpha = 0.25f),
                label = "swipe"
            )
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(c).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd
            ) { Icon(Icons.Default.Delete, null, tint = Color.White) }
        }
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = if (p.dark) Color(0xFF2A2650) else Color(0xFFEFEAFB)),
            modifier = Modifier.fillMaxWidth().clickable { playItem(ctx, h) }
        ) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(96.dp).height(64.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    AsyncImage(model = h.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PlayArrow, "Play", tint = Color.White)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(h.title, color = p.text, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = listOf(
                        h.channel, h.quality, fmtSize(h.size),
                        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(h.date))
                    ).filter { it.isNotBlank() }.joinToString(" • ")
                    Text(meta, color = p.sub, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Column {
                    IconButton(onClick = { shareItem(ctx, h) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Share, "Share", tint = p.sub) }
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Delete, "Delete", tint = Pink) }
                }
            }
        }
    }
}

// ─────────────────────────── Settings tab ───────────────────────────

@Composable
fun <T> Dropdown(current: String, options: List<Pair<String, T>>, onSelect: (T) -> Unit) {
    val p = LocalPal.current
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(p.card).clickable { open = true }.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(current, color = Pink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (label, v) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onSelect(v) }) }
        }
    }
}

@Composable
fun SettingRow(icon: String, title: String, sub: String, trailing: @Composable () -> Unit) {
    val p = LocalPal.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (icon.isNotEmpty()) Text(icon, fontSize = 20.sp, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            if (sub.isNotBlank()) Text(sub, color = p.sub, fontSize = 12.sp)
        }
        trailing()
    }
}

@Composable
fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow("", title, sub) {
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Pink, checkedThumbColor = Color.White)
        )
    }
}

@Composable
fun SettingsTab() {
    val p = LocalPal.current
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val cfg by DM.cfgFlow.collectAsState()
    var api by remember(cfg.apiUrl) { mutableStateOf(cfg.apiUrl) }
    var conc by remember(cfg.concurrent) { mutableFloatStateOf(cfg.concurrent.toFloat()) }
    var upd by remember { mutableStateOf("") }
    var updBusy by remember { mutableStateOf(false) }
    var showCk by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = p.text)

        // Download engine
        Glass {
            SettingRow("⚙️", "Download Engine", cfg.engine.title) {
                Dropdown(
                    cfg.engine.title.substringBefore(" ("), EngineMode.values().map { it.title to it }
                ) { scope.launch { SettingsStore.setEngine(ctx, it) } }
            }
            if (cfg.engine != EngineMode.NATIVE) {
                OutlinedTextField(
                    value = api, onValueChange = { api = it; scope.launch { SettingsStore.setApi(ctx, it) } },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    label = { Text("Flask API server URL") }, placeholder = { Text("https://your-server.com") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = p.text, unfocusedTextColor = p.text, focusedBorderColor = Neon,
                        unfocusedBorderColor = p.sub.copy(alpha = 0.4f), cursorColor = Neon,
                        focusedLabelColor = Neon, unfocusedLabelColor = p.sub,
                        focusedPlaceholderColor = p.sub, unfocusedPlaceholderColor = p.sub
                    )
                )
            }
        }

        // Cookies (private / login-only videos)
        Glass {
            SettingRow(
                "🍪", "Cookies",
                if (Cookies.count > 0) "✅ ${Cookies.count} cookies saved" else "Facebook private video এর জন্য cookies add করুন"
            ) {}
            GradBtn("🍪  Add / Manage Cookies", Grad, Modifier.fillMaxWidth()) { showCk = true }
        }
        if (showCk) CookiesDialog(onDismiss = { showCk = false })

        // yt-dlp updater
        Glass {
            SettingRow("📦", "yt-dlp Version: ${DM.ytVersion}", DM.engineError?.let { "Engine error: ${it.take(80)}" } ?: "") {}
            GradBtn(if (updBusy) "Updating…" else "🔄  Update yt-dlp Engine", Grad2, Modifier.fillMaxWidth(), !updBusy && DM.engineReady) {
                scope.launch {
                    updBusy = true; upd = "Updating…"
                    upd = withContext(Dispatchers.IO) { DM.updateYt() }
                    updBusy = false
                }
            }
            if (upd.isNotBlank()) Text(upd, color = p.sub, fontSize = 12.sp)
        }

        // Preferences
        Glass {
            SettingRow("🎚️", "Default Format", "Auto-selected when you paste a link") {
                Dropdown(cfg.fmt.short, Fmt.values().map { it.short to it }) { scope.launch { SettingsStore.setFmt(ctx, it) } }
            }
            SettingRow("📁", "Download Folder", "Movies/YTDownloader  •  Music/YTDownloader\n(Public MediaStore — visible in Gallery & Music player)") {}
        }

        Glass {
            SettingRow("📚", "Concurrent Downloads", "Simultaneous downloads (1 - 3)") {
                Text("${conc.roundToInt()}", color = Pink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Slider(
                value = conc, onValueChange = { conc = it }, valueRange = 1f..3f, steps = 1,
                onValueChangeFinished = { scope.launch { SettingsStore.setConcurrent(ctx, conc.roundToInt()) } },
                colors = SliderDefaults.colors(thumbColor = Pink, activeTrackColor = Pink, inactiveTrackColor = p.sub.copy(alpha = 0.3f))
            )
        }

        Glass {
            SwitchRow("Download on Wi-Fi Only", "Pause or prevent downloads when using mobile data", cfg.wifiOnly) {
                scope.launch { SettingsStore.setWifi(ctx, it) }
            }
            SwitchRow("Embed Thumbnail", "Add video artwork directly into the media file", cfg.embedThumb) {
                scope.launch { SettingsStore.setThumb(ctx, it) }
            }
            SwitchRow("Embed Metadata", "Add title, artist, album, and date tags", cfg.embedMeta) {
                scope.launch { SettingsStore.setMeta(ctx, it) }
            }
            SwitchRow("Download Subtitles", "Save auto-generated or manual subtitles if available", cfg.subs) {
                scope.launch { SettingsStore.setSubs(ctx, it) }
            }
        }

        Glass {
            SettingRow("🌙", "App Theme", cfg.theme.title) {
                Dropdown(cfg.theme.title, ThemeMode.values().map { it.title to it }) { scope.launch { SettingsStore.setTheme(ctx, it) } }
            }
        }

        Glass {
            Text("ⓘ  About YT Downloader", color = p.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("Version 3.0.0 • Powered by yt-dlp & FFmpeg", color = p.sub)
            Text("The App Create By HASIB Bro (@Yyuu43222) have permission to save.", color = Pink, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
    }
}
