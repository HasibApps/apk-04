package com.example.ytdl

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shows in Android's Share menu. Opens a bottom-sheet popup over the current app — the main app UI is never opened. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DM.init(this)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.dataString ?: ""
        val url = DM.extractUrl(text)
        if (url == null) {
            Toast.makeText(this, "No video link found in the shared text", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) { ShareSheet(url) { finish() } }
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

private val Yellow = Color(0xFFFFCC1A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(url: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cfg by DM.cfgFlow.collectAsState()
    var info by remember { mutableStateOf<Info?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var sel by remember { mutableStateOf<Fmt?>(null) }
    var more by remember { mutableStateOf(false) }
    val cur = sel ?: cfg.fmt
    val showAll = more || cur == Fmt.V480 || cur == Fmt.V1080 || cur == Fmt.MP4

    LaunchedEffect(url) {
        try { info = withContext(Dispatchers.IO) { DM.fetchInfo(url) } }
        catch (e: Throwable) { err = e.message?.lineSequence()?.lastOrNull { it.isNotBlank() }?.take(120) }
        loading = false
    }

    fun go() {
        DM.enqueue(url, cur, info)
        Toast.makeText(ctx, "Download started — see the Downloads tab", Toast.LENGTH_SHORT).show()
        onClose()
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) go() else Toast.makeText(ctx, "Storage permission is required to save files", Toast.LENGTH_LONG).show()
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState, containerColor = Color.White) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        ) {
            Text("Download video as", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111111))
            Spacer(Modifier.height(6.dp))
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF888888))
                    Spacer(Modifier.width(8.dp)); Text("Fetching video info…", color = Color(0xFF888888), fontSize = 13.sp)
                }
                info != null -> Text(info!!.title, color = Color(0xFF666666), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                err != null -> Text("Couldn't read info ($err) — you can still download.", color = Color(0xFFCC3344), fontSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))

            fun szOf(f: Fmt) = info?.sizes?.get(f)?.let { fmtSize(it) } ?: ""

            Text("Music", color = Color(0xFF888888), fontSize = 15.sp)
            OptRow("🎵", "Fast (M4A)", szOf(Fmt.M4A), cur == Fmt.M4A) { sel = Fmt.M4A }
            OptRow("🎵", "Classic MP3", szOf(Fmt.MP3), cur == Fmt.MP3) { sel = Fmt.MP3 }
            Spacer(Modifier.height(8.dp))
            Text("Video", color = Color(0xFF888888), fontSize = 15.sp)
            OptRow("🎬", "Fast (360p)", szOf(Fmt.V360), cur == Fmt.V360) { sel = Fmt.V360 }
            OptRow("🎬", "High quality (720p)", szOf(Fmt.V720), cur == Fmt.V720) { sel = Fmt.V720 }
            if (showAll) {
                OptRow("🎬", "480p", szOf(Fmt.V480), cur == Fmt.V480) { sel = Fmt.V480 }
                OptRow("🎬", "Full HD (1080p)", szOf(Fmt.V1080), cur == Fmt.V1080) { sel = Fmt.V1080 }
                OptRow("🎬", "Best MP4", szOf(Fmt.MP4), cur == Fmt.MP4) { sel = Fmt.MP4 }
            } else {
                Divider(Modifier.padding(top = 6.dp), color = Color(0xFFEEEEEE))
                Row(
                    Modifier.fillMaxWidth().clickable { more = true }.padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("More formats", fontSize = 18.sp, color = Color(0xFF222222), modifier = Modifier.weight(1f))
                    Text("All  ›", color = Color(0xFF999999), fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(Yellow).clickable {
                    if (Build.VERSION.SDK_INT <= 28 &&
                        ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    ) permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else go()
                },
                contentAlignment = Alignment.Center
            ) { Text("Download", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OptRow(icon: String, label: String, size: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 22.sp)
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 18.sp, color = Color(0xFF111111), modifier = Modifier.weight(1f))
        Text(size, color = Color(0xFF888888), fontSize = 15.sp)
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier.size(28.dp).clip(CircleShape).then(
                if (selected) Modifier.background(Yellow) else Modifier.border(1.5.dp, Color(0xFFB5B5B5), CircleShape)
            ),
            contentAlignment = Alignment.Center
        ) { if (selected) Text("✓", fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 15.sp) }
    }
}
