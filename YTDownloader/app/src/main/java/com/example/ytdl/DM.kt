package com.example.ytdl

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Central download manager (singleton). Lives for the whole process; a foreground service keeps the process alive. */
object DM {
    const val NID = 1
    private const val CH = "dl_channel"
    private const val CH_DONE = "dl_done"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val speedRe = Regex("""at\s+([\d.]+\S*?/s)""")
    private val partRe = Regex("""\.f\d+\.""")
    private val MEDIA_EXT = setOf("mp4", "mkv", "webm", "mov", "mp3", "m4a", "opus", "ogg", "aac", "wav", "flac")
    private val AUDIO_EXT = setOf("mp3", "m4a", "opus", "ogg", "aac", "wav", "flac")
    private val SUB_EXT = setOf("srt", "vtt")

    lateinit var app: Application
    private var inited = false
    private lateinit var db: HistoryDb
    private val main = Handler(Looper.getMainLooper())
    private val idGen = AtomicLong(System.currentTimeMillis())
    private var svcUp = false

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val tasks = mutableStateListOf<DlTask>()
    val history = mutableStateOf<List<HItem>>(emptyList())
    val cfgFlow = MutableStateFlow(Cfg())
    val cfg: Cfg get() = cfgFlow.value

    private val ready = CompletableDeferred<Boolean>()
    var engineReady by mutableStateOf(false)
    var engineError by mutableStateOf<String?>(null)
    var ytVersion by mutableStateOf("Unknown")

    private fun onMain(b: () -> Unit) { main.post { b() } }

    // ───────────── init ─────────────

    @Synchronized
    fun init(c: Context) {
        if (inited) return
        inited = true
        app = c.applicationContext as Application
        db = HistoryDb(app)
        createChannels()
        Cookies.init(app)
        scope.launch { SettingsStore.flow(app).collect { cfgFlow.value = it } }
        scope.launch { reloadHistory() }
        scope.launch {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                engineReady = true
                ready.complete(true)
                refreshVersion()
            } catch (e: Throwable) {
                engineError = e.message ?: e.toString()
                ready.complete(false)
            }
        }
        scope.launch {
            while (true) {
                delay(1000)
                try { schedule() } catch (_: Throwable) {}
            }
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH, "Downloads", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "Completed downloads", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    fun refreshVersion() {
        try {
            val r = YoutubeDLRequest("https://example.com")
            r.addOption("--version")
            val o = YoutubeDL.getInstance().execute(r, "ver_${idGen.incrementAndGet()}") { _, _, _ -> }.out.trim()
            if (o.isNotBlank()) ytVersion = o.lineSequence().first().trim()
        } catch (_: Throwable) {}
    }

    suspend fun updateYt(): String {
        if (!ready.await()) return "Engine not ready: ${engineError ?: ""}"
        return try {
            YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
            refreshVersion()
            "yt-dlp updated ✅"
        } catch (e: Throwable) {
            "Update failed: ${e.message?.take(100)}"
        }
    }

    // ───────────── URL helpers ─────────────

    fun extractUrl(text: String): String? =
        Regex("https?://[^\\s]+").find(text)?.value?.trimEnd(')', ',', '.', '"', '\'')

    /** Facebook mobile/other sub-domains → www (yt-dlp's extractor handles www reliably). */
    fun norm(u: String): String =
        u.trim().replace(
            Regex("^(https?://)(m|mbasic|web|touch|mobile)\\.facebook\\.com", RegexOption.IGNORE_CASE),
            "\$1www.facebook.com"
        )

    private fun isFb(u: String) = Regex("facebook\\.com|fb\\.watch|fb\\.com", RegexOption.IGNORE_CASE).containsMatchIn(u)

    private fun fbOpts(r: YoutubeDLRequest, url: String) {
        if (isFb(url)) {
            r.addOption("--add-header", "Referer:https://www.facebook.com/")
            r.addOption("--user-agent", UA)
        }
    }

    // ───────────── Info ─────────────

    suspend fun fetchInfo(raw: String): Info {
        val url = norm(raw)
        val mode = cfg.engine
        if (mode != EngineMode.FLASK) {
            try {
                if (!ready.await()) throw Exception("Engine init failed: ${engineError ?: ""}")
                return nativeInfo(url)
            } catch (e: Throwable) {
                if (mode == EngineMode.NATIVE || cfg.apiUrl.isBlank()) throw e
            }
        }
        return flaskInfo(url)
    }

    private fun nativeInfo(url: String): Info {
        val r = YoutubeDLRequest(url)
        r.addOption("--dump-json"); r.addOption("--no-playlist"); r.addOption("--no-warnings")
        r.addOption("--socket-timeout", "30")
        fbOpts(r, url)
        val cf = Cookies.use(r, app.cacheDir, "i${idGen.incrementAndGet()}")
        val out = try {
            YoutubeDL.getInstance().execute(r, "info_${idGen.incrementAndGet()}") { _, _, _ -> }.out
        } finally { cf?.delete() }
        val line = out.lineSequence().firstOrNull { it.trimStart().startsWith("{") } ?: throw Exception("No info returned")
        val j = JSONObject(line)

        class F(val h: Int, val size: Long, val v: Boolean, val a: Boolean)
        val fl = ArrayList<F>()
        val arr = j.optJSONArray("formats")
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val vc = o.optString("vcodec", "none"); val ac = o.optString("acodec", "none")
            val v = vc.isNotBlank() && vc != "none" && vc != "null"
            val a = ac.isNotBlank() && ac != "none" && ac != "null"
            var size = o.optLong("filesize", 0L)
            if (size <= 0) size = o.optLong("filesize_approx", 0L)
            fl.add(F(o.optInt("height", 0), size, v, a))
        }
        val bestAudio = fl.filter { it.a && !it.v }.maxOfOrNull { it.size } ?: 0L
        val vids = fl.filter { it.v }
        val sizes = HashMap<Fmt, Long>()
        for (f in Fmt.values()) {
            val s: Long = if (f.audio) bestAudio else {
                val pick = (if (f.height == 0) vids.maxByOrNull { it.h }
                else vids.filter { it.h in 1..f.height }.maxByOrNull { it.h } ?: vids.minByOrNull { it.h }) ?: continue
                pick.size + (if (pick.a) 0L else bestAudio)
            }
            if (s > 0) sizes[f] = s
        }
        return Info(
            j.optString("title", "").ifBlank { j.optString("id", "Video") },
            j.optString("thumbnail", "").ifBlank { null },
            (j.optString("uploader", "").ifBlank { j.optString("channel", "") }).ifBlank { null },
            j.optDouble("duration", 0.0).toInt(),
            sizes
        )
    }

    private fun flaskInfo(url: String): Info {
        val base = cfg.apiUrl.trim().trimEnd('/')
        if (base.isBlank()) throw Exception("Flask API URL is empty (Settings → Download Engine)")
        val c = (URL("$base/video_info").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20000; readTimeout = 60000
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(JSONObject().put("url", url).toString().toByteArray()) }
        if (c.responseCode !in 200..299) throw Exception("API error ${c.responseCode}")
        val j = JSONObject(c.inputStream.bufferedReader().readText())
        c.disconnect()
        return Info(
            j.optString("title", "Unknown"),
            j.optString("thumbnail_url", j.optString("thumbnail", "")).ifBlank { null },
            j.optString("author", "").ifBlank { null },
            j.optInt("length", 0)
        )
    }

    // ───────────── Queue controls ─────────────

    fun enqueue(url: String, fmt: Fmt, info: Info? = null) {
        val t = DlTask(idGen.incrementAndGet(), url.trim(), fmt)
        info?.let { t.title = it.title; t.thumb = it.thumb; t.channel = it.author ?: "" }
        onMain { tasks.add(t) }
        ensureService()
    }

    fun pause(t: DlTask, auto: Boolean = false) {
        if (t.status != Status.RUNNING && t.status != Status.QUEUED) return
        t.stop = "pause"
        t.autoPaused = auto
        t.status = Status.PAUSED
        t.speed = ""
        killProcess(t)
        t.job?.cancel()
    }

    fun resume(t: DlTask) {
        if (t.status == Status.PAUSED) { t.autoPaused = false; t.status = Status.QUEUED }
    }

    fun retry(t: DlTask) {
        if (t.status == Status.FAILED) { t.error = null; t.progress = 0f; t.status = Status.QUEUED }
    }

    fun cancel(t: DlTask) {
        t.stop = "cancel"
        killProcess(t)
        t.job?.cancel()
        scope.launch { dirOf(t).deleteRecursively() }
        onMain { tasks.remove(t) }
    }

    private fun killProcess(t: DlTask) { try { YoutubeDL.getInstance().destroyProcessById("dl_${t.id}") } catch (_: Throwable) {} }
    private fun dirOf(t: DlTask) = File(app.cacheDir, "dl/${t.id}")
    private fun stopped(t: DlTask, gen: Int) = t.gen != gen || t.stop != null

    // ───────────── Scheduler (runs every second) ─────────────

    private fun onWifi(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun schedule() {
        val c = cfg
        val netOk = !c.wifiOnly || onWifi()
        val list = tasks.toList()
        if (!netOk) list.filter { it.status == Status.RUNNING }.forEach { pause(it, auto = true) }
        else list.filter { it.status == Status.PAUSED && it.autoPaused }.forEach { it.autoPaused = false; it.status = Status.QUEUED }

        var running = list.count { it.status == Status.RUNNING }
        if (netOk) for (t in list.filter { it.status == Status.QUEUED }) {
            if (running >= c.concurrent) break
            start(t); running++
        }

        val active = list.any { it.status == Status.QUEUED || it.status == Status.RUNNING || (it.status == Status.PAUSED && it.autoPaused) }
        if (active) {
            ensureService()
            if (svcUp) app.getSystemService(NotificationManager::class.java).notify(NID, buildNotif(app))
        } else if (svcUp) {
            svcUp = false
            try { app.stopService(Intent(app, DownloadService::class.java)) } catch (_: Throwable) {}
        }
    }

    private fun ensureService() {
        if (svcUp) return
        try {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
            svcUp = true
        } catch (_: Throwable) {}
    }

    private fun start(t: DlTask) {
        t.gen++
        val gen = t.gen
        t.stop = null
        t.error = null
        t.autoPaused = false
        t.status = Status.RUNNING
        t.job = scope.launch {
            try {
                runTask(t, gen)
            } catch (e: Throwable) {
                if (!stopped(t, gen)) {
                    t.status = Status.FAILED
                    t.speed = ""; t.eta = ""
                    t.error = (e.message ?: e.toString()).lineSequence().lastOrNull { it.isNotBlank() }?.take(220) ?: "Failed"
                }
            }
        }
    }

    // ───────────── The actual download ─────────────

    private suspend fun runTask(t: DlTask, gen: Int) {
        val mode = cfg.engine
        if (mode != EngineMode.FLASK && !ready.await()) throw Exception("Engine init failed: ${engineError ?: ""}")
        val url = norm(t.url)

        if (t.title.isBlank()) {
            try {
                val i = fetchInfo(url)
                t.title = i.title; t.thumb = i.thumb; t.channel = i.author ?: ""
            } catch (_: Throwable) {}
        }
        if (stopped(t, gen)) return

        val dir = dirOf(t).apply { mkdirs() }
        t.stage = "Downloading…"
        when (mode) {
            EngineMode.NATIVE -> nativeWithRetry(t, gen, dir)
            EngineMode.FLASK -> flask(t, gen, dir)
            EngineMode.HYBRID -> try {
                nativeWithRetry(t, gen, dir)
            } catch (e: Throwable) {
                if (stopped(t, gen) || cfg.apiUrl.isBlank() || t.fmt.audio) throw e
                t.stage = "Native engine failed → trying API…"; t.progress = 0f
                flask(t, gen, dir)
            }
        }
        if (stopped(t, gen)) return

        t.stage = "Saving…"
        finish(t, dir)
    }

    private fun nativeWithRetry(t: DlTask, gen: Int, dir: File) {
        try {
            native(t, gen, dir, true)
        } catch (e: Throwable) {
            if (stopped(t, gen)) throw e
            val m = (e.message ?: "").lowercase()
            val embeds = cfg.embedThumb || cfg.embedMeta
            if (embeds && listOf("thumbnail", "postprocess", "ffmpeg", "embed", "metadata").any { m.contains(it) }) {
                native(t, gen, dir, false)   // retry without embedding; files already downloaded are reused
            } else throw e
        }
    }

    private fun selector(f: Fmt): String {
        if (f.height == 0) return "bv*[vcodec^=avc1]+ba[ext=m4a]/bv*+ba/b/best"
        val h = f.height
        // The last fallbacks (bv*+ba/b/best) make sites without height info (e.g. Facebook) work.
        return "bv*[height<=$h][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b/best"
    }

    private fun native(t: DlTask, gen: Int, dir: File, embeds: Boolean) {
        val url = norm(t.url)
        val c = cfg
        val f = t.fmt
        val r = YoutubeDLRequest(url)
        // File name is the video id (safe). The nice title-based name is applied when saving.
        r.addOption("-o", dir.absolutePath + "/%(id)s.%(ext)s")
        if (f.audio) {
            r.addOption("-f", if (f == Fmt.M4A) "ba[ext=m4a]/ba/b" else "ba/b")
            r.addOption("-x")
            r.addOption("--audio-format", if (f == Fmt.M4A) "m4a" else "mp3")
            r.addOption("--audio-quality", "0")
        } else {
            r.addOption("-f", selector(f))
            r.addOption("--merge-output-format", "mp4")
        }
        r.addOption("--no-mtime"); r.addOption("--no-playlist"); r.addOption("--continue")
        r.addOption("--retries", "10"); r.addOption("--fragment-retries", "10")
        r.addOption("--socket-timeout", "30"); r.addOption("-N", "4")
        fbOpts(r, url)
        Cookies.use(r, dir, "dl")   // private copy lives in the task dir, deleted with it
        if (embeds && c.embedThumb) { r.addOption("--embed-thumbnail"); r.addOption("--convert-thumbnails", "jpg") }
        if (embeds && c.embedMeta) r.addOption("--embed-metadata")
        if (c.subs) {
            r.addOption("--write-subs"); r.addOption("--write-auto-subs")
            r.addOption("--sub-langs", "en.*,bn.*"); r.addOption("--convert-subs", "srt")
        }

        // yt-dlp downloads video and audio as separate streams (each 0→100%) → one combined bar.
        val total = if (f.audio) 1 else 2
        var phase = 0
        YoutubeDL.getInstance().execute(r, "dl_${t.id}") { p, eta, line ->
            if (t.gen != gen) return@execute
            val l = line ?: ""
            if ((l.contains("[download]") && l.contains("Destination:")) || l.contains("has already been downloaded")) phase++
            if (l.contains("[Merger]") || l.contains("[ExtractAudio]") || l.contains("[EmbedThumbnail]") || l.contains("[Metadata]"))
                t.stage = "Processing…"
            val v = ((maxOf(phase, 1) - 1) + p.coerceIn(0f, 100f) / 100f) / total
            if (v > t.progress) t.progress = v.coerceAtMost(0.99f)
            speedRe.find(l)?.let { t.speed = it.groupValues[1] }
            if (eta >= 0) t.eta = hms(eta.toLong())
        }
    }

    private fun flask(t: DlTask, gen: Int, dir: File) {
        if (t.fmt.audio) throw Exception("Flask API engine does not support audio. Use Native or Hybrid mode.")
        val base = cfg.apiUrl.trim().trimEnd('/')
        if (base.isBlank()) throw Exception("Flask API URL is empty (Settings → Download Engine)")
        val con = (URL("$base/download").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20000; readTimeout = 60000
            setRequestProperty("Content-Type", "application/json")
        }
        val res = if (t.fmt.height > 0) "${t.fmt.height}p" else "720p"
        con.outputStream.use { it.write(JSONObject().put("url", norm(t.url)).put("resolution", res).toString().toByteArray()) }
        if (con.responseCode !in 200..299) throw Exception("API error ${con.responseCode}")
        val total = con.contentLengthLong
        val out = File(dir, "api_${t.id}.mp4")
        var done = 0L; var lastT = System.currentTimeMillis(); var lastB = 0L
        con.inputStream.use { i ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (stopped(t, gen)) throw RuntimeException("stopped")
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n); done += n
                    val now = System.currentTimeMillis()
                    if (now - lastT >= 1000) {
                        val bps = (done - lastB) * 1000 / (now - lastT)
                        t.speed = if (bps >= 1_048_576) "%.1f MB/s".format(bps / 1048576.0) else "%.0f KB/s".format(bps / 1024.0)
                        if (total > 0 && bps > 0) t.eta = hms((total - done) / bps)
                        lastT = now; lastB = done
                    }
                    if (total > 0) t.progress = (done.toFloat() / total).coerceAtMost(0.99f)
                }
            }
        }
        con.disconnect()
    }

    private fun hms(s: Long) = "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)

    // ───────────── Saving to public storage ─────────────

    private fun sanitize(n: String): String {
        val s = n.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").replace(Regex("\\s+"), " ").trim().trimStart('.').take(100).trim()
        return s.ifBlank { "video" }
    }

    private fun finish(t: DlTask, dir: File) {
        val files = dir.listFiles().orEmpty().filter { it.isFile && it.length() > 0 }
        val media = files.filter { it.extension.lowercase() in MEDIA_EXT && !partRe.containsMatchIn(it.name) }
        val mainFile = media.maxByOrNull { it.length() } ?: throw Exception("No output file was created")
        val base = sanitize(t.title.ifBlank { mainFile.nameWithoutExtension })

        val (uri, mime) = saveMedia(mainFile, "$base.${mainFile.extension.lowercase()}")
        files.filter { it.extension.lowercase() in SUB_EXT }.forEach {
            // "<id>.en.srt" → "<title>.en.srt"
            val tail = it.name.removePrefix(mainFile.nameWithoutExtension.substringBefore('.'))
            try { saveSub(it, base + tail) } catch (_: Throwable) {}
        }
        val size = mainFile.length()
        dir.deleteRecursively()

        db.add(HItem(0, t.title.ifBlank { base }, t.channel, t.thumb, t.url, t.fmt.short, uri.toString(), mime, size, System.currentTimeMillis()))
        reloadHistory()
        onMain { tasks.remove(t) }
        notifyDone(t.title.ifBlank { base }, t.id)
    }

    fun reloadHistory() { history.value = db.all() }

    fun deleteHistory(h: HItem, alsoFile: Boolean) {
        scope.launch {
            if (alsoFile) try { app.contentResolver.delete(Uri.parse(h.uri), null, null) } catch (_: Throwable) {}
            db.delete(h.id)
            reloadHistory()
        }
    }

    private fun mimeOf(ext: String, audio: Boolean) = when (ext) {
        "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "opus", "ogg" -> "audio/ogg"; "aac" -> "audio/aac"
        "wav" -> "audio/x-wav"; "flac" -> "audio/flac"
        else -> if (audio) "audio/*" else "video/*"
    }

    /** Video → Movies/YTDownloader, audio → Music/YTDownloader (visible in Gallery / Music player / Files). */
    private fun saveMedia(f: File, name: String): Pair<Uri, String> {
        val ext = f.extension.lowercase()
        val audio = ext in AUDIO_EXT
        val mime = mimeOf(ext, audio)
        val dirName = if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        if (Build.VERSION.SDK_INT >= 29) {
            val col = if (audio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            return insert(col, f, name, mime, "$dirName/YTDownloader") to mime
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(dirName), "YTDownloader").apply { mkdirs() }
        val out = File(dir, name); f.copyTo(out, true)
        var res: Uri? = null
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(app, arrayOf(out.absolutePath), arrayOf(mime)) { _, u -> res = u; latch.countDown() }
        latch.await(4, TimeUnit.SECONDS)
        return (res ?: Uri.fromFile(out)) to mime
    }

    private fun saveSub(f: File, name: String) {
        val mime = if (f.extension.equals("vtt", true)) "text/vtt" else "application/x-subrip"
        if (Build.VERSION.SDK_INT >= 29) {
            insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), f, name, mime, "Download/YTDownloader")
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "YTDownloader").apply { mkdirs() }
            f.copyTo(File(dir, name), true)
        }
    }

    private fun insert(col: Uri, f: File, name: String, mime: String, rel: String): Uri {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val cr = app.contentResolver
        val uri = cr.insert(col, cv) ?: throw Exception("MediaStore insert failed")
        cr.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } } ?: throw Exception("Cannot write to MediaStore")
        cv.clear(); cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
        cr.update(uri, cv, null, null)
        return uri
    }

    // ───────────── Notifications ─────────────

    fun buildNotif(ctx: Context): Notification {
        val running = tasks.filter { it.status == Status.RUNNING }
        val first = running.firstOrNull()
        val pct = ((first?.progress ?: 0f) * 100).toInt()
        val text = if (first == null) "${tasks.size} waiting…"
        else "$pct%  ${first.speed}  ${first.title.take(40)}"
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("YT Downloader — ${running.size} downloading")
            .setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi)
            .setProgress(100, pct, first == null)
            .build()
    }

    private fun notifyDone(title: String, id: Long) {
        try {
            val pi = PendingIntent.getActivity(
                app, 1, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(app, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Download complete").setContentText(title)
                .setAutoCancel(true).setContentIntent(pi).build()
            app.getSystemService(NotificationManager::class.java).notify(1000 + (id % 100000).toInt(), n)
        } catch (_: Throwable) {}
    }
}
