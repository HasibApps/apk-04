package com.example.ytdl

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val GradGray = Brush.horizontalGradient(listOf(Color(0xFF55556A), Color(0xFF3E3E52)))

/**
 * "Add cookies" dialog: Facebook login (WebView), import cookies.txt, paste cookies, or remove them.
 * onSaved is called after cookies were saved successfully (e.g. to retry a failed download).
 */
@Composable
fun CookiesDialog(onDismiss: () -> Unit, onSaved: () -> Unit = {}) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var paste by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }

    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) { msg = "✅ Facebook cookies saved"; onSaved() }
    }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val s = try { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } } catch (_: Throwable) { null }
            val n = if (s != null) Cookies.saveText(s) else 0
            if (n > 0) { msg = "✅ $n cookies imported"; onSaved() } else msg = "❌ Invalid cookies file"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🍪 Cookies") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    when {
                        Cookies.hasFacebookLogin -> "Status: ✅ Facebook login saved (${Cookies.count} cookies)"
                        Cookies.count > 0 -> "Status: ✅ ${Cookies.count} cookies saved"
                        else -> "Status: কোনো cookies নেই"
                    },
                    fontSize = 13.sp
                )
                if (!paste) {
                    Text(
                        "Facebook private / “registered users only” video download করতে login cookies লাগে।",
                        fontSize = 12.sp
                    )
                    GradBtn("🔐  Login with Facebook (সহজ)", Grad, Modifier.fillMaxWidth()) {
                        loginLauncher.launch(Intent(ctx, CookieLoginActivity::class.java))
                    }
                    GradBtn("📂  Import cookies.txt", Grad2, Modifier.fillMaxWidth()) { pickLauncher.launch(arrayOf("*/*")) }
                    GradBtn("📋  Paste cookies", Grad2, Modifier.fillMaxWidth()) { paste = true }
                    if (Cookies.count > 0) {
                        GradBtn("🗑  Remove cookies", GradGray, Modifier.fillMaxWidth()) { Cookies.clear(); msg = "Cookies removed" }
                    }
                } else {
                    Text("cookies.txt (Netscape), JSON, অথবা Cookie header (c_user=…; xs=…) paste করুন।", fontSize = 12.sp)
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, maxLines = 8,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                        shape = RoundedCornerShape(14.dp), placeholder = { Text("Paste here…") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GradBtn("Clipboard", Grad2, Modifier.weight(1f)) { clip.getText()?.let { text = it.text } }
                        GradBtn("Save", Grad, Modifier.weight(1f), text.isNotBlank()) {
                            val n = Cookies.saveText(text)
                            if (n > 0) { msg = "✅ $n cookies saved"; text = ""; paste = false; onSaved() }
                            else msg = "❌ Cookies চেনা যায়নি — format ঠিক আছে?"
                        }
                    }
                }
                if (msg.isNotBlank()) Text(msg, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = { if (paste) paste = false else onDismiss() }) { Text(if (paste) "Back" else "Close") } }
    )
}
